package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 一条状态流转日志（t_status_log 一行）—— 管理员日志查询用。
 *
 * 它和工单详情里的 statusHistory 是**同一张表**，区别只在于：
 *   · statusHistory（详情里）只看一张单、字段精简到 5 个（前端画进度条够了）；
 *   · 这里给管理员"查账"，所以多带 logId / operatorId / operatorRole / remark ——
 *     排查问题时，"谁、以什么角色、为什么这么做"都得看得见。
 *
 * action 取值（ENUM）：SUBMIT / ACCEPT / REPLY / FOLLOW_UP / CONFIRM / MANUAL_CLOSE /
 *                      ADMIN_CLOSE / RETURN / MERGE / TRANSFER
 * remark 目前只有 ADMIN_CLOSE（管理员强制关闭）会写"关闭理由"。
 */
public class StatusLogItem {

    private String logId;
    private String workOrderId;
    private String beforeStatus;    // SUBMIT 时为 null（工单诞生，没有前一状态）
    private String afterStatus;
    private String action;
    private String operatorId;
    private String operatorName;
    private String operatorRole;    // STUDENT / STAFF / ADMIN
    private String remark;          // 备注，多半是 null
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operationTime;

    public String getLogId() { return logId; }
    public void setLogId(String logId) { this.logId = logId; }

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getBeforeStatus() { return beforeStatus; }
    public void setBeforeStatus(String beforeStatus) { this.beforeStatus = beforeStatus; }

    public String getAfterStatus() { return afterStatus; }
    public void setAfterStatus(String afterStatus) { this.afterStatus = afterStatus; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }

    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }

    public String getOperatorRole() { return operatorRole; }
    public void setOperatorRole(String operatorRole) { this.operatorRole = operatorRole; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public LocalDateTime getOperationTime() { return operationTime; }
    public void setOperationTime(LocalDateTime operationTime) { this.operationTime = operationTime; }
}
