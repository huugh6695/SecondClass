package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单详情的返回结构（对应 API 文档 GET /api/work-orders/{id} 的 data）。
 * 比列表项字段全，还多三块"明细"：
 *   · messages       —— 消息时间线（每条消息自带 attachments）
 *   · attachments    —— **工单级**附件（提交工单 / 再次咨询时带上来的，target_type='WORK_ORDER'）
 *   · statusHistory  —— 状态流转历史（画进度条用）
 *
 * mainOrderId / sourceOrderId 是"这张单从哪来 / 到哪去"的两个指针，**平时都是 null**：
 *   · mainOrderId   —— 被合并进的主工单（MERGED 时才有值，前端据此显示"已被合并"）
 *   · sourceOrderId —— 再次咨询的来源老单（由 POST /{老单号}/re-consult 建出的新单才有值，
 *                      前端据此显示"本单由 XXX 再次咨询而来"，并可点着跳回老单）
 */
public class WorkOrderDetailResult {

    private String workOrderId;
    private String userId;
    private String userName;
    private String category;
    private String content;
    private String status;              // 英文枚举
    private String statusLabel;         // 中文标签
    private String handlerId;
    private String handlerName;
    private String mainOrderId;         // 被合并到的主工单（MERGED 时才有值，前端据此跳转）
    private String sourceOrderId;       // 再次咨询的来源老单（re-consult 建的新单才有值，平时 null）
    private Boolean isFlagged;          // 是否命中敏感词
    private String duplicateOfOrderId;
    private int version;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime acceptTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime replyTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;
    private List<WorkOrderMessageItem> messages;   // 消息时间线
    private List<AttachmentItem> attachments;      // 工单级附件（挂在工单本身上，不是某条消息）
    private List<StatusHistoryItem> statusHistory; // 状态流转历史

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }

    public String getHandlerId() { return handlerId; }
    public void setHandlerId(String handlerId) { this.handlerId = handlerId; }

    public String getHandlerName() { return handlerName; }
    public void setHandlerName(String handlerName) { this.handlerName = handlerName; }

    public String getMainOrderId() { return mainOrderId; }
    public void setMainOrderId(String mainOrderId) { this.mainOrderId = mainOrderId; }

    public String getSourceOrderId() { return sourceOrderId; }
    public void setSourceOrderId(String sourceOrderId) { this.sourceOrderId = sourceOrderId; }

    public Boolean getIsFlagged() { return isFlagged; }
    public void setIsFlagged(Boolean isFlagged) { this.isFlagged = isFlagged; }

    public String getDuplicateOfOrderId() { return duplicateOfOrderId; }
    public void setDuplicateOfOrderId(String duplicateOfOrderId) { this.duplicateOfOrderId = duplicateOfOrderId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getAcceptTime() { return acceptTime; }
    public void setAcceptTime(LocalDateTime acceptTime) { this.acceptTime = acceptTime; }

    public LocalDateTime getReplyTime() { return replyTime; }
    public void setReplyTime(LocalDateTime replyTime) { this.replyTime = replyTime; }

    public LocalDateTime getFinishTime() { return finishTime; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime = finishTime; }

    public List<WorkOrderMessageItem> getMessages() { return messages; }
    public void setMessages(List<WorkOrderMessageItem> messages) { this.messages = messages; }

    public List<AttachmentItem> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentItem> attachments) { this.attachments = attachments; }

    public List<StatusHistoryItem> getStatusHistory() { return statusHistory; }
    public void setStatusHistory(List<StatusHistoryItem> statusHistory) { this.statusHistory = statusHistory; }
}
