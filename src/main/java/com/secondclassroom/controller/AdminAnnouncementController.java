package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.AnnouncementItem;
import com.secondclassroom.dto.AnnouncementSaveRequest;
import com.secondclassroom.service.AnnouncementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公告管理接口（文档 §9 的 #31 / #32 / #33）：/api/admin/announcements
 *
 * 和 AnnouncementController 的区别就一句话：**这里的每个接口都强制要管理员 Token**。
 * 拦截器管"你有没有进门"（没带 Token → 401），
 * Service 里那句 `if (!"ADMIN".equals(userType))` 管"你有没有这个权限"（→ 403）。
 *
 * 三个写操作都往 t_operation_log 记一笔
 * （operation_type = CREATE_ANNOUNCEMENT / UPDATE_ANNOUNCEMENT / ARCHIVE_ANNOUNCEMENT，
 *  target_type 统一是 ANNOUNCEMENT），这样管理员在后台的每次改动都能在日志里查到。
 */
@RestController
@RequestMapping("/api/admin/announcements")
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    public AdminAnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    /**
     * POST /api/admin/announcements —— 发布公告。
     * 请求体 {"title","content","sortNum","expireTime"}；status 固定 ACTIVE。
     */
    @PostMapping
    public Result<AnnouncementItem> create(HttpServletRequest request,
                                          @RequestBody AnnouncementSaveRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return announcementService.createAnnouncement(userId, userType, req);
    }

    /**
     * PUT /api/admin/announcements/{noticeId} —— 编辑公告（部分更新：传了才改，null 表示不动）。
     * 请求体可只写要改的字段，例如 {"title":"新的标题"}；
     * 想把公告改成永久有效，传 {"expireTime":""}。
     */
    @PutMapping("/{noticeId}")
    public Result<AnnouncementItem> update(HttpServletRequest request,
                                          @PathVariable String noticeId,
                                          @RequestBody AnnouncementSaveRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return announcementService.updateAnnouncement(userId, userType, noticeId, req);
    }

    /**
     * DELETE /api/admin/announcements/{noticeId} —— 归档公告。
     * **软删除**：只把 status 改成 ARCHIVED，行留在库里（审计日志里还引用着它，
     * 而且管理员还能用 GET /api/announcements?status=ARCHIVED 把它翻出来）。
     */
    @DeleteMapping("/{noticeId}")
    public Result<Void> archive(HttpServletRequest request, @PathVariable String noticeId) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return announcementService.archiveAnnouncement(userId, userType, noticeId);
    }
}
