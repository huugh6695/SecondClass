package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 回复工单的返回结构（对应 POST /api/work-orders/{id}/reply 的 data）。
 * 回复成功后：多了一条 REPLY 消息、工单状态变 REPLIED、reply_time 落库、version+1。
 */
public class WorkOrderReplyResult {

    private String messageId;       // 本次新增的消息ID
    private String workOrderId;
    private String status;          // 回复后应为 REPLIED
    private String statusLabel;     // 中文标签
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime replyTime;
    private int version;            // 回复后 version 已 +1

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }

    public LocalDateTime getReplyTime() { return replyTime; }
    public void setReplyTime(LocalDateTime replyTime) { this.replyTime = replyTime; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
