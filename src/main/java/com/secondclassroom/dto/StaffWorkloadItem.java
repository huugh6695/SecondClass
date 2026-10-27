package com.secondclassroom.dto;

/**
 * 干事工作量统计里的一行（文档 §10.4 / 接口 #46）。
 *
 * 对应文档给的 SQL：
 *   SELECT handler_id, handler_name,
 *     COUNT(*) as total,
 *     SUM(CASE WHEN status='CLOSED' THEN 1 ELSE 0 END) as closed_count,
 *     AVG(TIMESTAMPDIFF(MINUTE, accept_time, finish_time)) as avg_close_minutes
 *   FROM t_work_order
 *   WHERE handler_id IS NOT NULL AND create_time BETWEEN ? AND ?
 *   GROUP BY handler_id, handler_name
 *   ORDER BY total DESC
 *
 * ⚠️ 文档**没给响应示例**（写完 SQL 就直接跳到 10.5 导出了），
 *    所以字段名由我按 SQL 的列名转成驼峰定：handlerId / handlerName / total / closedCount / avgCloseMinutes。
 *    如果前端另有约定，改这里就行，影响面只有这一个类。
 *
 * ⚠️ avgCloseMinutes 的语义值得留意：SQL 里 TIMESTAMPDIFF(MINUTE, accept_time, finish_time)
 *    在 finish_time 为 NULL（还没完结）时返回 NULL，而 AVG **会自动跳过 NULL**。
 *    所以它的含义其实是"**已完结那些单**的平均办理时长"，不是"这个干事所有单的平均"。
 *    文档就是这么写的，我照做，但这个区别必须说清楚，免得被误读成"效率低"。
 */
public class StaffWorkloadItem {

    private String handlerId;
    private String handlerName;
    private long total;             // 该干事名下的单量
    private long closedCount;       // 其中已完结的
    private long avgCloseMinutes;   // 已完结单的平均办理时长（分钟）= 受理 → 完结

    public String getHandlerId() { return handlerId; }
    public void setHandlerId(String handlerId) { this.handlerId = handlerId; }

    public String getHandlerName() { return handlerName; }
    public void setHandlerName(String handlerName) { this.handlerName = handlerName; }

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }

    public long getClosedCount() { return closedCount; }
    public void setClosedCount(long closedCount) { this.closedCount = closedCount; }

    public long getAvgCloseMinutes() { return avgCloseMinutes; }
    public void setAvgCloseMinutes(long avgCloseMinutes) { this.avgCloseMinutes = avgCloseMinutes; }
}
