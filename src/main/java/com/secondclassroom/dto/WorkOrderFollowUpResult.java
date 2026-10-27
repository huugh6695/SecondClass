package com.secondclassroom.dto;

/**
 * 学生追问的返回结构（对应 POST /api/work-orders/{id}/follow-up 的 data）。
 * 追问成功后：多了一条 FOLLOW_UP 消息、工单状态从 REPLIED 回到 PROCESSING、
 * 干事未读数 +1、学生自己的未读数清零、version+1。
 */
public class WorkOrderFollowUpResult {

    private String messageId;       // 本次新增的消息ID
    private String workOrderId;
    private String status;          // 追问后应为 PROCESSING
    private String statusLabel;     // 中文标签
    private int staffUnreadCount;   // 追问后干事的未读数
    private int version;            // 追问后 version 已 +1

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }

    public int getStaffUnreadCount() { return staffUnreadCount; }
    public void setStaffUnreadCount(int staffUnreadCount) { this.staffUnreadCount = staffUnreadCount; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
