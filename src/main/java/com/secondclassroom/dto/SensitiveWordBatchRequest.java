package com.secondclassroom.dto;

/**
 * 批量导入敏感词的请求体（文档 §10.2）：{"words": "词1,词2,词3,词4"}
 *
 * 文档给的是一个**逗号分隔的长字符串**，不是数组 —— 大概是为了方便管理员从 Excel 里
 * 直接复制一列粘进来（复制出来的就是 "词1,词2,词3" 这种）。
 * 所以服务端要自己 split + 去空白 + 去重。
 */
public class SensitiveWordBatchRequest {

    /** 逗号分隔的敏感词串；中英文逗号、顿号、换行都认（见 Service 里的 splitWords） */
    private String words;

    public String getWords() { return words; }
    public void setWords(String words) { this.words = words; }
}
