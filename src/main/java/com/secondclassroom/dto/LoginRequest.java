package com.secondclassroom.dto;

/**
 * 登录请求体：前端 POST /api/auth/login 时传来的 JSON。
 * 对应你 API 文档里的 { "userId": "2023001001", "password": "123456" }
 */
public class LoginRequest {
    private String userId;
    private String password;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
