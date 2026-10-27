package com.secondclassroom.service;

import com.secondclassroom.util.DailySeqId;
import com.secondclassroom.util.WebUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 操作审计日志（t_operation_log）。
 *
 * 它和另外两张"日志表"的区别，别搞混：
 *   · t_status_log     —— 只记**工单状态流转**，给工单详情页画时间线用，是业务数据；
 *   · t_notification_log —— 只记**要推给谁的通知**，给通知中心和红点用；
 *   · t_operation_log  —— 记**谁在什么时候干了什么**（含登录、改密码），是给管理员查账用的。
 *
 * 所以审计日志的写法是"宁可多记、不可漏记"：它不参与任何业务判断，
 * 只是往一张只增不改的表里追加一行，谁都不能靠它来改业务结果。
 *
 * 另外注意：这里**不加 @Transactional**。
 * 这是故意的 —— 工单业务方法自己开着事务，JdbcTemplate 会认线程上绑定的那条连接，
 * 于是审计行自动落进同一个事务（业务回滚它也回滚，语义正确：操作没成功就不该留痕）。
 * 而 AuthService 的登录/登出没有事务，它就是一条独立自动提交的 INSERT，也没问题。
 */
@Service
public class OperationLogService {

    private final JdbcTemplate jdbcTemplate;

    /** 生成日志号（OLOG + yyyyMMdd + 6 位序号）用；见 DailySeqId 的类注释 */
    private final StringRedisTemplate redis;

    public OperationLogService(JdbcTemplate jdbcTemplate, StringRedisTemplate redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
    }

    /**
     * 记一条操作日志。
     *
     * 已使用的 operationType（按模块）：
     *   认证：LOGIN / LOGOUT / CHANGE_PASSWORD
     *   工单：SUBMIT / ACCEPT / REPLY / FOLLOW_UP / CONFIRM / MANUAL_CLOSE / ADMIN_CLOSE / RETURN / MERGE
     *   再次咨询：RE_CONSULTATION（re-consult 会另开一张新单，审计用这个类型，不是 SUBMIT）
     *
     * ⚠️ 再次咨询必须用 `RE_CONSULTATION`，**不要图省事复用 `SUBMIT`**。
     *    原因：新工单表上的 `relation_type='RE_CONSULTATION'` 就是它的身份标记，
     *    审计里用同一个名字，才能直接 `GROUP BY operation_type` 统计出"再次咨询占比"。
     *    复用 SUBMIT 的话，第一次提问和"老问题又来了"会被混成一堆，这个指标就永远算不出来。
     *    （注意别和 t_status_log.action 搞混：那张表的 action 是 ENUM，没有 RE_CONSULTATION 这个值，
     *      再次咨询的状态日志仍然写 'SUBMIT'。两张表口径不同是正常的：状态日志描述"工单状态怎么变的"，
     *      审计日志描述"用户干了件什么事"。）
     *
     * @param userId        操作人 ID（从 Token 里来，不用前端传）
     * @param userName      操作人姓名快照（表要求 NOT NULL，所以要查一次库或从业务里带过来）
     * @param userRole      STUDENT / STAFF / ADMIN
     * @param operationType 操作类型，取值见上
     * @param targetType    操作对象类型：USER / WORK_ORDER / FAQ / ANNOUNCEMENT ...
     * @param targetId      操作对象 ID（登录这类操作用自己的 userId）
     * @param content       人能看懂的一句话描述，方便管理员排查（可为 null）
     */
    public void record(String userId, String userName, String userRole,
                       String operationType, String targetType, String targetId,
                       String content) {
        jdbcTemplate.update(
                "INSERT INTO t_operation_log (log_id, user_id, user_name, user_role, "
                        + "operation_type, target_type, target_id, operation_content, ip_address) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                generateLogId(), userId, userName, userRole,
                operationType, targetType, targetId, content, WebUtil.getClientIp());
    }

    /**
     * 日志号：OLOG + yyyyMMdd + 6 位当日序号。
     *
     * 走的是公共的 DailySeqId（MAX(序号)+1，不是 COUNT(*)+1）。
     * ⚠️ 这里踩过一次真实事故：清理测试数据删掉几行审计日志后，COUNT 变小，
     *    生成出了已经存在的 OLOG...047，直接主键冲突报 500。换成 MAX 就不会倒退。
     */
    private String generateLogId() {
        return DailySeqId.next(jdbcTemplate, redis, "OLOG", "t_operation_log", "log_id", 6);
    }
}
