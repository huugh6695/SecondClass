package com.secondclassroom.controller;

import com.secondclassroom.common.IdempotencyStore;
import com.secondclassroom.common.Result;
import com.secondclassroom.dto.WorkOrderAcceptRequest;
import com.secondclassroom.dto.WorkOrderAcceptResult;
import com.secondclassroom.dto.WorkOrderCloseRequest;
import com.secondclassroom.dto.WorkOrderCloseResult;
import com.secondclassroom.dto.WorkOrderDetailResult;
import com.secondclassroom.dto.WorkOrderFollowUpRequest;
import com.secondclassroom.dto.WorkOrderFollowUpResult;
import com.secondclassroom.dto.WorkOrderListResult;
import com.secondclassroom.dto.WorkOrderMergeRequest;
import com.secondclassroom.dto.WorkOrderMergeResult;
import com.secondclassroom.dto.WorkOrderMessageItem;
import com.secondclassroom.dto.WorkOrderMessagesReadResult;
import com.secondclassroom.dto.WorkOrderReConsultRequest;
import com.secondclassroom.dto.WorkOrderReConsultResult;
import com.secondclassroom.dto.WorkOrderReplyRequest;
import com.secondclassroom.dto.WorkOrderReplyResult;
import com.secondclassroom.dto.WorkOrderReturnRequest;
import com.secondclassroom.dto.WorkOrderReturnResult;
import com.secondclassroom.dto.WorkOrderSubmitRequest;
import com.secondclassroom.dto.WorkOrderSubmitResult;
import com.secondclassroom.service.WorkOrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单接口：/api/work-orders
 * - POST   /api/work-orders           学生提交工单
 * - GET    /api/work-orders           列表查询（按角色过滤 + 分页 + 筛选）
 * - GET    /api/work-orders/{id}      工单详情（含消息时间线）
 * - POST   /api/work-orders/{id}/accept  干事受理工单（仅 STAFF/ADMIN）
 * - POST   /api/work-orders/{id}/reply   干事回复工单（仅当前处理人/ADMIN）
 * - POST   /api/work-orders/{id}/follow-up  学生追问（仅提交人/ADMIN）
 * - POST   /api/work-orders/{id}/confirm    学生确认解决（REPLIED → CLOSED）
 * - POST   /api/work-orders/{id}/close      干事手动完结（PROCESSING/REPLIED → CLOSED）
 * - POST   /api/work-orders/{id}/admin-close 管理员强制关闭（PENDING → CLOSED，必填 remark）
 * - POST   /api/work-orders/{id}/return      退回工单回待接单池（PROCESSING → PENDING，清空负责人）
 * - POST   /api/work-orders/{id}/merge       合并重复工单（子工单 → MERGED 终态）
 * - POST   /api/work-orders/{id}/re-consult  再次咨询（基于已完结单再开一张新单，7 天限 1 次）
 * - POST   /api/work-orders/{id}/messages/read 把该工单的消息全部标为已读（同事务清两种红点）
 */
@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    /**
     * 幂等存储。放在 **Controller** 而不是 Service，是因为结果缓存要在
     * **事务提交之后**才写：Controller 这一层拿到返回值时，Service 的 @Transactional
     * 已经提交完了。要是写在 Service 的返回语句旁边，万一事务最后提交失败，
     * 缓存里就留下一个"指向不存在工单"的假结果。
     */
    private final IdempotencyStore idempotencyStore;

    public WorkOrderController(WorkOrderService workOrderService, IdempotencyStore idempotencyStore) {
        this.workOrderService = workOrderService;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * POST /api/work-orders —— 学生提交工单（需 Token，JWT 拦截器已放好 userId）。
     *
     * 幂等（文档 §1.11）：前端每次点击生成一个 requestId，重试时沿用同一个。
     * 指纹里带上 userId / 接口名 / 分类 / 正文 —— 只有这四样都一样，才算"同一个请求"。
     */
    @PostMapping
    public Result<WorkOrderSubmitResult> submit(HttpServletRequest request,
                                              @RequestBody WorkOrderSubmitRequest req) {
        String userId = (String) request.getAttribute("userId");
        String fingerprint = IdempotencyStore.fingerprint(
                userId, "SUBMIT", req.getCategory(), req.getContent());
        return idempotencyStore.run(req.getRequestId(), fingerprint, WorkOrderSubmitResult.class,
                "工单提交成功", () -> workOrderService.submit(userId, req));
    }

    /** GET /api/work-orders —— 列表查询（需 Token，按角色过滤；page/pageSize/status/category/keyword 都可省略） */
    @GetMapping
    public Result<WorkOrderListResult> list(HttpServletRequest request,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer pageSize,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String category,
                                           @RequestParam(required = false) String keyword) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return workOrderService.listWorkOrders(userId, userType, page, pageSize, status, category, keyword);
    }

    /** GET /api/work-orders/{workOrderId} —— 工单详情（含消息时间线；需 Token，按角色鉴权） */
    @GetMapping("/{workOrderId}")
    public Result<WorkOrderDetailResult> detail(HttpServletRequest request,
                                              @PathVariable String workOrderId) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return workOrderService.getDetail(userId, userType, workOrderId);
    }

    /**
     * POST /api/work-orders/{workOrderId}/accept —— 干事受理工单。
     * 只有 STAFF / ADMIN 能调；请求体可选，传 { "version": 0 } 可触发乐观锁校验。
     */
    @PostMapping("/{workOrderId}/accept")
    public Result<WorkOrderAcceptResult> accept(HttpServletRequest request,
                                               @PathVariable String workOrderId,
                                               @RequestBody(required = false) WorkOrderAcceptRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return workOrderService.accept(userId, userType, workOrderId, req);
    }

    /**
     * POST /api/work-orders/{workOrderId}/reply —— 干事回复工单。
     * 只有"当前处理人（handler_id）"或 ADMIN 能回；请求体必填 content，可传 requestId 做幂等。
     */
    @PostMapping("/{workOrderId}/reply")
    public Result<WorkOrderReplyResult> reply(HttpServletRequest request,
                                             @PathVariable String workOrderId,
                                             @RequestBody WorkOrderReplyRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        String fingerprint = IdempotencyStore.fingerprint(
                userId, "REPLY", workOrderId, req.getContent());
        return idempotencyStore.run(req.getRequestId(), fingerprint, WorkOrderReplyResult.class,
                "回复成功", () -> workOrderService.reply(userId, userType, workOrderId, req));
    }

    /**
     * POST /api/work-orders/{workOrderId}/follow-up —— 学生追问。
     * 只有"工单提交人"或 ADMIN 能调；请求体必填 content，可传 requestId 做幂等。
     */
    @PostMapping("/{workOrderId}/follow-up")
    public Result<WorkOrderFollowUpResult> followUp(HttpServletRequest request,
                                                   @PathVariable String workOrderId,
                                                   @RequestBody WorkOrderFollowUpRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        String fingerprint = IdempotencyStore.fingerprint(
                userId, "FOLLOW_UP", workOrderId, req.getContent());
        return idempotencyStore.run(req.getRequestId(), fingerprint, WorkOrderFollowUpResult.class,
                "追问成功", () -> workOrderService.followUp(userId, userType, workOrderId, req));
    }

    /**
     * POST /api/work-orders/{workOrderId}/confirm —— 学生确认解决。
     * 仅工单提交人；前置状态必须是 REPLIED；请求体 {"version": N}。
     */
    @PostMapping("/{workOrderId}/confirm")
    public Result<WorkOrderCloseResult> confirm(HttpServletRequest request,
                                                @PathVariable String workOrderId,
                                                @RequestBody WorkOrderCloseRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return workOrderService.confirm(userId, userType, workOrderId, req);
    }

    /**
     * POST /api/work-orders/{workOrderId}/close —— 干事手动完结。
     * 仅该单负责人或 ADMIN；前置状态 PROCESSING / REPLIED；请求体 {"version": N}。
     */
    @PostMapping("/{workOrderId}/close")
    public Result<WorkOrderCloseResult> close(HttpServletRequest request,
                                              @PathVariable String workOrderId,
                                              @RequestBody WorkOrderCloseRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return workOrderService.close(userId, userType, workOrderId, req);
    }

    /**
     * POST /api/work-orders/{workOrderId}/admin-close —— 管理员强制关闭。
     * 仅 ADMIN；只能关闭 PENDING 单；请求体 {"remark": "关闭原因", "version": N}。
     */
    @PostMapping("/{workOrderId}/admin-close")
    public Result<WorkOrderCloseResult> adminClose(HttpServletRequest request,
                                                   @PathVariable String workOrderId,
                                                   @RequestBody WorkOrderCloseRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return workOrderService.adminClose(userId, userType, workOrderId, req);
    }

    /**
     * POST /api/work-orders/{workOrderId}/return —— 退回工单，丢回待接单池。
     * 仅该单负责人或 ADMIN；前置状态必须是 PROCESSING；请求体 {"remark": "退回原因", "version": N}。
     * remark 选填；退回后 handler_id 会被清空，工单重新变成 PENDING 等人接。
     */
    @PostMapping("/{workOrderId}/return")
    public Result<WorkOrderReturnResult> returnOrder(HttpServletRequest request,
                                                     @PathVariable String workOrderId,
                                                     @RequestBody WorkOrderReturnRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return workOrderService.returnOrder(userId, userType, workOrderId, req);
    }

    /**
     * POST /api/work-orders/{workOrderId}/merge —— 合并重复工单。
     * 仅 STAFF / ADMIN；URL 里的 {workOrderId} 是**被合并的子工单**，
     * 请求体 {"mainOrderId": "主工单号", "version": 子工单当前版本}。
     * 成功后子工单变 MERGED 终态，不再允许任何修改操作。
     */
    @PostMapping("/{workOrderId}/merge")
    public Result<WorkOrderMergeResult> merge(HttpServletRequest request,
                                             @PathVariable String workOrderId,
                                             @RequestBody WorkOrderMergeRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return workOrderService.merge(userId, userType, workOrderId, req);
    }

    /**
     * POST /api/work-orders/{workOrderId}/re-consult —— 再次咨询。
     * 仅原单提交人本人；原单必须是 CLOSED 终态；同一张原单 7 天内只能再来一次（否则 429）。
     * 请求体 {"category": "...", "content": "...", "requestId": "uuid", "attachmentIds": []}。
     * 注意这里**没有 version** —— 它不改原单，只是新建一张带 source_order_id 的新单。
     */
    @PostMapping("/{workOrderId}/re-consult")
    public Result<WorkOrderReConsultResult> reConsult(HttpServletRequest request,
                                                      @PathVariable String workOrderId,
                                                      @RequestBody WorkOrderReConsultRequest req) {
        String userId = (String) request.getAttribute("userId");
        String fingerprint = IdempotencyStore.fingerprint(
                userId, "RE_CONSULT", workOrderId, req.getCategory(), req.getContent());
        return idempotencyStore.run(req.getRequestId(), fingerprint, WorkOrderReConsultResult.class,
                "再次咨询提交成功", () -> workOrderService.reConsult(userId, workOrderId, req));
    }

    /**
     * GET /api/work-orders/{workOrderId}/messages —— 消息列表（文档 §5，含每条消息的附件）。
     *
     * 和「详情」分开是有意的：聊天页轮询时只要消息，不需要工单字段 / 附件 / 状态历史。
     * 权限校验与详情一致（学生仅本人、干事仅待接单或自己接的单、管理员全部）。
     * 返回的是**数组**（不像其他接口返回对象），直接放在 Result.data 里。
     */
    @GetMapping("/{workOrderId}/messages")
    public Result<List<WorkOrderMessageItem>> listMessages(HttpServletRequest request,
                                                          @PathVariable String workOrderId) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return workOrderService.listMessages(userId, userType, workOrderId);
    }

    /**
     * POST /api/work-orders/{workOrderId}/messages/read —— 把这张单的消息全部标为已读。
     * 需 Token；权限校验同工单详情。
     * 关键：它会在**同一个事务**里同时清掉「消息红点」和「通知红点」，
     * 否则用户会看到"消息都读了但通知列表还有红点"。请求体为空即可。
     */
    @PostMapping("/{workOrderId}/messages/read")
    public Result<WorkOrderMessagesReadResult> markMessagesRead(HttpServletRequest request,
                                                               @PathVariable String workOrderId) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return workOrderService.markMessagesRead(userId, userType, workOrderId);
    }
}
