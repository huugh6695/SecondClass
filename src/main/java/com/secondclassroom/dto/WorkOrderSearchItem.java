package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 后台工单搜索每一条的返回结构（文档 §10.7 / 接口 #50 的 list[] 元素）。
 *
 * 为什么不复用学生端的 WorkOrderListItem？因为字段其实**对不上**：
 *   · 这边要 userId / handlerId（管理员查账得看到学号、工号），学生端不需要；
 *   · 这边要 acceptTime / replyTime / finishTime（管理员关心处理时效），学生端只给 createTime；
 *   · 这边要 isFlagged（配合敏感词库做复核）；
 *   · 反过来，学生端的 unreadCount / version 在这儿没用。
 * 硬塞进一个类会让两边的字段都变得含糊，所以**另建一个**（和 FAQ 拆
 * FaqListItem / FaqItem 是同一个思路）。
 *
 * ⚠️ isFlagged 的 getter 必须叫 getIsFlagged()，**不能**叫 isFlagged()：
 *    Jackson 会把 `isXxx()` 当成布尔属性和它的属性名 `xxx`，字段就会输出成 "flagged"，
 *    和文档对不上。（已有 WorkOrderDetailResult 就是这么写的，保持一致。）
 */
public class WorkOrderSearchItem {

    private String workOrderId;
    private String userId;              // 提交人学号
    private String userName;            // 提交人姓名
    private String category;
    private String content;
    private String status;              // 英文枚举
    private String statusLabel;         // 中文标签
    private String handlerId;           // 负责人工号（未接单为 null）
    private String handlerName;         // 负责人姓名（未接单为 null）
    private Boolean isFlagged;          // 是否命中敏感词
    private String duplicateOfOrderId;  // 被合并到哪张主单（没有则为 null）
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime acceptTime;   // 受理时间，可能为 null
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime replyTime;    // 首次回复时间，可能为 null
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;   // 完结时间，可能为 null

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

    public Boolean getIsFlagged() { return isFlagged; }
    public void setIsFlagged(Boolean isFlagged) { this.isFlagged = isFlagged; }

    public String getDuplicateOfOrderId() { return duplicateOfOrderId; }
    public void setDuplicateOfOrderId(String duplicateOfOrderId) { this.duplicateOfOrderId = duplicateOfOrderId; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getAcceptTime() { return acceptTime; }
    public void setAcceptTime(LocalDateTime acceptTime) { this.acceptTime = acceptTime; }

    public LocalDateTime getReplyTime() { return replyTime; }
    public void setReplyTime(LocalDateTime replyTime) { this.replyTime = replyTime; }

    public LocalDateTime getFinishTime() { return finishTime; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime = finishTime; }
}
