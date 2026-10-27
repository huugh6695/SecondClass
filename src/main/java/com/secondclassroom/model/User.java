package com.secondclassroom.model;

import java.time.LocalDateTime;

/**
 * t_user 表的实体类（数据容器）。
 * 字段和数据库表一一对应；这里用 String 存 user_type / status，
 * 因为数据库里这俩枚举本来就是英文（STUDENT/STAFF/ADMIN、ACTIVE/DISABLED），直接拿字符串最省事。
 */
public class User {
    private String userId;
    private String userName;
    private String userType;          // STUDENT / STAFF / ADMIN
    private String password;          // bcrypt 哈希值
    private String openId;            // 未来微信扩展字段
    private String subscribeStatus;   // SUBSCRIBED / UNSUBSCRIBED
    private String status;            // ACTIVE / DISABLED
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime lastLoginTime;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getOpenId() { return openId; }
    public void setOpenId(String openId) { this.openId = openId; }

    public String getSubscribeStatus() { return subscribeStatus; }
    public void setSubscribeStatus(String subscribeStatus) { this.subscribeStatus = subscribeStatus; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public LocalDateTime getLastLoginTime() { return lastLoginTime; }
    public void setLastLoginTime(LocalDateTime lastLoginTime) { this.lastLoginTime = lastLoginTime; }
}
