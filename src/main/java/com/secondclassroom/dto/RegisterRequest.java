package com.secondclassroom.dto;

public class RegisterRequest {
    private String userId;
    private String userName;
    private String phone;
    private String smsCode;
    private String password;
    private boolean agreedToPrivacy;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getSmsCode() { return smsCode; }
    public void setSmsCode(String smsCode) { this.smsCode = smsCode; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isAgreedToPrivacy() { return agreedToPrivacy; }
    public void setAgreedToPrivacy(boolean agreedToPrivacy) { this.agreedToPrivacy = agreedToPrivacy; }
}
