package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 通知列表里的一条（对应 API 文档 GET /api/notifications 的 data.list[]）。
 *
 * 字段和文档的通知列表示例一一对应。
 * targetType + targetId 是给前端做「点击跳转」用的：
 *   前端拿到通知后 ① 调 POST /api/notifications/{notifyId}/read 标已读
 *                ② 按 targetType + targetId 跳到对应页面
 * 所以后端在创建通知时就**必须**写这两个字段（H 阶段已经写了）。
 */
public class NotificationListItem {

    private String notifyId;
    private String workOrderId;
    private String type;          // NEW_TICKET / ACCEPT / REPLY / FOLLOW_UP / CLOSE / MERGE
    private String title;
    private String content;
    private String targetType;    // WORK_ORDER
    private String targetId;      // 跳转目标单号
    private Boolean isRead;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public String getNotifyId() { return notifyId; }
    public void setNotifyId(String notifyId) { this.notifyId = notifyId; }

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public Boolean getIsRead() { return isRead; }
    public void setIsRead(Boolean isRead) { this.isRead = isRead; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
