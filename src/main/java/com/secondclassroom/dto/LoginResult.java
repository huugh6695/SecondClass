package com.secondclassroom.dto;

/**
 * 登录成功后的返回数据，会包在 Result.data 里。
 * 对应你 API 文档里的 token / userId / userName / role / needChangePassword
 */
public class LoginResult {
    private String token;              // JWT 字符串
    private String userId;            // 学号
    private String userName;          // 姓名
    private String role;              // 角色：STUDENT / STAFF / ADMIN
    private boolean needChangePassword; // 是否仍是默认密码（学号后6位），需强制改密

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public boolean isNeedChangePassword() { return needChangePassword; }
    public void setNeedChangePassword(boolean needChangePassword) { this.needChangePassword = needChangePassword; }
}
