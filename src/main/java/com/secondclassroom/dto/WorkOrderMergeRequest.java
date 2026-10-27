package com.secondclassroom.dto;

/**
 * 合并工单的请求体（POST /api/work-orders/{id}/merge）。
 *
 * mainOrderId：要合并进的"主工单"编号。URL 里的 {id} 是"被合并的子工单"。
 * version    ：子工单的乐观锁版本号，**必传**。合并是终态操作，必须防并发。
 *
 * 文档里没有"合并原因"字段，所以 t_status_log 的 remark 传 null。
 */
public class WorkOrderMergeRequest {

    private String mainOrderId;
    private Integer version;

    public String getMainOrderId() { return mainOrderId; }
    public void setMainOrderId(String mainOrderId) { this.mainOrderId = mainOrderId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
