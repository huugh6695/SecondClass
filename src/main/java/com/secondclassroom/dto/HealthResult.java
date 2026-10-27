package com.secondclassroom.dto;

import java.time.Instant;

/**
 * 健康检查的返回数据（文档 §2）：
 *   { "status": "UP", "db": "UP", "redis": "UP", "timestamp": "2026-08-23T10:00:00Z" }
 *
 * status 是**汇总**：只要有一个关键组件 DOWN，它就是 DOWN（HTTP 也会变 503）。
 *
 * ⚠️ redis 有三个取值，比文档多一个，这是**有意为之**（已与用户确认）：
 *   · "UP"      —— 真的 PING 通了；
 *   · "DOWN"    —— 连不上（这才是故障，会把汇总状态一起拉成 DOWN + HTTP 503）；
 *   · "SKIPPED" —— 探活**开关被关掉了**（app.health.redis-enabled=false）。
 *     这时它不影响汇总状态：DB 正常就是 UP / HTTP 200。
 *     为什么保留这个档？因为它不是"故障"而是"明确不做"：本地没双击启 Redis、
 *     只想看数据库情况时用它，免得每次探活都白等一次超时。
 *     ⑧ 阶段接入 Redis 后默认值是 true，正常情况看到的是 UP / DOWN，不是 SKIPPED。
 *
 * timestamp 用 Instant 而不是 LocalDateTime：文档给的格式是带 Z 的 UTC
 * （"2026-08-23T10:00:00Z"），Instant 正好序列化成这个形状；项目其他地方的中文时间用
 * LocalDateTime + @JsonFormat("yyyy-MM-dd HH:mm:ss")。
 */
public class HealthResult {

    private String status;      // UP / DOWN（汇总）
    private String db;          // UP / DOWN
    private String redis;       // UP / DOWN / SKIPPED
    private Instant timestamp;

    public HealthResult() {}

    public HealthResult(String status, String db, String redis, Instant timestamp) {
        this.status = status;
        this.db = db;
        this.redis = redis;
        this.timestamp = timestamp;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDb() { return db; }
    public void setDb(String db) { this.db = db; }

    public String getRedis() { return redis; }
    public void setRedis(String redis) { this.redis = redis; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
