package com.secondclassroom.dto;

/**
 * 管理员新增 / 编辑 FAQ 的请求体（POST /api/admin/faqs 与 PUT /api/admin/faqs/{faqId} 共用）。
 *
 * 为什么两个接口共用一个类？因为字段完全一样，只是**语义不同**：
 *   · POST（新增）：只用 category / question / answer / keywords；
 *     status 固定写 'PUBLISHED'、sortNum 取数据库默认 0 —— 新加的 FAQ 没必要一上来就"未发布"，
 *     管理员想下架随时可以 DELETE（软删除）。传了也会被忽略。
 *   · PUT（编辑）：6 个字段都是"**传了才改**"，传 null 表示这个字段保持原样。
 *     所以只想改答案的话，{"answer": "..."} 就够了，不用把整条记录重新传一遍。
 *     ⚠️ 但传了空字符串不算"不改"，会报 400（不能把问题标题改成空白）。
 *
 * category 必须是 t_faq.category 这个中文 ENUM 里的值，校验在 Service 里做。
 */
public class FaqSaveRequest {

    private String category;
    private String question;   // 问题标题，≤50 字（表注释的约定）
    private String answer;     // 标准答案，支持富文本
    private String keywords;   // 匹配关键词，逗号分隔，全文检索用
    private String status;     // 仅 PUT 有效：PUBLISHED / UNPUBLISHED
    private Integer sortNum;   // 仅 PUT 有效：排序号，越小越靠前

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getSortNum() { return sortNum; }
    public void setSortNum(Integer sortNum) { this.sortNum = sortNum; }
}
