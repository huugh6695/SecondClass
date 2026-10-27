package com.secondclassroom.dto;

/**
 * 启用 / 禁用用户的请求体（文档 §10.1）：{"status": "DISABLED"}
 *
 * 只有这一个字段 —— 状态是**开关**，不是"改成某个值"：
 * 想启用传 ACTIVE，想禁用传 DISABLED，没有第三种。
 */
public class UserStatusUpdateRequest {

    /** ACTIVE=启用 / DISABLED=禁用 */
    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
