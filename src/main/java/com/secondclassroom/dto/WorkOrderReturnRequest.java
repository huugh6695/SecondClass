package com.secondclassroom.dto;

/**
 * 退回工单的请求体（POST /api/work-orders/{id}/return）。
 *
 * version：乐观锁版本号，**必传**。SQL 里会拼 `AND version = ?`，
 *          防止两个干事对着同一张单同时点"退回"，后一个把前一个的结果覆盖掉。
 * remark ：退回原因，选填。文档只在 admin-close 明确写了"必填"，退回没写，所以不强制；
 *          但填了会原样写进 t_status_log.remark，让"为什么退"有据可查。
 */
public class WorkOrderReturnRequest {

    private Integer version;
    private String remark;

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
