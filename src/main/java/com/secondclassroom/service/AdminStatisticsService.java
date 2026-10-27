package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.StaffWorkloadItem;
import com.secondclassroom.dto.StatisticsOverviewResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员数据统计（文档 §10.4 / 接口 #45、#46）：
 * - GET /api/admin/statistics/overview        —— 统计总览（总量 / 重复率 / 分布 / 平均时长）
 * - GET /api/admin/statistics/staff-workload  —— 干事工作量（每人多少单、完结多少、平均办理多久）
 *
 * 两个都只有管理员能调（Service 里判角色），都可以按 create_time 的日期区间过滤。
 *
 * ──────────────────────────────────────────────────────────────
 * 🔴 这里有一处**必须偏离文档**的地方，而且是个真坑：
 *
 *   文档的 SQL 一律写成 `create_time BETWEEN ? AND ?`。
 *   参数是"日期"（yyyy-MM-dd）时，MySQL 会把两端都当**当天 00:00:00**，
 *   于是 `BETWEEN '2026-09-20' AND '2026-09-26'` 实际只统计到 9-26 零点整 ——
 *   **截止日那一整天几乎全被丢掉**（只有正好落在 00:00:00 的那一两条会进来）。
 *
 *   项目里早已统一的写法是：`create_time >= 起始日 00:00` 且 `create_time < 次日 00:00`，
 *   这样"截止日整天都算在内"。本类沿用这个约定（AdminLogService / AdminWorkOrderService 也是）。
 *
 *   另一个选择：两个日期都可选，不传就是**全时段**（而不是偷偷给个默认 7 天）——
 *   统计口径必须一眼看得懂，"没传参数时到底统计了哪些数据"不能靠猜。
 * ──────────────────────────────────────────────────────────────
 */
@Service
public class AdminStatisticsService {

    private final JdbcTemplate jdbcTemplate;

    /** 五个状态（顺序即前端图例顺序） */
    private static final List<String> STATUSES =
            List.of("PENDING", "PROCESSING", "REPLIED", "CLOSED", "MERGED");

    /** 五个分类。
     *  ⚠️ 这是项目里**第 4 处**重复这份中文分类列表了（FaqService / TemplateService /
     *     WorkOrderService 各一份）。抽公共常量类要动已验证的代码，按既定原则先各留一份；
     *     技术债再记一笔，等一起统一。 */
    private static final List<String> CATEGORIES =
            List.of("学时录入", "审核驳回", "活动咨询", "规则疑问", "其他");

    public AdminStatisticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ============ #45 统计总览 ============

    /**
     * 统计总览。
     *
     * @param userType  操作人角色，必须是 ADMIN
     * @param startDate 起始日期 yyyy-MM-dd（含当天），可空 = 不限
     * @param endDate   截止日期 yyyy-MM-dd（含当天），可空 = 不限
     */
    public Result<StatisticsOverviewResult> overview(String userType, String startDate, String endDate) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可查看统计");
        }
        // 2. 日期解析（收 String 自己解析，格式错给 400 而不是 500 —— 见 AdminLogService 的说明）
        LocalDate start;
        LocalDate end;
        try {
            start = parseDate(startDate);
            end = parseDate(endDate);
        } catch (DateTimeParseException e) {
            return Result.fail(400, "日期格式不正确，应为 yyyy-MM-dd");
        }
        if (start != null && end != null && start.isAfter(end)) {
            return Result.fail(400, "起始日期不能晚于截止日期");
        }

        // 3. 统一的日期条件（>= 起始日 00:00 且 < 次日 00:00）
        DateRange range = buildRange(start, end);
        String where = range.where;
        Object[] params = range.params.toArray();

        // 4. 六条聚合查询（照文档 §10.4 的口径，逐条对应，方便对照着看）
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_work_order" + where, Long.class, params);
        long totalTickets = total == null ? 0 : total;

        Long dupTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_work_order" + and(where, "duplicate_of_order_id IS NOT NULL"),
                Long.class, params);
        long duplicates = dupTotal == null ? 0 : dupTotal;

        // 重复率：总量为 0 时直接给 0，避免除零；保留 2 位小数（和文档示例的 0.12 一致）
        double duplicateRate = totalTickets == 0 ? 0d
                : BigDecimal.valueOf(duplicates * 1.0d / totalTickets)
                        .setScale(2, RoundingMode.HALF_UP).doubleValue();

        // 5. 两个分布：先铺满全部键（=0），再把查到的填进去。
        //    这样前端画图时图例是固定的，不会因为"当天某个状态没数据"就少一块。
        //
        //    🔴 坑：这里的回调是个**只做副作用、不返回结果**的 lambda，编译器会选中
        //       `query(String, RowCallbackHandler)` 这个**不带参数**的重载 ——
        //       SQL 里的 `?` 会被原样发给 MySQL，报 "bad SQL grammar ... near '?'"（表现为 500）。
        //       必须显式把 params 作为第三个参数传进去，命中 `query(String, RowCallbackHandler, Object...)`。
        //       （不带日期时 where 里没有 `?`，所以这个 bug 只在传了日期时才现形。）
        Map<String, Long> statusDist = zeros(STATUSES);
        jdbcTemplate.query("SELECT status, COUNT(*) AS cnt FROM t_work_order" + where + " GROUP BY status",
                rs -> {
                    statusDist.put(rs.getString("status"), rs.getLong("cnt"));
                }, params);
        Map<String, Long> categoryDist = zeros(CATEGORIES);
        jdbcTemplate.query("SELECT category, COUNT(*) AS cnt FROM t_work_order" + where + " GROUP BY category",
                rs -> {
                    categoryDist.put(rs.getString("category"), rs.getLong("cnt"));
                }, params);

        // 6. 两个平均时长。COALESCE 兜住"一条都没有"的情况，省得在 Java 里判 null。
        //    注意 AVG 会自动跳过 NULL：没回复过的单不进 avgResponse，没完结的不进 avgClose。
        Double avgResp = jdbcTemplate.queryForObject(
                "SELECT COALESCE(AVG(TIMESTAMPDIFF(MINUTE, create_time, reply_time)), 0) FROM t_work_order"
                        + and(where, "reply_time IS NOT NULL"), Double.class, params);
        Double avgClose = jdbcTemplate.queryForObject(
                "SELECT COALESCE(AVG(TIMESTAMPDIFF(MINUTE, create_time, finish_time)), 0) FROM t_work_order"
                        + and(where, "finish_time IS NOT NULL"), Double.class, params);

        StatisticsOverviewResult r = new StatisticsOverviewResult();
        r.setTotalTickets(totalTickets);
        r.setDuplicateRate(duplicateRate);
        r.setStatusDistribution(statusDist);
        r.setCategoryDistribution(categoryDist);
        r.setAvgResponseMinutes(Math.round(avgResp == null ? 0d : avgResp));
        r.setAvgCloseMinutes(Math.round(avgClose == null ? 0d : avgClose));
        return Result.success("success", r);
    }

    // ============ #46 干事工作量 ============

    /**
     * 干事工作量统计：每个干事名下的单量、其中已完结多少、已完结单的平均办理时长。
     *
     * 文档只给了 SQL、**没给响应示例**，字段名按列名转驼峰定的（见 StaffWorkloadItem 的注释）。
     * 排序照文档 `ORDER BY total DESC`，末尾补 handler_id 是为了"单量相同时顺序稳定"
     * （不然两次请求的顺序可能不一样，前端看着像数据在跳）。
     */
    public Result<List<StaffWorkloadItem>> staffWorkload(String userType, String startDate, String endDate) {
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可查看统计");
        }
        LocalDate start;
        LocalDate end;
        try {
            start = parseDate(startDate);
            end = parseDate(endDate);
        } catch (DateTimeParseException e) {
            return Result.fail(400, "日期格式不正确，应为 yyyy-MM-dd");
        }
        if (start != null && end != null && start.isAfter(end)) {
            return Result.fail(400, "起始日期不能晚于截止日期");
        }

        DateRange range = buildRange(start, end);

        // 这里**不能**复用 overview 的 ` WHERE 1 = 1 ...` 拼接方式：
        // 本查询自带一个固定条件（handler_id IS NOT NULL），再拼一个 " WHERE ..." 就会变成两个 WHERE。
        // 所以直接用 range.conds（纯条件片段，不带 WHERE 关键字）逐个 AND 上去。
        StringBuilder sql = new StringBuilder(
                "SELECT handler_id, handler_name, COUNT(*) AS total, "
                        + "SUM(CASE WHEN status = 'CLOSED' THEN 1 ELSE 0 END) AS closed_count, "
                        // COALESCE 兜住"这个干事一单都没完结"→ AVG 全 NULL → 0，而不是 JSON 里出现 null
                        + "COALESCE(AVG(TIMESTAMPDIFF(MINUTE, accept_time, finish_time)), 0) AS avg_close_minutes "
                        + "FROM t_work_order WHERE handler_id IS NOT NULL");
        for (String cond : range.conds) {
            sql.append(" AND ").append(cond);
        }
        sql.append(" GROUP BY handler_id, handler_name ORDER BY total DESC, handler_id");

        List<StaffWorkloadItem> list = jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> {
                    StaffWorkloadItem it = new StaffWorkloadItem();
                    it.setHandlerId(rs.getString("handler_id"));
                    it.setHandlerName(rs.getString("handler_name"));
                    it.setTotal(rs.getLong("total"));
                    it.setClosedCount(rs.getLong("closed_count"));
                    it.setAvgCloseMinutes(Math.round(rs.getDouble("avg_close_minutes")));
                    return it;
                }, range.params.toArray());

        return Result.success("success", list);
    }

    // ============ 私有辅助 ============

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return LocalDate.parse(raw.trim());
    }

    /**
     * 日期条件集合 + 对应参数。
     *
     * `conds` 是**纯条件片段**（如 `create_time >= ?`），不带 WHERE 关键字 ——
     * 因为有的查询自带固定条件（例如 `handler_id IS NOT NULL`），需要把日期条件 AND 上去，
     * 而不是再起一个新的 WHERE。
     * `where` 则是给"光秃秃的 t_work_order"用的完整片段，形如 ` WHERE 1 = 1 AND ...`。
     */
    private static class DateRange {
        final List<String> conds;
        final List<Object> params;
        final String where;

        DateRange(List<String> conds, List<Object> params) {
            this.conds = conds;
            this.params = params;
            StringBuilder w = new StringBuilder(" WHERE 1 = 1");
            for (String c : conds) {
                w.append(" AND ").append(c);
            }
            this.where = w.toString();
        }
    }

    /**
     * 生成日期区间条件。截止日用"次日 00:00 之前"，这样截止日**整天都算**（见类注释里那个坑）。
     * 起止都可空；都空时返回空条件（= 全时段统计）。
     */
    private static DateRange buildRange(LocalDate start, LocalDate end) {
        List<String> conds = new ArrayList<>();
        List<Object> ps = new ArrayList<>();
        if (start != null) {
            conds.add("create_time >= ?");
            ps.add(start.atStartOfDay());
        }
        if (end != null) {
            conds.add("create_time < ?");
            ps.add(end.plusDays(1).atStartOfDay());
        }
        return new DateRange(conds, ps);
    }

    /**
     * 在已有 WHERE 上再追加一个条件。
     *
     * 注意：追加的参数必须**排在后面对应的位置**，所以调用方（overview）里用的都是
     * "把新条件拼进 SQL、但参数复用原来那一份" —— 因为这里追加的条件
     * （`duplicate_of_order_id IS NOT NULL` / `reply_time IS NOT NULL` / `finish_time IS NOT NULL`）
     * 都**不带参数**，所以 params 不用变。带参数的条件下不能这么用。
     */
    private static String and(String where, String extra) {
        return extra == null ? where : where + " AND " + extra;
    }

    /** 铺一个"所有键都在、值都是 0"的有序 Map */
    private static Map<String, Long> zeros(List<String> keys) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String k : keys) {
            m.put(k, 0L);
        }
        return m;
    }
}
