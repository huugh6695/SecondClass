package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.AnnouncementItem;
import com.secondclassroom.service.AnnouncementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公告查询接口（文档 §9 的 #30）：/api/announcements
 *
 * 「可选 Token」—— 没登录也能看。理由：首页的公告栏是**打开网站第一眼**看到的东西，
 * 要是它要求先登录，那游客根本不知道这个系统是干什么的。
 * （拦截器里的 OPTIONAL_AUTH_PREFIXES 加了 "/api/announcements"）
 *
 * 注意这里读到的 userType **可能是 null**（游客）。Service 里
 * `"ADMIN".equals(userType)` 对 null 是安全的（返回 false），不会 NPE ——
 * 这就是为什么项目里统一写成「常量.equals(变量)」而不是「变量.equals(常量)」。
 */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    /**
     * GET /api/announcements —— 公告列表。
     *
     * 默认只返回「ACTIVE 且未过期」的公告，按 sortNum 升序、发布时间倒序。
     *
     * @param status 可选，**只对管理员生效**（学生/游客传了也没用）：
     *               ARCHIVED = 只看已归档的，ALL = 全部（含已过期、已归档）。
     *               后台需要一个入口把归档的公告翻出来，不然软删除之后就再也找不到了。
     */
    @GetMapping
    public Result<List<AnnouncementItem>> list(HttpServletRequest request,
                                              @RequestParam(required = false) String status) {
        String userType = (String) request.getAttribute("userType");   // 游客为 null
        return announcementService.listAnnouncements(userType, status);
    }
}
