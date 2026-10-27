package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.TemplateItem;
import com.secondclassroom.dto.TemplateSaveRequest;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 快捷回复模板（t_quick_reply_template）业务逻辑：
 * - 列表（GET /api/templates，**需要 Token，但任何角色都能读**）
 * - 新增 / 编辑 / 禁用（POST|PUT|DELETE /api/admin/templates...，仅 ADMIN，每次都写审计）
 *
 * ──────────────────────────────────────────────────────────────
 * 🔴 **权限上最容易搞错的一点**
 *
 * `GET /api/templates` 在文档里标的是「**需** Token」——不是「可选 Token」，也**不是**管理员专属。
 * 所以：
 *   · 它**不在** `/api/admin/**` 下面（路径是 /api/templates），
 *     但拦截器默认就拦住了**所有**路径，所以它天然"需要 Token"，**不需要改拦截器**；
 *   · 千万**不要**把 "/api/templates" 加进 JwtInterceptor 的 OPTIONAL_AUTH_PREFIXES ——
 *     那样游客也能看到模板，而模板是给干事回复时套用的，游客没有使用场景。
 *   · 三个角色（学生/干事/管理员）都能读，所以 Service 里不做角色判断，
 *     只按"是不是管理员"决定能不能多看到禁用的模板。
 *
 * 和 FAQ 的对照（这俩最容易被问混）：
 *   · FAQ   ：学生自助查询的问答，**可选 Token**（游客也能看），有 view_count。
 *   · 模板  ：干事回复时套用的现成话术，**需要 Token**，没有浏览量。
 * ──────────────────────────────────────────────────────────────
 */
@Service
public class TemplateService {

    private final JdbcTemplate jdbcTemplate;
    private final OperationLogService operationLogService;

    /** 生成模板号（TPL + 4 位全局序号）用；见 DailySeqId 的类注释 */
    private final StringRedisTemplate redis;

    /**
     * 允许的分类。⚠️ 这份列表和 t_work_order.category / t_faq.category 是**同一套中文 ENUM**，
     * 目前项目里 FaqService、WorkOrderService、这里各有一份 —— 已知的重复，
     * 抽成公共常量类是更干净的做法，但那要动已验证的代码，先记在账上不动。
     */
    private static final List<String> CATEGORIES =
            List.of("学时录入", "审核驳回", "活动咨询", "规则疑问", "其他");

    /** t_quick_reply_template.status 的两个取值 */
    private static final List<String> STATUSES = List.of("ACTIVE", "DISABLED");

    /** 列表筛选允许的额外取值：ALL 只对管理员开放，用来把禁用掉的模板翻出来 */
    private static final List<String> LIST_FILTERS = List.of("ACTIVE", "DISABLED", "ALL");

    /** 模板名称长度上限（表里是 varchar(100)） */
    private static final int TITLE_MAX = 100;

    public TemplateService(JdbcTemplate jdbcTemplate, OperationLogService operationLogService,
                           StringRedisTemplate redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationLogService = operationLogService;
        this.redis = redis;
    }

    // ============ 列表（GET /api/templates） ============

    /**
     * 模板列表。文档给的 SQL：
     * <pre>
     *   SELECT * FROM t_quick_reply_template
     *   WHERE status = 'ACTIVE' [AND category = :category]
     *   ORDER BY sort_num
     *   （干事查看可用模板，管理员可加 status 筛选全部）
     * </pre>
     *
     * 照文档实现 + 沿用 FAQ/公告那边已确立的"管理员能多看一点"做法：
     *   · 非管理员：**永远**只看 ACTIVE（传什么 status 都忽略，不会越权）
     *   · 管理员传 DISABLED → 只看已禁用的；传 ALL → 全部
     *     （禁用是软删除，没这个入口的话管理员禁掉一个模板之后就再也找不回它了）
     *
     * @param userType 当前登录角色（这个接口需要 Token，所以不会是 null）
     * @param category 可选，按分类筛；传了就必须合法（不合法给 400，而不是静默返回空列表）
     * @param status   可选，**只对管理员生效**
     */
    public Result<List<TemplateItem>> listTemplates(String userType, String category, String status) {
        // 1. 分类校验
        if (category != null && !category.isBlank() && !CATEGORIES.contains(category)) {
            return Result.fail(400, "分类不合法，必须是：学时录入 / 审核驳回 / 活动咨询 / 规则疑问 / 其他");
        }
        // 2. 状态口径
        boolean admin = "ADMIN".equals(userType);
        String effective = "ACTIVE";
        if (admin && status != null && !status.isBlank()) {
            effective = status.trim().toUpperCase();
            if (!LIST_FILTERS.contains(effective)) {
                return Result.fail(400, "状态不合法，必须是：ACTIVE / DISABLED / ALL");
            }
        }

        // 3. 拼条件（WHERE 1=1 占位，后面无脑 append " AND ..."）
        StringBuilder sql = new StringBuilder(
                "SELECT template_id, category, title, content, sort_num, status, creator_name, "
                        + "create_time, update_time FROM t_quick_reply_template WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (!"ALL".equals(effective)) {
            sql.append(" AND status = ?");
            args.add(effective);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category);
        }
        // 文档：ORDER BY sort_num。末尾补 template_id 是为了"同 sort_num"时顺序稳定，
        // 否则两次查询顺序可能不一样，前端看着像在乱跳。
        sql.append(" ORDER BY sort_num, template_id");

        List<TemplateItem> list =
                jdbcTemplate.query(sql.toString(), ItemRowMapper.INSTANCE, args.toArray());
        return Result.success("success", list);
    }

    // ============ 新增（POST /api/admin/templates） ============

    /**
     * 新增模板（仅管理员）。
     *
     * 编号：文档写 `TPL{seq:04d}`，表注释也是 `TPL+4位序号`（不带日期），
     * 所以走 DailySeqId.nextGlobal。
     * ⚠️ 别用 next() —— 那个假设 id 里有 8 位日期，对 TPL0001 会截出空串，
     *    每次都算回 1，直接主键冲突（FAQ / 公告都在这上面踩过，教训已经写进 DailySeqId 的注释）。
     */
    @Transactional
    public Result<TemplateItem> createTemplate(String userId, String userType, TemplateSaveRequest req) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理快捷回复模板");
        }
        // 2. 入参校验（新增时分类/名称/内容必填）
        Result<TemplateItem> invalid = validate(req, true);
        if (invalid != null) {
            return invalid;
        }
        // 3. 查创建人姓名（写快照 + 给审计用）
        String userName;
        try {
            userName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 4. 生成模板号并落库（status 固定 ACTIVE，sort_num 没传就 0）
        String templateId = DailySeqId.nextGlobal(
                jdbcTemplate, redis, "TPL", "t_quick_reply_template", "template_id", 4);
        jdbcTemplate.update(
                "INSERT INTO t_quick_reply_template (template_id, category, title, content, "
                        + "sort_num, status, creator_id, creator_name) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                templateId, req.getCategory(), req.getTitle(), req.getContent(),
                req.getSortNum() == null ? 0 : req.getSortNum(), userId, userName);

        // 5. 审计日志（文档 §10.3 要求 operation_type='CREATE_TEMPLATE'）
        operationLogService.record(userId, userName, userType,
                "CREATE_TEMPLATE", "TEMPLATE", templateId,
                "新增快捷回复模板[" + req.getCategory() + "]：" + req.getTitle());

        // 6. 回查返回（不凭内存里的值编返回体）
        return Result.success("模板新增成功", loadTemplate(templateId));
    }

    // ============ 编辑（PUT /api/admin/templates/{templateId}） ============

    /**
     * 编辑模板（仅管理员）。**部分更新**：传了值才覆盖，null 表示不动。
     * 只想改话术正文，body 里只写 {"content": "..."} 就行；
     * 想重新启用一个被禁用的模板，写 {"status":"ACTIVE"}。
     */
    @Transactional
    public Result<TemplateItem> updateTemplate(String userId, String userType,
                                              String templateId, TemplateSaveRequest req) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理快捷回复模板");
        }
        // 2. 入参校验
        Result<TemplateItem> invalid = validate(req, false);
        if (invalid != null) {
            return invalid;
        }
        // 3. 存在性校验
        if (loadTemplate(templateId) == null) {
            return Result.fail(404, "模板不存在");
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
        StringBuilder sql = new StringBuilder("UPDATE t_quick_reply_template SET ");
        List<Object> args = new ArrayList<>();
        List<String> sets = new ArrayList<>();

        if (req.getCategory() != null) {
            sets.add("category = ?");
            args.add(req.getCategory());
        }
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

        if (sets.isEmpty()) {
            // 一个字段都没传：不改库，直接把当前值还回去（语义上是"无事发生"，比报错友好）
            return Result.success("没有需要修改的字段", loadTemplate(templateId));
        }

        sql.append(String.join(", ", sets)).append(" WHERE template_id = ?");
        args.add(templateId);
        // update_time 有 ON UPDATE CURRENT_TIMESTAMP，不用手动写
        jdbcTemplate.update(sql.toString(), args.toArray());

        // 6. 审计日志
        operationLogService.record(userId, userName, userType,
                "UPDATE_TEMPLATE", "TEMPLATE", templateId,
                "编辑快捷回复模板：修改了 " + String.join(" / ", changedFields(req)));

        // 7. 回查返回
        return Result.success("模板修改成功", loadTemplate(templateId));
    }

    // ============ 禁用（DELETE /api/admin/templates/{templateId}） ============

    /**
     * 禁用模板（仅管理员）—— **软删除**：status 改成 DISABLED，行还在库里。
     *
     * 为什么不物理 DELETE？和前几个模块同一个理由：
     *   · t_operation_log.target_id 里记着这个 templateId，删了就成了查不到的死引用；
     *   · 禁用是可逆的（PUT 一个 status=ACTIVE 就复活了），
     *     管理员也能用 ?status=DISABLED / ?status=ALL 把它翻出来。
     *
     * 幂等：已经禁用的再禁用一次不报错（affected 可能为 0），仍然返回成功。
     */
    @Transactional
    public Result<Void> disableTemplate(String userId, String userType, String templateId) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理快捷回复模板");
        }
        // 2. 存在性校验
        if (loadTemplate(templateId) == null) {
            return Result.fail(404, "模板不存在");
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
                "UPDATE t_quick_reply_template SET status = 'DISABLED' WHERE template_id = ?",
                templateId);

        // 5. 审计日志
        operationLogService.record(userId, userName, userType,
                "DELETE_TEMPLATE", "TEMPLATE", templateId, "禁用快捷回复模板（软删除）");

        return Result.success("模板已禁用", null);
    }

    // ============ 私有辅助 ============

    /**
     * 入参校验。
     *
     * @param creating true=新增（分类/名称/内容必填）；false=编辑（字段可省，省了表示不改）
     * @return null 表示通过；否则是要原样返回给前端的错误
     */
    private Result<TemplateItem> validate(TemplateSaveRequest req, boolean creating) {
        if (req == null) {
            return Result.fail(400, "请求体不能为空");
        }
        // 分类
        if (creating && req.getCategory() == null) {
            return Result.fail(400, "分类不能为空");
        }
        if (req.getCategory() != null && !CATEGORIES.contains(req.getCategory())) {
            return Result.fail(400, "分类不合法，必须是：学时录入 / 审核驳回 / 活动咨询 / 规则疑问 / 其他");
        }
        // 名称
        if (creating && req.getTitle() == null) {
            return Result.fail(400, "模板名称不能为空");
        }
        if (req.getTitle() != null) {
            if (req.getTitle().isBlank()) {
                return Result.fail(400, "模板名称不能为空白");
            }
            if (req.getTitle().length() > TITLE_MAX) {
                return Result.fail(400, "模板名称不能超过 " + TITLE_MAX + " 字");
            }
        }
        // 内容
        if (creating && req.getContent() == null) {
            return Result.fail(400, "模板内容不能为空");
        }
        if (req.getContent() != null && req.getContent().isBlank()) {
            return Result.fail(400, "模板内容不能为空白");
        }
        // 状态（一般只有编辑会传）
        if (req.getStatus() != null) {
            if (req.getStatus().isBlank()) {
                return Result.fail(400, "状态不能为空白");
            }
            if (!STATUSES.contains(req.getStatus())) {
                return Result.fail(400, "状态不合法，必须是：ACTIVE / DISABLED");
            }
        }
        // 排序号
        if (req.getSortNum() != null && req.getSortNum() < 0) {
            return Result.fail(400, "排序号不能为负数");
        }
        return null;
    }

    /** 列出本次 PUT 实际改动的字段名，写进审计内容（"改了啥"比"改过"有用得多） */
    private List<String> changedFields(TemplateSaveRequest req) {
        List<String> fields = new ArrayList<>();
        if (req.getCategory() != null) fields.add("分类");
        if (req.getTitle() != null) fields.add("名称");
        if (req.getContent() != null) fields.add("内容");
        if (req.getSortNum() != null) fields.add("排序号");
        if (req.getStatus() != null) fields.add("状态");
        return fields;
    }

    /** 查一条模板的完整字段；不存在返回 null（交给调用方决定报 404 还是别的） */
    private TemplateItem loadTemplate(String templateId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT template_id, category, title, content, sort_num, status, creator_name, "
                            + "create_time, update_time FROM t_quick_reply_template "
                            + "WHERE template_id = ?",
                    ItemRowMapper.INSTANCE, templateId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 模板行映射（列表、详情共用同一套字段） */
    private static class ItemRowMapper implements RowMapper<TemplateItem> {
        static final ItemRowMapper INSTANCE = new ItemRowMapper();

        @Override
        public TemplateItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            TemplateItem t = new TemplateItem();
            t.setTemplateId(rs.getString("template_id"));
            t.setCategory(rs.getString("category"));
            t.setTitle(rs.getString("title"));
            t.setContent(rs.getString("content"));
            t.setSortNum(rs.getInt("sort_num"));
            t.setStatus(rs.getString("status"));
            t.setCreatorName(rs.getString("creator_name"));
            t.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            t.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            return t;
        }
    }
}
