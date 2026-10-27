package com.secondclassroom.dto;

/**
 * 批量导入的结果（文档 §10.2 要求"返回成功/跳过数量"）。
 *
 * 三个数各是什么，必须说清楚，不然管理员看到 "成功 3 / 跳过 7" 会以为出了问题：
 *   · total   —— 本次实际处理的**去重后**的词数（重复提交 10 个里有 3 个重复 → total=7）
 *   · added   —— 真正写进库的（新增的 + 之前被禁用、这次恢复启用的）
 *   · skipped —— 本来就已经在库且处于生效状态的，什么都没做
 *
 * 恒等式：added + skipped == total。
 */
public class SensitiveWordBatchResult {

    private int total;
    private int added;
    private int skipped;

    public SensitiveWordBatchResult() {}

    public SensitiveWordBatchResult(int total, int added, int skipped) {
        this.total = total;
        this.added = added;
        this.skipped = skipped;
    }

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }

    public int getAdded() { return added; }
    public void setAdded(int added) { this.added = added; }

    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
}
