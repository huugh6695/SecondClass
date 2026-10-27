package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.AttachmentItem;
import com.secondclassroom.dto.StatusHistoryItem;
import com.secondclassroom.dto.WorkOrderAcceptRequest;
import com.secondclassroom.dto.WorkOrderAcceptResult;
import com.secondclassroom.dto.WorkOrderCloseRequest;
import com.secondclassroom.dto.WorkOrderCloseResult;
import com.secondclassroom.dto.WorkOrderDetailResult;
import com.secondclassroom.dto.WorkOrderFollowUpRequest;
import com.secondclassroom.dto.WorkOrderFollowUpResult;
import com.secondclassroom.dto.WorkOrderListResult;
import com.secondclassroom.dto.WorkOrderListItem;
import com.secondclassroom.dto.WorkOrderMessageItem;
import com.secondclassroom.dto.WorkOrderMessagesReadResult;
import com.secondclassroom.dto.WorkOrderReConsultRequest;
import com.secondclassroom.dto.WorkOrderReConsultResult;
import com.secondclassroom.dto.WorkOrderMergeRequest;
import com.secondclassroom.dto.WorkOrderMergeResult;
import com.secondclassroom.dto.WorkOrderReplyRequest;
import com.secondclassroom.dto.WorkOrderReplyResult;
import com.secondclassroom.dto.WorkOrderReturnRequest;
import com.secondclassroom.dto.WorkOrderReturnResult;
import com.secondclassroom.dto.WorkOrderSubmitRequest;
import com.secondclassroom.dto.WorkOrderSubmitResult;
import com.secondclassroom.util.DailySeqId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单业务逻辑：
 * - 提交工单（POST /api/work-orders）
 * - 列表查询（GET /api/work-orders，按角色过滤 + 分页 + 筛选）
 * - 工单详情（GET /api/work-orders/{id}，含消息时间线 + 附件 + 状态历史）
 * - 消息列表（GET /api/work-orders/{id}/messages，聊天页轮询用）
 * - 受理工单（POST /api/work-orders/{id}/accept，仅 STAFF/ADMIN，乐观锁）
 * - 回复工单（POST /api/work-orders/{id}/reply，仅处理人/ADMIN，事务写两张表）
 * - 学生追问（POST /api/work-orders/{id}/follow-up，仅提交人/ADMIN，状态回到 PROCESSING）
 * - 关闭工单（confirm / close / admin-close 三个入口，终态 CLOSED）
 * - 退回工单（POST /api/work-orders/{id}/return，回到 PENDING 待接单池）
 * - 合并工单（POST /api/work-orders/{id}/merge，子工单终态 MERGED）
 * - 再次咨询（POST /api/work-orders/{id}/re-consult，为已完结单再开一张新单）
 *
 * 通知：每个业务动作都会往 t_notification_log 写一条 status='PENDING' 的通知记录，
 * 真正发送交给后续的异步通知服务（业务成功不以通知成功为前提）。
 *
 * 审计：每个会改变工单的动作都往 t_operation_log 记一笔，给管理员查账用：
 *   SUBMIT / ACCEPT / REPLY / FOLLOW_UP / CONFIRM / MANUAL_CLOSE / ADMIN_CLOSE / RETURN / MERGE
 *   （其中 FOLLOW_UP 与三个关闭入口、RETURN、MERGE 是文档漏写、按"完整审计"补的）
 *   ⚠️ 记审计时"操作人"一律取 Token 里的 userId/userType 对应的那个人，
 *      绝不能图方便拿别人（例如 merge 里的子工单提交人 subOwnerId）顶上。
 *
 * 附件：提交工单 / 回复 / 追问 / 再次咨询都可以带 attachmentIds。
 *   流程是"先上传（TEMP）→ 传 ID → 业务事务内校验并绑定（BOUND）"：
 *   · 提交工单 / 再次咨询的附件挂在**工单级**（target_type='WORK_ORDER'，绑工单号）；
 *   · 回复 / 追问的附件挂在**消息级**（target_type='MESSAGE'，绑消息号）。
 *   校验一律在写库之前做完（checkAttachments），绑定在业务写入之后（bindAttachments），两者同一事务。
 *
 * 展示：详情接口一起带出 attachments（工单级）+ 每条消息的 attachments + statusHistory（状态流转历史）；
 *   另有单独的消息列表接口（GET /api/work-orders/{id}/messages）供聊天页轮询，只取消息。
 *   这三处（详情 / 消息列表 / messages/read）的可见性判断统一走 canViewWorkOrder，只维护一份规则。
 *
 * 已做的：敏感词检测（命中则 is_flagged=1，覆盖提交 / 再次咨询 / 回复 / 追问**四处**，
 *   都是"只标记不拦截"；详见各方法的注释）。
 * 还没做的：jieba 重复检测（duplicate_of_order_id）、XSS 转义、Redis 幂等锁 / 单号并发安全。
 *  */
@Service
public class WorkOrderService {

    private final JdbcTemplate jdbcTemplate;
    private final OperationLogService operationLogService;
    private final SensitiveWordService sensitiveWordService;

    /** 重复检测（jieba 分词比对），只读；口径全在它的类注释里 */
    private final DuplicateDetectService duplicateDetectService;

    /** 单号生成（Redis INCR）用；为什么要用 Redis、为什么它挂了就直接报错，见 DailySeqId 的类注释 */
    private final StringRedisTemplate redis;

    /** 允许的分类，和数据库 ENUM 完全一致（中文） */
    private static final List<String> CATEGORIES =
            List.of("学时录入", "审核驳回", "活动咨询", "规则疑问", "其他");

    /** 允许的状态（列表/筛选用） */
    private static final List<String> STATUSES =
            List.of("PENDING", "PROCESSING", "REPLIED", "CLOSED", "MERGED");

    /** 状态 → 中文标签，返回给前端展示用 */
    private static final Map<String, String> STATUS_LABEL = Map.of(
            "PENDING", "待接单",
            "PROCESSING", "处理中",
            "REPLIED", "已回复",
            "CLOSED", "已关闭",
            "MERGED", "已合并");

    /**
     * 「关闭原因 / 退回理由」（remark）的长度上限 = **500 字**。
     *
     * 为什么必须校验（⑩ 第十步欠账 A1）：
     *   remark 最终写进 `t_status_log.remark`，那一列是 **VARCHAR(500)**。
     *   而 DB 层没拦住也没兜住 —— 超长会抛 `Data too long for column 'remark'`，
     *   被全局异常处理器变成 **500「数据库操作失败」**，**整条事务回滚**。
     *   实测：管理员传 660 个字的关闭原因，直接 500（探查处方 `target/probe-adminclose.py`）。
     *   也就是说 —— **一个用户随手粘贴一段长文本，就能把接口打成 500**，这是必须堵的洞。
     *
     * 为什么是 500 而不是别的数：
     *   它**就是那一列的真实容量**，是"数据能存多长"这个客观事实，不是拍脑袋的偏好值。
     *   前端输入框另有自己的 `maxlength=200`，那管的是"输入体验"，两者是不同层次：
     *   后端守"能不能存进去"，前端守"好不好填"。后端**不该**跟着前端改（改前端不该动后端）。
     *
     * 为什么在 Service 入口校验、而不是在 writeStatusLog 里校验：
     *   铁律 1 —— `@Transactional` 里正常 return 也会提交事务，只有抛异常才回滚。
     *   所以"要返回错误"的校验必须排在**任何写库之前**。
     *   同时这里也是**所有调用方的唯一入口**（closeOrder 覆盖 confirm/close/admin-close，
     *   returnOrder 覆盖 return），堵在这里等于把同一列的所有洞一次堵完。
     */
    private static final int REMARK_MAX = 500;

    /**
     * remark 长度预校验（**只读**，不写任何表）。
     *
     * @param label  出错时怎么称呼它（"关闭原因" / "退回理由"），让提示像人话
     * @return null = 通过；否则是直接回给前端的 400 文案
     */
    private static String remarkTooLong(String label, String remark) {
        if (remark != null && remark.length() > REMARK_MAX) {
            return label + "不能超过 " + REMARK_MAX + " 字（当前 " + remark.length() + " 字）";
        }
        return null;
    }

    public WorkOrderService(JdbcTemplate jdbcTemplate, OperationLogService operationLogService,
                            SensitiveWordService sensitiveWordService,
                            DuplicateDetectService duplicateDetectService, StringRedisTemplate redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationLogService = operationLogService;
        this.sensitiveWordService = sensitiveWordService;
        this.duplicateDetectService = duplicateDetectService;
        this.redis = redis;
    }

    // ============ 提交工单（POST /api/work-orders） ============

    /**
     * 提交工单（最小可用版）。
     * userId 从 JWT 拦截器放进 request 的属性里，由 Controller 取出来传进来；
     * 姓名快照 userName 这里查一次库（Token 里只有 userId / userType，没有姓名）。
     */
    @Transactional
    public Result<WorkOrderSubmitResult> submit(String userId, WorkOrderSubmitRequest req) {
        // 1. 入参校验
        if (req.getCategory() == null || !CATEGORIES.contains(req.getCategory())) {
            return Result.fail(400, "分类不合法，必须是：学时录入 / 审核驳回 / 活动咨询 / 规则疑问 / 其他");
        }
        if (req.getContent() == null || req.getContent().isBlank()) {
            return Result.fail(400, "问题描述不能为空");
        }
        if (req.getContent().length() > 500) {
            return Result.fail(400, "问题描述不能超过 500 字");
        }

        // 1.1 附件预校验（只读）。必须排在下面所有写库动作之前 ——
        //     否则一旦这里失败要 `return Result.fail(...)`，前面已经写进去的工单会被提交（见 checkAttachments 注释）。
        AttachmentCheck attachError = checkAttachments(userId, req.getAttachmentIds());
        if (attachError != null) {
            return Result.fail(attachError.code(), attachError.message());
        }

        // 2. 查提交人姓名（写入工单/消息的姓名快照）
        String userName;
        try {
            userName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 3. 生成工单号 + 首条消息号
        String workOrderId = generateWorkOrderId();
        String messageId = generateMessageId();

        // 3.1 敏感词检测（文档 §4 第 4 步）：命中就标记，但**仍允许提交**。
        //     它只是"给管理员留个待复核的记号"，不是提交闸门 —— 所以这里绝不 return。
        //     放在写库之前算完，是因为它只读；放在这里而不是更靠前，是为了让
        //     "参数校验 / 权限 / 附件预校验"这些**会返回错误**的检查都排在它前面
        //     （@Transactional 里 return 也会提交事务，顺序错了会留下脏数据）。
        boolean flagged = sensitiveWordService.hitsSensitiveWord(req.getContent());

        // 3.2 重复检测（文档 §4 第 5 步）：jieba 分词 → 同分类近 7 天未完结单关键词交集 ≥ 2
        //     → 命中的话把原单号记进 duplicate_of_order_id。
        //     和敏感词检测**并列**：同样只读、同样只标记不拦截、同样排在所有写库之前。
        //     具体口径（未完结含哪些状态 / 平手取哪张 / 停用词 / 短内容 / 条数上限）
        //     全在 DuplicateDetectService 的类注释里，这里不重复。
        //     null = 没命中，正常写库即可。
        String duplicateOf = duplicateDetectService.findDuplicate(req.getCategory(), req.getContent());

        try {
            // 4. 落库 t_work_order（status 默认 PENDING，version=0；时间交给数据库默认值）
            //    is_flagged 由上面的检测结果决定（以前这里写死 0，导致敏感词库形同虚设）
            //    duplicate_of_order_id 同理（以前恒 NULL，导致重复率恒为 0、isDuplicate 筛选查不出东西）
            jdbcTemplate.update(
                    "INSERT INTO t_work_order (work_order_id, user_id, user_name, category, content, request_id, status, is_flagged, duplicate_of_order_id, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, 0)",
                    workOrderId, userId, userName, req.getCategory(), req.getContent(), req.getRequestId(),
                    flagged ? 1 : 0, duplicateOf);

            // 5. 落库首条消息（QUESTION，内容和工单 content 完全一致）
            jdbcTemplate.update(
                    "INSERT INTO t_work_order_message (message_id, work_order_id, sender_id, sender_type, sender_name, msg_type, content) "
                            + "VALUES (?, ?, ?, 'STUDENT', ?, 'QUESTION', ?)",
                    messageId, workOrderId, userId, userName, req.getContent());

            // 6. 附件绑定：挂在**工单级**（target_type='WORK_ORDER'），挂的是工单号不是消息号。
            //    按文档"工单详情"的响应结构，提交时带的附件属于工单本体（attachments 平级字段），
            //    而 REPLY / FOLLOW_UP 里的附件才挂在各自那条消息上。
            //    放在事务里的意义：这一步失败会把上面三条 INSERT 全部回滚。
            bindAttachments(req.getAttachmentIds(), workOrderId, "WORK_ORDER");

            // 7. 写状态日志 SUBMIT：before 传 null，表示这是工单生命周期的起点
            writeStatusLog(workOrderId, null, "PENDING", "SUBMIT", userId, userName, "STUDENT", null);
        } catch (DuplicateKeyException e) {
            // 幂等恢复（文档 §1.11「DB UNIQUE 冲突后的幂等恢复」）。
            // 走到这里说明 request_id 撞了 UNIQUE，而 Redis 那层没拦住。什么时候会这样？
            //   · Redis 我们配的是**不落盘**，每次重启缓存全丢；
            //   · 结果缓存只有 1 小时，过期也就没了；
            //   · 或者上次是"写库成功、响应却没回到前端"（网络断/进程被杀）时中断的。
            // 关键认识：这类"同一个 requestId 的重试"**不能直接报失败** ——
            // 第一次其实是成功的，用户要的就是那张单。所以去库里把原单捞出来还给他。
            return recoverSubmitByRequestId(req.getRequestId(), userId,
                    req.getCategory(), req.getContent());
        }

        // 7.1 记审计日志 SUBMIT。
        //     特意放在 try 外面：重复提交会被上面的 catch 提前 return 掉，
        //     那种情况下压根没建单，不该留下"提交成功"的账。
        operationLogService.record(userId, userName, "STUDENT",
                "SUBMIT", "WORK_ORDER", workOrderId, "提交工单[" + req.getCategory() + "]");

        // 8. 通知所有干事 / 管理员：有新工单进来了（一人一条，渠道=站内 PLATFORM）
        //    ⚠️ 特意放在 try 外面：通知是"顺带做的事"，万一写通知出错，
        //       不能让用户看到"请勿重复提交"这种驴唇不对马嘴的提示。
        List<String> receivers = jdbcTemplate.queryForList(
                "SELECT user_id FROM t_user WHERE user_type IN ('STAFF','ADMIN') AND status = 'ACTIVE'",
                String.class);
        for (String receiverId : receivers) {
            writeNotification(workOrderId, receiverId, "NEW_TICKET",
                    "新工单: " + req.getCategory(),
                    userName + ": " + req.getContent(),
                    workOrderId, "P0");
        }

        // 9. 组装返回
        //    isDuplicate / duplicateOfOrderId 以前是**写死的占位**（false / null），
        //    现在由第 3.2 步的检测结果决定 —— 学生自己提交时就能看到"你这张单疑似重复"。
        WorkOrderSubmitResult result = new WorkOrderSubmitResult();
        result.setWorkOrderId(workOrderId);
        result.setStatus("PENDING");
        result.setDuplicate(duplicateOf != null);
        result.setDuplicateOfOrderId(duplicateOf);
        return Result.success("工单提交成功", result);
    }

    /**
     * DB 兜底幂等恢复：request_id 撞了 UNIQUE 时，去库里把原单找出来。
     *
     * <p>它只在"Redis 那层没拦住"时才被调用（见 submit 的 catch 注释），
     * 作用是把一个**看起来像失败**的响应，纠正成**第一次的真实结果**。
     *
     * <p>三种结局：
     * <ul>
     *   <li>找到，且「谁 + 分类 + 正文」全一致 → 同一次请求的重试 → 返回首次的成功结果；</li>
     *   <li>找到，但内容对不上 → 同一个 requestId 配了不同参数（前端复用 ID 的 bug）→ 409 IDEMPOTENCY_CONFLICT；</li>
     *   <li>压根没找到 → 撞的是别的东西（比如工单号主键），退回老的 409 请勿重复提交。</li>
     * </ul>
     */
    private Result<WorkOrderSubmitResult> recoverSubmitByRequestId(String requestId, String userId,
                                                                   String category, String content) {
        if (requestId == null || requestId.isBlank()) {
            // 没传 requestId 时本就没有"幂等"这回事，撞主键只能是别的意外
            return Result.fail(409, "请勿重复提交");
        }

        Map<String, Object> existed;
        try {
            existed = jdbcTemplate.queryForMap(
                    "SELECT work_order_id, user_id, category, content, status, duplicate_of_order_id "
                            + "FROM t_work_order WHERE request_id = ?", requestId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(409, "请勿重复提交");
        }

        boolean sameRequest = userId.equals(existed.get("user_id"))
                && category.equals(existed.get("category"))
                && content.equals(existed.get("content"));
        if (!sameRequest) {
            return Result.fail(409, "请求ID已被其他请求占用（IDEMPOTENCY_CONFLICT）");
        }

        // 是同一个请求的重试 → 把首次的结果原样还回去（注意：**不写任何日志/通知**，
        // 因为第一次该记的都记过了，这里只是"重播"）
        WorkOrderSubmitResult replayed = new WorkOrderSubmitResult();
        replayed.setWorkOrderId((String) existed.get("work_order_id"));
        replayed.setStatus((String) existed.get("status"));
        String duplicateOf = (String) existed.get("duplicate_of_order_id");
        replayed.setDuplicate(duplicateOf != null);
        replayed.setDuplicateOfOrderId(duplicateOf);
        return Result.success("工单提交成功", replayed);
    }

    // ============ 再次咨询（POST /api/work-orders/{id}/re-consult） ============

    /**
     * 再次咨询：一张已经完结（CLOSED）的工单，学生可以**为它再开一张新单**。
     *
     * 为什么要有这个接口，而不是让学生直接重新提交一张？
     *   因为"老问题又来了"和"新问题"在业务上是两回事：
     *   新单会带上 source_order_id + relation_type='RE_CONSULTATION'，
     *   于是可以统计"再次咨询占比"，也能让干事一眼看到前情（省得学生复述一遍）。
     *
     * 三条硬规则：
     *   ① 原单必须是**你本人**提交的（不是本人 → 403，也看不到别人有没有这张单）
     *   ② 原单必须是 CLOSED 终态（没完结就追问，别开新单 → 409）
     *   ③ **同一张原单 7 天内只能再咨询一次**（超了 → 429），防止有人狂开新单刷屏
     *
     * 关于第 ③ 条：文档的设计是"Redis 7 天锁 + DB COUNT 兜底"。
     * 本阶段 Redis 还没接，所以这里**只用 DB 判断**（文档自己也说"数据库 COUNT 检查仍然是
     * 最终业务判断依据"）。为了补上 Redis 锁本该提供的**并发控制**，这里对原单加了一把行锁
     * （lockOrder 里的 SELECT ... FOR UPDATE）：两个并发请求会排队，后一个能看见前一个插进去的新单。
     */
    @Transactional
    public Result<WorkOrderReConsultResult> reConsult(String userId, String workOrderId,
                                                      WorkOrderReConsultRequest req) {
        // 1. 入参校验（和提交工单同一套规则）
        if (req == null || req.getCategory() == null || !CATEGORIES.contains(req.getCategory())) {
            return Result.fail(400, "分类不合法，必须是：学时录入 / 审核驳回 / 活动咨询 / 规则疑问 / 其他");
        }
        if (req.getContent() == null || req.getContent().isBlank()) {
            return Result.fail(400, "问题描述不能为空");
        }
        if (req.getContent().length() > 500) {
            return Result.fail(400, "问题描述不能超过 500 字");
        }
        String requestId = req.getRequestId();

        // 2. 幂等恢复：这个 requestId 是不是已经建过单了？
        //    文档的幂等是在 Redis 里做的（SET idempotent:{requestId} NX），Redis 没接，
        //    所以在数据库里做等价的事：查 t_work_order.request_id（有 UNIQUE 索引）。
        //    为什么这一步必须排在"7 天频率校验"前面？—— 因为它本来就是先于频率校验发生的：
        //    网络抖动导致前端重试时，如果先撞上频率校验，用户会收到莫名其妙的 429，
        //    而实际上他的第一次请求早就成功了。这里直接把他上次的结果原样还回去。
        if (requestId != null && !requestId.isBlank()) {
            Map<String, Object> existed = null;
            try {
                existed = jdbcTemplate.queryForMap(
                        "SELECT work_order_id, source_order_id, category, content "
                                + "FROM t_work_order WHERE request_id = ?", requestId);
            } catch (EmptyResultDataAccessException ignored) {
                // 没用过这个 requestId，正常往下走
            }
            if (existed != null) {
                // 请求指纹比对：原单号 / 分类 / 内容三者全一致，才认作"同一个请求的重试"
                boolean sameRequest = workOrderId.equals(existed.get("source_order_id"))
                        && req.getCategory().equals(existed.get("category"))
                        && req.getContent().equals(existed.get("content"));
                if (sameRequest) {
                    WorkOrderReConsultResult replayed = new WorkOrderReConsultResult();
                    replayed.setWorkOrderId((String) existed.get("work_order_id"));
                    replayed.setSourceOrderId(workOrderId);
                    return Result.success("再次咨询提交成功", replayed);
                }
                // requestId 被另一个"内容不一样"的请求占了 → 是前端复用 ID 的 bug，明确报错
                return Result.fail(409, "请求ID已被其他请求占用（IDEMPOTENCY_CONFLICT）");
            }
        }

        // 3. 锁住原单并读它（FOR UPDATE：让并发的再次咨询请求排队，见方法注释）
        Map<String, Object> sourceRow = lockOrder(workOrderId);
        if (sourceRow == null) {
            return Result.fail(404, "工单不存在");
        }
        String ownerId = (String) sourceRow.get("user_id");
        String sourceStatus = (String) sourceRow.get("status");

        // 4. 归属校验：只能是原单提交人本人
        //    这里故意**不加角色门**（其他接口都是"先角色后归属"）—— 因为文档这一步写的是
        //    `user_id = :currentUserId（否则 403）`，纯归属判断。归属本身就把范围卡死在提交人身上了。
        if (!userId.equals(ownerId)) {
            return Result.fail(403, "只能对自己的工单再次咨询");
        }

        // 5. 状态校验：只有已完结的单才谈得上"再次咨询"
        if (!"CLOSED".equals(sourceStatus)) {
            return Result.fail(409, "仅已完结工单可再次咨询（WO_STATUS_INVALID）");
        }

        // 6. 频率校验：同一张原单 7 天内只能再次咨询一次
        //    这里也用 FOR UPDATE 而不是普通 SELECT —— FOR UPDATE 是"当前读"，
        //    一定看得见并发事务刚提交的新单，不受可重复读快照影响。
        List<String> recent = jdbcTemplate.queryForList(
                "SELECT work_order_id FROM t_work_order "
                        + "WHERE source_order_id = ? AND create_time > DATE_SUB(NOW(), INTERVAL 7 DAY) "
                        + "LIMIT 1 FOR UPDATE",
                String.class, workOrderId);
        if (!recent.isEmpty()) {
            return Result.fail(429, "该工单 7 天内已再次咨询过，请勿重复提交（RE_CONSULTATION_LIMIT）");
        }

        // 6.1 附件预校验（只读，必须排在所有写库之前；理由见 checkAttachments 注释）
        AttachmentCheck attachError = checkAttachments(userId, req.getAttachmentIds());
        if (attachError != null) {
            return Result.fail(attachError.code(), attachError.message());
        }

        // 7. 查提交人姓名（写进新单的姓名快照）+ 生成新单号 / 首条消息号
        String userName;
        try {
            userName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }
        String newWorkOrderId = generateWorkOrderId();
        String messageId = generateMessageId();

        // 7.1 敏感词检测：文档在这一步写的是「敏感词检测 + XSS 转义 + 重复检测（同提交工单逻辑）」，
        //     所以和 submit 走同一套：命中就把新单标成 is_flagged=1，**依然允许提交**。
        boolean flagged = sensitiveWordService.hitsSensitiveWord(req.getContent());

        try {
            // 8. 落新单：多写了 source_order_id + relation_type 两个字段，这就是"再次咨询"的身份标记
            jdbcTemplate.update(
                    "INSERT INTO t_work_order (work_order_id, user_id, user_name, category, content, "
                            + "source_order_id, relation_type, request_id, status, is_flagged, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'RE_CONSULTATION', ?, 'PENDING', ?, 0)",
                    newWorkOrderId, userId, userName, req.getCategory(), req.getContent(),
                    workOrderId, requestId, flagged ? 1 : 0);

            // 9. 首条消息（QUESTION），和提交工单完全一致
            jdbcTemplate.update(
                    "INSERT INTO t_work_order_message (message_id, work_order_id, sender_id, sender_type, "
                            + "sender_name, msg_type, content) "
                            + "VALUES (?, ?, ?, 'STUDENT', ?, 'QUESTION', ?)",
                    messageId, newWorkOrderId, userId, userName, req.getContent());

            // 10. 附件绑定：绑在**新单**上（不是原单）。原单已是 CLOSED 终态，本接口从不改动它。
            bindAttachments(req.getAttachmentIds(), newWorkOrderId, "WORK_ORDER");

            // 11. 状态日志：动作仍然是 SUBMIT（t_status_log.action 是 ENUM，没有 RE_CONSULTATION 这个值，
            //     它记的是"工单状态怎么变的"——新单从无到有，就是 SUBMIT）
            writeStatusLog(newWorkOrderId, null, "PENDING", "SUBMIT", userId, userName, "STUDENT", null);
        } catch (DuplicateKeyException e) {
            // 两个请求同时抢同一个 requestId，UNIQUE 索引把后来的那个拦下
            return Result.fail(409, "请勿重复提交");
        }

        // 11.1 记审计日志。operationType 用 RE_CONSULTATION 而**不是** SUBMIT ——
        //      要和 t_work_order.relation_type='RE_CONSULTATION' 对齐，
        //      这样 `GROUP BY operation_type` 就能直接算出"再次咨询占比"。
        //      （注意它和上面那条状态日志的口径是不同的，这不是笔误。）
        operationLogService.record(userId, userName, "STUDENT",
                "RE_CONSULTATION", "WORK_ORDER", newWorkOrderId,
                "再次咨询（源自 " + workOrderId + "）");

        // 12. 通知所有干事 / 管理员：新单进来了（和提交工单一样，P0）
        //     放在 try 外面 —— 通知"顺带做"，失败不该影响建单结果
        List<String> receivers = jdbcTemplate.queryForList(
                "SELECT user_id FROM t_user WHERE user_type IN ('STAFF','ADMIN') AND status = 'ACTIVE'",
                String.class);
        for (String receiverId : receivers) {
            writeNotification(newWorkOrderId, receiverId, "NEW_TICKET",
                    "新工单: " + req.getCategory(),
                    userName + ": " + req.getContent(),
                    newWorkOrderId, "P0");
        }

        // 13. 组装返回（文档只要求这两个字段）
        WorkOrderReConsultResult result = new WorkOrderReConsultResult();
        result.setWorkOrderId(newWorkOrderId);
        result.setSourceOrderId(workOrderId);
        return Result.success("再次咨询提交成功", result);
    }

    // ============ 列表查询（GET /api/work-orders） ============

    public Result<WorkOrderListResult> listWorkOrders(String userId, String userType,
                                                     Integer page, Integer pageSize,
                                                     String status, String category, String keyword) {
        // 1. 分页参数兜底：page 默认 1；pageSize 默认 20，上限 50
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 50);

        // 2. status 合法性校验（可逗号传多个，如 PENDING,REPLIED）
        List<String> statusList = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            for (String s : status.split(",")) {
                s = s.trim();
                if (!STATUSES.contains(s)) {
                    return Result.fail(400, "非法的 status 值：" + s);
                }
                statusList.add(s);
            }
        }

        // 3. 拼 WHERE：先按角色加权限条件，再叠加筛选条件
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();

        // 3.1 角色权限（核心：不同角色看到的范围不同）
        if ("STUDENT".equals(userType)) {
            where.append(" WHERE user_id = ?");
            params.add(userId);
        } else if ("STAFF".equals(userType)) {
            where.append(" WHERE (handler_id = ? OR (status = 'PENDING' AND handler_id IS NULL))");
            params.add(userId);
        }
        // ADMIN：不加任何条件，看全部

        // 3.2 status 筛选
        if (!statusList.isEmpty()) {
            where.append(where.length() == 0 ? " WHERE" : " AND");
            where.append(" status IN (");
            for (int i = 0; i < statusList.size(); i++) {
                where.append(i == 0 ? "?" : ",?");
                params.add(statusList.get(i));
            }
            where.append(")");
        }
        // 3.3 category 筛选
        if (category != null && !category.isBlank()) {
            where.append(where.length() == 0 ? " WHERE" : " AND");
            where.append(" category = ?");
            params.add(category);
        }
        // 3.4 keyword 模糊搜索 content
        if (keyword != null && !keyword.isBlank()) {
            where.append(where.length() == 0 ? " WHERE" : " AND");
            where.append(" content LIKE ?");
            params.add("%" + keyword + "%");
        }

        // 4. 总数（用于前端分页条）
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_work_order" + where, Long.class, params.toArray());

        // 5. 分页查询（OFFSET 从 0 开始）
        int offset = (p - 1) * ps;
        String sql = "SELECT work_order_id, category, content, status, user_name, handler_name, "
                + "create_time, student_unread_count, staff_unread_count, duplicate_of_order_id, version "
                + "FROM t_work_order" + where + " ORDER BY create_time DESC LIMIT ?, ?";
        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(offset);
        queryParams.add(ps);
        List<WorkOrderListItem> list = jdbcTemplate.query(
                sql, new WorkOrderListItemRowMapper(userType), queryParams.toArray());

        // 6. 组装返回
        WorkOrderListResult result = new WorkOrderListResult();
        result.setTotal(total == null ? 0 : total);
        result.setPage(p);
        result.setPageSize(ps);
        result.setList(list);
        return Result.success("success", result);
    }

    // ============ 工单详情（GET /api/work-orders/{id}） ============

    public Result<WorkOrderDetailResult> getDetail(String userId, String userType, String workOrderId) {
        // 1. 查工单本体
        WorkOrderDetailResult order;
        try {
            order = jdbcTemplate.queryForObject(
                    "SELECT work_order_id, user_id, user_name, category, content, status, "
                            + "handler_id, handler_name, main_order_id, source_order_id, "
                            + "is_flagged, duplicate_of_order_id, version, "
                            + "create_time, accept_time, reply_time, finish_time "
                            + "FROM t_work_order WHERE work_order_id = ?",
                    new WorkOrderDetailRowMapper(), workOrderId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "工单不存在");
        }

        // 2. 权限校验：不同角色能看的单不同
        if (!canViewWorkOrder(userId, userType, order.getUserId(), order.getStatus(), order.getHandlerId())) {
            return Result.fail(403, "无权查看该工单");
        }

        // 3. 消息时间线（每条消息自带 attachments）
        order.setMessages(loadMessages(workOrderId));

        // 4. 工单级附件：提交工单 / 再次咨询时带上来的那些（挂在工单本身，不属于任何一条消息）
        order.setAttachments(loadWorkOrderAttachments(workOrderId));

        // 5. 状态流转历史（前端画进度条用）
        order.setStatusHistory(loadStatusHistory(workOrderId));

        return Result.success("success", order);
    }

    // ============ 消息列表（GET /api/work-orders/{id}/messages） ============

    /**
     * 只取一张工单的消息列表（文档 §5）。
     *
     * 为什么不直接复用"详情"、让前端自己从详情里取 messages？
     *   因为详情里还捎带了附件、状态历史、工单字段，前端聊天页轮询时不需要这些；
     *   文档（以及前端轮询 15 秒的设计）单独给了这个轻量接口。
     *
     * ⚠️ 别以为它和 messages/read 是一对：read 是 POST 用来清红点的，这个是 GET 取数据。
     * 权限校验与工单详情完全一致 —— 所以统一走 canViewWorkOrder，避免三处各写一遍判断（写歪一处就是越权）。
     */
    public Result<List<WorkOrderMessageItem>> listMessages(String userId, String userType,
                                                          String workOrderId) {
        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(
                    "SELECT user_id, status, handler_id FROM t_work_order WHERE work_order_id = ?", workOrderId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "工单不存在");
        }

        if (!canViewWorkOrder(userId, userType, (String) row.get("user_id"),
                (String) row.get("status"), (String) row.get("handler_id"))) {
            return Result.fail(403, "无权查看该工单");
        }

        return Result.success("success", loadMessages(workOrderId));
    }

    // ============ 标记工单消息已读（POST /api/work-orders/{id}/messages/read） ============

    /**
     * 把一张工单的**所有消息**标成"当前用户已读"，同时清掉这张单的通知红点。
     *
     * 为什么必须放在同一个事务里？因为前端页面上其实有**两个独立红点**：
     *   ① 消息红点：来自 t_message_read（查不到记录 = 未读）
     *   ② 通知红点：来自 t_notification_log.is_read
     * 如果只清掉其中一个，用户会看到"消息明明都读了、通知列表里还有红点"这种诡异现象。
     * 文档原话："此接口必须在同一事务中同时更新 MessageRead 和 NotificationLog，保证红点一致清除。"
     *
     * 权限校验与工单详情完全一致（学生=提交人 / 干事=负责人或待接单 / 管理员不限）。
     */
    @Transactional
    public Result<WorkOrderMessagesReadResult> markMessagesRead(String userId, String userType,
                                                              String workOrderId) {
        // 1. 查工单并做权限校验（逻辑同 getDetail）
        String ownerId;
        String status;
        String handlerId;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT user_id, status, handler_id FROM t_work_order WHERE work_order_id = ?",
                    workOrderId);
            ownerId = (String) row.get("user_id");
            status = (String) row.get("status");
            handlerId = (String) row.get("handler_id");
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "工单不存在");
        }

        if (!canViewWorkOrder(userId, userType, ownerId, status, handlerId)) {
            return Result.fail(403, "无权查看该工单");
        }

        // 2. 第①张表：给这张单的每条消息补一条"已读"记录。
        //    INSERT IGNORE 是关键 —— 撞上 UNIQUE(message_id, user_id) 就跳过，
        //    所以"已经读过再点一次"不报错、也不重复插，天然幂等。
        int messageCount = jdbcTemplate.update(
                "INSERT IGNORE INTO t_message_read (message_id, user_id, read_time) "
                        + "SELECT message_id, ?, NOW() FROM t_work_order_message WHERE work_order_id = ?",
                userId, workOrderId);

        // 3. 第②张表：清掉这张单剩下的通知红点
        int notificationCount = jdbcTemplate.update(
                "UPDATE t_notification_log SET is_read = 1, read_time = NOW() "
                        + "WHERE user_id = ? AND work_order_id = ? AND is_read = 0",
                userId, workOrderId);

        return Result.success("已标记为已读",
                new WorkOrderMessagesReadResult(workOrderId, messageCount, notificationCount));
    }

    // ============ 受理工单（POST /api/work-orders/{id}/accept） ============

    /**
     * 干事受理工单。
     * 关键点有两个：
     * 1) 角色校验：只有 STAFF / ADMIN 能受理（学生自己不能接自己的单）。
     * 2) 乐观锁 + 条件更新：用一条 UPDATE 同时卡住 status='PENDING'、handler_id IS NULL，
     *    这样两个人同时点"受理"时，数据库只会让其中一个成功（affected=1），另一个 affected=0 → 409。
     */
    @Transactional
    public Result<WorkOrderAcceptResult> accept(String userId, String userType,
                                               String workOrderId, WorkOrderAcceptRequest req) {
        // 1. 角色校验
        if (!"STAFF".equals(userType) && !"ADMIN".equals(userType)) {
            return Result.fail(403, "仅干事或管理员可受理工单");
        }

        // 2. 工单是否存在（顺便把提交人取出来，一会儿要给他发"已接单"通知）
        String ownerId;
        try {
            ownerId = jdbcTemplate.queryForObject(
                    "SELECT user_id FROM t_work_order WHERE work_order_id = ?", String.class, workOrderId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "工单不存在");
        }

        // 3. 取受理人姓名（Token 里没有姓名，按 userId 查快照）
        String handlerName;
        try {
            handlerName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 4. 条件更新：只有"待接单且没人接"的单才能被受理；带了 version 就再叠一层版本条件
        String sql = "UPDATE t_work_order "
                + "SET handler_id = ?, handler_name = ?, status = 'PROCESSING', "
                + "accept_time = NOW(), version = version + 1 "
                + "WHERE work_order_id = ? AND status = 'PENDING' AND handler_id IS NULL";
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.add(handlerName);
        params.add(workOrderId);
        if (req != null && req.getVersion() != null) {
            sql += " AND version = ?";
            params.add(req.getVersion());
        }
        int affected = jdbcTemplate.update(sql, params.toArray());
        if (affected == 0) {
            return Result.fail(409, "该工单已被受理或状态不允许受理");
        }

        // 5. 写状态日志 ACCEPT + 一条 SYSTEM 系统消息（时间线上能看到「谁接的单」）
        writeStatusLog(workOrderId, "PENDING", "PROCESSING", "ACCEPT", userId, handlerName, userType, null);
        insertSystemMessage(workOrderId, handlerName + " 已接单");

        // 6. 记审计日志 ACCEPT（放在条件 UPDATE 成功之后，affected==0 的情况上面已经 409 返回了）
        operationLogService.record(userId, handlerName, userType,
                "ACCEPT", "WORK_ORDER", workOrderId, "受理工单");

        // 7. 通知提交人：你的工单有人管了
        writeNotification(workOrderId, ownerId, "ACCEPT",
                "您的工单已被受理",
                handlerName + " 已受理您的问题", workOrderId, "P1");

        // 8. 回查最新状态（accept_time 已落库、version 已 +1）
        WorkOrderAcceptResult result = jdbcTemplate.queryForObject(
                "SELECT work_order_id, status, handler_id, handler_name, accept_time, version "
                        + "FROM t_work_order WHERE work_order_id = ?",
                (rs, rowNum) -> {
                    WorkOrderAcceptResult r = new WorkOrderAcceptResult();
                    String st = rs.getString("status");
                    r.setWorkOrderId(rs.getString("work_order_id"));
                    r.setStatus(st);
                    r.setStatusLabel(STATUS_LABEL.getOrDefault(st, st));
                    r.setHandlerId(rs.getString("handler_id"));
                    r.setHandlerName(rs.getString("handler_name"));
                    r.setAcceptTime(rs.getObject("accept_time", LocalDateTime.class));
                    r.setVersion(rs.getInt("version"));
                    return r;
                }, workOrderId);

        return Result.success("受理成功", result);
    }

    // ============ 回复工单（POST /api/work-orders/{id}/reply） ============

    /**
     * 干事回复工单。
     * 这是第一个「一次动两张表」的接口，两张表的写入必须放在同一个事务里：
     *   ① 往 t_work_order_message 插一条 REPLY 消息
     *   ② 更新 t_work_order：status → REPLIED、reply_time、学生未读数 +1、version +1
     *
     * 为什么"先插消息、后改工单"？
     *   - 最常见的失败是重复提交（requestId 撞 UNIQUE），这时第①条 INSERT 就失败、什么都没写，直接返回 409，干净；
     *   - 万一第②步没更新到行，抛异常让整个事务回滚，刚插的消息也不会孤零零留在库里。
     */
    @Transactional
    public Result<WorkOrderReplyResult> reply(String userId, String userType,
                                             String workOrderId, WorkOrderReplyRequest req) {
        // 1. 入参校验
        if (req == null || req.getContent() == null || req.getContent().isBlank()) {
            return Result.fail(400, "回复内容不能为空");
        }
        if (req.getContent().length() > 1000) {
            return Result.fail(400, "回复内容不能超过 1000 字");
        }

        // 2. 只有干事/管理员能回复（学生的继续追问走另一个接口）
        if (!"STAFF".equals(userType) && !"ADMIN".equals(userType)) {
            return Result.fail(403, "仅干事或管理员可回复工单");
        }

        // 3. 查工单的当前状态、处理人和提交人（提交人后面要收通知）
        String status;
        String handlerId;
        String ownerId;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT status, handler_id, user_id FROM t_work_order WHERE work_order_id = ?", workOrderId);
            status = (String) row.get("status");
            handlerId = (String) row.get("handler_id");
            ownerId = (String) row.get("user_id");
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "工单不存在");
        }

        // 4. 干事必须是"当前处理人"本人；管理员不受限
        if ("STAFF".equals(userType) && !userId.equals(handlerId)) {
            return Result.fail(403, "只有当前处理人可回复该工单");
        }

        // 5. 状态校验：待接单要先去受理；已关闭/已合并不能再回复
        if ("PENDING".equals(status)) {
            return Result.fail(409, "该工单尚未受理，请先受理");
        }
        if ("CLOSED".equals(status) || "MERGED".equals(status)) {
            return Result.fail(409, "该工单已关闭或已合并，不能回复");
        }

        // 5.1 附件预校验（只读）。放在"工单级校验都过了"之后、"任何写库"之前：
        //     这样 404 / 403 / 409 这些更严重的错误仍然优先报出来，
        //     同时保证附件不合法时不会留下半截写入（见 checkAttachments 注释）。
        AttachmentCheck attachError = checkAttachments(userId, req.getAttachmentIds());
        if (attachError != null) {
            return Result.fail(attachError.code(), attachError.message());
        }

        // 6. 查回复人姓名（Token 里没有姓名）
        String senderName;
        try {
            senderName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 6.1 敏感词检测（契约 #9 第 6 步「敏感词检测 + XSS 转义 content」的前半）：
        //     先在这里**算出结果**，等下面业务写入成功之后再落标记（见第 9.2 步）。
        //
        //     为什么"算"要放在所有写库之前？
        //       检测本身是**只读**的（读 Redis 缓存 / 退化为查库）。万一读库失败，
        //       那是真故障，此时一行都还没写、直接 500 冒出来最干净；
        //       要是放到写库之后，就会先写一大堆再回滚，白忙一趟。
        //       （这也是提交工单 submit 的做法：检测结果在 INSERT 之前就算好。）
        //
        //     ⚠️ 和提交工单一样**只标记、不拦截**：命中仍然把回复正常发出去。
        //        理由见下面 9.2 的长注释。
        boolean flagged = sensitiveWordService.hitsSensitiveWord(req.getContent());

        // 7. 生成消息号
        String messageId = generateMessageId();

        // 8. 第①张表：插一条 REPLY 消息（request_id 撞 UNIQUE 说明是重复提交）
        try {
            jdbcTemplate.update(
                    "INSERT INTO t_work_order_message (message_id, work_order_id, sender_id, sender_type, "
                            + "sender_name, msg_type, content, request_id) "
                            + "VALUES (?, ?, ?, 'STAFF', ?, 'REPLY', ?, ?)",
                    messageId, workOrderId, userId, senderName, req.getContent(), req.getRequestId());
        } catch (DuplicateKeyException e) {
            return Result.fail(409, "请勿重复提交");
        }

        // 9. 第②张表：改工单状态 + 给学生加 1 条未读
        int affected = jdbcTemplate.update(
                "UPDATE t_work_order SET status = 'REPLIED', reply_time = NOW(), "
                        + "student_unread_count = student_unread_count + 1, version = version + 1 "
                        + "WHERE work_order_id = ? AND status IN ('PROCESSING', 'REPLIED')",
                workOrderId);
        if (affected == 0) {
            // 极罕见的并发情况（刚好被别人关闭/合并）。抛异常触发事务回滚，
            // 保证上面刚插进去的那条消息不会留在库里。
            throw new IllegalStateException("工单状态已变化，回复失败：" + workOrderId);
        }

        // 9.1 附件绑定：挂在**这条回复消息**上（target_type='MESSAGE'），不是整张工单上。
        //     和上面两条写入同一个事务：绑定失败 → 消息和状态变更一起回滚。
        bindAttachments(req.getAttachmentIds(), messageId, "MESSAGE");

        // 9.2 敏感词命中的话，给**这张工单**打上 is_flagged=1。
        //
        //     为什么标记落在"工单"上、而不是"这条消息"上？
        //       因为 t_work_order_message 表**根本没有** is_flagged 这一列，
        //       而 t_work_order.is_flagged 早就有（管理端工单列表的「命中敏感词」筛选
        //       筛的就是它）。用户拍板：复用现成字段，零表结构改动。
        //       代价是语义从"学生提交时命中"扩大到"这张单里的任何内容命中过"——
        //       对"标记出来给人工审核"这个用途来说，这个口径反而更有用。
        //
        //     为什么只标记、不拦截？
        //       和提交工单完全同一个口径（契约写的是「仍允许提交，标记人工审核」）。
        //       干事打错两个字就发不出回复、学生打错两个字就追问不了，
        //       会把正常沟通卡死，而敏感词库本来就只是"减少人工巡查"的钩子。
        //
        //     ⚠️ 这是**单向**标记：一旦为 1 就永远是 1，不会因为下次回复干净了而清回 0。
        //        "这张单曾经出现过敏感内容"是个历史事实，抹掉它才是丢信息。
        //
        //     ⚠️ 单独一条 UPDATE、不并进上面第 9 步的状态更新 ——
        //        并进去就得写成 `is_flagged = is_flagged | ?`，可读性差，
        //        而且会改到已经跑过回归的语句（改已验证代码要付回归成本）。
        if (flagged) {
            jdbcTemplate.update(
                    "UPDATE t_work_order SET is_flagged = 1 WHERE work_order_id = ?", workOrderId);
        }

        // 10. 写状态日志 REPLY。
        // 注意 before 用的是"实际查出来的 status"，而不是写死 'PROCESSING' ——
        // 因为干事对同一条单补充回复时，before 其实已经是 REPLIED 了。
        writeStatusLog(workOrderId, status, "REPLIED", "REPLY", userId, senderName, userType, null);

        // 10.1 记审计日志 REPLY。
        //      放在乐观锁 UPDATE 成功之后 —— 影响 0 行的情况上面已经抛异常回滚了，
        //      所以能走到这里就一定是一次真实生效的回复。
        operationLogService.record(userId, senderName, userType,
                "REPLY", "WORK_ORDER", workOrderId, "回复工单");

        // 11. 通知提交人：你的工单有新回复了（标题直接用文档通知列表示例里的原文）
        writeNotification(workOrderId, ownerId, "REPLY",
                "您的工单有新回复",
                senderName + ": " + req.getContent(), workOrderId, "P1");

        // 12. 回查最新状态，组装返回
        WorkOrderReplyResult result = jdbcTemplate.queryForObject(
                "SELECT status, reply_time, version FROM t_work_order WHERE work_order_id = ?",
                (rs, rowNum) -> {
                    WorkOrderReplyResult r = new WorkOrderReplyResult();
                    String st = rs.getString("status");
                    r.setMessageId(messageId);
                    r.setWorkOrderId(workOrderId);
                    r.setStatus(st);
                    r.setStatusLabel(STATUS_LABEL.getOrDefault(st, st));
                    r.setReplyTime(rs.getObject("reply_time", LocalDateTime.class));
                    r.setVersion(rs.getInt("version"));
                    return r;
                }, workOrderId);

        return Result.success("回复成功", result);
    }

    // ============ 学生追问（POST /api/work-orders/{id}/follow-up） ============

    /**
     * 学生追问 —— 回复接口的"镜像版"。
     *   ① 插一条 FOLLOW_UP 消息（发送人是学生）
     *   ② 工单状态从 REPLIED 拉回 PROCESSING（表示又轮到干事处理了）
     *      干事未读 +1、学生自己的未读清零、version +1
     *
     * 和 reply 的差别：
     *   - 权限反过来：只有「工单提交人本人」能追问（管理员也可）
     *   - 未读数方向相反：reply 加的是学生的未读，followUp 加的是干事的未读，同时把学生自己的清零
     *   - 🔴 **前置状态严格 = REPLIED**（⑩ 第十步欠账 A2 收严）。
     *     而 reply 是宽松的（PROCESSING **或** REPLIED 都行，允许"补充回复"）。
     *     两者**故意不对称**：干事补充回复不改变轮次，学生追问会开启新轮次，
     *     从"处理中"再开一轮是没意义的（答复还没出来）。详见第 5 步的长注释。
     */
    @Transactional
    public Result<WorkOrderFollowUpResult> followUp(String userId, String userType,
                                                   String workOrderId, WorkOrderFollowUpRequest req) {
        // 1. 入参校验
        if (req == null || req.getContent() == null || req.getContent().isBlank()) {
            return Result.fail(400, "追问内容不能为空");
        }
        if (req.getContent().length() > 1000) {
            return Result.fail(400, "追问内容不能超过 1000 字");
        }

        // 2. 只有学生/管理员能追问（干事要说话请用"回复"接口）
        if (!"STUDENT".equals(userType) && !"ADMIN".equals(userType)) {
            return Result.fail(403, "仅工单提交人或管理员可追问");
        }

        // 3. 查工单的提交人、当前状态和处理人（处理人后面要收"学生追问"通知）
        String ownerId;
        String status;
        String handlerId;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT user_id, status, handler_id FROM t_work_order WHERE work_order_id = ?", workOrderId);
            ownerId = (String) row.get("user_id");
            status = (String) row.get("status");
            handlerId = (String) row.get("handler_id");
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "工单不存在");
        }

        // 4. 学生必须是"提交人本人"；管理员不受限
        if ("STUDENT".equals(userType) && !userId.equals(ownerId)) {
            return Result.fail(403, "只有工单提交人可追问");
        }

        // 5. 状态校验（⑩ 第十步欠账 A2：**收严到只允许 REPLIED**）。
        //
        //    ⚠️ 这里以前是"挡 PENDING、挡 CLOSED/MERGED，放行 PROCESSING"，
        //       和 reply 的放宽对称（都允许"补充回复/补充追问"）。
        //       但契约 §4 写的是「status 必须 = 'REPLIED'，否则 409」，用户拍板按契约收严。
        //
        //    收严的业务理由（不是为了跟文档较劲）：
        //      "追问"的语义是"对答复不满意，接着说"。所以合法的时机**只有**"已回复"：
        //        · 待接单   —— 还没人管，该等，不是追问；
        //        · 处理中   —— 答复还没出来，没什么可"不满意"的，该等；（← 本次收掉的）
        //        · 已回复   —— 唯一说得通的时候；追问后状态回到"处理中"，轮次重新开始；
        //        · 已关闭/已合并 —— 终态，要走"再次咨询"另开新单。
        //      顺带把"学生在处理中连点追问刷屏"也挡掉了（收严前实测能连点 3 次成功）。
        //
        //    ⚠️ 三档文案分开写，是为了让前端/用户知道**现在该干什么**，
        //       而不是笼统一句"状态不对"。契约只要求 409，没说文案。
        if (!"REPLIED".equals(status)) {
            if ("PENDING".equals(status)) {
                return Result.fail(409, "该工单尚未受理，暂不能追问");
            }
            if ("PROCESSING".equals(status)) {
                return Result.fail(409, "该工单还在处理中，请等干事答复后再追问");
            }
            return Result.fail(409, "该工单已关闭或已合并，不能追问");
        }

        // 5.1 附件预校验（只读，理由同 reply）
        AttachmentCheck attachError = checkAttachments(userId, req.getAttachmentIds());
        if (attachError != null) {
            return Result.fail(attachError.code(), attachError.message());
        }

        // 6. 查追问人姓名（Token 里没有姓名）
        String senderName;
        try {
            senderName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 6.1 敏感词检测（契约 #10 第 6 步「敏感词检测 + XSS 转义 content」的前半）。
        //     和 reply 第 6.1 步完全同一个口径：**写库之前**只读地算出结果，
        //     业务写入成功后再落标记（见第 9.2 步）。只标记、不拦截。
        boolean flagged = sensitiveWordService.hitsSensitiveWord(req.getContent());

        // 7. 生成消息号
        String messageId = generateMessageId();

        // 8. 第①张表：插一条 FOLLOW_UP 消息（request_id 撞 UNIQUE 说明重复提交）
        try {
            jdbcTemplate.update(
                    "INSERT INTO t_work_order_message (message_id, work_order_id, sender_id, sender_type, "
                            + "sender_name, msg_type, content, request_id) "
                            + "VALUES (?, ?, ?, 'STUDENT', ?, 'FOLLOW_UP', ?, ?)",
                    messageId, workOrderId, userId, senderName, req.getContent(), req.getRequestId());
        } catch (DuplicateKeyException e) {
            return Result.fail(409, "请勿重复提交");
        }

        // 9. 第②张表：状态回到 PROCESSING；干事未读 +1、学生自己的未读清零
        //
        //    ⚠️ 条件里的 `status = 'REPLIED'` 必须和第 5 步的校验**完全一致**
        //       （以前写的是 `IN ('PROCESSING','REPLIED')`）。两个理由：
        //       ① 这里**没有 version 条件**，状态条件就是本接口唯一的并发闸门 ——
        //          两个并发追问都读到 REPLIED 时，若条件写成 IN(...,'PROCESSING')，
        //          第二个会在第一个把状态改成 PROCESSING 之后**照样成功** ⇒
        //          两条重复追问消息、version 白涨一次，看上去"收严了"其实被绕过。
        //       ② 校验和写库两处白名单不一致，是将来最容易出错的隐患
        //          （改了一处忘另一处，静默放宽）。
        //    条件不满足时 affected=0 ⇒ 抛异常回滚 ⇒ 全局处理器转 409（铁律 2）。
        int affected = jdbcTemplate.update(
                "UPDATE t_work_order SET status = 'PROCESSING', "
                        + "staff_unread_count = staff_unread_count + 1, student_unread_count = 0, "
                        + "version = version + 1 "
                        + "WHERE work_order_id = ? AND status = 'REPLIED'",
                workOrderId);
        if (affected == 0) {
            throw new IllegalStateException("工单状态已变化，追问失败：" + workOrderId);
        }

        // 9.1 附件绑定：挂在**这条追问消息**上（和 reply 一样是 MESSAGE 级）
        bindAttachments(req.getAttachmentIds(), messageId, "MESSAGE");

        // 9.2 命中敏感词就给这张工单打 is_flagged=1（同 reply 第 9.2 步）。
        //     标记落在工单上而不是消息上，是因为消息表没有这个列（详述见 reply 的注释）。
        //     同样**单向**：为 1 之后不会清回 0。
        if (flagged) {
            jdbcTemplate.update(
                    "UPDATE t_work_order SET is_flagged = 1 WHERE work_order_id = ?", workOrderId);
        }

        // 10. 写状态日志 FOLLOW_UP（状态从 REPLIED 被拉回 PROCESSING，轮次重新开始）
        writeStatusLog(workOrderId, status, "PROCESSING", "FOLLOW_UP", userId, senderName, userType, null);

        // 10.1 记审计日志 FOLLOW_UP（文档没写，按"完整审计"补齐）
        operationLogService.record(userId, senderName, userType,
                "FOLLOW_UP", "WORK_ORDER", workOrderId, "学生追问");

        // 11. 通知负责干事：学生又追问了，该你回了
        //     handler_id 理论上不会为空（能追问说明已被受理），但真为空时没必要写一条没人收的通知
        if (handlerId != null) {
            writeNotification(workOrderId, handlerId, "FOLLOW_UP",
                    "学生追问了您的工单",
                    senderName + ": " + req.getContent(), workOrderId, "P1");
        }

        // 12. 回查最新状态，组装返回
        WorkOrderFollowUpResult result = jdbcTemplate.queryForObject(
                "SELECT status, staff_unread_count, version FROM t_work_order WHERE work_order_id = ?",
                (rs, rowNum) -> {
                    WorkOrderFollowUpResult r = new WorkOrderFollowUpResult();
                    String st = rs.getString("status");
                    r.setMessageId(messageId);
                    r.setWorkOrderId(workOrderId);
                    r.setStatus(st);
                    r.setStatusLabel(STATUS_LABEL.getOrDefault(st, st));
                    r.setStaffUnreadCount(rs.getInt("staff_unread_count"));
                    r.setVersion(rs.getInt("version"));
                    return r;
                }, workOrderId);

        return Result.success("追问成功", result);
    }

    // ============ 退回工单（POST /api/work-orders/{id}/return） ============

    /**
     * 退回工单：干事接了单之后，发现这单不该自己处理（分类不对、需要别的部门等），
     * 把它**丢回待接单池**，让下一个干事重新受理。
     *
     * 注意"退回"和"关闭"完全是两件事：
     *   · 关闭 → 终态 CLOSED，这单结束了；
     *   · 退回 → 回到 PENDING，这单还活着，只是换人处理。
     * 所以退回**不写 finish_time**，还要把 handler_id / handler_name 一起清空 ——
     * 否则单子回到待接单池却还挂着原来的负责人，列表里会显示成"已被受理"。
     *
     * 方法名为什么叫 returnOrder 而不是 return？
     * 因为 `return` 是 Java 关键字，不能当方法名。
     *
     * 事务内的写法沿用前面的套路：**最容易失败的写排最前面**。
     * 这里最容易挂的是乐观锁 UPDATE（别人已经动过这张单），它失败时零写入、直接 409。
     */
    @Transactional
    public Result<WorkOrderReturnResult> returnOrder(String userId, String userType,
                                                     String workOrderId, WorkOrderReturnRequest req) {
        // 1. version 必传
        if (req == null || req.getVersion() == null) {
            return Result.fail(400, "缺少 version 参数");
        }
        String remark = req.getRemark();

        // 1.1 remark 长度预校验（⑩ 第十步欠账 A1）。
        //     退回理由和关闭原因写的是**同一列** t_status_log.remark（VARCHAR(500)），
        //     所以洞一模一样：不校验的话超长会抛 Data too long → 全局处理器变成 500。
        //     前端虽然只在输入框做了 maxlength，但接口是可以被直接调用的，兜底必须在后端。
        String remarkError = remarkTooLong("退回理由", remark);
        if (remarkError != null) {
            return Result.fail(400, remarkError);
        }

        // 2. 角色校验
        if (!"STAFF".equals(userType) && !"ADMIN".equals(userType)) {
            return Result.fail(403, "仅负责人或管理员可退回工单");
        }

        // 3. 查工单
        String status;
        String handlerId;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT status, handler_id FROM t_work_order WHERE work_order_id = ?", workOrderId);
            status = (String) row.get("status");
            handlerId = (String) row.get("handler_id");
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "工单不存在");
        }

        // 4. 归属校验：干事只能退自己负责的单（管理员不限）
        if ("STAFF".equals(userType) && !userId.equals(handlerId)) {
            return Result.fail(403, "只有该工单的负责人可退回");
        }

        // 5. 前置状态校验：只有"处理中"的单才谈得上退回
        //    已回复（REPLIED）说明干事已经把结果给出去了，要反悔得走别的流程，不能直接退回
        if (!"PROCESSING".equals(status)) {
            return Result.fail(409, "只有处理中的工单才能退回（WO_STATUS_INVALID）");
        }

        // 6. 操作人姓名（写状态日志 / 系统消息用）
        String operatorName;
        try {
            operatorName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 7. 乐观锁更新：状态回 PENDING、负责人清空、version + 1
        //    条件里除了 version 再带一次 status = 'PROCESSING'：
        //    version 是双保险，真正拦住"状态被人改过"的是 status 条件。
        int affected = jdbcTemplate.update(
                "UPDATE t_work_order SET status = 'PENDING', handler_id = NULL, handler_name = NULL, "
                        + "version = version + 1 "
                        + "WHERE work_order_id = ? AND status = 'PROCESSING' AND version = ?",
                workOrderId, req.getVersion());
        if (affected == 0) {
            return Result.fail(409, "版本冲突，请刷新后重试（WO_VERSION_CONFLICT）");
        }

        // 8. 写状态流转日志：PROCESSING → PENDING，动作 RETURN
        writeStatusLog(workOrderId, "PROCESSING", "PENDING", "RETURN", userId, operatorName, userType, remark);

        // 9. 写一条 SYSTEM 系统消息
        //    文案用**执行人**的名字：文档原文写的是 :handlerName，干事自己退回时两者本来就是同一个人；
        //    但管理员可以退别人的单，那时 handlerName 是被退的那个干事，写成"他退的"就不对了。
        insertSystemMessage(workOrderId, operatorName + " 退回工单"
                + (remark == null || remark.isBlank() ? "" : ": " + remark));

        // 10. 记审计日志 RETURN
        operationLogService.record(userId, operatorName, userType,
                "RETURN", "WORK_ORDER", workOrderId,
                "退回工单" + (remark == null || remark.isBlank() ? "" : ": " + remark));

        // 11. 回查最新状态（version 已 +1），组装返回
        WorkOrderReturnResult result = jdbcTemplate.queryForObject(
                "SELECT status, version FROM t_work_order WHERE work_order_id = ?",
                (rs, rowNum) -> {
                    WorkOrderReturnResult r = new WorkOrderReturnResult();
                    String st = rs.getString("status");
                    r.setWorkOrderId(workOrderId);
                    r.setStatus(st);
                    r.setStatusLabel(STATUS_LABEL.getOrDefault(st, st));
                    r.setRemark(remark);
                    r.setVersion(rs.getInt("version"));
                    return r;
                }, workOrderId);

        return Result.success("退回成功", result);
    }

    // ============ 关闭工单（confirm / close / admin-close） ============

    /** 学生确认解决（CONFIRM）：只有提交人本人，且状态必须是 REPLIED */
    @Transactional
    public Result<WorkOrderCloseResult> confirm(String userId, String userType, String workOrderId,
                                               WorkOrderCloseRequest req) {
        return closeOrder(userId, userType, workOrderId, "CONFIRM", req);
    }

    /** 干事手动完结（MANUAL_CLOSE）：负责人或管理员，状态 PROCESSING / REPLIED */
    @Transactional
    public Result<WorkOrderCloseResult> close(String userId, String userType, String workOrderId,
                                             WorkOrderCloseRequest req) {
        return closeOrder(userId, userType, workOrderId, "MANUAL_CLOSE", req);
    }

    /** 管理员强制关闭（ADMIN_CLOSE）：仅管理员，只能关 PENDING 单，必须填 remark */
    @Transactional
    public Result<WorkOrderCloseResult> adminClose(String userId, String userType, String workOrderId,
                                                  WorkOrderCloseRequest req) {
        return closeOrder(userId, userType, workOrderId, "ADMIN_CLOSE", req);
    }

    /**
     * 三个关闭入口的公共实现。差别只有三处，用 action 区分：
     *   | action       | 谁能调         | 允许的前置状态        | remark |
     *   | CONFIRM      | 学生（提交人）  | REPLIED              | 不写   |
     *   | MANUAL_CLOSE | 负责人 / 管理员 | PROCESSING / REPLIED | 不写   |
     *   | ADMIN_CLOSE  | 仅管理员       | PENDING              | 必填   |
     *
     * 三个接口都要干同一件事：乐观锁关单 → 写 t_status_log → 写一条 SYSTEM 系统消息。
     *
     * 写入顺序：这次把「条件 UPDATE」放在最前面，因为本接口最常见的失败是**版本冲突**
     * （别人已经操作过这张单），而它恰好就是这条 UPDATE —— 失败了什么都还没写，直接返回 409。
     */
    private Result<WorkOrderCloseResult> closeOrder(String userId, String userType, String workOrderId,
                                                   String action, WorkOrderCloseRequest req) {
        // 1. version 必传（文档规定三个接口都要走乐观锁）
        if (req == null || req.getVersion() == null) {
            return Result.fail(400, "缺少 version 参数");
        }
        Integer version = req.getVersion();
        String remark = req.getRemark();

        // 1.1 remark 长度预校验（⑩ 第十步欠账 A1）。
        //     位置讲究：必须在**任何写库之前**（铁律 1），而且三个关闭入口共用这一段，
        //     所以 confirm / close 也被顺带保护了 —— 它们同样会把这个 remark 写进
        //     t_status_log.remark，洞是一模一样的。只堵 admin-close 等于假安全。
        //     这不改变任何合法输入的行为：500 字以内的理由照常成功。
        String remarkError = remarkTooLong("关闭原因", remark);
        if (remarkError != null) {
            return Result.fail(400, remarkError);
        }

        // 2. 管理员关闭必须填原因
        if ("ADMIN_CLOSE".equals(action) && (remark == null || remark.isBlank())) {
            return Result.fail(400, "关闭原因不能为空");
        }

        // 3. 角色校验
        if ("CONFIRM".equals(action)) {
            if (!"STUDENT".equals(userType)) {
                return Result.fail(403, "仅工单提交人可确认解决");
            }
        } else if ("MANUAL_CLOSE".equals(action)) {
            if (!"STAFF".equals(userType) && !"ADMIN".equals(userType)) {
                return Result.fail(403, "仅负责人或管理员可完结工单");
            }
        } else if ("ADMIN_CLOSE".equals(action)) {
            if (!"ADMIN".equals(userType)) {
                return Result.fail(403, "仅管理员可强制关闭工单");
            }
        }

        // 4. 查工单
        String ownerId;
        String status;
        String handlerId;
        String handlerName;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT user_id, status, handler_id, handler_name "
                            + "FROM t_work_order WHERE work_order_id = ?", workOrderId);
            ownerId = (String) row.get("user_id");
            status = (String) row.get("status");
            handlerId = (String) row.get("handler_id");
            handlerName = (String) row.get("handler_name");
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "工单不存在");
        }

        // 5. 归属校验：学生必须是提交人；干事必须是负责人（管理员不限）
        if ("CONFIRM".equals(action) && !userId.equals(ownerId)) {
            return Result.fail(403, "只有工单提交人可确认解决");
        }
        if ("MANUAL_CLOSE".equals(action) && "STAFF".equals(userType) && !userId.equals(handlerId)) {
            return Result.fail(403, "只有该工单的负责人可完结");
        }

        // 6. 前置状态校验（按 action 不同）
        if ("CONFIRM".equals(action) && !"REPLIED".equals(status)) {
            return Result.fail(409, "只有已回复的工单才能确认解决");
        }
        if ("MANUAL_CLOSE".equals(action)
                && !"PROCESSING".equals(status) && !"REPLIED".equals(status)) {
            return Result.fail(409, "该工单当前状态不允许完结");
        }
        if ("ADMIN_CLOSE".equals(action) && !"PENDING".equals(status)) {
            return Result.fail(409, "管理员只能关闭待接单的工单");
        }

        // 7. 操作人姓名（写状态日志用）
        String operatorName;
        try {
            operatorName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 8. 乐观锁关单：status → CLOSED、finish_time 落库、version + 1
        int affected = jdbcTemplate.update(
                "UPDATE t_work_order SET status = 'CLOSED', finish_time = NOW(), version = version + 1 "
                        + "WHERE work_order_id = ? AND status = ? AND version = ?",
                workOrderId, status, version);
        if (affected == 0) {
            return Result.fail(409, "版本冲突，请刷新后重试（WO_VERSION_CONFLICT）");
        }

        // 9. 写状态流转日志 t_status_log
        writeStatusLog(workOrderId, status, "CLOSED", action, userId, operatorName, userType, remark);

        // 9.1 记审计日志。operationType 直接复用 action，因为三个入口的动作名
        //     （CONFIRM / MANUAL_CLOSE / ADMIN_CLOSE）本身就是规范的操作类型，
        //     一行代码就把三个关闭入口全覆盖了 —— 不用 if-else 分三份写。
        operationLogService.record(userId, operatorName, userType,
                action, "WORK_ORDER", workOrderId, closeLogContent(action, remark));

        // 10. 写一条 SYSTEM 系统消息（三个接口文案不同）
        String systemText;
        if ("CONFIRM".equals(action)) {
            systemText = "学生确认问题已解决";
        } else if ("MANUAL_CLOSE".equals(action)) {
            systemText = (handlerName == null ? operatorName : handlerName) + " 已完结工单";
        } else {
            systemText = "管理员关闭工单: " + remark;
        }
        insertSystemMessage(workOrderId, systemText);

        // 11. 通知"对方"：文档原话是"确认完结/手动完结/管理员关闭 → 通知对方"
        //     学生确认  → 让对方（负责干事）知道
        //     干事/管理员关闭 → 让提交人知道
        String notifyUserId = "CONFIRM".equals(action) ? handlerId : ownerId;
        String notifyTitle = "CONFIRM".equals(action) ? "学生已确认解决" : "您的工单已关闭";
        if (notifyUserId != null) {
            writeNotification(workOrderId, notifyUserId, "CLOSE",
                    notifyTitle, systemText, workOrderId, "P1");
        }

        // 12. 回查最新状态，组装返回
        WorkOrderCloseResult result = jdbcTemplate.queryForObject(
                "SELECT status, finish_time, version FROM t_work_order WHERE work_order_id = ?",
                (rs, rowNum) -> {
                    WorkOrderCloseResult r = new WorkOrderCloseResult();
                    String st = rs.getString("status");
                    r.setWorkOrderId(workOrderId);
                    r.setStatus(st);
                    r.setStatusLabel(STATUS_LABEL.getOrDefault(st, st));
                    r.setAction(action);
                    r.setFinishTime(rs.getObject("finish_time", LocalDateTime.class));
                    r.setVersion(rs.getInt("version"));
                    return r;
                }, workOrderId);

        return Result.success("工单已关闭", result);
    }

    // ============ 合并工单（POST /api/work-orders/{id}/merge） ============

    /**
     * 合并工单：把「重复的子工单」并入「主工单」，子工单进入 MERGED 终态。
     *
     * 和之前的接口相比，这个接口多了两个新东西：
     *
     * ① **一次性动两张工单**（子单 + 主单），所以要用 SELECT ... FOR UPDATE 把两行都锁住。
     *    为什么必须按 work_order_id 升序锁？假设 A、B 两个请求分别要合并 (X→Y) 和 (Y→X)：
     *    A 先锁 X 再锁 Y，B 先锁 Y 再锁 X —— 两边各攥着一把锁等对方放，就死锁了。
     *    固定"永远先锁 ID 小的那个"，两人的加锁顺序就一样，只会排队不会死锁。
     *
     * ② **防重复合并**。合并是终态操作，错一次就得手工改库，所以文档给了四条规则层层拦截：
     *    规则1 子工单 main_order_id 必须为 NULL（没被合并过）
     *    规则2 主工单 main_order_id 必须为 NULL（主工单不能自己是别人的子工单）
     *    规则3 主工单 main_order_id ≠ 子工单（防 A→B→A 合并环）
     *    规则4 t_merge_relation 里不能已有 sub_order_id = 子工单 的记录
     *    最后 DB 的 UNIQUE(sub_order_id) 再兜一层底。
     */
    @Transactional
    public Result<WorkOrderMergeResult> merge(String userId, String userType,
                                             String workOrderId, WorkOrderMergeRequest req) {
        // 1. 入参校验
        if (req == null || req.getMainOrderId() == null || req.getMainOrderId().isBlank()) {
            return Result.fail(400, "缺少 mainOrderId 参数");
        }
        if (req.getVersion() == null) {
            return Result.fail(400, "缺少 version 参数");
        }
        String mainOrderId = req.getMainOrderId().trim();
        Integer version = req.getVersion();

        // 2. 角色校验
        if (!"STAFF".equals(userType) && !"ADMIN".equals(userType)) {
            return Result.fail(403, "仅干事或管理员可合并工单");
        }

        // 3. 规则0：不能把工单合并到自己
        if (workOrderId.equals(mainOrderId)) {
            return Result.fail(400, "不能把工单合并到自己（WO_CANNOT_MERGE_SELF）");
        }

        // 4. 查操作人姓名（要赶在任何写库之前查完，查不到就直接返回，库里还是干净的）
        String operatorName;
        try {
            operatorName = jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }

        // 5. 按 work_order_id 升序锁两行（顺序固定成 ID 小的在前，避免死锁）
        boolean subFirst = workOrderId.compareTo(mainOrderId) <= 0;
        String firstId = subFirst ? workOrderId : mainOrderId;
        String secondId = subFirst ? mainOrderId : workOrderId;

        Map<String, Object> firstRow = lockOrder(firstId);
        if (firstRow == null) {
            return Result.fail(404, "工单不存在：" + firstId);
        }
        Map<String, Object> secondRow = lockOrder(secondId);
        if (secondRow == null) {
            return Result.fail(404, "工单不存在：" + secondId);
        }

        Map<String, Object> subRow = subFirst ? firstRow : secondRow;
        Map<String, Object> mainRow = subFirst ? secondRow : firstRow;

        String subStatus = (String) subRow.get("status");
        String subMainOrderId = (String) subRow.get("main_order_id");
        String subHandlerId = (String) subRow.get("handler_id");
        String mainStatus = (String) mainRow.get("status");
        String mainMainOrderId = (String) mainRow.get("main_order_id");
        String mainHandlerId = (String) mainRow.get("handler_id");

        // 6. 越权校验：干事只能碰"自己负责的"或"还没人接的"工单；管理员不限
        if ("STAFF".equals(userType)) {
            boolean canTouchSub = userId.equals(subHandlerId) || "PENDING".equals(subStatus);
            boolean canTouchMain = userId.equals(mainHandlerId) || "PENDING".equals(mainStatus);
            if (!canTouchSub || !canTouchMain) {
                return Result.fail(403, "只能合并自己负责的工单，或尚未受理的工单");
            }
        }

        // 7. 子工单必须是"活着"的状态才能被合并（CLOSED / MERGED 都是终态，不能再动）
        if (!"PENDING".equals(subStatus) && !"PROCESSING".equals(subStatus) && !"REPLIED".equals(subStatus)) {
            return Result.fail(409, "该工单已关闭或已合并，不能作为子工单合并（WO_STATUS_INVALID）");
        }

        // 8. 规则1：子工单没被合并过
        if (subMainOrderId != null) {
            return Result.fail(409, "该工单已被合并过，不能重复合并（WO_STATUS_INVALID）");
        }

        // 9. 规则2：主工单不能自己已经是别人的子工单
        //    （注意：这一条成立后，规则3"主工单 main_order_id ≠ 子工单"其实就是它的一个特例，
        //      因为 main_order_id 既然必须是 NULL，自然不可能等于某个具体单号。）
        if (mainMainOrderId != null) {
            return Result.fail(409, "主工单本身已被合并到其他工单，不能再作为主工单（WO_ALREADY_MERGED）");
        }

        // 10. 主工单不能处于 MERGED 终态
        if ("MERGED".equals(mainStatus)) {
            return Result.fail(409, "主工单已被合并，不能再作为主工单（WO_ALREADY_MERGED）");
        }

        // 11. 规则4：合并关系表里不能已有这张子工单的记录
        Integer relExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_merge_relation WHERE sub_order_id = ?", Integer.class, workOrderId);
        if (relExists != null && relExists > 0) {
            return Result.fail(409, "该工单已有合并记录，请勿重复合并");
        }

        // 12. 乐观锁更新子工单：变成 MERGED 终态、记下归属主工单
        //     文档里的 UPDATE 没有带 finish_time，这里严格照文档来（不擅自加字段）
        int affected = jdbcTemplate.update(
                "UPDATE t_work_order SET status = 'MERGED', main_order_id = ?, version = version + 1 "
                        + "WHERE work_order_id = ? AND version = ?",
                mainOrderId, workOrderId, version);
        if (affected == 0) {
            return Result.fail(409, "版本冲突，请刷新后重试（WO_VERSION_CONFLICT）");
        }

        // 13. 落一条合并关系记录（审计留痕，一条记录 = 一次合并操作，永不修改）
        String mergeId = generateMergeId();
        try {
            jdbcTemplate.update(
                    "INSERT INTO t_merge_relation (merge_id, main_order_id, sub_order_id, "
                            + "operator_id, operator_name) VALUES (?, ?, ?, ?, ?)",
                    mergeId, mainOrderId, workOrderId, userId, operatorName);
        } catch (DuplicateKeyException e) {
            // 并发兜底：DB 的 UNIQUE(sub_order_id) 把重复创建拦下来了。
            // ⚠️ 这里必须 throw 而不是 return —— return 会让事务照常提交，
            //    上面第 12 步改掉的状态就白改了；抛异常才能整体回滚。
            throw new IllegalStateException("并发合并冲突，子工单已有合并记录：" + workOrderId);
        }

        // 14. 写状态日志 MERGE（before 是子工单原来的状态）
        writeStatusLog(workOrderId, subStatus, "MERGED", "MERGE", userId, operatorName, userType, null);

        // 14.1 记审计日志 MERGE（文档没写，按"完整审计"补齐）。
        //      ⚠️ 操作人必须是**执行合并的那个人**（userId / operatorName / userType，来自 Token），
        //         不是子工单提交人 subOwnerId（那个是第 16 步写通知用的收件人）。
        //         两者在"管理员帮学生合并"的场景下完全不同，写反了就会变成"学生自己合并的"。
        //      target_id 用子工单号：接口路径是 /{id}/merge，被操作的对象就是这张子单，
        //      它从普通状态变成 MERGED 终态；主单号放进描述里，方便对照。
        operationLogService.record(userId, operatorName, userType,
                "MERGE", "WORK_ORDER", workOrderId,
                "合并工单 " + workOrderId + " → " + mainOrderId);

        // 15. 两条 SYSTEM 消息：子工单留一条指向主单，主工单留一条说"谁并进来了"
        insertSystemMessage(workOrderId, "工单已合并至 " + mainOrderId);
        insertSystemMessage(mainOrderId, "工单 " + workOrderId + " 已合并至此");

        // 16. 通知子工单提交人：你的工单被合并了
        //     ⚠️ targetId 传**主工单号**而不是子单号 —— 文档的跳转表规定 MERGE 类型点击后
        //        要跳到主工单详情页，因为子单已经是只读终态，看它没意义。
        String subOwnerId = (String) subRow.get("user_id");
        if (subOwnerId != null) {
            writeNotification(workOrderId, subOwnerId, "MERGE",
                    "您的工单已合并",
                    "您的工单已合并至 " + mainOrderId, mainOrderId, "P1");
        }

        // 17. 回查最新状态，组装返回
        WorkOrderMergeResult result = jdbcTemplate.queryForObject(
                "SELECT t.status, t.version, t.main_order_id, m.merge_id, m.merge_time "
                        + "FROM t_work_order t JOIN t_merge_relation m ON m.sub_order_id = t.work_order_id "
                        + "WHERE t.work_order_id = ?",
                (rs, rowNum) -> {
                    WorkOrderMergeResult r = new WorkOrderMergeResult();
                    String st = rs.getString("status");
                    r.setWorkOrderId(workOrderId);
                    r.setStatus(st);
                    r.setStatusLabel(STATUS_LABEL.getOrDefault(st, st));
                    r.setMainOrderId(rs.getString("main_order_id"));
                    r.setMergeId(rs.getString("merge_id"));
                    r.setMergeTime(rs.getObject("merge_time", LocalDateTime.class));
                    r.setVersion(rs.getInt("version"));
                    return r;
                }, workOrderId);

        return Result.success("合并成功", result);
    }

    // ============ 私有辅助：状态日志 / 系统消息 ============

    /**
     * 三个关闭入口共用的审计描述文案。
     *
     * 抽出来的原因：closeOrder 是三个接口的公共实现，操作类型（operationType）可以直接复用 action，
     * 但"人话描述"每个入口不一样，写成三目嵌套会很难读，所以单独放到这里。
     */
    private String closeLogContent(String action, String remark) {
        if ("CONFIRM".equals(action)) {
            return "学生确认问题已解决";
        }
        if ("MANUAL_CLOSE".equals(action)) {
            return "负责人完结工单";
        }
        return "管理员关闭工单: " + remark;
    }

    /**
     * 写一条状态流转日志 t_status_log。
     *
     * 为什么抽成方法？因为 submit / accept / reply / followUp / 三个关闭入口全都要写这一张表，
     * 列名和 INSERT 语句只维护一份；以后加"退回(RETURN)""转派(TRANSFER)"直接调用，不用再抄 SQL。
     *
     * @param workOrderId  工单号
     * @param beforeStatus 流转前状态。SUBMIT 表示工单刚诞生、没有前一状态，所以传 null
     * @param afterStatus  流转后状态
     * @param action       动作：SUBMIT / ACCEPT / REPLY / FOLLOW_UP / CONFIRM / MANUAL_CLOSE / ADMIN_CLOSE
     * @param remark       备注。目前只有 ADMIN_CLOSE（管理员强制关闭）会用到，其余传 null
     */
    private void writeStatusLog(String workOrderId, String beforeStatus, String afterStatus,
                                String action, String operatorId, String operatorName,
                                String operatorRole, String remark) {
        jdbcTemplate.update(
                "INSERT INTO t_status_log (log_id, work_order_id, before_status, after_status, "
                        + "action, operator_id, operator_name, operator_role, remark) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                generateStatusLogId(), workOrderId, beforeStatus, afterStatus,
                action, operatorId, operatorName, operatorRole, remark);
    }

    /**
     * 写一条 SYSTEM 系统消息（时间线上那句灰字：谁接单了、谁完结了、管理员为什么关单……）。
     *
     * 系统消息没有真实的发送人，所以按表约定：sender_id 固定 NULL、sender_name 固定"系统"、
     * msg_type 固定 SYSTEM。它和"人说的话"（QUESTION / REPLY / FOLLOW_UP）在展示上要区分开。
     */
    private void insertSystemMessage(String workOrderId, String content) {
        jdbcTemplate.update(
                "INSERT INTO t_work_order_message (message_id, work_order_id, sender_id, sender_type, "
                        + "sender_name, msg_type, content) "
                        + "VALUES (?, ?, NULL, 'SYSTEM', '系统', 'SYSTEM', ?)",
                generateMessageId(), workOrderId, content);
    }

    /**
     * 写一条通知记录 t_notification_log。
     *
     * 关键设计：**业务事务里只负责把通知"记下来"（status='PENDING'），不在这里真的发送**。
     * 文档明确规定"工单业务成功不以通知发送成功为前提" —— 也就是说：
     *   工单该变更就变更，通知发不出去是另一码事，不能因为它把工单业务给回滚了。
     * 后续会有一个异步通知服务，专门扫 status='PENDING' 的记录去推送，
     * 成功改 SUCCESS、失败改 FAILED 并把 retry_count+1（最多重试 3 次）。
     *
     * @param workOrderId 通知所属的工单（可为 null）
     * @param userId      收件人
     * @param type        NEW_TICKET / ACCEPT / REPLY / FOLLOW_UP / CLOSE / MERGE
     * @param title       标题，前端通知列表第一行
     * @param content     正文，前端通知列表第二行
     * @param targetId    前端点击后跳转的目标单号（点进去看哪张单）
     * @param priority    P0 最高（新工单）、P1 默认
     */
    private void writeNotification(String workOrderId, String userId, String type,
                                   String title, String content, String targetId, String priority) {
        jdbcTemplate.update(
                "INSERT INTO t_notification_log (notify_id, work_order_id, user_id, type, title, content, "
                        + "channel, priority, target_type, target_id, status, is_read) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'PLATFORM', ?, 'WORK_ORDER', ?, 'PENDING', 0)",
                generateNotifyId(), workOrderId, userId, type, title, content, priority, targetId);
    }

    /**
     * 用 `SELECT ... FOR UPDATE` 把一行工单"锁住"，返回这一行的字段；工单不存在则返回 null。
     *
     * FOR UPDATE 加的是**排他锁**：如果两个事务同时想锁同一行，后到的那个会阻塞等待，
     * 必须等前一个事务提交或回滚之后才能继续。所以它能在"读"的时候就把并发挡住，
     * 而普通的 SELECT 是读快照，两边都会读到旧值。
     *
     * 注意：FOR UPDATE 必须在事务里用（@Transactional），否则 MySQL 会立刻释放锁，等于没锁。
     */
    private Map<String, Object> lockOrder(String workOrderId) {
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT work_order_id, user_id, status, handler_id, main_order_id, version "
                            + "FROM t_work_order WHERE work_order_id = ? FOR UPDATE",
                    workOrderId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 把 t_work_order 一行映射成详情对象（含状态中文标签） */
    private static class WorkOrderDetailRowMapper implements RowMapper<WorkOrderDetailResult> {
        @Override
        public WorkOrderDetailResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            WorkOrderDetailResult o = new WorkOrderDetailResult();
            String status = rs.getString("status");
            o.setWorkOrderId(rs.getString("work_order_id"));
            o.setUserId(rs.getString("user_id"));
            o.setUserName(rs.getString("user_name"));
            o.setCategory(rs.getString("category"));
            o.setContent(rs.getString("content"));
            o.setStatus(status);
            o.setStatusLabel(STATUS_LABEL.getOrDefault(status, status));
            o.setHandlerId(rs.getString("handler_id"));
            o.setHandlerName(rs.getString("handler_name"));
            o.setMainOrderId(rs.getString("main_order_id"));
            o.setSourceOrderId(rs.getString("source_order_id"));
            o.setIsFlagged(rs.getBoolean("is_flagged"));
            o.setDuplicateOfOrderId(rs.getString("duplicate_of_order_id"));
            o.setVersion(rs.getInt("version"));
            o.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            o.setAcceptTime(rs.getObject("accept_time", LocalDateTime.class));
            o.setReplyTime(rs.getObject("reply_time", LocalDateTime.class));
            o.setFinishTime(rs.getObject("finish_time", LocalDateTime.class));
            return o;
        }
    }

    /** 把 t_work_order_message 一行映射成消息项 */
    private static class WorkOrderMessageRowMapper implements RowMapper<WorkOrderMessageItem> {
        @Override
        public WorkOrderMessageItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            WorkOrderMessageItem m = new WorkOrderMessageItem();
            m.setMessageId(rs.getString("message_id"));
            m.setSenderId(rs.getString("sender_id"));
            m.setSenderName(rs.getString("sender_name"));
            m.setSenderType(rs.getString("sender_type"));
            m.setMsgType(rs.getString("msg_type"));
            m.setContent(rs.getString("content"));
            m.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            return m;
        }
    }

    /** 把 t_work_order 一行映射成列表项（含状态中文标签、按角色取未读数） */
    private static class WorkOrderListItemRowMapper implements RowMapper<WorkOrderListItem> {
        private final String userType;
        WorkOrderListItemRowMapper(String userType) { this.userType = userType; }

        @Override
        public WorkOrderListItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            WorkOrderListItem item = new WorkOrderListItem();
            String status = rs.getString("status");
            item.setWorkOrderId(rs.getString("work_order_id"));
            item.setCategory(rs.getString("category"));
            item.setContent(rs.getString("content"));
            item.setStatus(status);
            item.setStatusLabel(STATUS_LABEL.getOrDefault(status, status));
            item.setUserName(rs.getString("user_name"));
            item.setHandlerName(rs.getString("handler_name"));
            item.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            // 未读数按角色取对应列：学生看 student_unread_count，干事/管理员看 staff_unread_count
            int unread = "STUDENT".equals(userType)
                    ? rs.getInt("student_unread_count")
                    : rs.getInt("staff_unread_count");
            item.setUnreadCount(unread);
            item.setDuplicateOfOrderId(rs.getString("duplicate_of_order_id"));
            item.setVersion(rs.getInt("version"));
            return item;
        }
    }

    // ============ 私有辅助：读取（可见性 / 消息 / 附件 / 状态历史） ============

    /**
     * 工单可见性判断 —— 工单详情、消息列表、标记已读三个接口**共用同一套规则**。
     *
     * 抽出来的原因：这三处原本各写一遍一模一样的 if/else，任何一处写歪就是越权漏洞（能看别人的单）。
     * 规则（文档 §4 第 3 步）：
     *   · STUDENT：只有**提交人本人**能看；
     *   · STAFF  ：只有"我接的单"或"还没人接的待接单"能看 —— 待接单要让大家都能看见才有意义（不然没法抢单）；
     *   · ADMIN  ：全部可见。
     *
     * @return true 表示可以看
     */
    private boolean canViewWorkOrder(String userId, String userType, String ownerId,
                                     String status, String handlerId) {
        if ("STUDENT".equals(userType)) {
            return userId.equals(ownerId);
        }
        if ("STAFF".equals(userType)) {
            return userId.equals(handlerId) || ("PENDING".equals(status) && handlerId == null);
        }
        return true;   // ADMIN
    }

    /**
     * 取一张工单的完整消息时间线，并把**每条消息自己的附件**塞进去。
     *
     * 附件用**一条 SQL 全部捞出来再分组**，而不是"先查消息、再 for 每条消息查一次附件"。
     * 后者就是经典的 N+1 查询：一张单有 50 条消息就是 51 次数据库往返。这里始终只要 2 次。
     * JOIN t_work_order_message 的作用是限定"附件所属的消息属于这张单"，
     * 免得只靠 target_id 匹配时捞到别的单的消息附件（消息号是全局唯一的，但多一层 JOIN 更稳）。
     *
     * ORDER BY create_time, message_id：同一秒内插的几条消息，靠消息号保证顺序稳定（不会每次查出来顺序乱跳）。
     */
    private List<WorkOrderMessageItem> loadMessages(String workOrderId) {
        List<WorkOrderMessageItem> messages = jdbcTemplate.query(
                "SELECT message_id, sender_id, sender_name, sender_type, msg_type, content, create_time "
                        + "FROM t_work_order_message WHERE work_order_id = ? "
                        + "ORDER BY create_time, message_id",
                new WorkOrderMessageRowMapper(), workOrderId);

        if (messages.isEmpty()) {
            return messages;
        }

        Map<String, List<AttachmentItem>> byMessage = new LinkedHashMap<>();
        List<MessageAttachmentRow> rows = jdbcTemplate.query(
                "SELECT a.target_id AS message_id, a.attachment_id, a.file_url, a.file_name, a.sort_num "
                        + "FROM t_attachment a "
                        + "JOIN t_work_order_message m ON m.message_id = a.target_id "
                        + "WHERE a.target_type = 'MESSAGE' AND m.work_order_id = ? "
                        + "ORDER BY a.sort_num, a.attachment_id",
                MessageAttachmentRow::from, workOrderId);
        for (MessageAttachmentRow row : rows) {
            byMessage.computeIfAbsent(row.messageId(), k -> new ArrayList<>()).add(row.item());
        }

        for (WorkOrderMessageItem m : messages) {
            // 没有附件的消息也要给空数组 []，不要给 null —— 前端少写一层判空，系统消息也不会因此报错
            m.setAttachments(byMessage.getOrDefault(m.getMessageId(), new ArrayList<>()));
        }
        return messages;
    }

    /** 中间载体：一行"某条消息的某个附件"。只为了把 message_id 和附件三元组一起带出来便于分组。 */
    private record MessageAttachmentRow(String messageId, AttachmentItem item) {
        static MessageAttachmentRow from(ResultSet rs, int rowNum) throws SQLException {
            return new MessageAttachmentRow(
                    rs.getString("message_id"),
                    new AttachmentItem(rs.getString("attachment_id"), rs.getString("file_url"),
                            rs.getString("file_name"), rs.getInt("sort_num")));
        }
    }

    /**
     * 取**工单级**附件（提交工单 / 再次咨询时带上来的）。
     * ORDER BY sort_num：按用户上传的顺序展示，和绑定时的编号一致。
     */
    private List<AttachmentItem> loadWorkOrderAttachments(String workOrderId) {
        return jdbcTemplate.query(
                "SELECT attachment_id, file_url, file_name, sort_num FROM t_attachment "
                        + "WHERE target_type = 'WORK_ORDER' AND target_id = ? "
                        + "ORDER BY sort_num, attachment_id",
                (rs, rowNum) -> new AttachmentItem(rs.getString("attachment_id"), rs.getString("file_url"),
                        rs.getString("file_name"), rs.getInt("sort_num")),
                workOrderId);
    }

    /**
     * 取状态流转历史（t_status_log）。
     * ORDER BY operation_time, log_id：同一秒内发生的多次流转（比如"接单+回复"），用日志号兜底保证顺序稳定。
     */
    private List<StatusHistoryItem> loadStatusHistory(String workOrderId) {
        return jdbcTemplate.query(
                "SELECT action, before_status, after_status, operator_name, operation_time "
                        + "FROM t_status_log WHERE work_order_id = ? "
                        + "ORDER BY operation_time, log_id",
                (rs, rowNum) -> {
                    StatusHistoryItem h = new StatusHistoryItem();
                    h.setAction(rs.getString("action"));
                    h.setBeforeStatus(rs.getString("before_status"));
                    h.setAfterStatus(rs.getString("after_status"));
                    h.setOperatorName(rs.getString("operator_name"));
                    h.setOperationTime(rs.getObject("operation_time", LocalDateTime.class));
                    return h;
                },
                workOrderId);
    }

    // ============ 私有辅助：附件绑定 ============

    /**
     * 附件预校验的"结果信封"：通过时返回 null，不通过时带错误码 + 提示语。
     *
     * 为什么用一个 record 而不是直接返回 Result？
     *   Result 是泛型的（Result<T>），如果返回 Result<Void>，调用方还得拆开再包一次，
     *   写出来是 `Result.fail(e.getCode(), e.getMessage())` —— 啰嗦且容易写错。
     *   这里只需要"错误码 + 一句话"，用一个小 record 最直白。
     */
    private record AttachmentCheck(int code, String message) {}

    /**
     * 附件**预校验**：只读，不改库，把文档 1.12 / 第 6 章要求的 6 条规则挨个查一遍。
     *
     * ⚠️ 为什么一定要"预校验"和"真绑定"分成两步？这踩过坑：
     *   @Transactional 的方法里**正常 return（哪怕是 Result.fail）也会提交事务**，
     *   只有抛异常才回滚。所以如果在"工单已经 INSERT 之后"才发现附件不合法、然后
     *   `return Result.fail(400, ...)`，那这张工单会被悄悄地写进库里 —— 用户看到报错，
     *   数据库里却多了一张单。因此所有会导致"失败返回"的校验，必须排在**任何写库之前**。
     *
     * 校验顺序严格照文档：
     *   ① 附件存在吗（不存在 → 400）
     *   ② 还是 TEMP 吗（已绑定 → 400 "附件已使用"）
     *   ③ 是你自己传的吗（他人上传 → 403，这是越权）
     *   ④ target_id / target_type 都为 NULL 吗（有不一致 → 400 "附件已使用"）
     *   ⑤ 还在 7 天有效期内吗（过期 → 400）
     *
     * @param attachmentIds 前端传的附件ID列表；为空表示这条消息没有附件，直接通过
     * @return null 表示全部通过；否则是要原样返回给前端的错误
     */
    private AttachmentCheck checkAttachments(String userId, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return null;
        }
        // 用 Set 去重：前端手滑传了重复 ID 时，不该在后面绑定环节报"附件已使用"这种莫名奇妙的错
        Set<String> ids = new LinkedHashSet<>(attachmentIds);
        for (String attachmentId : ids) {
            if (attachmentId == null || attachmentId.isBlank()) {
                return new AttachmentCheck(400, "附件ID不能为空");
            }

            Map<String, Object> row;
            try {
                // not_expired 交给数据库算（create_time > NOW()-7天）：
                // 免得把 datetime 取成 Java 对象再比较，中间多一层类型转换的坑。
                row = jdbcTemplate.queryForMap(
                        "SELECT upload_user_id, status, target_id, target_type, "
                                + "CASE WHEN create_time > DATE_SUB(NOW(), INTERVAL 7 DAY) THEN 1 ELSE 0 END AS not_expired "
                                + "FROM t_attachment WHERE attachment_id = ?",
                        attachmentId);
            } catch (EmptyResultDataAccessException e) {
                return new AttachmentCheck(400, "附件不存在");
            }

            // ②③④ 三条的顺序照文档：先判"已使用"，再判"是不是你的"
            if (!"TEMP".equals(row.get("status"))
                    || row.get("target_id") != null
                    || row.get("target_type") != null) {
                return new AttachmentCheck(400, "附件已使用");
            }
            if (!userId.equals(row.get("upload_user_id"))) {
                return new AttachmentCheck(403, "不能绑定他人上传的附件");
            }
            // ⑤ 有效期：CASE WHEN 出来的是 1/0（BIGINT），用 Number 接最稳
            Object notExpired = row.get("not_expired");
            if (!(notExpired instanceof Number n) || n.intValue() != 1) {
                return new AttachmentCheck(400, "附件已过期，请重新上传");
            }
        }
        return null;
    }

    /**
     * 附件**真绑定**：把附件挂到业务对象上（工单或消息）。
     *
     * 必须满足两个前提：
     *   ① 已经过了 checkAttachments 预校验；
     *   ② 业务数据（工单 / 消息）已经 INSERT 成功，能拿到它的 ID。
     * 因为它和业务写入在**同一个 @Transactional 事务**里，所以：
     *   · 绑定失败 → 抛异常 → 工单/消息一起回滚，"工单建好了但附件没挂上"这种脏状态不会出现；
     *   · 业务失败 → 回滚 → 刚绑好的附件也跟着撤销。
     *
     * 最后一道并发闸门写在 UPDATE 的 WHERE 里（`status = 'TEMP'`）：
     * 两个请求同时抢同一个附件时，数据库只会让一个 UPDATE 影响 1 行，另一个影响 0 行。
     * 影响 0 行 → 直接抛异常回滚，绝不做"先 SELECT 再判断"那种有竞态的写法。
     *
     * sort_num 用列表下标（0 开始），这样前端拿到的附件顺序 = 用户上传时的顺序。
     *
     * @param targetId   工单号（targetType=WORK_ORDER）或消息号（targetType=MESSAGE）
     * @param targetType 'WORK_ORDER' 或 'MESSAGE'
     */
    private void bindAttachments(List<String> attachmentIds, String targetId, String targetType) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        int sortNum = 0;
        for (String attachmentId : new LinkedHashSet<>(attachmentIds)) {
            int affected = jdbcTemplate.update(
                    "UPDATE t_attachment SET target_id = ?, target_type = ?, status = 'BOUND', "
                            + "bind_time = NOW(), sort_num = ? "
                            + "WHERE attachment_id = ? AND status = 'TEMP'",
                    targetId, targetType, sortNum, attachmentId);
            if (affected == 0) {
                throw new IllegalStateException("附件已被占用，绑定失败：" + attachmentId);
            }
            sortNum++;
        }
    }

    // ---------- 各类业务编号 ----------
    // 全部委托给 DailySeqId：它用 MAX(序号)+1 而不是 COUNT(*)+1，
    // 因为 COUNT 在"表里删过行"之后会算小，生成出已经存在的编号 → 主键冲突（踩过一次）。
    // 并发安全仍靠后续接 Redis INCR（就改 DailySeqId 一处）。

    /** 工单号：WO + yyyyMMdd + 4 位当日序号 */
    private String generateWorkOrderId() {
        return DailySeqId.next(jdbcTemplate, redis, "WO", "t_work_order", "work_order_id", 4);
    }

    /** 消息号：MSG + yyyyMMdd + 6 位当日序号 */
    private String generateMessageId() {
        return DailySeqId.next(jdbcTemplate, redis, "MSG", "t_work_order_message", "message_id", 6);
    }

    /** 状态日志号：SLOG + yyyyMMdd + 6 位当日序号 */
    private String generateStatusLogId() {
        return DailySeqId.next(jdbcTemplate, redis, "SLOG", "t_status_log", "log_id", 6);
    }

    /** 合并记录号：MRG + yyyyMMdd + 4 位当日序号（和工单号一样是 4 位） */
    private String generateMergeId() {
        return DailySeqId.next(jdbcTemplate, redis, "MRG", "t_merge_relation", "merge_id", 4);
    }

    /** 通知号：NOT + yyyyMMdd + 6 位当日序号 */
    private String generateNotifyId() {
        return DailySeqId.next(jdbcTemplate, redis, "NOT", "t_notification_log", "notify_id", 6);
    }
}
