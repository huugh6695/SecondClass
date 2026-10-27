package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 敏感词（t_sensitive_word）的对外结构。
 *
 * ⚠️ wordId 是 **int**（表里是 `word_id int AUTO_INCREMENT`），不是像工单号/附件号那样的字符串 ——
 *    这是项目里少见的自增主键。所以它不需要 DailySeqId 生成器，
 *    但**传参时要注意**：路径上写的 `:wordId` 如果收成 int/Integer，
 *    用户传个 `abc` 会让 Spring 抛 MethodArgumentTypeMismatchException，
 *    全局异常处理器没覆盖它 → 直接 500。
 *    （同一个坑在 AdminLogService 的日期参数上踩过。）所以 Controller 里收成 String 自己解析。
 */
public class SensitiveWordItem {

    private Integer wordId;

    /** 敏感词本身，表上是 UNIQUE（uk_word） */
    private String word;

    /** ACTIVE=生效中 / DISABLED=已禁用（软删除） */
    private String status;

    private String creatorName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public Integer getWordId() { return wordId; }
    public void setWordId(Integer wordId) { this.wordId = wordId; }

    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String creatorName) { this.creatorName = creatorName; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
