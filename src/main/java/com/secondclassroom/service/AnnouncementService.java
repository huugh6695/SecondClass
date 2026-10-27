package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.AnnouncementItem;
import com.secondclassroom.dto.AnnouncementSaveRequest;
import com.secondclassroom.util.DailySeqId;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 公告（t_announcement）业务逻辑：
 * - 列表（GET /api/announcements，可选 Token：只有 ACTIVE 且未过期的会展示）
 * - 发布 / 编辑 / 归档（POST|PUT|DELETE /api/admin/announcements...，仅 ADMIN，每次都写审计）
 *
 * 公告和 FAQ 的区别（答辩常被问）：
 *   · FAQ 是"长期沉淀的问答"，面向学生的**自助查询**，按分类和关键词找；
 *   · 公告是"有时效的通知"，面向**推送**（首页公告栏），所以它有个 expire_time ——
 *     过期就自动从列表里消失，不用管理员手动去下架。
 *   两者都是"管理员写、学生读"，但生命周期管理方式不同。
 *
 * 三个坑写在最前面：
 * 1) **"可选 Token"**：列表接口没登录也能看（首页公告栏不能要求先登录）。
 *    拦截器里的 OPTIONAL_AUTH_PREFIXES 加了 "/api/announcements"，
 *    匹配用的是 equals / 前缀+"/"，所以 /api/admin/announcements 不会被顺带放行。
 * 2) **归档（DELETE）是软删除**：只把 status 改成 ARCHIVED，行留在库里 ——
 *    否则审计日志里的 target_id 变成悬空引用，管理员也找不回误删的公告。
 * 3) **过期的公告不物理删**：它只是不出现在列表里（SQL 里挡掉）。
 *    所以"下架"和"归档"是两件事 —— 过期是自动的、临时的；归档是人工的、永久的。
 *
 * 📌 关于 Redis：文档在敏感词那边说了"更新 Redis 缓存"，公告这边文档**没提缓存**，
 *    我们也没有 Redis，所以这里就是纯数据库读写，无缓存层。（等第⑧阶段接 Redis 时再看要不要加。）
 */
@Service
public class AnnouncementService {

    private final JdbcTemplate jdbcTemplate;
    private final OperationLogService operationLogService;

    /** 生成公告号（NOTICE + 6 位全局序号）用；见 DailySeqId 的类注释 */
    private final StringRedisTemplate redis;

    /** t_announcement.status 的两个取值 */
    private static final List<String> STATUSES = List.of("ACTIVE", "ARCHIVED");

    /** 列表筛选允许的额外取值：ALL 只对管理员开放，用来把已归档的公告翻出来 */
    private static final List<String> LIST_FILTERS = List.of("ACTIVE", "ARCHIVED", "ALL");

    /** 标题长度上限（表注释约定"≤30字"） */
    private static final int TITLE_MAX = 30;

    /** 文档示例里的时间格式：2026-09-30 23:59:59 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 前端传 ISO 格式（带 T）也认，省得因为一个字符报错 */
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public AnnouncementService(JdbcTemplate jdbcTemplate, OperationLogService operationLogService,
                               StringRedisTemplate redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationLogService = operationLogService;
        this.redis = redis;
    }

    // ============ 列表（GET /api/announcements） ============

    /**
     * 公告列表。
     *
     * 文档给的 SQL 是：
     * <pre>
     *   SELECT * FROM t_announcement
     *   WHERE status = 'ACTIVE' AND (expire_time IS NULL OR expire_time &gt; NOW())
     *   ORDER BY sort_num, publish_time DESC
     * </pre>
     * 这是**学生/游客**口径。但照这个口径，管理员刚归档的公告就再也查不到了
     * （归档是软删除，行还在库里，只是没有任何接口能看见它），体验上说不过去。
     *
     * 所以补了一个只对管理员生效的可选参数 `status`，沿用 FAQ 那边"管理员能多看一点"的既定做法：
     *   · 非管理员：**永远**只看 ACTIVE 且未过期（传什么 status 都忽略，不会越权）
     *   · 管理员传 ARCHIVED → 只看已归档的；传 ALL → 全部（含已过期、已归档）
     *
     * 结尾的 `notice_id` 排序是老规矩：同 sort_num、同一秒发布的两条公告，
     * 不加主键排序的话两次查询顺序可能不一样，前端看着像在乱跳。
     */
    public Result<List<AnnouncementItem>> listAnnouncements(String userType, String status) {
        boolean admin = "ADMIN".equals(userType);

        // 1. 决定这次实际按什么状态查
        String effective = "ACTIVE";
        if (admin && status != null && !status.isBlank()) {
            effective = status.trim().toUpperCase();
            if (!LIST_FILTERS.contains(effective)) {
                return Result.fail(400, "状态不合法，必须是：ACTIVE / ARCHIVED / ALL");
            }
        }

        // 2. 拼条件
        StringBuilder sql = new StringBuilder(
                "SELECT notice_id, title, content, sort_num, status, expire_time, publisher_name, "
                        + "publish_time, update_time FROM t_announcement WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (!"ALL".equals(effective)) {
            sql.append(" AND status = ?");
            args.add(effective);
        }
        if ("ACTIVE".equals(effective)) {
            // 文档语义：expire_time 为 NULL = 永久有效；有值 = 到点自动从列表消失
            sql.append(" AND (expire_time IS NULL OR expire_time > NOW())");
        }
        sql.append(" ORDER BY sort_num, publish_time DESC, notice_id");

        List<AnnouncementItem> list =
                jdbcTemplate.query(sql.toString(), ItemRowMapper.INSTANCE, args.toArray());
        return Result.success("success", list);
    }

    // ============ 发布（POST /api/admin/announcements） ============

    /**
     * 发布公告（仅管理员）。
     *
     * 编号：文档写的是"生成 noticeId"，表注释给的格式是 `NOTICE+6位序号`（不带日期），
     * 所以走 DailySeqId.nextGlobal（现在是 Redis `INCR announcement:seq`，原子自增）。
     * ⚠️ 千万别用 next() —— 那个假设 id 里有 8 位日期，对 NOTICE000001 会截出空串，
     *    每次都算回 1，直接主键冲突（FAQ 那边就是这么踩的）。
     *
     * publish_time 不传 —— 表上有 DEFAULT CURRENT_TIMESTAMP，让数据库自己填，
     * 比在 Java 里取一次时间更准（以数据库时钟为准，多个实例部署时不会因为我机器时区不对而乱）。
     */
    @Transactional
    public Result<AnnouncementItem> createAnnouncement(String userId, String userType,
                                                      AnnouncementSaveRequest req) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理公告");
        }
        // 2. 入参校验（新增时标题和内容必填）
        Result<AnnouncementItem> invalid = validate(req, true);
        if (invalid != null) {
            return invalid;
        }
        // 3. 查发布人姓名（写快照 + 给审计用）
        String userName;
        try {
            userName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 4. 生成公告号并落库
        String noticeId = DailySeqId.nextGlobal(jdbcTemplate, redis, "NOTICE", "t_announcement", "notice_id", 6);
        jdbcTemplate.update(
                "INSERT INTO t_announcement (notice_id, title, content, sort_num, status, "
                        + "expire_time, publisher_id, publisher_name) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)",
                noticeId, req.getTitle(), req.getContent(),
                req.getSortNum() == null ? 0 : req.getSortNum(),
                resolveExpireTime(req), userId, userName);

        // 5. 审计日志（文档 §9 要求 operation_type='CREATE_ANNOUNCEMENT'）
        operationLogService.record(userId, userName, userType,
                "CREATE_ANNOUNCEMENT", "ANNOUNCEMENT", noticeId,
                "发布公告：" + req.getTitle());

        // 6. 回查返回（不凭内存里的值编返回体）
        return Result.success("公告发布成功", loadAnnouncement(noticeId));
    }

    // ============ 编辑（PUT /api/admin/announcements/{noticeId}） ============

    /**
     * 编辑公告（仅管理员）。**部分更新**：只覆盖传了值的字段，null 表示不动。
     *
     * 一个仍然没解决的"坑"（先记在这，不乱加规则）：文档没规定"过期时间不能早于当前时间"。
     * 所以管理员把 expireTime 设成昨天，接口照样成功 —— 只是这条公告**立刻从列表里消失**。
     * 严格点应该在 validate 里加一条 400，但那属于改需求，没做。
     */
    @Transactional
    public Result<AnnouncementItem> updateAnnouncement(String userId, String userType,
                                                      String noticeId, AnnouncementSaveRequest req) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理公告");
        }
        // 2. 入参校验
        Result<AnnouncementItem> invalid = validate(req, false);
        if (invalid != null) {
            return invalid;
        }
        // 3. 存在性校验
        if (loadAnnouncement(noticeId) == null) {
            return Result.fail(404, "公告不存在");
        }
        // 4. 查操作人姓名
        String userName;
        try {
            userName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 5. 动态拼 UPDATE（只更新"传了的"字段，避免前端漏传一个字段就把库里冲掉）
        StringBuilder sql = new StringBuilder("UPDATE t_announcement SET ");
        List<Object> args = new ArrayList<>();
        List<String> sets = new ArrayList<>();

        if (req.getTitle() != null) {
            sets.add("title = ?");
            args.add(req.getTitle());
        }
        if (req.getContent() != null) {
            sets.add("content = ?");
            args.add(req.getContent());
        }
        if (req.getSortNum() != null) {
            sets.add("sort_num = ?");
            args.add(req.getSortNum());
        }
        if (req.getStatus() != null) {
            sets.add("status = ?");
            args.add(req.getStatus());
        }
        if (req.getExpireTime() != null) {
            // 传了非 null（含空串）：空串 = 清成 NULL = 改成永久有效
            sets.add("expire_time = ?");
            args.add(resolveExpireTime(req));
        }

        if (sets.isEmpty()) {
            // 一个字段都没传：不改库，直接把当前值还回去（语义上是"无事发生"，比报错友好）
            return Result.success("没有需要修改的字段", loadAnnouncement(noticeId));
        }

        sql.append(String.join(", ", sets)).append(" WHERE notice_id = ?");
        args.add(noticeId);
        // update_time 有 ON UPDATE CURRENT_TIMESTAMP，不用手动写
        jdbcTemplate.update(sql.toString(), args.toArray());

        // 6. 审计日志（文档 §9 要求 operation_type='UPDATE_ANNOUNCEMENT'）
        operationLogService.record(userId, userName, userType,
                "UPDATE_ANNOUNCEMENT", "ANNOUNCEMENT", noticeId,
                "编辑公告：修改了 " + String.join(" / ", changedFields(req)));

        // 7. 回查返回
        return Result.success("公告修改成功", loadAnnouncement(noticeId));
    }

    // ============ 归档（DELETE /api/admin/announcements/{noticeId}） ============

    /**
     * 归档公告（仅管理员）—— **软删除**：status 改成 ARCHIVED，行还在库里。
     *
     * 为什么不物理 DELETE？和 FAQ 下架同一个理由：
     *   · t_operation_log.target_id 里记着这个 noticeId，删了就成了查不到的死引用；
     *   · 归档后管理员还能用列表接口的 `status=ARCHIVED`（见 listAnnouncements）把它翻出来看，
     *     甚至 PUT 一个 status=ACTIVE 就能"复活"。
     *
     * 幂等：已经归档的再归档一次不报错（affected 可能为 0），仍然返回成功。
     */
    @Transactional
    public Result<Void> archiveAnnouncement(String userId, String userType, String noticeId) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理公告");
        }
        // 2. 存在性校验
        if (loadAnnouncement(noticeId) == null) {
            return Result.fail(404, "公告不存在");
        }
        // 3. 查操作人姓名
        String userName;
        try {
            userName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 4. 软删除
        jdbcTemplate.update(
                "UPDATE t_announcement SET status = 'ARCHIVED' WHERE notice_id = ?", noticeId);

        // 5. 审计日志（文档 §9 要求 operation_type='ARCHIVE_ANNOUNCEMENT'）
        operationLogService.record(userId, userName, userType,
                "ARCHIVE_ANNOUNCEMENT", "ANNOUNCEMENT", noticeId, "归档公告（软删除）");

        return Result.success("公告已归档", null);
    }

    // ============ 私有辅助 ============

    /**
     * 入参校验。
     *
     * @param creating true=发布（标题、内容必填）；false=编辑（字段可省，省了表示不改）
     * @return null 表示通过；否则是要原样返回给前端的错误
     */
    private Result<AnnouncementItem> validate(AnnouncementSaveRequest req, boolean creating) {
        if (req == null) {
            return Result.fail(400, "请求体不能为空");
        }
        // 标题
        if (creating && req.getTitle() == null) {
            return Result.fail(400, "公告标题不能为空");
        }
        if (req.getTitle() != null) {
            if (req.getTitle().isBlank()) {
                return Result.fail(400, "公告标题不能为空白");
            }
            if (req.getTitle().length() > TITLE_MAX) {
                return Result.fail(400, "公告标题不能超过 " + TITLE_MAX + " 字");
            }
        }
        // 内容
        if (creating && req.getContent() == null) {
            return Result.fail(400, "公告内容不能为空");
        }
        if (req.getContent() != null && req.getContent().isBlank()) {
            return Result.fail(400, "公告内容不能为空白");
        }
        // 状态（一般只有编辑会传）
        if (req.getStatus() != null) {
            if (req.getStatus().isBlank()) {
                return Result.fail(400, "状态不能为空白");
            }
            if (!STATUSES.contains(req.getStatus())) {
                return Result.fail(400, "状态不合法，必须是：ACTIVE / ARCHIVED");
            }
        }
        // 排序号
        if (req.getSortNum() != null && req.getSortNum() < 0) {
            return Result.fail(400, "排序号不能为负数");
        }
        // 过期时间：非空串就必须能被解析（空串是合法输入 = 清除）
        if (req.getExpireTime() != null && !req.getExpireTime().isBlank()
                && tryParseTime(req.getExpireTime()) == null) {
            return Result.fail(400, "过期时间格式不正确，应为 yyyy-MM-dd HH:mm:ss");
        }
        return null;
    }

    /**
     * 把请求里的 expireTime 字符串算成要写库的值。
     * 约定：null = null（数据库里表示永久有效）；空串 = null；有值 = 解析出来的时间。
     * （格式合不合法在 validate 里已经拦过了，这里不必再判。）
     */
    private LocalDateTime resolveExpireTime(AnnouncementSaveRequest req) {
        String raw = req.getExpireTime();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return tryParseTime(raw);
    }

    /** 依次尝试 "yyyy-MM-dd HH:mm:ss" 和 ISO 的 "yyyy-MM-dd'T'HH:mm:ss"，都失败返回 null */
    private LocalDateTime tryParseTime(String raw) {
        String s = raw.trim();
        try {
            return LocalDateTime.parse(s, TIME_FMT);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(s, ISO_FMT);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    /** 列出本次 PUT 实际改动的字段名，写进审计内容（"改了啥"比"改过"有用得多） */
    private List<String> changedFields(AnnouncementSaveRequest req) {
        List<String> fields = new ArrayList<>();
        if (req.getTitle() != null) fields.add("标题");
        if (req.getContent() != null) fields.add("内容");
        if (req.getSortNum() != null) fields.add("排序号");
        if (req.getExpireTime() != null) fields.add("过期时间");
        if (req.getStatus() != null) fields.add("状态");
        return fields;
    }

    /** 查一条公告的完整字段；不存在返回 null（交给调用方决定报 404 还是别的） */
    private AnnouncementItem loadAnnouncement(String noticeId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT notice_id, title, content, sort_num, status, expire_time, publisher_name, "
                            + "publish_time, update_time FROM t_announcement WHERE notice_id = ?",
                    ItemRowMapper.INSTANCE, noticeId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 公告行映射（列表、详情共用同一套字段，所以一个 mapper 就够） */
    private static class ItemRowMapper implements RowMapper<AnnouncementItem> {
        static final ItemRowMapper INSTANCE = new ItemRowMapper();

        @Override
        public AnnouncementItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            AnnouncementItem a = new AnnouncementItem();
            a.setNoticeId(rs.getString("notice_id"));
            a.setTitle(rs.getString("title"));
            a.setContent(rs.getString("content"));
            a.setSortNum(rs.getInt("sort_num"));
            a.setStatus(rs.getString("status"));
            a.setExpireTime(rs.getObject("expire_time", LocalDateTime.class));
            a.setPublisherName(rs.getString("publisher_name"));
            a.setPublishTime(rs.getObject("publish_time", LocalDateTime.class));
            a.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            return a;
        }
    }
}
