package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * FAQ **列表**项（对应 GET /api/faqs 返回的数组元素）。
 *
 * 注意这里**故意不带 answer（答案正文）**：
 *   · 列表页展示的是"问题目录"，点开某一条才去看答案；
 *   · 文档把 view_count 的自增放在**详情接口**里（"viewCount 只有在用户有权限成功查看 FAQ 内容后才允许增加"），
 *     如果列表就把答案发下去了，详情接口就永远不会被调用，浏览量永远是 0 —— 那这个字段就白设计了。
 *   想一次拿全部内容的话，走详情接口。
 */
public class FaqListItem {

    private String faqId;
    private String category;
    private String question;
    private String keywords;        // 匹配关键词，逗号分隔（列表页一般不用，先带着）
    private int sortNum;
    private String status;          // PUBLISHED / UNPUBLISHED（非管理员只会看到 PUBLISHED）
    private int viewCount;
    private String creatorName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public String getFaqId() { return faqId; }
    public void setFaqId(String faqId) { this.faqId = faqId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public int getSortNum() { return sortNum; }
    public void setSortNum(int sortNum) { this.sortNum = sortNum; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getViewCount() { return viewCount; }
    public void setViewCount(int viewCount) { this.viewCount = viewCount; }

    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String creatorName) { this.creatorName = creatorName; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
