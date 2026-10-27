package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.HealthResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

/**
 * 健康检查（文档 §2 / 接口 #0）：GET /health，**无需 Token**。
 *
 * 用途：运维监控、负载均衡探活、K8s liveness/readiness probe。
 * 所以它必须满足三个"不能"（文档明确要求）：
 *   · **不能被认证拦住** —— 探活工具不会带 Token。已在 WebConfig 里把 /health 排除出拦截器；
 *   · **不能记操作日志** —— 探活几秒一次，记了会把审计日志彻底糊掉；
 *   · **不能查业务表** —— 只做 SELECT 1，不碰 t_work_order 之类的表（探活不该有业务副作用）。
 *
 * 单项检测超时 3 秒（文档要求）：DB 用 Statement.setQueryTimeout；
 * Redis 用 spring.data.redis.timeout / connect-timeout（都配成 2s，见 application.yml）。
 *
 * 关于 redis 的三种取值：UP / DOWN / SKIPPED。SKIPPED 只在 `app.health.redis-enabled=false`
 * 时出现（那是本地没启 Redis 时的调试开关）；⑧ 阶段接入后默认 true，真实探活。
 *
 * ⚠️ 这个接口是全项目**第二个 HTTP 状态码不是 200 的例外**（第一个是拦截器里的 401）：
 * 文档要求"任一组件不可用 → 503"，所以这里刻意不走"HTTP 恒 200"那套约定。
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    /** 单项检测超时（秒），文档 §2 要求 3s */
    private static final int TIMEOUT_SECONDS = 3;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Redis 探活用官方客户端。
     *
     * ⚠️ 这里刻意用 **StringRedisTemplate** 而不是 RedisTemplate：
     *    Boot 自动配置的那个 `RedisTemplate<Object,Object>` 用的是 JDK 序列化，
     *    key 存进去会变成一堆带二进制前缀的乱码（`redis-cli` 里看起来像乱码、也跟别的语言对不上）。
     *    StringRedisTemplate 的 key/value 都是纯字符串，存进去长什么样就是什么样。
     *    ⇒ 本项目所有 Redis 读写统一走 StringRedisTemplate，**不要注入那个默认的 RedisTemplate**。
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Redis 探活开关。默认为 true（已接入）；设成 false 时 /health 里 redis 报 "SKIPPED"
     * 且不拖累整体 status —— 留给"本地没起 Redis 又想看 DB 情况"的场景。
     */
    @Value("${app.health.redis-enabled:true}")
    private boolean redisEnabled;

    public HealthController(JdbcTemplate jdbcTemplate, StringRedisTemplate stringRedisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<Result<HealthResult>> health() {
        String db = checkDb();
        String redis = checkRedis();
        // 汇总规则：DB 必须 UP；redis 只要不是 DOWN（UP 或 SKIPPED）都算不拖后腿
        boolean allUp = "UP".equals(db) && !"DOWN".equals(redis);
        String status = allUp ? "UP" : "DOWN";
        HealthResult data = new HealthResult(status, db, redis, Instant.now());

        if (allUp) {
            return ResponseEntity.ok(Result.success("ok", data));
        }
        // 文档规定：任一组件不可用 → HTTP 503 + code=500（message 也要一模一样）
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Result<>(500, "service unavailable", data));
    }

    /**
     * DB 探活：执行 `SELECT 1 FROM DUAL`（文档里写的就是这条）。
     *
     * 为什么用 ConnectionCallback 手写 Statement，而不是直接 jdbcTemplate.queryForObject？
     *   因为要设 **3 秒查询超时**。JdbcTemplate 也有 setQueryTimeout()，但那是**实例级**的
     *   （改了会影响所有用到它的地方），而它是个共享的单例 Bean —— 为了探活去改全局超时不合适。
     *
     * 顺带说明：连接池（HikariCP）自己的 connection-timeout 也配成了 3s（见 application.yml），
     * 否则"拿不到连接"这一步可能要等默认的 30 秒，整个探活就失去意义了。
     */
    private String checkDb() {
        try {
            jdbcTemplate.execute((ConnectionCallback<Integer>) conn -> {
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(TIMEOUT_SECONDS);
                    try (ResultSet rs = st.executeQuery("SELECT 1 FROM DUAL")) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            });
            return "UP";
        } catch (Exception e) {
            log.warn("健康检查：数据库探活失败 - {}", e.getMessage());
            return "DOWN";
        }
    }

    /**
     * Redis 探活。
     *
     * 关掉开关（本地没启 Redis 时的调试档）→ 直接返回 "SKIPPED"，连都不连。
     *
     * 开着开关 → 用官方客户端发一次 PING（走的是 spring.data.redis.* 那份配置）：
     *   `RedisConnection.ping()` 底下的动作和手写 socket 发 "PING\r\n" 完全一样，
     *   只是交给 Lettuce 去管连接、超时和重连，我们不用自己拼协议。
     *   ⚠️ 用 `redisTemplate.execute(RedisCallback)` 是为了让 Spring 负责**归还连接** ——
     *      自己 `getConnectionFactory().getConnection()` 拿连接很容易忘了关，连接池会被耗干。
     */
    private String checkRedis() {
        if (!redisEnabled) {
            return "SKIPPED";
        }
        try {
            String pong = stringRedisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
            return "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN";
        } catch (Exception e) {
            // 连接被拒 / 超时 / 认证失败都落到这里。异常消息里带 host:port，排障够用了
            log.warn("健康检查：Redis 探活失败 - {}", e.getMessage());
            return "DOWN";
        }
    }
}
