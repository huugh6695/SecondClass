package com.secondclassroom.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 幂等存储：让"怕重复提交"的写接口，重复点第二次时**不会真的做两遍**。
 * 对应设计文档 §1.11「幂等机制」。
 *
 * <h3>没有它会出什么事？</h3>
 * 场景：学生点"提交工单"，网卡了一下，浏览器/前端自动重试了一次；
 * 或者用户手快点了两下。后端就会收到两个几乎同时到达的请求 ——
 * 于是**凭空多出一张工单**（附件被绑走、干事收到两条通知）。
 * 有了幂等，第二次请求会被识别成"同一个请求"，直接返回第一次的结果。
 *
 * <h3>它是怎么认人的？—— 靠 requestId + 请求指纹</h3>
 * <ul>
 *   <li><b>requestId</b>：前端为**每一次点击**生成的 UUID。重试时前端会沿用同一个 requestId，
 *       所以它天然就是"同一次操作"的名字。</li>
 *   <li><b>请求指纹（fingerprint）</b>：把"谁 + 调哪个接口 + 传了什么参数"拼起来算 SHA-256。
 *       用来防一种前端 bug：两次**不同**的提交不小心用了同一个 requestId。
 *       这时不能把上次的结果还给用户（那是驴唇不对马嘴），必须报 409 IDEMPOTENCY_CONFLICT。</li>
 * </ul>
 *
 * <h3>Redis 里存两个 key（与文档命名一致）</h3>
 * <pre>
 *   idempotent:{requestId}         值 PROCESSING，60 秒过期  —— 处理中的"锁"
 *   idempotent:{requestId}:result  值 {"fp":"...","data":{...}}，1 小时过期 —— 成功结果
 * </pre>
 * 锁只有 60 秒，是因为它只用来挡"真正同时到达"的请求；万一业务处理超时没释放，
 * 60 秒后自动过期，不至于把一个 requestId 永久锁死。
 *
 * <h3>为什么锁在"干活的代码"之前抢，而不是之后？</h3>
 * 因为要挡住的正是**同时到达**的两个请求。抢不到锁的那个立刻返回 409「请求正在处理中」，
 * 连数据库都不用碰 —— 比"两个都写进去、靠唯一索引撞掉一个"干净得多。
 *
 * <h3>⚠️ Redis 挂了怎么办：不静默降级</h3>
 * 与单号生成器（{@link com.secondclassroom.util.DailySeqId}）保持一致：直接抛
 * {@link IdempotencyException}（→ HTTP 500，消息写清原因）。
 * 因为这几个写接口本来就依赖 Redis 发号，Redis 挂了它们必然失败，
 * 与其偷偷跳过幂等、让用户看到"提交成功两次"，不如响亮地报出来。
 *
 * <p>⚠️ 小知识：本项目是 **Spring Boot 4**，JSON 库已经换成 **Jackson 3** ——
 * 包名是 {@code tools.jackson.*}，不再是 {@code com.fasterxml.jackson.*}
 * （DTO 上那些 {@code @JsonProperty} 注解仍来自 jackson-annotations，那个包名没变）。
 * 而且 Jackson 3 的异常 {@code JacksonException} 是**非受检**的（继承 RuntimeException），
 * 编译器不强制你 catch —— 下面显式接住只是为了"缓存坏了就当成没命中"。
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    /** 处理中的锁：60 秒（文档 §1.11 第 6 条：应对处理超时） */
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    /** 成功结果的缓存：1 小时（文档 §1.11 第 4 条） */
    private static final Duration RESULT_TTL = Duration.ofHours(1);

    private static final String KEY_PREFIX = "idempotent:";
    private static final String RESULT_SUFFIX = ":result";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public IdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等总入口：**把"真正要干活的那段代码"当作参数传进来**，剩下的事它全包了。
     *
     * <p>用法（Controller 里一行）：
     * <pre>
     *   return idempotencyStore.run(requestId, fingerprint, WorkOrderSubmitResult.class,
     *           "工单提交成功", () -> workOrderService.submit(userId, req));
     * </pre>
     * 把业务代码写成 {@code () -> ...}（一个 Supplier）传进去，是为了让
     * "提交 / 回复 / 追问 / 再次咨询"四个接口共用**同一段**幂等流程，不用各写一遍。
     *
     * @param requestId      前端传的 UUID；为 null/空白时说明是老客户端，直接干活不做幂等
     * @param fingerprint    请求指纹，见 {@link #fingerprint(String...)}
     * @param dataType       成功结果里 data 的类型（从缓存还原时用）
     * @param successMessage 成功提示语。**从缓存重放时也用同一句**，这样重试的响应和第一次一模一样
     * @param work           真正执行业务的代码
     */
    public <T> Result<T> run(String requestId, String fingerprint, Class<T> dataType,
                             String successMessage, Supplier<Result<T>> work) {
        // 0) 前端没传 requestId → 按老办法直接干活（幂等是"可选增强"，不能影响老客户端）
        if (requestId == null || requestId.isBlank()) {
            return work.get();
        }

        // 1) 这个 requestId 之前**成功过**吗？成功过就把上次的结果原样还回去（结果缓存 1 小时）
        Cached cached = readResult(requestId);
        if (cached != null) {
            if (!cached.fingerprint().equals(fingerprint)) {
                // 同一个 requestId 却带着不一样的参数 → 前端复用 ID 的 bug，明确报错
                return Result.fail(409, "请求ID已被其他请求占用（IDEMPOTENCY_CONFLICT）");
            }
            return Result.success(successMessage, toObject(cached.data(), dataType));
        }

        // 2) 抢锁（SETNX）。抢不到 = 有一个**一模一样的请求**正在处理中，让它等一等
        if (!tryLock(requestId)) {
            return Result.fail(409, "请求正在处理中，请稍候再试（IDEMPOTENT_PROCESSING）");
        }

        // 3) 干活
        try {
            Result<T> result = work.get();
            if (result.getCode() == 0 && result.getData() != null) {
                // 成功 → 把结果缓存起来，下次重试直接还回去
                saveResult(requestId, fingerprint, result.getData());
            } else {
                // 失败（参数错 / 权限不够 / 状态冲突……）→ 把锁放开，
                // 否则用户改了参数再传同一个 requestId 重试，会莫名其妙地被自己的锁挡 60 秒
                release(requestId);
            }
            return result;
        } catch (RuntimeException e) {
            // 抛异常（乐观锁冲突、数据库出错……）→ 同样放开锁，然后原样往上抛，
            // 让全局处理器去翻译成对应的错误码
            release(requestId);
            throw e;
        }
    }

    /**
     * 算请求指纹：把几段文字拼起来做 SHA-256，得到一串定长十六进制。
     *
     * <p>为什么要算成哈希、而不是直接把原文存进 Redis？
     * 一是长度固定（用户正文可能有几百字）；二是避免把用户输入原样丢进缓存。
     *
     * <p>拼接用 {@code \u001F}（ASCII 的"单元分隔符"）而不是 {@code |} 之类：
     * 用户正文里可能出现竖线，拼起来会有歧义（"a|b"+"c" 和 "a"+"b|c" 会撞成同一个串）。
     * 控制字符不可能出现在正常输入里，用它分隔才真的分得开。
     */
    public static String fingerprint(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(part == null ? "" : part).append('\u001F');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然自带 SHA-256，走到这里说明环境坏了
            throw new IdempotencyException("无法计算请求指纹：SHA-256 不可用", e);
        }
    }

    // ==================== 下面都是内部细节 ====================

    /** 抢锁：SETNX + 60 秒过期。true = 抢到了（我是第一个） */
    private boolean tryLock(String requestId) {
        Boolean ok = callRedis(requestId, () -> redis.opsForValue()
                .setIfAbsent(lockKey(requestId), "PROCESSING", LOCK_TTL));
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 放开锁。**故意做成"尽力而为"**：失败只记一条 warn，不往外抛。
     * 因为它总是出现在 catch 块里（业务已经出错），如果这里再抛一个，
     * 就会把真正的错误原因顶掉，排查时更糊涂。
     */
    private void release(String requestId) {
        try {
            redis.delete(lockKey(requestId));
        } catch (RuntimeException e) {
            log.warn("幂等锁释放失败（不影响业务结果）：requestId={}, 原因={}", requestId, e.getMessage());
        }
    }

    /** 存"指纹 + 结果" */
    private void saveResult(String requestId, String fingerprint, Object data) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("fp", fingerprint);
        node.set("data", objectMapper.valueToTree(data));
        callRedisVoid(requestId, () -> redis.opsForValue()
                .set(resultKey(requestId), node.toString(), RESULT_TTL));
    }

    /** 读缓存：返回 null = 没缓存（没成功过，或缓存已过期） */
    private Cached readResult(String requestId) {
        String json = callRedis(requestId, () -> redis.opsForValue().get(resultKey(requestId)));
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return new Cached(node.path("fp").asText(), node.path("data"));
        } catch (JacksonException e) {
            // 缓存内容坏了（理论上不会）→ 当成"没缓存"，让请求正常往下走。
            // 宁可少一次重放，也不能因为一条脏缓存把接口打死。
            log.warn("幂等缓存解析失败，按未命中处理：requestId={}", requestId);
            return null;
        }
    }

    /** 把缓存的 JSON 还原成 Java 对象 */
    private <T> T toObject(JsonNode node, Class<T> dataType) {
        try {
            return objectMapper.treeToValue(node, dataType);
        } catch (JacksonException e) {
            throw new IdempotencyException("读取幂等缓存失败：无法还原成 " + dataType.getSimpleName(), e);
        }
    }

    private static String lockKey(String requestId) {
        return KEY_PREFIX + requestId;
    }

    private static String resultKey(String requestId) {
        return KEY_PREFIX + requestId + RESULT_SUFFIX;
    }

    /**
     * 把一次 Redis 调用包起来，失败统一翻译成 {@link IdempotencyException}
     * （写法和 DailySeqId 里那个一样，原因见 IdempotencyException 类注释）。
     */
    private static <T> T callRedis(String requestId, Supplier<T> op) {
        try {
            return op.get();
        } catch (RuntimeException e) {
            throw redisUnavailable(requestId, e);
        }
    }

    /**
     * 没有返回值的 Redis 调用（比如 SET）用这个。
     * 单独开一个方法是为了让调用处不用在 lambda 里写一句别扭的 {@code return null;}。
     */
    private static void callRedisVoid(String requestId, Runnable op) {
        try {
            op.run();
        } catch (RuntimeException e) {
            throw redisUnavailable(requestId, e);
        }
    }

    /** 拼一条"看得懂、能照着修"的错误消息：说清是什么失败、哪个 requestId、怎么办、底层原因 */
    private static IdempotencyException redisUnavailable(String requestId, RuntimeException cause) {
        return new IdempotencyException("幂等校验失败：Redis 不可用（requestId=" + requestId + "）。"
                + "请确认 Redis 已启动（本机双击 start-redis.bat；服务器上检查 redis-server 进程与 6379 端口）。"
                + "原因：" + cause.getMessage(), cause);
    }

    /** 缓存里存的东西：这次请求的指纹 + 成功的返回数据 */
    private record Cached(String fingerprint, JsonNode data) {
    }
}
