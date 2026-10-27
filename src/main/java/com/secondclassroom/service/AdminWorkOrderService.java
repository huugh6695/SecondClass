package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.PageResult;
import com.secondclassroom.dto.WorkOrderSearchItem;
import com.secondclassroom.dto.WorkOrderSearchQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 后台工单检索（文档 §10.7 / 接口 #50 + §10.5 / 接口 #47 的数据来源），仅管理员。
 *
 * 和学生端的 GET /api/work-orders 不是一回事，别混：
 *   · 学生端那个**按角色做数据隔离**（学生只看自己的、干事只看自己接的+待接单池），
 *     目的是"让每个人只看到该看的"；
 *   · 这个类是**管理员查账用的**：不受任何数据隔离，还额外支持 学号/姓名/工号/状态/
 *     分类/内容关键词/日期区间/是否命中敏感词/是否被合并 一共 9 个检索维度。
 *   一句话：一个是"我的工单"，一个是"全站检索"。
 *
 * 对外提供两个方法，**检索条件完全是同一套**（都走 buildFilter）：
 *   · search(...)         —— 分页，给 #50 /api/admin/work-orders/search 用；
 *   · listForExport(...)  —— 不分页，给 #47 /api/admin/export/work-orders 用。
 *
 * 🔴 为什么要把条件拼装抽成 buildFilter，而不是让导出自己写一遍 SQL？
 *    因为"列表能按这个条件筛、导出却筛不出来"是这类系统最典型的坑：
 *    两处各写一份，改了 A 忘了 B，用户就会拿到一份"看着像筛选过、其实没筛"的 Excel ——
 *    而这种错误**不会报错**，只会安静地给错数据。抽出来共用，从结构上杜绝。
 *
 * 全部条件都是**可选**的，不传就是"这一维不筛"，所以内部用 WHERE 1 = 1 起头。
 */
@Service
public class AdminWorkOrderService {

    private final JdbcTemplate jdbcTemplate;

    /** t_work_order.status 的五个取值（和 WorkOrderService 里的一致） */
    private static final List<String> STATUSES =
            List.of("PENDING", "PROCESSING", "REPLIED", "CLOSED", "MERGED");

    /**
     * 状态 → 中文标签。
     *
     * ⚠️ 这份映射和 WorkOrderService.STATUS_LABEL 是**故意重复**的：
     *    那边是 private，要共用就得抽公共常量类，而那要动已经验证过的 WorkOrderService ——
     *    按"动已验证代码的风险大于收益"这个既定原则，先各留一份。
     *    已记入技术债（和 FaqService / TemplateService 里重复的中文分类列表一起，待统一）。
     *
     * ⚠️ 文档 §10.7 的响应示例把 CLOSED 写成了「已完结」，而项目里所有已实现的接口
     *    （工单详情 / 列表 / 各操作返回）用的都是「已关闭」。这里**跟随项目现状**保持
     *    「已关闭」—— 同一个 API 里同一个状态出现两种叫法，比跟文档示例不一致更糟。
     */
    private static final Map<String, String> STATUS_LABEL = Map.of(
            "PENDING", "待接单",
            "PROCESSING", "处理中",
            "REPLIED", "已回复",
            "CLOSED", "已关闭",
            "MERGED", "已合并");

    /** 查询列（列表和导出共用，保证两边字段一模一样） */
    private static final String SELECT_COLUMNS =
            "SELECT work_order_id, user_id, user_name, category, content, status, "
                    + "handler_id, handler_name, is_flagged, duplicate_of_order_id, "
                    + "create_time, accept_time, reply_time, finish_time FROM t_work_order";

    /**
     * 排序：时间倒序，末尾补主键。
     * 补主键是为了"同一秒创建的两张单"顺序稳定 —— 否则翻页时可能重复或漏掉一条。
     */
    private static final String ORDER_BY = " ORDER BY create_time DESC, work_order_id DESC";

    public AdminWorkOrderService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 全维度搜索工单（仅管理员），分页。给 #50 用。
     *
     * @param userType 操作人角色，必须是 ADMIN
     * @param q        查询条件（全部可选，不传即不筛）
     */
    public Result<PageResult<WorkOrderSearchItem>> search(String userType, WorkOrderSearchQuery q) {
        // 1. 角色校验：拦截器只管"有没有带 Token"，这里管"是不是管理员"
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可搜索工单");
        }
        if (q == null) {
            q = new WorkOrderSearchQuery();
        }

        // 2. 条件（9 个维度 + 2 个开关）—— 与导出共用同一套拼装
        Result<Filter> fr = buildFilter(q);
        if (fr.getCode() != 0) {
            return Result.fail(fr.getCode(), fr.getMessage());
        }
        Filter f = fr.getData();

        // 3. 分页兜底（沿用全项目约定：page 默认 1、pageSize 默认 20、上限 50）
        int p = (q.getPage() == null || q.getPage() < 1) ? 1 : q.getPage();
        int ps = (q.getPageSize() == null || q.getPageSize() < 1) ? 20 : Math.min(q.getPageSize(), 50);

        // 4. 总数
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_work_order" + f.where, Long.class, f.params.toArray());

        // 5. 分页查明细。
        //    🔴 LIMIT 的两个 ? 必须是 **offset 在前、pageSize 在后**
        //    （MySQL 的 `LIMIT a, b` 是"跳过 a 行、取 b 行"，写反了会静默返回空页）。
        int offset = (p - 1) * ps;
        List<Object> queryParams = new ArrayList<>(f.params);
        queryParams.add(offset);
        queryParams.add(ps);
        List<WorkOrderSearchItem> list = jdbcTemplate.query(
                SELECT_COLUMNS + f.where + ORDER_BY + " LIMIT ?, ?",
                ItemRowMapper.INSTANCE, queryParams.toArray());

        return Result.success("success", PageResult.of(total == null ? 0 : total, p, ps, list));
    }

    /**
     * 按同样的条件**全量**取工单（不分页），给 #47 导出 Excel 用。仅管理员。
     *
     * 文档 §10.5 明确写的是"查询工单数据（同列表查询，不分页）" ——
     * 所以这里就是 search 去掉了 COUNT 和 LIMIT 的版本，条件部分一个字都没变。
     *
     * ⚠️ 没有条数上限（文档没规定）。这是一把双刃剑：
     *    数据量真涨到几万条时，全量塞进内存的 XSSFWorkbook 会吃掉大量堆内存。
     *    真到那天要换成 SXSSFWorkbook（流式写，行数据随时可刷盘）+ 分片查询。
     *    现在库里是几百条量级，先不引入这个复杂度，但**记在这里备查**。
     */
    public Result<List<WorkOrderSearchItem>> listForExport(String userType, WorkOrderSearchQuery q) {
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可导出工单");
        }
        if (q == null) {
            q = new WorkOrderSearchQuery();
        }
        Result<Filter> fr = buildFilter(q);
        if (fr.getCode() != 0) {
            return Result.fail(fr.getCode(), fr.getMessage());
        }
        Filter f = fr.getData();
        List<WorkOrderSearchItem> rows = jdbcTemplate.query(
                SELECT_COLUMNS + f.where + ORDER_BY,
                ItemRowMapper.INSTANCE, f.params.toArray());
        return Result.success("success", rows);
    }

    // ============ 私有辅助 ============

    /** 条件拼装的结果：一段 ` WHERE 1 = 1 AND ...` + 与其中 `?` 一一对应的参数 */
    private static class Filter {
        final String where;
        final List<Object> params;

        Filter(String where, List<Object> params) {
            this.where = where;
            this.params = params;
        }
    }

    /**
     * 把查询对象解析成 SQL 条件。校验失败时通过 `Result.fail` 返回 400 类错误，
     * 而不是抛异常 —— 这是项目统一的"用户传错参数不该变成 500"做法。
     *
     * ⚠️ 这里的校验**全都发生在查库之前**（纯解析，没有写库），
     *    所以无论是搜索还是导出，参数不对都不会产生任何副作用。
     */
    private Result<Filter> buildFilter(WorkOrderSearchQuery q) {
        // 1. status：可逗号分隔多个（如 PENDING,CLOSED）。
        //    这是**固定 ENUM**，传个不存在的值说明前端写错了，给 400 比静默返回空列表好排查
        //    （与 WorkOrderService.listWorkOrders 的处理保持一致）。
        List<String> statusList = new ArrayList<>();
        if (notBlank(q.getStatus())) {
            for (String s : q.getStatus().split(",")) {
                s = s.trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (!STATUSES.contains(s)) {
                    return Result.fail(400, "非法的 status 值：" + s);
                }
                statusList.add(s);
            }
        }

        // 2. 日期：收 String 自己解析，格式错给 400
        //    （声明成 LocalDate 会在参数绑定阶段抛 MethodArgumentTypeMismatchException → 500）
        LocalDate start = null;
        LocalDate end = null;
        try {
            if (notBlank(q.getStartDate())) {
                start = LocalDate.parse(q.getStartDate().trim());
            }
            if (notBlank(q.getEndDate())) {
                end = LocalDate.parse(q.getEndDate().trim());
            }
        } catch (DateTimeParseException e) {
            return Result.fail(400, "日期格式不正确，应为 yyyy-MM-dd");
        }
        if (start != null && end != null && start.isAfter(end)) {
            return Result.fail(400, "起始日期不能晚于截止日期");
        }

        // 3. 两个布尔开关：同样收 String 自己解析。
        //    做成 true/false 双向都有意义（true=只看这类，false=只看"不是这类"），
        //    比只认 true 更符合"筛选"这个词的直觉。
        Boolean flagFilter = parseBool(q.getIsFlagged());
        if (flagFilter == null && notBlank(q.getIsFlagged())) {
            return Result.fail(400, "isFlagged 只能是 true 或 false");
        }
        Boolean dupFilter = parseBool(q.getIsDuplicate());
        if (dupFilter == null && notBlank(q.getIsDuplicate())) {
            return Result.fail(400, "isDuplicate 只能是 true 或 false");
        }

        // 4. 拼条件。注意每个条件都写成 " AND ..." 直接接在 " WHERE 1 = 1" 后面
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (notBlank(q.getWorkOrderId())) {
            where.append(" AND work_order_id = ?");
            params.add(q.getWorkOrderId().trim());
        }
        if (notBlank(q.getUserId())) {
            where.append(" AND user_id = ?");
            params.add(q.getUserId().trim());
        }
        if (notBlank(q.getUserName())) {
            // ⚠️ 文档给的 SQL 是 LEFT JOIN t_user 取 u.user_name；
            //    实际 t_work_order 自己就冗余存了 user_name（NOT NULL），
            //    学生端列表也一直用它，所以这里**不 JOIN**，直接用本表列 —— 少一次关联。
            where.append(" AND user_name LIKE ?");
            params.add("%" + q.getUserName().trim() + "%");
        }
        if (notBlank(q.getHandlerId())) {
            where.append(" AND handler_id = ?");
            params.add(q.getHandlerId().trim());
        }
        if (notBlank(q.getHandlerName())) {
            where.append(" AND handler_name LIKE ?");
            params.add("%" + q.getHandlerName().trim() + "%");
        }
        if (!statusList.isEmpty()) {
            where.append(" AND status IN (");
            for (int i = 0; i < statusList.size(); i++) {
                where.append(i == 0 ? "?" : ",?");
                params.add(statusList.get(i));
            }
            where.append(")");
        }
        if (notBlank(q.getCategory())) {
            // 分类**不校验取值**：它是中文 ENUM，传错了结果就是空列表，管理员自己看得见
            // （和 WorkOrderService 的 category 筛一致）
            where.append(" AND category = ?");
            params.add(q.getCategory().trim());
        }
        if (notBlank(q.getKeyword())) {
            where.append(" AND content LIKE ?");
            params.add("%" + q.getKeyword().trim() + "%");
        }
        if (start != null) {
            where.append(" AND create_time >= ?");
            params.add(start.atStartOfDay());
        }
        if (end != null) {
            // 截止日要**整天都算在内**，所以用"次日 00:00 之前"，而不是 <= 当天 00:00
            // （否则 endDate 当天的工单一律查不到，是个很容易踩的边界）
            where.append(" AND create_time < ?");
            params.add(end.plusDays(1).atStartOfDay());
        }
        if (flagFilter != null) {
            where.append(" AND is_flagged = ?");
            params.add(flagFilter ? 1 : 0);
        }
        if (dupFilter != null) {
            where.append(dupFilter
                    ? " AND duplicate_of_order_id IS NOT NULL"
                    : " AND duplicate_of_order_id IS NULL");
        }

        return Result.success("success", new Filter(where.toString(), params));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** "true"/"false"（不分大小写）→ Boolean；其它（含空）→ null，由调用方决定是"不筛"还是 400 */
    private static Boolean parseBool(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if ("true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 一行 t_work_order → WorkOrderSearchItem */
    private static class ItemRowMapper implements RowMapper<WorkOrderSearchItem> {
        static final ItemRowMapper INSTANCE = new ItemRowMapper();

        @Override
        public WorkOrderSearchItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            WorkOrderSearchItem it = new WorkOrderSearchItem();
            it.setWorkOrderId(rs.getString("work_order_id"));
            it.setUserId(rs.getString("user_id"));
            it.setUserName(rs.getString("user_name"));
            it.setCategory(rs.getString("category"));
            it.setContent(rs.getString("content"));
            String st = rs.getString("status");
            it.setStatus(st);
            it.setStatusLabel(STATUS_LABEL.getOrDefault(st, st));
            it.setHandlerId(rs.getString("handler_id"));
            it.setHandlerName(rs.getString("handler_name"));
            it.setIsFlagged(rs.getBoolean("is_flagged"));
            it.setDuplicateOfOrderId(rs.getString("duplicate_of_order_id"));
            it.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            it.setAcceptTime(rs.getObject("accept_time", LocalDateTime.class));
            it.setReplyTime(rs.getObject("reply_time", LocalDateTime.class));
            it.setFinishTime(rs.getObject("finish_time", LocalDateTime.class));
            return it;
        }
    }
}
