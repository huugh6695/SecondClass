package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 公告（t_announcement）的对外结构 —— 列表、新增、编辑的返回都用它。
 *
 * 为什么不学 FAQ 拆成「列表项 / 详情」两个类？
 *   因为 FAQ 拆开是为了**列表里不返回 answer**（几百字的答案，一页 20 条就是好几十 KB，
 *   而列表页根本不显示它）。公告的 title/content 都要在列表页展示（后台是"公告栏"卡片样式），
 *   拆开反而要写两套 mapper，没有收益。
 *
 * 时间字段都加了 @JsonFormat：默认 Jackson 对 LocalDateTime 会输出
 * `2026-09-27T00:39:32.123456` 这种带 T 带纳秒的格式，前端还得自己处理。
 * 统一定成 "yyyy-MM-dd HH:mm:ss"，和文档里所有示例的写法一致。
 */
public class AnnouncementItem {

    /** NOTICE + 6 位序号，如 NOTICE000001 */
    private String noticeId;

    /** 公告标题（≤30 字） */
    private String title;

    /** 公告内容，支持富文本（前端按 HTML 渲染，后端只当字符串存） */
    private String content;

    /** 排序号，越小越靠前 */
    private Integer sortNum;

    /** ACTIVE=展示中 / ARCHIVED=已归档（软删除） */
    private String status;

    /** 过期时间；为 null 表示**永久有效**（列表 SQL 里就是这个语义） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;

    /** 发布人姓名快照（发布时从 t_user 抄一份，发布人改名字不影响历史公告） */
    private String publisherName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public String getNoticeId() { return noticeId; }
    public void setNoticeId(String noticeId) { this.noticeId = noticeId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getSortNum() { return sortNum; }
    public void setSortNum(Integer sortNum) { this.sortNum = sortNum; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }

    public String getPublisherName() { return publisherName; }
    public void setPublisherName(String publisherName) { this.publisherName = publisherName; }

    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
