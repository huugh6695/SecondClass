package com.secondclassroom.dto;

/**
 * 受理工单的请求体（对应 POST /api/work-orders/{id}/accept）。
 * version：客户端已知的乐观锁版本号（从详情接口的 data.version 拿到）；允许不传。
 */
public class WorkOrderAcceptRequest {

    private Integer version;

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
