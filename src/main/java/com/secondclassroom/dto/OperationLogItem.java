package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 一条操作审计日志（t_operation_log 一行）—— 管理员日志查询用。
 *
 * 它回答的问题是"**谁、什么时候、干了什么、对谁干的**"，跟 t_status_log 的分工是：
 *   · t_status_log    —— 工单的**状态**怎么变的（业务时间线，详情页要用）；
 *   · t_operation_log —— 有**谁**动过系统（查账/追责用，登录登出、改密码、管 FAQ 都记在这）。
 *
 * operationType 是 varchar(50) 而不是 ENUM，因为类型会随功能增长：
 *   LOGIN / LOGOUT / CHANGE_PASSWORD / SUBMIT / ACCEPT / REPLY / FOLLOW_UP / CONFIRM /
 *   MANUAL_CLOSE / ADMIN_CLOSE / RETURN / MERGE / RE_CONSULTATION / CREATE_FAQ / UPDATE_FAQ / DELETE_FAQ
 * （正因为会增长，这个接口**不校验** operationType 的取值 —— 校验就等于把列表写死在代码里，
 *   以后加一个新操作类型还得回来改这里。传了没有的类型，就返回空列表。）
 *
 * targetType 常见 WORK_ORDER / USER / FAQ；targetId 是被操作对象的 ID。
 */
public class OperationLogItem {

    private String logId;
    private String userId;          // 操作人
    private String userName;
    private String userRole;        // STUDENT / STAFF / ADMIN
    private String operationType;   // 见上面那一串
    private String targetType;      // WORK_ORDER / USER / FAQ
    private String targetId;
    private String operationContent;// 人话描述，如"提交工单[学时录入]"
    private String ipAddress;       // 取不到时为 null
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operationTime;

    public String getLogId() { return logId; }
    public void setLogId(String logId) { this.logId = logId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getUserRole() { return userRole; }
    public void setUserRole(String userRole) { this.userRole = userRole; }

    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getOperationContent() { return operationContent; }
    public void setOperationContent(String operationContent) { this.operationContent = operationContent; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public LocalDateTime getOperationTime() { return operationTime; }
    public void setOperationTime(LocalDateTime operationTime) { this.operationTime = operationTime; }
}
