package com.secondclassroom.common;

import com.secondclassroom.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 黑名单：用户登出后把 Token 登记进来，拦截器见到就拒绝。
 *
 * ⑧④ 从「纯内存 Set」换成「Redis 为主 + 内存兜底」：
 *
 *  · 主存 Redis：key = {@code blacklist:token:{token 的 SHA-256}}，value = "1"，
 *    TTL = 这个 Token **自己还剩多少秒**（见 JwtUtil#getRemainingSeconds）。
 *    好处：多台服务器共享、服务重启不丢。
 *
 *  · 为什么 key 存哈希、不存 Token 原文：key 恒为 64 个字符（Token 原文约 200 字符），
 *    而且不会把登录凭证明文摊在 Redis 里。
 *
 *  · 内存兜底：本机写过的记录同时也留在内存里（带同样的过期时间）。
 *    查询时命中内存就**不再问 Redis**，于是带来两个好处：
 *      ① Redis 连不上时，本机登出过的 Token 依然被挡住，不会"全站 500"；
 *      ② 堵住一个隐蔽漏洞 —— Redis 挂掉期间登出的 Token 只落在内存里，
 *         等 Redis 恢复后如果只查 Redis 就会"活过来"；现在内存先命中，照样挡住。
 *    ⚠️ 兜底只在**本机**有效：多实例部署时，A 机器登出的 Token，B 机器在 Redis 挂掉期间看不到。
 *       这是"Redis 不可用"时躲不掉的取舍 —— 用一点点安全缺口换"不整体瘫痪"。
 */
@Component
public class TokenBlacklist {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklist.class);

    /** Redis key 前缀（文档 §1.11 规定：blacklist:token:{...}） */
    private static final String KEY_PREFIX = "blacklist:token:";

    /** 兜底表攒到这个条数就顺手清一次已过期的，免得长期运行越攒越多 */
    private static final int SWEEP_THRESHOLD = 1000;

    /**
     * Redis 出问题时的日志节流：至少隔 30 秒才再报一次。
     * 拦截器**每个请求**都会查黑名单，不节流的话 Redis 一挂日志就被刷爆。
     */
    private static final long WARN_INTERVAL_MS = 30_000L;

    private final StringRedisTemplate redis;
    private final JwtUtil jwtUtil;

    /** 本机登出记录：token 的哈希 -> 绝对过期时间（毫秒）。查不到 = 没被拉黑。 */
    private final Map<String, Long> fallback = new ConcurrentHashMap<>();

    /** 上次打印"Redis 不可用"的时间，用于节流 */
    private volatile long lastWarnAt = 0L;

    public TokenBlacklist(StringRedisTemplate redis, JwtUtil jwtUtil) {
        this.redis = redis;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 登出时调用：把 Token 拉黑，时长 = 这个 Token 自己的剩余寿命。
     *
     * 故意**不抛异常**：Redis 写不进去也不能让登出失败 ——
     * 本机兜底照样能把 Token 挡住，只是重启/换机器后失效而已。
     */
    public void add(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        long ttlSeconds = jwtUtil.getRemainingSeconds(token);
        if (ttlSeconds <= 0) {
            return;   // 已过期 / 无效的 Token 本来就用不了，不必占位置
        }
        String hash = sha256Hex(token);
        // ① 本机兜底：解决"多进程共用一份 Redis 但各自内存独立"之外的可用性问题
        fallback.put(hash, System.currentTimeMillis() + ttlSeconds * 1000L);
        // ② Redis：真正的事实来源，多实例共享
        try {
            redis.opsForValue().set(KEY_PREFIX + hash, "1", Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            warnRedisDown("写入", e);
        }
        if (fallback.size() > SWEEP_THRESHOLD) {
            sweepFallback();
        }
    }

    /** 拦截器每个请求都调一次：这个 Token 被拉黑了吗？ */
    public boolean contains(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String hash = sha256Hex(token);
        // ① 先看本机兜底：命中就一定是"本机登出过"，省掉一次网络往返
        if (containsInFallback(hash)) {
            return true;
        }
        // ② 再问 Redis（本机之外、别的服务器登出的记录在这里）
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + hash));
        } catch (RuntimeException e) {
            warnRedisDown("查询", e);
            return false;   // 只剩"本机兜底"可信，上面已经查过且没命中
        }
    }

    private boolean containsInFallback(String hash) {
        Long expireAt = fallback.get(hash);
        if (expireAt == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireAt) {
            fallback.remove(hash);   // 顺手清掉这一条过期的
            return false;
        }
        return true;
    }

    /** 清掉兜底表里所有已过期的记录 */
    private void sweepFallback() {
        long now = System.currentTimeMillis();
        fallback.entrySet().removeIf(e -> e.getValue() < now);
    }

    private void warnRedisDown(String action, RuntimeException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < WARN_INTERVAL_MS) {
            return;
        }
        lastWarnAt = now;
        log.warn("Redis {}黑名单失败，本次改用内存兜底（单机仍有效，重启/多实例后失效）：{}",
                action, e.getMessage());
    }

    /**
     * Token -> 64 位十六进制字符串（SHA-256）。
     * ⚠️ 这里抛的是 RuntimeException 而不是 IllegalStateException：
     *    全局处理器把 IllegalStateException 认成"工单状态冲突(409)"，会给出驴唇不对马嘴的提示。
     */
    private static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("JVM 不支持 SHA-256，无法计算 Token 哈希", e);
        }
    }
}
