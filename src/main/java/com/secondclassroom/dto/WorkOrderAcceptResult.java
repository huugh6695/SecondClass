package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 受理工单的返回结构（对应 POST /api/work-orders/{id}/accept 的 data）。
 * 受理成功后：status=PROCESSING、handler 为当前干事、accept_time 落库、version+1。
 */
public class WorkOrderAcceptResult {

    private String workOrderId;
    private String status;          // 受理后应为 PROCESSING
    private String statusLabel;     // 中文标签
    private String handlerId;
    private String handlerName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime acceptTime;
    private int version;            // 受理后 version 已 +1

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }

    public String getHandlerId() { return handlerId; }
    public void setHandlerId(String handlerId) { this.handlerId = handlerId; }

    public String getHandlerName() { return handlerName; }
    public void setHandlerName(String handlerName) { this.handlerName = handlerName; }

    public LocalDateTime getAcceptTime() { return acceptTime; }
    public void setAcceptTime(LocalDateTime acceptTime) { this.acceptTime = acceptTime; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
