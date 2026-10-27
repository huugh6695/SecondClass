package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 用户列表项（t_user），给管理后台的用户管理页用。
 *
 * ⚠️ **绝对不要把 password 放进这个类**。哪怕前端用不上、哪怕只是"顺手带上"，
 *    一旦返回体里出现 password 字段，整个 bcrypt 哈希就随着每次列表查询漏出去了
 *    （日志、浏览器 DevTools、抓包都能看到）。这类"多带一个字段"的疏忽是最常见的低级漏洞。
 *
 * 同理没带 open_id：那是微信公众号的 OpenID，属于用户标识，列表页用不着。
 */
public class UserListItem {

    /** 学号 / 工号 */
    private String userId;

    private String userName;

    /** STUDENT / STAFF / ADMIN */
    private String userType;

    /** ACTIVE / DISABLED —— DISABLED 的账号**每次请求**都会被拦（见 JwtInterceptor） */
    private String status;

    /** 微信公众号订阅状态，未来扩展用；现在固定 UNSUBSCRIBED */
    private String subscribeStatus;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 最后一次登录时间；从没登录过是 null */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastLoginTime;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSubscribeStatus() { return subscribeStatus; }
    public void setSubscribeStatus(String subscribeStatus) { this.subscribeStatus = subscribeStatus; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getLastLoginTime() { return lastLoginTime; }
    public void setLastLoginTime(LocalDateTime lastLoginTime) { this.lastLoginTime = lastLoginTime; }
}
