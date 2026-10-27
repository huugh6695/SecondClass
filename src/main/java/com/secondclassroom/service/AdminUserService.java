package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.PageResult;
import com.secondclassroom.dto.UserListItem;
import com.secondclassroom.dto.UserStatusUpdateRequest;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.dao.EmptyResultDataAccessException;
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
 * 用户管理（文档 §10.1 / #34~#36），全部仅管理员可用。
 *
 * ──────────────────────────────────────────────────────────────
 * 这一组最容易出的事故是"**把管理员自己锁在系统外**"：
 * 管理员禁用了自己的账号 → 下一个请求就被拦（403）→ 再也进不了后台去把自己改回来。
 * 所以 updateStatus 里有一条硬规则：**不能修改自己的账号状态**。
 * （这条文档没写，是我补的，属于"把明显的坑堵上"，不影响正常业务。）
 *
 * 另一个必须说清楚的**既有局限**（不是这一组引入的）：
 *   JWT 是无状态的 —— **重置密码不会让已经发出去的 Token 失效**。
 *   也就是说，把某个学生的密码重置了，他手上那个 Token 在过期前仍然能用。
 *   要真正"踢下线"，得靠 Redis 黑名单或版本号机制，属于第⑧阶段的事。
 *   （对比：**禁用**账号是立即生效的，因为拦截器每次请求都会查 t_user.status。）
 * ──────────────────────────────────────────────────────────────
 */
@Service
public class AdminUserService {

    private final JdbcTemplate jdbcTemplate;
    private final OperationLogService operationLogService;

    /** t_user.user_type 的三个取值（固定 ENUM，所以筛选时可以严格校验） */
    private static final List<String> USER_TYPES = List.of("STUDENT", "STAFF", "ADMIN");

    /** t_user.status 的两个取值 */
    private static final List<String> STATUSES = List.of("ACTIVE", "DISABLED");

    /** 重置密码时取学号末尾几位（文档规定"默认密码 = 学号后6位"） */
    private static final int DEFAULT_PASSWORD_SUFFIX = 6;

    /** bcrypt 代价因子（文档写的是 hash(pwd, 10)，和登录/改密码那边保持一致） */
    private static final int BCRYPT_ROUNDS = 10;

    public AdminUserService(JdbcTemplate jdbcTemplate, OperationLogService operationLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationLogService = operationLogService;
    }

    // ============ 列表（GET /api/admin/users） ============

    /**
     * 用户列表（仅管理员），支持 角色 / 状态 / 关键词 三个筛选 + 分页。
     *
     * 分页沿用项目统一约定：page 默认 1、pageSize 默认 20、**上限 50**，
     * 排序 `create_time DESC, user_id DESC`（补主键是为了同秒创建的用户翻页不重复不漏）。
     *
     * 筛选参数严格校验：user_type 和 status 都是**固定 ENUM**（不是 operation_type 那种会长大的 varchar），
     * 传个不存在的值说明是前端写错了，给 400 比静默返回空列表好排查。
     *
     * @param operatorUserType 操作人的角色（必须是 ADMIN）
     * @param roleFilter       按角色筛（STUDENT / STAFF / ADMIN），可空
     * @param statusFilter     按状态筛（ACTIVE / DISABLED），可空
     * @param keyword          关键词，同时模糊匹配 userId 和 userName，可空
     */
    public Result<PageResult<UserListItem>> listUsers(String operatorUserType,
                                                     String roleFilter, String statusFilter,
                                                     String keyword,
                                                     Integer page, Integer pageSize) {
        // 1. 角色校验
        if (!"ADMIN".equals(operatorUserType)) {
            return Result.fail(403, "仅管理员可查看用户列表");
        }
        // 2. 筛选值校验
        if (roleFilter != null && !roleFilter.isBlank() && !USER_TYPES.contains(roleFilter)) {
            return Result.fail(400, "角色不合法，必须是：STUDENT / STAFF / ADMIN");
        }
        if (statusFilter != null && !statusFilter.isBlank() && !STATUSES.contains(statusFilter)) {
            return Result.fail(400, "状态不合法，必须是：ACTIVE / DISABLED");
        }
        // 3. 分页兜底
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 50);

        // 4. 拼条件（WHERE 1=1 占位）
        StringBuilder where = new StringBuilder(" FROM t_user WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (roleFilter != null && !roleFilter.isBlank()) {
            where.append(" AND user_type = ?");
            params.add(roleFilter);
        }
        if (statusFilter != null && !statusFilter.isBlank()) {
            where.append(" AND status = ?");
            params.add(statusFilter);
        }
        if (keyword != null && !keyword.isBlank()) {
            // 学号精确匹配会更快的场景这里不考虑 —— 关键词就是"随手搜一下"，
            // 同时糊 userId 和 userName 最符合直觉（搜"2023"或搜"张"都能命中）。
            where.append(" AND (user_id LIKE ? OR user_name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
        }

        // 5. 总数
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + where, Long.class, params.toArray());

        // 6. 当前页
        int offset = (p - 1) * ps;
        List<Object> queryParams = new ArrayList<>(params);
        // ⚠️ MySQL 的 `LIMIT a, b` 语义是「**跳过 a 行、取 b 行**」——
        // 也就是第一个 ? 是 offset、第二个 ? 是 pageSize，**顺序不能反**。
        // （写反了是"静默返回 0 行"：total 正常、list 空，接口不报错，最难查的一种。）
        queryParams.add(offset);
        queryParams.add(ps);
        List<UserListItem> list = jdbcTemplate.query(
                "SELECT user_id, user_name, user_type, status, subscribe_status, "
                        + "create_time, last_login_time" + where
                        + " ORDER BY create_time DESC, user_id DESC LIMIT ?, ?",
                ItemRowMapper.INSTANCE, queryParams.toArray());

        return Result.success("success", PageResult.of(total == null ? 0 : total, p, ps, list));
    }

    // ============ 启用 / 禁用（PUT /api/admin/users/{userId}/status） ============

    /**
     * 启用或禁用用户（仅管理员）。
     *
     * 文档要求：`UPDATE t_user SET status = ? WHERE user_id = ?`，不物理删除；
     * 审计 operation_type 按方向区分：**DISABLE_USER / ENABLE_USER**，target_id = 被操作用户。
     *
     * 🔴 补的一条硬规则：**不能修改自己的账号状态**。
     *    不留这条的话，管理员手一滑把自己禁用了 → 下一个请求就被拦截器 403 →
     *    再也进不了后台把自己改回来（只能去数据库里手动 UPDATE 救命）。
     *    这是"明显的事故入口"，堵上它不影响任何正常业务。
     */
    @Transactional
    public Result<UserListItem> updateStatus(String operatorId, String operatorUserType,
                                            String targetUserId, UserStatusUpdateRequest req) {
        // 1. 角色校验
        if (!"ADMIN".equals(operatorUserType)) {
            return Result.fail(403, "仅管理员可启用/禁用账号");
        }
        // 2. 入参校验
        String status = req == null ? null : req.getStatus();
        if (status == null || status.isBlank()) {
            return Result.fail(400, "状态不能为空");
        }
        if (!STATUSES.contains(status)) {
            return Result.fail(400, "状态不合法，必须是：ACTIVE / DISABLED");
        }
        // 3. 不许动自己（见上面注释）
        if (targetUserId != null && targetUserId.equals(operatorId)) {
            return Result.fail(400, "不能修改自己的账号状态，请让其他管理员操作");
        }
        // 4. 目标用户必须存在
        UserListItem target = loadUser(targetUserId);
        if (target == null) {
            return Result.fail(404, "用户不存在");
        }
        // 5. 查操作人姓名（审计用）
        String operatorName = loadUserName(operatorId);
        if (operatorName == null) {
            return Result.fail(404, "操作人不存在");
        }

        // 6. 更新状态
        jdbcTemplate.update("UPDATE t_user SET status = ? WHERE user_id = ?", status, targetUserId);

        // 7. 审计日志（文档 §10.1 要求：DISABLE_USER / ENABLE_USER，target_id = 被操作用户）
        boolean disabling = "DISABLED".equals(status);
        operationLogService.record(operatorId, operatorName, operatorUserType,
                disabling ? "DISABLE_USER" : "ENABLE_USER", "USER", targetUserId,
                (disabling ? "禁用账号：" : "启用账号：")
                        + targetUserId + "（" + target.getUserName() + " / " + target.getUserType() + "）");

        return Result.success(disabling ? "账号已禁用" : "账号已启用", loadUser(targetUserId));
    }

    // ============ 重置密码（POST /api/admin/users/{userId}/reset-password） ============

    /**
     * 重置用户密码为**默认密码 = 学号后 6 位**（仅管理员）。
     *
     * 文档明确写了："（管理员不知道明文密码，仅知道规则"学号后6位"）" ——
     * 所以**返回体里不带明文密码**，只回一句提示。这不是"懒"，是设计意图：
     * 管理员知道规则就够了，系统不该把明文吐回来（吐回来就会写进日志、留在浏览器里）。
     *
     * 边界情况：userId 不足 6 位时（比如测试用的短账号），取整个 userId 当默认密码 ——
     * 否则 substring 会越界抛异常，变成用户看不懂的 500。
     *
     * ⚠️ 局限（见类注释）：重置密码**不会**让该用户已发出的 Token 失效（JWT 无状态）。
     *    真要"踢下线"需要 Redis 黑名单，属第⑧阶段。
     */
    @Transactional
    public Result<Void> resetPassword(String operatorId, String operatorUserType, String targetUserId) {
        // 1. 角色校验
        if (!"ADMIN".equals(operatorUserType)) {
            return Result.fail(403, "仅管理员可重置密码");
        }
        // 2. 目标用户必须存在
        UserListItem target = loadUser(targetUserId);
        if (target == null) {
            return Result.fail(404, "用户不存在");
        }
        // 3. 查操作人姓名（审计用）
        String operatorName = loadUserName(operatorId);
        if (operatorName == null) {
            return Result.fail(404, "操作人不存在");
        }

        // 4. 算默认密码：学号后 6 位（不够 6 位就全用）
        String defaultPassword = targetUserId.length() <= DEFAULT_PASSWORD_SUFFIX
                ? targetUserId
                : targetUserId.substring(targetUserId.length() - DEFAULT_PASSWORD_SUFFIX);

        // 5. bcrypt 哈希后写库（绝不存明文）
        jdbcTemplate.update("UPDATE t_user SET password = ? WHERE user_id = ?",
                BCrypt.hashpw(defaultPassword, BCrypt.gensalt(BCRYPT_ROUNDS)), targetUserId);

        // 6. 审计日志（文档 §10.1 要求 operation_type='RESET_PASSWORD'，target_id = 被操作用户）
        operationLogService.record(operatorId, operatorName, operatorUserType,
                "RESET_PASSWORD", "USER", targetUserId,
                "重置密码：" + targetUserId + "（" + target.getUserName() + "），"
                        + "已重置为该账号后 " + DEFAULT_PASSWORD_SUFFIX + " 位");

        return Result.success("密码已重置为该账号后 " + DEFAULT_PASSWORD_SUFFIX + " 位", null);
    }

    // ============ 私有辅助 ============

    /** 查一个用户（不含 password 字段）；不存在返回 null */
    private UserListItem loadUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT user_id, user_name, user_type, status, subscribe_status, "
                            + "create_time, last_login_time FROM t_user WHERE user_id = ?",
                    ItemRowMapper.INSTANCE, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 查操作人姓名（审计要求 NOT NULL，所以查不到就直接失败） */
    private String loadUserName(String userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 用户行映射（**没有 password**，这是刻意的，见 UserListItem 的注释） */
    private static class ItemRowMapper implements RowMapper<UserListItem> {
        static final ItemRowMapper INSTANCE = new ItemRowMapper();

        @Override
        public UserListItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            UserListItem u = new UserListItem();
            u.setUserId(rs.getString("user_id"));
            u.setUserName(rs.getString("user_name"));
            u.setUserType(rs.getString("user_type"));
            u.setStatus(rs.getString("status"));
            u.setSubscribeStatus(rs.getString("subscribe_status"));
            u.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            u.setLastLoginTime(rs.getObject("last_login_time", LocalDateTime.class));
            return u;
        }
    }
}
