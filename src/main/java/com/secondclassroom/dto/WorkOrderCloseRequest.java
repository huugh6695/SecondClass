package com.secondclassroom.dto;

/**
 * 关闭工单的请求体（confirm / close / admin-close 三个接口共用）。
 * version：乐观锁版本号，**必传**（文档规定），SQL 里会拼 `AND version = ?`
 * remark ：关闭原因，**只有管理员强制关闭（admin-close）需要**，另外两个接口留空即可
 */
public class WorkOrderCloseRequest {

    private Integer version;
    private String remark;

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
