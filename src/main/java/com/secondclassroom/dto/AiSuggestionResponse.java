package com.secondclassroom.dto;

import java.util.List;

/**
 * 干事端「AI 回复建议」的返回体。
 *
 * 为什么不用一个裸字符串：干事需要知道这段草稿**依据了什么**。
 * 只给一段话、不给来源，干事没法判断该不该信 —— 而这类内容一旦说错，
 * 学生照着做是要吃亏的。
 */
public class AiSuggestionResponse {

    /** 工单号（回显，前端好确认自己拿的是哪张单的建议） */
    private String workOrderId;

    /**
     * 生成方式：
     * AI          = 真模型生成，suggestion 是模型写的
     * SEARCH_ONLY = 没有调模型（未开启 / 没配密钥 / 调用失败），suggestion 是相关 FAQ 答案的拼接
     */
    private String mode;

    /** mode=SEARCH_ONLY 时的原因，给前端直接显示用；AI 正常时为 null */
    private String reason;

    /** 建议草稿正文。干事可直接采用，也可以改完再发 */
    private String suggestion;

    /** 依据的 FAQ（最多 5 条）。SEARCH_ONLY 时就是拼接的那些 */
    private List<Source> sources;

    /** 本次调用耗时（毫秒）。含本地检索 + 模型返回 */
    private long elapsedMs;

    /** 实际使用的模型名；未调模型时为 null */
    private String model;

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }

    public List<Source> getSources() { return sources; }
    public void setSources(List<Source> sources) { this.sources = sources; }

    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    /** 一条被引用的 FAQ */
    public static class Source {
        private String faqId;
        private String question;
        private String answer;

        public Source() { }

        public Source(String faqId, String question, String answer) {
            this.faqId = faqId;
            this.question = question;
            this.answer = answer;
        }

        public String getFaqId() { return faqId; }
        public void setFaqId(String faqId) { this.faqId = faqId; }

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }

        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
    }
}
