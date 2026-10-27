package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.MarkReadResult;
import com.secondclassroom.dto.NotificationListItem;
import com.secondclassroom.dto.NotificationListResult;
import com.secondclassroom.dto.UnreadCountResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知中心：通知列表、已读标记、未读数。
 *
 * 这里只管「读」——通知的「写」在 WorkOrderService 里（业务动作发生时顺手 INSERT 一条 PENDING）。
 * 这样分层的好处：WorkOrderService 不关心通知怎么展示，NotificationService 也不关心工单怎么流转。
 *
 * 另一个易混点：系统里有**两种红点**，别搞混了
 *   ① 通知红点：t_notification_log.is_read —— 本类的接口
 *   ② 消息红点：t_message_read 表  —— 见 /api/messages/unread-count 和工单的 messages/read
 * 文档要求「工单的 messages/read」必须在**同一事务**里同时清掉这两种红点，保证前端红点一致。
 */
@Service
public class NotificationService {

    private final JdbcTemplate jdbcTemplate;

    public NotificationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ============ 通知列表（GET /api/notifications） ============

    /**
     * 我的通知列表。
     * 只查自己的（user_id = 当前用户），且只查渠道为 PLATFORM（站内）的 ——
     * BROWSER 推送只是投递方式不同，站内列表不该重复出现同一条。
     */
    public Result<NotificationListResult> list(String userId, Integer page, Integer pageSize, Boolean isRead) {
        // 1. 分页兜底：page 默认 1；pageSize 默认 20，范围 1~50
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 50);

        // 2. 拼条件（isRead 是可选筛选：true 只看已读，false 只看未读，不传看全部）
        StringBuilder where = new StringBuilder(
                " WHERE user_id = ? AND channel = 'PLATFORM'");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (isRead != null) {
            where.append(" AND is_read = ?");
            params.add(isRead ? 1 : 0);
        }

        // 3. 总数
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification_log" + where, Long.class, params.toArray());

        // 4. 分页取数据（新的在前）
        int offset = (p - 1) * ps;
        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(offset);
        queryParams.add(ps);
        List<NotificationListItem> list = jdbcTemplate.query(
                "SELECT notify_id, work_order_id, type, title, content, "
                        + "target_type, target_id, is_read, create_time "
                        + "FROM t_notification_log" + where + " ORDER BY create_time DESC LIMIT ?, ?",
                new NotificationRowMapper(), queryParams.toArray());

        // 5. 组装返回（结构和工单列表一致，前端分页组件可复用）
        NotificationListResult result = new NotificationListResult();
        result.setTotal(total == null ? 0 : total);
        result.setPage(p);
        result.setPageSize(ps);
        result.setList(list);
        return Result.success("success", result);
    }

    // ============ 单条已读（POST /api/notifications/{notifyId}/read） ============

    /**
     * 把一条通知标为已读。
     * SQL 里的 `AND user_id = ?` 是**防越权的关键**：别人的通知改不动（影响 0 行）。
     * 返回 404 而不是 403，是为了不暴露"这条通知存在但不属于你"。
     */
    public Result<MarkReadResult> markRead(String userId, String notifyId) {
        int affected = jdbcTemplate.update(
                "UPDATE t_notification_log SET is_read = 1, read_time = NOW() "
                        + "WHERE notify_id = ? AND user_id = ?",
                notifyId, userId);
        if (affected == 0) {
            return Result.fail(404, "通知不存在或不属于你");
        }
        return Result.success("已标记为已读", new MarkReadResult(notifyId, affected));
    }

    // ============ 全部已读（POST /api/notifications/read-all） ============

    /** 把我所有未读通知一次标完。返回本次真正改动的行数。 */
    public Result<MarkReadResult> markAllRead(String userId) {
        int affected = jdbcTemplate.update(
                "UPDATE t_notification_log SET is_read = 1, read_time = NOW() "
                        + "WHERE user_id = ? AND is_read = 0",
                userId);
        return Result.success("已全部标记为已读", new MarkReadResult(null, affected));
    }

    // ============ 通知未读数（GET /api/notifications/unread-count） ============

    /** 我的未读通知数（前端红点，15 秒轮询一次）。 */
    public Result<UnreadCountResult> unreadCount(String userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification_log "
                        + "WHERE user_id = ? AND channel = 'PLATFORM' AND is_read = 0",
                Long.class, userId);
        return Result.success("success", new UnreadCountResult(count == null ? 0 : count));
    }

    // ============ 消息未读数（GET /api/messages/unread-count） ============

    /**
     * 我在**所有工单里**的未读消息数（另一个红点，和通知红点相互独立）。
     *
     * 判定"未读"的依据是有没有 t_message_read 记录 —— 查不到就是没读过。
     * 三个附加条件：
     *   ① 我能看到这张单（我是提交人 / 我是负责人 / 我是管理员）
     *   ② 不是我发的消息（自己发的不算打扰）
     *   ③ 系统消息（sender_id 为 NULL）要算未读
     *
     * ⚠️ 第 ③ 点是我对文档 SQL 的一处修正：
     *    文档写的是 `m.sender_id != :userId`，但 SQL 里 `NULL != '某学号'` 的结果是
     *    **NULL 而不是 true**，会被 WHERE 当假处理 —— 结果所有 SYSTEM 消息（"XX 已接单"这种）
     *    都被漏掉了。文档的原意是"不统计自己发的消息"，所以我改成：
     *    `(m.sender_id IS NULL OR m.sender_id != ?)`，让 NULL 也计入未读。
     */
    public Result<UnreadCountResult> unreadMessageCount(String userId, String userType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_work_order_message m "
                        + "LEFT JOIN t_message_read r ON r.message_id = m.message_id AND r.user_id = ? "
                        + "INNER JOIN t_work_order w ON w.work_order_id = m.work_order_id "
                        + "WHERE r.read_id IS NULL "
                        + "  AND (w.user_id = ? OR w.handler_id = ? OR ? = 'ADMIN') "
                        + "  AND (m.sender_id IS NULL OR m.sender_id != ?)",
                Long.class, userId, userId, userId, userType, userId);
        return Result.success("success", new UnreadCountResult(count == null ? 0 : count));
    }

    /** 把 t_notification_log 一行映射成列表项 */
    private static class NotificationRowMapper implements RowMapper<NotificationListItem> {
        @Override
        public NotificationListItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            NotificationListItem n = new NotificationListItem();
            n.setNotifyId(rs.getString("notify_id"));
            n.setWorkOrderId(rs.getString("work_order_id"));
            n.setType(rs.getString("type"));
            n.setTitle(rs.getString("title"));
            n.setContent(rs.getString("content"));
            n.setTargetType(rs.getString("target_type"));
            n.setTargetId(rs.getString("target_id"));
            n.setIsRead(rs.getBoolean("is_read"));
            n.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            return n;
        }
    }
}
