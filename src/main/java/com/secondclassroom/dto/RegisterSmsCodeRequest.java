package com.secondclassroom.dto;

public class RegisterSmsCodeRequest {
    private String userId;
    private String userName;
    private String phone;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}
