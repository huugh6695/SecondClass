package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * FAQ **详情**（GET /api/faqs/{faqId} 的 data；管理员新增 / 编辑后也回查它返回）。
 * 比列表项多一个 answer（答案正文），其余的更新时间、创建人给 admin 后台看。
 *
 * category 的取值和工单完全一致（t_faq.category 是同一个中文 ENUM）：
 * 学时录入 / 审核驳回 / 活动咨询 / 规则疑问 / 其他
 */
public class FaqItem {

    private String faqId;
    private String category;
    private String question;
    private String answer;          // 答案正文
    private String keywords;        // 匹配关键词，逗号分隔
    private int sortNum;
    private String status;          // PUBLISHED / UNPUBLISHED
    private int viewCount;
    private String creatorName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public String getFaqId() { return faqId; }
    public void setFaqId(String faqId) { this.faqId = faqId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

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

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
