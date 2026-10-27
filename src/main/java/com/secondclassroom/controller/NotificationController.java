package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.MarkReadResult;
import com.secondclassroom.dto.NotificationListResult;
import com.secondclassroom.dto.UnreadCountResult;
import com.secondclassroom.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知中心接口：/api/notifications
 * - GET  /api/notifications                       我的通知列表（分页 + 已读/未读筛选）
 * - GET  /api/notifications/unread-count          未读通知数（前端红点，15 秒轮询）
 * - POST /api/notifications/read-all              全部标记为已读
 * - POST /api/notifications/{notifyId}/read       单条标记为已读
 *
 * 所有接口都要 Token（JwtInterceptor 拦 /**），且只能操作**自己的**通知 ——
 * 用户 id 一律从 Token 里取，**绝不从 URL 或请求体里取**，否则就能改别人的数据了。
 *
 * ⚠️ 路由顺序提醒：`/read-all` 和 `/unread-count` 必须能优先于 `/{notifyId}/read` 匹配。
 * 因为它们路径段数不同（前者 2 段，后者 3 段），Spring 不会混淆，所以这里不用特殊处理。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * GET /api/notifications —— 我的通知列表。
     * page 默认 1；pageSize 默认 20（上限 50）；isRead 可选（true 只看已读 / false 只看未读）。
     */
    @GetMapping
    public Result<NotificationListResult> list(HttpServletRequest request,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer pageSize,
                                              @RequestParam(required = false) Boolean isRead) {
        String userId = (String) request.getAttribute("userId");
        return notificationService.list(userId, page, pageSize, isRead);
    }

    /** GET /api/notifications/unread-count —— 未读通知数 */
    @GetMapping("/unread-count")
    public Result<UnreadCountResult> unreadCount(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return notificationService.unreadCount(userId);
    }

    /** POST /api/notifications/read-all —— 全部标记为已读 */
    @PostMapping("/read-all")
    public Result<MarkReadResult> readAll(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return notificationService.markAllRead(userId);
    }

    /**
     * POST /api/notifications/{notifyId}/read —— 单条标记为已读。
     * 只能标自己的；标别人的会得到 404（而不是 403，避免暴露"这条通知存在"）。
     */
    @PostMapping("/{notifyId}/read")
    public Result<MarkReadResult> read(HttpServletRequest request,
                                       @PathVariable String notifyId) {
        String userId = (String) request.getAttribute("userId");
        return notificationService.markRead(userId, notifyId);
    }
}
