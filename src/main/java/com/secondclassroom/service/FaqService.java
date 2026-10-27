package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.FaqItem;
import com.secondclassroom.dto.FaqListItem;
import com.secondclassroom.dto.FaqSaveRequest;
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
 * FAQ（常见问题）业务逻辑：
 * - 列表（GET /api/faqs，可选 Token：管理员能多看未发布的）
 * - 详情（GET /api/faqs/{faqId}，可选 Token；成功查看后 view_count + 1）
 * - 新增 / 编辑 / 下架（POST|PUT|DELETE /api/admin/faqs...，仅 ADMIN，每次都写审计）
 *
 * FAQ 和工单的关系：学生提问前先翻 FAQ，能自助解决就不建单 —— 这是"减少工单量"的第一道闸门。
 *
 * 几个容易踩的点，写在最前面：
 * 1) **"可选 Token"**：这三个读接口没登录也能看（游客/学生都能查 FAQ），但**登录了的管理员要多看到未发布的内容**。
 *    所以拦截器不能简单地"一律放行"或"一律拦"，见 JwtInterceptor 里的 OPTIONAL_AUTH_PREFIXES。
 * 2) **未发布对普通用户是 404，不是 403**：文档明说"不暴露存在性" ——
 *    否则别人拿 faqId 一个个试，就能数出后台藏了多少条草稿。
 * 3) **view_count 只在详情接口里、权限校验通过之后才 +1**：列表不加、404 不加、后端自己加（不信前端传的数字）。
 * 4) **下架是软删除**：只把 status 改成 UNPUBLISHED，不 DELETE 行 —— 否则审计日志里的 target_id 就成了悬空引用。
 */
@Service
public class FaqService {

    private final JdbcTemplate jdbcTemplate;
    private final OperationLogService operationLogService;

    /** 生成 FAQ 号（FAQ + 6 位全局序号，文档 §7 的 faq:seq）用；见 DailySeqId 的类注释 */
    private final StringRedisTemplate redis;

    /** 允许的分类，和 t_faq.category / t_work_order.category 完全一致（中文 ENUM） */
    private static final List<String> CATEGORIES =
            List.of("学时录入", "审核驳回", "活动咨询", "规则疑问", "其他");

    /** t_faq.status 的两个取值 */
    private static final List<String> STATUSES = List.of("PUBLISHED", "UNPUBLISHED");

    /** 问题标题长度上限（表注释约定"≤50字"） */
    private static final int QUESTION_MAX = 50;

    public FaqService(JdbcTemplate jdbcTemplate, OperationLogService operationLogService,
                      StringRedisTemplate redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationLogService = operationLogService;
        this.redis = redis;
    }

    // ============ 列表（GET /api/faqs） ============

    /**
     * FAQ 列表。文档给的筛选条件是 category + keyword，**没有分页**（FAQ 总量本来就是几十条的量级）。
     *
     * @param userType 当前登录角色；游客传 null。只要不是 ADMIN 就只能看 PUBLISHED。
     */
    public Result<List<FaqListItem>> listFaqs(String userType, String category, String keyword) {
        // 1. 分类筛选：传了就必须合法。校验而不是"查不到就返回空" ——
        //    前端把"学时录入"写成"学时登记"时，静默返回空列表很难排查。
        if (category != null && !category.isBlank() && !CATEGORIES.contains(category)) {
            return Result.fail(400, "分类不合法，必须是：学时录入 / 审核驳回 / 活动咨询 / 规则疑问 / 其他");
        }

        // 2. 只有管理员能看未发布的
        boolean includeUnpublished = "ADMIN".equals(userType);

        // 3. 拼条件（WHERE 1=1 是常用的占位写法，后面每个条件都能无脑 append " AND ..."）
        StringBuilder sql = new StringBuilder(
                "SELECT faq_id, category, question, keywords, sort_num, status, view_count, "
                        + "creator_name, create_time FROM t_faq WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (!includeUnpublished) {
            sql.append(" AND status = 'PUBLISHED'");
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category);
        }
        if (keyword != null && !keyword.isBlank()) {
            // 全文检索：用的是表上的 FULLTEXT(question, keywords) + ngram 分词器（中文才搜得动）。
            // ⚠️ 两个已知限制：① 关键词短于 ngram_token_size（默认 2 个字）搜不出来；
            //    ② NATURAL LANGUAGE MODE 不带词频权重，按相关度排序是"顺带"的，
            //    所以下面还是照文档 ORDER BY sort_num, create_time 排。
            sql.append(" AND MATCH(question, keywords) AGAINST(? IN NATURAL LANGUAGE MODE)");
            args.add(keyword);
        }
        // 文档：ORDER BY sort_num, create_time。末尾补个 faq_id 只是为了"同 sort_num 同时刻"时顺序稳定，
        // 不然两次查询的顺序可能不一样，前端看着像在乱跳。
        sql.append(" ORDER BY sort_num, create_time, faq_id");

        List<FaqListItem> list = jdbcTemplate.query(sql.toString(), FaqListItemRowMapper.INSTANCE,
                args.toArray());
        return Result.success("success", list);
    }

    // ============ 详情（GET /api/faqs/{faqId}） ============

    /**
     * FAQ 详情，并把浏览量 +1。
     *
     * 为什么加 @Transactional？因为这个接口**不是纯读** —— 它要 UPDATE view_count。
     * 放在事务里，"校验通过"和"计数 +1"就是一件事，不会出现"校验过了但计数没加"。
     * （注意即便 return Result.fail(...)，事务也只是被提交，而那种分支里我们一行都没写，所以没有副作用。）
     */
    @Transactional
    public Result<FaqItem> getFaqDetail(String faqId, String userType) {
        FaqItem faq = loadFaq(faqId);

        // 不区分"不存在"和"未发布且你不是管理员"，两者都给同一个 404 —— 不暴露存在性
        if (faq == null) {
            return Result.fail(404, "FAQ 不存在或未发布（FAQ_NOT_FOUND）");
        }
        if (!"PUBLISHED".equals(faq.getStatus()) && !"ADMIN".equals(userType)) {
            return Result.fail(404, "FAQ 不存在或未发布（FAQ_NOT_FOUND）");
        }

        // 权限过了才计数，而且由后端自己 +1（前端传什么都不算）
        jdbcTemplate.update(
                "UPDATE t_faq SET view_count = view_count + 1 WHERE faq_id = ?", faqId);
        faq.setViewCount(faq.getViewCount() + 1);   // 返回给前端的是"含本次访问"的最新值
        return Result.success("success", faq);
    }

    // ============ 新增（POST /api/admin/faqs） ============

    /**
     * 新增 FAQ（仅管理员）。
     *
     * 编号：文档写的是 Redis `INCR faq:seq` → `FAQ{seq:06d}`，是**不带日期的全局序号**。
     * ⑧ 阶段已接上 Redis，现在就是按文档这么做的 —— key 名叫 `faq:seq`（见 DailySeqId.keyName）。
     * 每次新建时若 key 不存在，会先拿库里的最大序号播种，保证不会和已有编号撞车。
     *
     * 新加的 FAQ 直接 PUBLISHED、sort_num 用数据库默认 0 —— 管理员想藏起来随时 DELETE（软删除）。
     */
    @Transactional
    public Result<FaqItem> createFaq(String userId, String userType, FaqSaveRequest req) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理 FAQ");
        }

        // 2. 入参校验（新增时四个字段都必须有）
        Result<FaqItem> invalid = validate(req, true);
        if (invalid != null) {
            return invalid;
        }

        // 3. 查创建人姓名（写进快照，同时给审计用）
        String userName;
        try {
            userName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 4. 生成 FAQ 号并落库
        String faqId = DailySeqId.nextGlobal(jdbcTemplate, redis, "FAQ", "t_faq", "faq_id", 6);
        jdbcTemplate.update(
                "INSERT INTO t_faq (faq_id, category, question, answer, keywords, status, "
                        + "creator_id, creator_name) "
                        + "VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?, ?)",
                faqId, req.getCategory(), req.getQuestion(), req.getAnswer(),
                blankToNull(req.getKeywords()), userId, userName);

        // 5. 审计日志（文档 §8 第 4 步要求）
        operationLogService.record(userId, userName, userType,
                "CREATE_FAQ", "FAQ", faqId, "新建 FAQ[" + req.getCategory() + "]：" + req.getQuestion());

        // 6. 回查返回（和 reply / followUp 一个套路：不凭内存里的值编返回体）
        return Result.success("FAQ 新增成功", loadFaq(faqId));
    }

    // ============ 编辑（PUT /api/admin/faqs/{faqId}） ============

    /**
     * 编辑 FAQ（仅管理员）。**部分更新**：传了非 null 的字段才覆盖，null 表示这个字段不动。
     * 所以只想改答案，body 里只写 {"answer": "..."} 就行。
     *
     * ⚠️ 传了空字符串 / 空白的字段算"非法改动"（400），不算"不改" ——
     *    否则手滑传个 "" 就把问题标题清空了，FAQ 列表里会出现一条没有标题的条目。
     */
    @Transactional
    public Result<FaqItem> updateFaq(String userId, String userType, String faqId, FaqSaveRequest req) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理 FAQ");
        }
        // 2. 入参校验（编辑时字段可省，但省了 ≠ 可以乱传）
        Result<FaqItem> invalid = validate(req, false);
        if (invalid != null) {
            return invalid;
        }
        // 3. 存在性校验
        if (loadFaq(faqId) == null) {
            return Result.fail(404, "FAQ 不存在");
        }

        // 4. 查操作人姓名（审计 + 返回体里可能需要）
        String userName;
        try {
            userName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 5. 动态拼 UPDATE：只更新"传了的"字段。
        //    这样写而不是"全部覆盖"，是为了不让前端漏传一个字段就把库里的值冲掉。
        StringBuilder sql = new StringBuilder("UPDATE t_faq SET ");
        List<Object> args = new ArrayList<>();
        List<String> sets = new ArrayList<>();

        if (req.getCategory() != null) {
            sets.add("category = ?");
            args.add(req.getCategory());
        }
        if (req.getQuestion() != null) {
            sets.add("question = ?");
            args.add(req.getQuestion());
        }
        if (req.getAnswer() != null) {
            sets.add("answer = ?");
            args.add(req.getAnswer());
        }
        if (req.getKeywords() != null) {
            sets.add("keywords = ?");
            args.add(blankToNull(req.getKeywords()));   // 传空串 = 清空关键词（允许，因为它是可选字段）
        }
        if (req.getStatus() != null) {
            sets.add("status = ?");
            args.add(req.getStatus());
        }
        if (req.getSortNum() != null) {
            sets.add("sort_num = ?");
            args.add(req.getSortNum());
        }

        if (sets.isEmpty()) {
            // 一个字段都没传：不改库，直接把当前详情还回去（比报错友好，语义上是"无事发生"）
            return Result.success("没有需要修改的字段", loadFaq(faqId));
        }

        sql.append(String.join(", ", sets)).append(" WHERE faq_id = ?");
        args.add(faqId);
        // update_time 有 ON UPDATE CURRENT_TIMESTAMP，不用手动写
        jdbcTemplate.update(sql.toString(), args.toArray());

        // 6. 审计日志（文档 §8 要求 operation_type='UPDATE_FAQ'）
        operationLogService.record(userId, userName, userType,
                "UPDATE_FAQ", "FAQ", faqId, "编辑 FAQ：修改了 " + String.join(" / ", changedFields(req)));

        // 7. 回查返回
        return Result.success("FAQ 修改成功", loadFaq(faqId));
    }

    // ============ 下架（DELETE /api/admin/faqs/{faqId}） ============

    /**
     * 下架 FAQ（仅管理员）—— **软删除**：只把 status 改成 UNPUBLISHED，行还在库里。
     *
     * 为什么不物理 DELETE？
     *   · 审计日志（t_operation_log.target_id）里记着这个 faqId，删了就成了查不到的死引用；
     *   · 管理员误删还能改回来（再 PUT 一次 status=PUBLISHED 即可）。
     *
     * 幂等：已经下架的再下架一次不报错（affected 可能为 0），仍然返回成功。
     */
    @Transactional
    public Result<Void> unpublishFaq(String userId, String userType, String faqId) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可管理 FAQ");
        }
        // 2. 存在性校验
        if (loadFaq(faqId) == null) {
            return Result.fail(404, "FAQ 不存在");
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
        jdbcTemplate.update("UPDATE t_faq SET status = 'UNPUBLISHED' WHERE faq_id = ?", faqId);

        // 5. 审计日志（文档 §8 要求 operation_type='DELETE_FAQ'）
        operationLogService.record(userId, userName, userType,
                "DELETE_FAQ", "FAQ", faqId, "下架 FAQ（软删除）");

        return Result.success("FAQ 已下架", null);
    }

    // ============ 私有辅助 ============

    /**
     * 入参校验。
     *
     * @param creating true=新增（核心字段必填）；false=编辑（字段可以省，省了表示不改）
     * @return null 表示通过；否则是要原样返回的错误
     */
    private Result<FaqItem> validate(FaqSaveRequest req, boolean creating) {
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
        // 问题标题
        if (creating && req.getQuestion() == null) {
            return Result.fail(400, "问题标题不能为空");
        }
        if (req.getQuestion() != null) {
            if (req.getQuestion().isBlank()) {
                return Result.fail(400, "问题标题不能为空白");
            }
            if (req.getQuestion().length() > QUESTION_MAX) {
                return Result.fail(400, "问题标题不能超过 " + QUESTION_MAX + " 字");
            }
        }
        // 答案
        if (creating && req.getAnswer() == null) {
            return Result.fail(400, "答案不能为空");
        }
        if (req.getAnswer() != null && req.getAnswer().isBlank()) {
            return Result.fail(400, "答案不能为空白");
        }
        // 状态（只有编辑会传）
        if (req.getStatus() != null && !STATUSES.contains(req.getStatus())) {
            return Result.fail(400, "状态不合法，必须是：PUBLISHED / UNPUBLISHED");
        }
        // 排序号
        if (req.getSortNum() != null && req.getSortNum() < 0) {
            return Result.fail(400, "排序号不能为负数");
        }
        return null;
    }

    /** 列出本次 PUT 实际改动的字段名，写进审计内容（"改了啥"比"改过"有用得多）。 */
    private List<String> changedFields(FaqSaveRequest req) {
        List<String> fields = new ArrayList<>();
        if (req.getCategory() != null) fields.add("分类");
        if (req.getQuestion() != null) fields.add("问题");
        if (req.getAnswer() != null) fields.add("答案");
        if (req.getKeywords() != null) fields.add("关键词");
        if (req.getStatus() != null) fields.add("状态");
        if (req.getSortNum() != null) fields.add("排序号");
        return fields;
    }

    /** 关键词是可选字段：空串统一存成 NULL，别在库里留一堆 ''（查询/统计时更干净） */
    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 查一条 FAQ 的完整字段；不存在返回 null（交给调用方决定报 404 还是别的） */
    private FaqItem loadFaq(String faqId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT faq_id, category, question, answer, keywords, sort_num, status, "
                            + "view_count, creator_name, create_time, update_time "
                            + "FROM t_faq WHERE faq_id = ?",
                    FaqItemRowMapper.INSTANCE, faqId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** FAQ 列表项映射（不含 answer，见 FaqListItem 注释） */
    private static class FaqListItemRowMapper implements RowMapper<FaqListItem> {
        static final FaqListItemRowMapper INSTANCE = new FaqListItemRowMapper();

        @Override
        public FaqListItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            FaqListItem f = new FaqListItem();
            f.setFaqId(rs.getString("faq_id"));
            f.setCategory(rs.getString("category"));
            f.setQuestion(rs.getString("question"));
            f.setKeywords(rs.getString("keywords"));
            f.setSortNum(rs.getInt("sort_num"));
            f.setStatus(rs.getString("status"));
            f.setViewCount(rs.getInt("view_count"));
            f.setCreatorName(rs.getString("creator_name"));
            f.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            return f;
        }
    }

    /** FAQ 详情映射 */
    private static class FaqItemRowMapper implements RowMapper<FaqItem> {
        static final FaqItemRowMapper INSTANCE = new FaqItemRowMapper();

        @Override
        public FaqItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            FaqItem f = new FaqItem();
            f.setFaqId(rs.getString("faq_id"));
            f.setCategory(rs.getString("category"));
            f.setQuestion(rs.getString("question"));
            f.setAnswer(rs.getString("answer"));
            f.setKeywords(rs.getString("keywords"));
            f.setSortNum(rs.getInt("sort_num"));
            f.setStatus(rs.getString("status"));
            f.setViewCount(rs.getInt("view_count"));
            f.setCreatorName(rs.getString("creator_name"));
            f.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            f.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            return f;
        }
    }
}
