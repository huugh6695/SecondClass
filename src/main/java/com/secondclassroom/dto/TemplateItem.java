package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 快捷回复模板（t_quick_reply_template）的对外结构。
 *
 * 这东西的用途：干事回复工单时，界面上给一排"常用话术"按钮，点一下就把 content 填进输入框，
 * 改两个字就能发。它解决的是"同一句话每天要手打二十遍"的问题 ——
 * 所以模板的 content 是**给别人看的现成话术**，不是给学生自助查的答案（那是 FAQ）。
 *
 * 和 FAQ 的区别一句话：FAQ 学生自己看，模板是**干事回复时套用**的。
 */
public class TemplateItem {

    /** TPL + 4 位序号，如 TPL0001 */
    private String templateId;

    /** 分类，和 t_work_order.category 同一套中文 ENUM */
    private String category;

    /** 模板名称（不是内容标题，就是给干事认的标签，≤100 字） */
    private String title;

    /** 现成的回复话术正文 */
    private String content;

    /** 排序号，越小越靠前（列表按它排） */
    private Integer sortNum;

    /** ACTIVE=可用 / DISABLED=已禁用（软删除） */
    private String status;

    private String creatorName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getSortNum() { return sortNum; }
    public void setSortNum(Integer sortNum) { this.sortNum = sortNum; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String creatorName) { this.creatorName = creatorName; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
