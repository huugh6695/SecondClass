package com.secondclassroom.dto;

/**
 * POST /api/work-orders/{id}/messages/read 的返回结构。
 *
 * 这个接口一次要动两张表（文档明确要求同事务）：
 *   t_message_read     —— 把这张单的所有消息标成"当前用户已读"
 *   t_notification_log —— 把这张单相关的通知也标已读
 * 两个计数分别返回，方便前端/排查时看清楚到底改了多少行。
 */
public class WorkOrderMessagesReadResult {

    private String workOrderId;
    private int messageCount;         // 本次新插入的"消息已读"记录数
    private int notificationCount;    // 本次新标记的通知数

    public WorkOrderMessagesReadResult() {}

    public WorkOrderMessagesReadResult(String workOrderId, int messageCount, int notificationCount) {
        this.workOrderId = workOrderId;
        this.messageCount = messageCount;
        this.notificationCount = notificationCount;
    }

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    public int getNotificationCount() { return notificationCount; }
    public void setNotificationCount(int notificationCount) { this.notificationCount = notificationCount; }
}
