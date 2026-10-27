package com.secondclassroom.util;

import com.secondclassroom.common.SeqIdException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 「前缀 + yyyyMMdd + N位当天序号」这一类业务编号的生成器。
 * 工单号 WO、消息号 MSG、状态日志号 SLOG、合并号 MRG、通知号 NOT、审计号 OLOG、附件号 ATT 全都走它。
 * 少数**不带日期**的编号（FAQ / NOTICE / TPL）走下面的 nextGlobal()。
 *
 * <h3>这个类经历过三代写法，值得记一笔</h3>
 * <ol>
 *   <li><b>第一代：`COUNT(*) + 1`</b>。致命坑：COUNT 数的是"现在还剩几行"，删过行就会变小，
 *       算出来的编号会撞回一个已经存在的值 → 主键冲突。真实事故：清理测试数据后
 *       OLOG 立刻算出 OLOG2026xxxx0047 这个已存在的号。</li>
 *   <li><b>第二代：`MAX(序号) + 1`</b>（只增不减，删行不会倒退）。但**仍然不是并发安全的**：
 *       两个请求可能同时读到同一个 MAX，然后生成同一个编号 → 主键冲突。</li>
 *   <li><b>第三代（现在）：Redis `INCR`</b>。INCR 是**原子**的，并发下每个调用者拿到的值必然互不相同，
 *       这一层才算真正解决。key 按文档 §1.11 的命名：
 *       当天序号 `work_order:seq:20260928`、全局序号 `faq:seq`。</li>
 * </ol>
 *
 * <h3>为什么还要读数据库？—— "播种"</h3>
 * Redis 里的计数器是**从 1 开始**的，可库里早就有一堆老编号了（比如 WO202609280007）。
 * 要是直接 INCR 拿到 1，就会生成一个已经存在的号 → 主键冲突。
 * 所以第一次用到某个 key 时，先查一次库里当前的**最大序号**当作"起点"写进 Redis，
 * 之后 INCR 就是从这个起点往上加。这一段叫播种（seed）。
 *
 * <p>播种为什么是安全的？顺序是「查存在 → 不存在才 SETNX → INCR」：
 * 两个线程同时发现 key 不存在，都会去算一次库里的最大值（白算一次，无害），
 * 但 `SETNX` 只有一个能写成功，另一个写不进去就算了；随后各自 `INCR`，拿到的值仍然互不相同。
 *
 * <p>还有个附带好处：Redis 我们配的是**不落盘**（`save ""` + `appendonly no`），
 * Redis 一重启 key 全没了 —— 但因为下次用到时会重新播种，所以**不会**从此重复发号。
 *
 * <h3>⚠️ Redis 挂了怎么办：故意让它"响亮地失败"</h3>
 * 这里**不做静默降级**（偷偷退回 `MAX+1`）。原因有两条：
 * <ul>
 *   <li>降级后的 `MAX+1` 本身就并发不安全，等于悄悄把老 bug 放回来；</li>
 *   <li>更糟的是两条路会**算得不一样**：降级期间库里的号涨了，而 Redis 的计数器没跟上，
 *       等 Redis 恢复后 INCR 会发一个**已经用过**的号 → 依然主键冲突，而且更难查。</li>
 * </ul>
 * 所以 Redis 不可用时直接抛异常（→ HTTP 500，消息里写清原因），让问题一眼可见。
 * 反正 Redis 已经是这套系统的必需依赖，/health 里也能看到它的状态。
 *
 * <p>⚠️ 抛的是自定义的 {@link SeqIdException}，不是普通 RuntimeException：
 * Spring Data Redis 的连接异常继承自 DataAccessException，会被全局处理器
 * 改成「数据库操作失败，请稍后重试」，那等于把"Redis 挂了"这条线索直接抹掉。
 */
public final class DailySeqId {

    private DailySeqId() {
    }

    private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 当天序号 key 的存活时间。
     * 过了这一天这个 key 就再也用不到了，给它 48 小时自动过期，
     * 免得 Redis 里日积月累堆一堆"历史当天"的计数器。
     */
    private static final Duration DAILY_TTL = Duration.ofHours(48);

    /**
     * 带日期的编号：`前缀 + yyyyMMdd + N位当天序号`。
     *
     * @param jdbcTemplate 用来查库里的当前最大序号（只在播种时用一次）
     * @param redis        计数器；key 形如 work_order:seq:20260928
     * @param prefix       前缀，如 "WO" / "OLOG"
     * @param table        表名（只接受本类内部传入的字面量，不要传用户输入）
     * @param idColumn     主键列名
     * @param seqWidth     序号位数，如 4（-> 0001）/ 6（-> 000001）
     */
    public static String next(JdbcTemplate jdbcTemplate, StringRedisTemplate redis, String prefix,
                              String table, String idColumn, int seqWidth) {
        String date = LocalDate.now().format(DATE_PATTERN);

        // 序号在 id 里的起始位置：前缀长度 + 8 位日期 + 1
        // 例：OLOG + 20260923 + 000047 → 前缀长 4，起始位 = 4+8+1 = 13
        int seqStart = prefix.length() + 8 + 1;

        // key 按文档命名：work_order:seq:20260928
        String key = keyName(prefix) + ":seq:" + date;

        long seq = nextSeq(redis, key, DAILY_TTL,
                () -> maxSeq(jdbcTemplate, table, idColumn, seqStart, prefix + date + "%"));

        return prefix + date + String.format("%0" + seqWidth + "d", seq);
    }

    /**
     * 不带日期的**全局**序号：`前缀 + N位序号`。
     * 目前用于 FAQ（faq:seq，文档 §7 就是这么命名的）、公告 NOTICE、快捷回复模板 TPL。
     *
     * <p>为什么不能直接用 next()？因为 next() 认为 id 的格式是「前缀 + 8 位日期 + 序号」，
     * 它从 `前缀长 + 8 + 1` 位开始截序号。FAQ000001 只有 9 位，从这个位置截出来是空串，
     * CAST('') 得到 NULL，MAX 永远是 NULL → 每次都算出 1 → **主键冲突**。
     *
     * <p>⚠️ 全局计数器**不设过期时间**：它的序号是连续的（跨天也不重置），
     * 一旦过期就只能重新按库里的最大值播种，虽然也不会错，但没必要给自己添麻烦。
     *
     * @param prefix   前缀，如 "FAQ"
     * @param table    表名（只接受本类内部传入的字面量）
     * @param idColumn 主键列名
     * @param seqWidth 序号位数，如 6（-> 000001）
     */
    public static String nextGlobal(JdbcTemplate jdbcTemplate, StringRedisTemplate redis, String prefix,
                                    String table, String idColumn, int seqWidth) {
        // 序号从前缀之后一位开始：FAQ000001 → 第 4 位起
        int seqStart = prefix.length() + 1;

        String key = keyName(prefix) + ":seq";

        long seq = nextSeq(redis, key, null,
                () -> maxSeq(jdbcTemplate, table, idColumn, seqStart, prefix + "%"));

        return prefix + String.format("%0" + seqWidth + "d", seq);
    }

    /**
     * 查库里当前的**最大序号**，作为 Redis 计数器的起点。
     * 表里一条都没有时返回 0（于是第一个号是 1）。
     */
    private static long maxSeq(JdbcTemplate jdbcTemplate, String table, String idColumn,
                               int seqStart, String likePattern) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(CAST(SUBSTRING(" + idColumn + ", " + seqStart + ") AS UNSIGNED)) "
                        + "FROM " + table + " WHERE " + idColumn + " LIKE ?",
                Integer.class, likePattern);
        return maxSeq == null ? 0L : maxSeq.longValue();
    }

    /**
     * 取下一个序号：先保证 key 已经"播种"，再 INCR。
     *
     * <h3>为什么把"读数据库"这一步单独留在外面</h3>
     * 下面的 {@code callRedis} 会把任何 RuntimeException 一律翻译成
     * 「Redis 不可用」。可播种时要执行 {@code dbSeeding.get()} —— 那是**读数据库**，
     * 万一库也挂了，它抛的是 DataAccessException（RuntimeException），
     * 如果一起塞进 callRedis，就会报成"Redis 不可用" —— 又是一次张冠李戴。
     * 所以顺序特意拆成：先问 Redis（key 在不在）→ 再读库（拿起点）→ 再写 Redis。
     *
     * @param ttl 为 null 表示不过期（全局序号用）
     */
    private static long nextSeq(StringRedisTemplate redis, String key, Duration ttl,
                                Supplier<Long> dbSeeding) {
        // 1) 先问 Redis：这个 key 在不在？
        //    多花一次 hasKey 的往返，换来"不用每次都查库"，值得。
        boolean keyExists = Boolean.TRUE.equals(callRedis(key, () -> redis.hasKey(key)));

        // 2) key 不在（首次使用，或 Redis 重启后 key 全丢）→ 读库拿"起点"。
        //    ⚠️ 这一步故意放在 callRedis 之外：数据库自己的错误要保持原样。
        String seed = keyExists ? null : String.valueOf(dbSeeding.get());

        // 3) SETNX 写起点（并发下只有一个能成功）→ INCR 原子自增。
        //    合成一次 callRedis：这两步都是纯 Redis 操作，中间不该插别的东西。
        Long seq = callRedis(key, () -> {
            if (seed != null) {
                if (ttl == null) {
                    redis.opsForValue().setIfAbsent(key, seed);
                } else {
                    redis.opsForValue().setIfAbsent(key, seed, ttl);
                }
            }
            return redis.opsForValue().increment(key);
        });

        if (seq == null) {
            // 理论上不会发生（INCR 一定返回数字），留个兜底免得后面拿到 null 拆箱炸掉
            throw new SeqIdException("单号生成失败：Redis 没有返回自增值（key=" + key + "）");
        }
        return seq;
    }

    /**
     * 把一次 Redis 调用包起来，失败统一翻译成 {@link SeqIdException}。
     *
     * <p>为什么用自定义异常而不是直接抛 RuntimeException？见 {@link SeqIdException}
     * 的类注释 —— 简单说：Spring Data Redis 的连接异常继承自 DataAccessException，
     * 会被全局处理器改写成「数据库操作失败」，把真相盖掉。
     */
    private static <T> T callRedis(String key, Supplier<T> op) {
        try {
            return op.get();
        } catch (RuntimeException e) {
            throw redisUnavailable(key, e);
        }
    }

    /**
     * 拼一条"看得懂、能照着修"的错误消息：说清是什么失败、哪个 key、怎么办、底层原因。
     */
    private static SeqIdException redisUnavailable(String key, RuntimeException cause) {
        return new SeqIdException("单号生成失败：Redis 不可用（key=" + key + "）。"
                + "请确认 Redis 已启动（本机双击 start-redis.bat；服务器上检查 redis-server 进程与 6379 端口）。"
                + "原因：" + cause.getMessage(), cause);
    }

    /**
     * 前缀 → Redis key 里的业务名。前两个是**文档写明**的：
     * `work_order:seq:{yyyyMMdd}`（§4 提交工单第 7 步）与 `faq:seq`（§7 新建 FAQ 第 2 步）。
     * 其余的文档没规定，按同一套风格补齐，免得 Redis 里出现两种命名风格。
     */
    private static String keyName(String prefix) {
        return switch (prefix) {
            case "WO" -> "work_order";
            case "MSG" -> "work_order_message";
            case "SLOG" -> "status_log";
            case "MRG" -> "merge_relation";
            case "NOT" -> "notification";
            case "OLOG" -> "operation_log";
            case "ATT" -> "attachment";
            case "FAQ" -> "faq";
            case "NOTICE" -> "announcement";
            case "TPL" -> "template";
            default -> prefix.toLowerCase(Locale.ROOT);
        };
    }
}
