package com.secondclassroom.model;

import java.time.LocalDateTime;

/**
 * t_work_order 表的实体类，字段与数据库一一对应。
 * category / status / relation_type 等枚举在库里是中文，这里统一用 String 存。
 */
public class WorkOrder {
    private String workOrderId;
    private String userId;
    private String userName;
    private String category;        // 学时录入 / 审核驳回 / 活动咨询 / 规则疑问 / 其他
    private String content;
    private String status;          // PENDING / PROCESSING / REPLIED / CLOSED / MERGED
    private String handlerId;
    private String handlerName;
    private String mainOrderId;
    private String sourceOrderId;
    private String relationType;
    private Boolean isRepeat;
    private String duplicateOfOrderId;
    private Boolean isFlagged;
    private String requestId;
    private int studentUnreadCount;
    private int staffUnreadCount;
    private int version;
    private LocalDateTime createTime;
    private LocalDateTime acceptTime;
    private LocalDateTime replyTime;
    private LocalDateTime finishTime;
    private LocalDateTime updateTime;

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

    public String getHandlerId() { return handlerId; }
    public void setHandlerId(String handlerId) { this.handlerId = handlerId; }

    public String getHandlerName() { return handlerName; }
    public void setHandlerName(String handlerName) { this.handlerName = handlerName; }

    public String getMainOrderId() { return mainOrderId; }
    public void setMainOrderId(String mainOrderId) { this.mainOrderId = mainOrderId; }

    public String getSourceOrderId() { return sourceOrderId; }
    public void setSourceOrderId(String sourceOrderId) { this.sourceOrderId = sourceOrderId; }

    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }

    public Boolean getIsRepeat() { return isRepeat; }
    public void setIsRepeat(Boolean isRepeat) { this.isRepeat = isRepeat; }

    public String getDuplicateOfOrderId() { return duplicateOfOrderId; }
    public void setDuplicateOfOrderId(String duplicateOfOrderId) { this.duplicateOfOrderId = duplicateOfOrderId; }

    public Boolean getIsFlagged() { return isFlagged; }
    public void setIsFlagged(Boolean isFlagged) { this.isFlagged = isFlagged; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public int getStudentUnreadCount() { return studentUnreadCount; }
    public void setStudentUnreadCount(int studentUnreadCount) { this.studentUnreadCount = studentUnreadCount; }

    public int getStaffUnreadCount() { return staffUnreadCount; }
    public void setStaffUnreadCount(int staffUnreadCount) { this.staffUnreadCount = staffUnreadCount; }

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

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
