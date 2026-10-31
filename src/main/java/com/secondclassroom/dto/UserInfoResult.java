package com.secondclassroom.dto;

/**
 * /api/auth/me 的返回数据。
 * 注意：这里刻意不含 password 等敏感字段，只返回前端需要展示的信息。
 */
public class UserInfoResult {
    private String userId;
    private String userName;
    private String role;               // STUDENT / STAFF / ADMIN
    private String openId;             // 当前版本恒为 null（未来微信扩展）
    private String subscribeStatus;    // 当前版本恒为 UNSUBSCRIBED
    private boolean phoneBound;
    private String maskedPhone;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getOpenId() { return openId; }
    public void setOpenId(String openId) { this.openId = openId; }

    public String getSubscribeStatus() { return subscribeStatus; }
    public void setSubscribeStatus(String subscribeStatus) { this.subscribeStatus = subscribeStatus; }

    public boolean isPhoneBound() { return phoneBound; }
    public void setPhoneBound(boolean phoneBound) { this.phoneBound = phoneBound; }

    public String getMaskedPhone() { return maskedPhone; }
    public void setMaskedPhone(String maskedPhone) { this.maskedPhone = maskedPhone; }
}
