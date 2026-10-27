package com.secondclassroom.dto;

/**
 * 「标记通知已读」的返回结构，单条已读和全部已读两个接口共用。
 *   POST /api/notifications/{notifyId}/read → { "notifyId": "NOT...", "markedCount": 1 }
 *   POST /api/notifications/read-all        → { "notifyId": null,    "markedCount": 7 }
 *
 * markedCount 是本次 UPDATE 真正影响了多少行，两个接口的"0"含义不同：
 *   - 单条已读：0 只在「这条通知不存在 / 不属于你」时出现 → 接口会返回 404。
 *              （对已读的再标一次仍然返回 1，因为 SET read_time=NOW() 确实把时间戳改了，
 *                相当于自动刷新已读时间 —— 重复调用是幂等的，不会报错。）
 *   - 全部已读：0 表示「本来就没有未读」→ 正常返回，不是错误。
 */
public class MarkReadResult {

    private String notifyId;      // 单条已读时有值；全部已读时为 null
    private int markedCount;

    public MarkReadResult() {}

    public MarkReadResult(String notifyId, int markedCount) {
        this.notifyId = notifyId;
        this.markedCount = markedCount;
    }

    public String getNotifyId() { return notifyId; }
    public void setNotifyId(String notifyId) { this.notifyId = notifyId; }

    public int getMarkedCount() { return markedCount; }
    public void setMarkedCount(int markedCount) { this.markedCount = markedCount; }
}
