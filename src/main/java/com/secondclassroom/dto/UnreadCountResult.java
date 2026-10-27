package com.secondclassroom.dto;

/**
 * 未读数返回结构，「通知未读」和「消息未读」两个接口共用。
 *
 * 都是同一套红点机制，所以字段名统一叫 unreadCount：
 *   GET /api/notifications/unread-count → {"unreadCount": 5}
 *   GET /api/messages/unread-count      → {"unreadCount": 3}
 * 前端每 15 秒轮询一次（页面不可见时暂停）。
 */
public class UnreadCountResult {

    private long unreadCount;

    public UnreadCountResult() {}

    public UnreadCountResult(long unreadCount) {
        this.unreadCount = unreadCount;
    }

    public long getUnreadCount() { return unreadCount; }
    public void setUnreadCount(long unreadCount) { this.unreadCount = unreadCount; }
}
