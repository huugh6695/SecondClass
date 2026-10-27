package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 工单列表里每一条的返回结构（对应 API 文档 GET /api/work-orders 的 list[] 元素）。
 * 比完整工单少一些字段，但多了 statusLabel（中文标签）和 unreadCount（未读数）。
 */
public class WorkOrderListItem {

    private String workOrderId;
    private String category;
    private String content;
    private String status;              // 英文枚举：PENDING / PROCESSING / ...
    private String statusLabel;         // 中文标签：待接单 / 处理中 / ...
    private String userName;            // 提交学生姓名
    private String handlerName;         // 处理干事姓名（未接单为 null）
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    private int unreadCount;            // 当前用户视角下的未读消息数
    private String duplicateOfOrderId;
    private int version;

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getHandlerName() { return handlerName; }
    public void setHandlerName(String handlerName) { this.handlerName = handlerName; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }

    public String getDuplicateOfOrderId() { return duplicateOfOrderId; }
    public void setDuplicateOfOrderId(String duplicateOfOrderId) { this.duplicateOfOrderId = duplicateOfOrderId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
