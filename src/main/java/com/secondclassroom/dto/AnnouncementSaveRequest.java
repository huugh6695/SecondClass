package com.secondclassroom.dto;

/**
 * 公告的新增（POST）与编辑（PUT）共用请求体。
 *
 * 字段可省，语义按"是否传了"区分 —— 这套约定和 FaqSaveRequest 完全一样，别记两套：
 *
 *   | 字段        | null（没传 / 显式传 null） | ""（空字符串）        | 有值              |
 *   |------------|--------------------------|---------------------|------------------|
 *   | title      | POST 报 400 / PUT 不改    | 报 400（不能清空标题） | 覆盖             |
 *   | content    | POST 报 400 / PUT 不改    | 报 400（不能清空内容） | 覆盖             |
 *   | sortNum    | 不改（POST 默认 0）       | —（不是字符串）      | 覆盖             |
 *   | expireTime | **不修改**                | **清空为 null**     | 覆盖             |
 *   | status     | 不改（POST 固定 ACTIVE）  | 报 400              | 覆盖             |
 *
 * ⚠️ 只有 expireTime 区分了"没传"和"传空串"：
 *    没传 = 别动它；传 "" = 我要把这个公告改成**永久有效**。因为清除过期时间是个正经操作，
 *    得给它一个表达方式（其它字段不允许被清空，所以没这个烦恼）。
 *    注意 JSON 里 `"expireTime": null` 和"整个字段不写"Jackson 都给你 null，区分不了，
 *    所以统一按"不修改"处理。
 *
 * ⚠️ expireTime 特意声明成 **String** 而不是 LocalDateTime：
 *    交给 Jackson 反序列化，格式一错就抛 HttpMessageNotReadableException，
 *    全局异常处理器没覆盖它 → 用户看到 500。收成 String 自己解析，才能回一个
 *    "过期时间格式不正确，应为 yyyy-MM-dd HH:mm:ss" 这种能看懂的 400。
 *    （同样的坑在 AdminLogService 的日期参数上踩过一次。）
 */
public class AnnouncementSaveRequest {

    /** 公告标题，≤30 字（表注释约定的） */
    private String title;

    /** 公告内容 */
    private String content;

    /** 排序号，越小越靠前 */
    private Integer sortNum;

    /** 过期时间，格式 "yyyy-MM-dd HH:mm:ss"；空串 = 清除（永久有效） */
    private String expireTime;

    /** 状态，ACTIVE / ARCHIVED，一般只有编辑时会传 */
    private String status;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getSortNum() { return sortNum; }
    public void setSortNum(Integer sortNum) { this.sortNum = sortNum; }

    public String getExpireTime() { return expireTime; }
    public void setExpireTime(String expireTime) { this.expireTime = expireTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
