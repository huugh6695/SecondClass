package com.secondclassroom.dto;

/**
 * 单个新增敏感词的请求体（文档 §10.2）：{"word": "违规词"}
 */
public class SensitiveWordAddRequest {

    /** 要加入敏感词库的词；长度上限 100（表里是 varchar(100)） */
    private String word;

    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }
}
