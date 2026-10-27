package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.UnreadCountResult;
import com.secondclassroom.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息红点接口：/api/messages
 * - GET /api/messages/unread-count   我在所有工单里的未读消息总数
 *
 * 注意它和 /api/notifications/unread-count 是**两个不同的红点**：
 *   - 这里统计的是「工单对话里的消息」有没有读过（依据 t_message_read 表）
 *   - 通知中心那个统计的是「通知列表里的条目」有没有读过（依据 t_notification_log.is_read）
 * 前端一般同时轮询两个，所以两处都要有。
 *
 * 把工单某一张单的消息标已读的接口在 WorkOrderController：
 *   POST /api/work-orders/{id}/messages/read
 * （它挂在工单资源下更自然，而且要在同一事务里同时清掉两种红点）
 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final NotificationService notificationService;

    public MessageController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** GET /api/messages/unread-count —— 未读消息数（前端红点，15 秒轮询，页面不可见时暂停） */
    @GetMapping("/unread-count")
    public Result<UnreadCountResult> unreadCount(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return notificationService.unreadMessageCount(userId, userType);
    }
}
