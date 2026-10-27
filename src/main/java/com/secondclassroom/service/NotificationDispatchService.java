package com.secondclassroom.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 异步通知派发服务：定时扫 t_notification_log 里 status='PENDING' 的记录，
 * 交给对应渠道的 {@link NotificationSender} 推送，然后把结果写回表。
 *
 * <h3>为什么业务代码里只写 PENDING、不直接推送</h3>
 * 文档 1.13「通知机制与事务边界」定的是：<b>工单业务成功不以通知发送成功为前提</b>。
 * 学生提交工单、干事回复，这些是主业务；通知发不出去是另一码事，
 * 绝不能因为推送失败就把业务回滚掉。所以业务事务里只 INSERT 一条 PENDING 记录，
 * 真发送全部交给这里异步做（{@code WorkOrderService.writeNotification} 只写不发）。
 *
 * <h3>状态机</h3>
 * <pre>
 *   PENDING --推送成功----------------> SUCCESS
 *   PENDING --推送失败、重试没用完----> PENDING（retry_count+1，等间隔到了再推）
 *   PENDING --推送失败、重试用尽------> FAILED（永久失败，不再被扫到）
 * </pre>
 * ⚠️ 文档的原话是"失败改 FAILED + retry_count+1"。如果字面照做，第一次失败就变 FAILED，
 *    而 FAILED 又不在扫描条件里 —— 那就再也重试不了了，自相矛盾。
 *    所以这里按能自洽的方式实现：<b>重试没用完之前保持 PENDING，用完才置 FAILED</b>。
 *    读法：PENDING = 还欠着、还有机会；FAILED = 彻底放弃了。
 *
 * <h3>重试节奏</h3>
 * 首次立即推；失败后按 30s / 60s / 120s 的间隔各再推一次。
 * 即最多尝试 <b>4 次 = 首次 + 重试 3 次</b>，间隔序列正好是 30/60/120，与文档"最多重试 3 次"对齐。
 * <p>间隔的起算点是「上一次尝试的时间」。表里没有 next_retry_time 字段，
 * {@code send_time} 是唯一能表达这个语义的现成列，所以约定：
 * <b>send_time = 最后一次尝试时间</b>（成功失败都写）。通知列表接口不返回 send_time，不影响前端展示。
 *
 * <h3>并发</h3>
 * 每处理一条前先用条件 UPDATE "抢占"（把 send_time 置为本次尝试时间，条件是
 * status 和 retry_count 都没变过）。抢不到说明别的实例已经处理了 → 直接跳过。
 * 这样多实例部署也不会重复推送，做法和项目里工单的乐观锁是同一套思路。
 *
 * ⚠️ 本方法<b>故意不加 @Transactional</b>：一轮可能有上百条通知，如果整批一个事务，
 *    第 5 条推送失败抛异常会把前 4 条已经写好的 SUCCESS 一起回滚，下一轮又重复推。
 *    每条通知的成败必须各自独立提交（JdbcTemplate 不加事务时每条语句自动提交）。
 */
@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    /** fail_reason 列宽 500，写之前统一截断，避免 DataIntegrityViolationException。 */
    private static final int FAIL_REASON_MAX_LEN = 500;

    private final JdbcTemplate jdbcTemplate;

    /** 渠道 -> 发送器。Spring 会把所有 NotificationSender 实现注入进来，按 channel() 自动建索引。 */
    private final Map<String, NotificationSender> senders;

    private final boolean enabled;
    private final int batchSize;
    private final int maxRetry;
    /** 重试间隔（秒），默认 {30,60,120}，与"最多重试 3 次"一一对应。 */
    private final int[] retryDelays;
    /** 拼好的"到点没到点"判断 SQL 片段，构造时算一次（见 buildDueCondition）。 */
    private final String dueCondition;

    public NotificationDispatchService(
            JdbcTemplate jdbcTemplate,
            List<NotificationSender> senderList,
            @Value("${app.notification.enabled:true}") boolean enabled,
            @Value("${app.notification.batch-size:100}") int batchSize,
            @Value("${app.notification.max-retry:3}") int maxRetry,
            @Value("${app.notification.retry-delay-seconds:30,60,120}") String retryDelaySeconds) {

        this.jdbcTemplate = jdbcTemplate;
        this.senders = senderList.stream()
                .collect(Collectors.toMap(NotificationSender::channel, Function.identity()));
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
        this.retryDelays = parseDelays(retryDelaySeconds);
        this.dueCondition = buildDueCondition(this.retryDelays);
    }

    /**
     * 定时扫一轮。fixedDelay = <b>上一轮跑完</b>再等 N 毫秒，天然不会自己叠自己（同一实例内串行）。
     * 启动后先等 initial-delay-ms 再跑第一次，避免应用还没起来就抢着发。
     */
    @Scheduled(fixedDelayString = "${app.notification.scan-interval-ms:10000}",
               initialDelayString = "${app.notification.initial-delay-ms:5000}")
    public void dispatchPending() {
        if (!enabled) {
            return;
        }
        try {
            List<Candidate> candidates = findCandidates();
            if (candidates.isEmpty()) {
                return;   // 空轮次不打日志，否则日志会被刷屏
            }

            int sent = 0, waiting = 0, failed = 0, skipped = 0;
            for (Candidate c : candidates) {
                switch (dispatchOne(c)) {
                    case SENT -> sent++;
                    case WAITING_RETRY -> waiting++;
                    case FAILED -> failed++;
                    case SKIPPED -> skipped++;
                }
            }
            // 有实际结果才打一行汇总，方便观察
            if (sent + waiting + failed + skipped > 0) {
                log.info("通知派发轮次结束：成功 {} / 待重试 {} / 永久失败 {} / 跳过 {}",
                        sent, waiting, failed, skipped);
            }
        } catch (Exception e) {
            // 兜底：某一轮因为数据库抖动整体失败，不能让定时线程挂掉 —— 记日志，下一轮继续。
            log.error("通知派发轮次异常（下一轮会继续）", e);
        }
    }

    // ============================================================
    // 内部实现
    // ============================================================

    private enum Outcome { SENT, WAITING_RETRY, FAILED, SKIPPED }

    /** 待推送的通知（含路由和重试判断需要的列）。 */
    private record Candidate(String notifyId, String userId, String workOrderId, String type,
                             String title, String content, String targetId,
                             String channel, int retryCount) { }

    /**
     * 捞出这一轮"该处理"的候选：
     *   · status = PENDING
     *   · 重试次数没超上限
     *   · 已经到点了（首次立即；重试则要距上次尝试够 30/60/120 秒）
     */
    private List<Candidate> findCandidates() {
        String sql = "SELECT notify_id, user_id, work_order_id, type, title, content, target_id, "
                + "       channel, retry_count "
                + "FROM t_notification_log "
                + "WHERE status = 'PENDING' "
                + "  AND retry_count <= " + maxRetry
                + "  AND " + dueCondition + " "
                + "ORDER BY create_time ASC "
                + "LIMIT " + batchSize;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new Candidate(
                rs.getString("notify_id"),
                rs.getString("user_id"),
                rs.getString("work_order_id"),
                rs.getString("type"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("target_id"),
                rs.getString("channel"),
                rs.getInt("retry_count")));
    }

    /** 处理单条：抢占 → 路由到对应渠道 → 发送 → 按结果落状态。 */
    private Outcome dispatchOne(Candidate c) {
        // 1) 抢占（乐观锁）：把 send_time 置成本次尝试时间。
        //    加 status / retry_count 双条件是关键 —— 只有"我看到的那一条"还原封不动时才抢得到。
        //    affected = 0 说明别的实例（或上一轮）已经动了它，本轮直接放弃，不重复推送。
        int claimed = jdbcTemplate.update(
                "UPDATE t_notification_log SET send_time = NOW() "
                        + "WHERE notify_id = ? AND status = 'PENDING' AND retry_count = ?",
                c.notifyId(), c.retryCount());
        if (claimed == 0) {
            return Outcome.SKIPPED;
        }

        // 2) 按渠道找发送器；没注册的渠道（BROWSER / WECHAT）判为"渠道未实现"→ 失败，走重试链路。
        NotificationSender sender = senders.get(c.channel());
        NotificationSender.SendResult result;
        if (sender == null) {
            result = NotificationSender.SendResult.fail("渠道未实现: " + c.channel());
        } else {
            result = sender.send(new NotificationSender.Task(
                    c.notifyId(), c.userId(), c.workOrderId(),
                    c.type(), c.title(), c.content(), c.targetId(), c.retryCount()));
        }

        int attemptNo = c.retryCount() + 1;   // 这是第几次尝试（1 = 首次）

        // 3) 成功：置 SUCCESS，顺手清掉历史失败原因
        if (result.success()) {
            jdbcTemplate.update(
                    "UPDATE t_notification_log SET status = 'SUCCESS', fail_reason = NULL "
                            + "WHERE notify_id = ?",
                    c.notifyId());
            log.info("通知推送成功 notifyId={} 渠道={} 第 {} 次尝试", c.notifyId(), c.channel(), attemptNo);
            return Outcome.SENT;
        }

        // 4) 失败：retry_count+1。重试用尽才置 FAILED，否则留在 PENDING 等间隔到了再推。
        int newRetryCount = c.retryCount() + 1;
        boolean exhausted = newRetryCount > maxRetry;
        jdbcTemplate.update(
                "UPDATE t_notification_log SET status = ?, retry_count = ?, fail_reason = ? "
                        + "WHERE notify_id = ?",
                exhausted ? "FAILED" : "PENDING",
                newRetryCount,
                truncate(result.failReason(), FAIL_REASON_MAX_LEN),
                c.notifyId());

        if (exhausted) {
            log.warn("通知推送最终失败：已尝试 {} 次全部失败，置为 FAILED。notifyId={} 渠道={} 原因={}",
                    attemptNo, c.notifyId(), c.channel(), result.failReason());
            return Outcome.FAILED;
        }
        log.warn("通知推送失败，稍后重试：notifyId={} 渠道={} 第 {} 次尝试失败，原因={}；距下次尝试需等 {} 秒",
                c.notifyId(), c.channel(), attemptNo, result.failReason(), delayOf(newRetryCount));
        return Outcome.WAITING_RETRY;
    }

    /** 第 retryCount 次失败之后、下一次尝试前要等的秒数。 */
    private int delayOf(int retryCount) {
        int idx = retryCount - 1;
        if (idx < 0) {
            return 0;
        }
        return retryDelays[Math.min(idx, retryDelays.length - 1)];
    }

    /**
     * 生成"到点没到点"的判断片段：
     * <pre>
     *   (send_time IS NULL
     *    OR send_time &lt;= DATE_SUB(NOW(), INTERVAL (CASE retry_count WHEN 1 THEN 30 WHEN 2 THEN 60 ELSE 120 END) SECOND))
     * </pre>
     * send_time 为 NULL = 从没尝试过 → 立即到期（首次不等）。
     * 之后 retry_count=1 / 2 / ≥3 分别对应 30 / 60 / 120 秒的等待。
     */
    private static String buildDueCondition(int[] delays) {
        StringBuilder cases = new StringBuilder("CASE retry_count");
        for (int i = 0; i < delays.length; i++) {
            cases.append(" WHEN ").append(i + 1).append(" THEN ").append(delays[i]);
        }
        cases.append(" ELSE ").append(delays[delays.length - 1]).append(" END");
        return "(send_time IS NULL OR send_time <= DATE_SUB(NOW(), INTERVAL (" + cases + ") SECOND))";
    }

    /** 解析 "30,60,120"；空配置时退回默认值，避免数组长度为 0 把后续逻辑搞崩。 */
    private static int[] parseDelays(String raw) {
        int[] parsed = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt)
                .toArray();
        return parsed.length > 0 ? parsed : new int[]{30, 60, 120};
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
