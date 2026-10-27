package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 关闭工单的返回结构（confirm / close / admin-close 三个接口共用）。
 * action 用来区分这次是哪一种关闭：CONFIRM / MANUAL_CLOSE / ADMIN_CLOSE。
 */
public class WorkOrderCloseResult {

    private String workOrderId;
    private String status;          // 关闭后恒为 CLOSED
    private String statusLabel;     // 中文标签
    private String action;          // CONFIRM / MANUAL_CLOSE / ADMIN_CLOSE
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;
    private int version;            // 关闭后 version 已 +1

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public LocalDateTime getFinishTime() { return finishTime; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime = finishTime; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
