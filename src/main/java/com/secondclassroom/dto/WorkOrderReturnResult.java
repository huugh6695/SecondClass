package com.secondclassroom.dto;

/**
 * 退回工单的返回结构（POST /api/work-orders/{id}/return）。
 *
 * 退回不是终态：工单回到 PENDING（待接单池），handler_id / handler_name 被清空，
 * 等下一个干事重新受理。所以这里没有 finishTime，只有最新 version 供前端继续操作。
 */
public class WorkOrderReturnResult {

    private String workOrderId;
    private String status;          // 退回后恒为 PENDING
    private String statusLabel;     // 中文标签「待接单」
    private String remark;          // 原样回显退回原因（没填就是 null）
    private int version;            // 退回后 version 已 +1

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
