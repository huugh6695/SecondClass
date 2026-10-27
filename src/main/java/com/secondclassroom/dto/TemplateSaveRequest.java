package com.secondclassroom.dto;

/**
 * 快捷回复模板的新增（POST）与编辑（PUT）共用请求体。
 *
 * 字段可省，语义按"是否传了"区分 —— 和 FaqSaveRequest / AnnouncementSaveRequest 完全同一套约定：
 *
 *   | 字段      | null（没传）      | ""（空字符串）    | 有值              |
 *   |----------|------------------|------------------|------------------|
 *   | category | POST 400 / PUT 不改 | 400（不是合法分类）| 覆盖（须是 5 个分类之一）|
 *   | title    | POST 400 / PUT 不改 | 400（不能清空）   | 覆盖（≤100 字）    |
 *   | content  | POST 400 / PUT 不改 | 400（不能清空）   | 覆盖              |
 *   | sortNum  | 不改（POST 默认 0） | —                | 覆盖（≥0）         |
 *   | status   | 不改（POST 固定 ACTIVE）| 400           | 覆盖（ACTIVE/DISABLED）|
 *
 * 想把禁用掉的模板重新启用，就是 PUT 一个 {"status":"ACTIVE"}。
 */
public class TemplateSaveRequest {

    /** 分类，必须是：学时录入 / 审核驳回 / 活动咨询 / 规则疑问 / 其他 */
    private String category;

    /** 模板名称，给干事认的标签，≤100 字 */
    private String title;

    /** 现成的回复话术正文 */
    private String content;

    /** 排序号，越小越靠前 */
    private Integer sortNum;

    /** ACTIVE / DISABLED，一般只有编辑时会传 */
    private String status;

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
}
