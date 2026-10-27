package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.PageResult;
import com.secondclassroom.dto.UserListItem;
import com.secondclassroom.dto.UserStatusUpdateRequest;
import com.secondclassroom.service.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（文档 §10.1 的 #34 / #35 / #36）：/api/admin/users
 *
 * 两个层缺一不可：拦截器管"有没有带 Token"，Service 里那句
 * `if (!"ADMIN".equals(userType))` 管"是不是管理员"。
 *
 * ⚠️ 注意参数命名的一个小坑：方法里有两个"userType"含义完全不同 ——
 *   · `request.getAttribute("userType")` 是**操作人**的角色（必须是 ADMIN）；
 *   · `@RequestParam userType` 是**筛选条件**（按 STUDENT/STAFF/ADMIN 过滤列表）。
 *   为了不让人看混，Controller 里把查询参数直接叫 userType（和文档一致），
 *   但传给 Service 时放在 `roleFilter` 位置；Service 的方法签名也用 roleFilter 命名。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /**
     * GET /api/admin/users —— 用户列表（分页 + 筛选）。
     *
     * @param userType 按角色筛：STUDENT / STAFF / ADMIN（可空）
     * @param status   按状态筛：ACTIVE / DISABLED（可空）
     * @param keyword  关键词，同时模糊匹配学号/工号和姓名（可空）
     * @param page     页码，默认 1
     * @param pageSize 每页条数，默认 20，**上限 50**
     */
    @GetMapping
    public Result<PageResult<UserListItem>> list(HttpServletRequest request,
                                                 @RequestParam(required = false) String userType,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer pageSize) {
        String operatorUserType = (String) request.getAttribute("userType");
        return adminUserService.listUsers(operatorUserType, userType, status, keyword, page, pageSize);
    }

    /**
     * PUT /api/admin/users/{userId}/status —— 启用 / 禁用账号。
     * 请求体 {"status":"DISABLED"} 或 {"status":"ACTIVE"}。
     *
     * 🔴 不能修改**自己**的账号状态（会把自己锁在系统外），Service 里会返回 400。
     * 禁用是**立即生效**的：拦截器每次请求都会查 t_user.status，DISABLED → 403。
     */
    @PutMapping("/{userId}/status")
    public Result<UserListItem> updateStatus(HttpServletRequest request,
                                            @PathVariable String userId,
                                            @RequestBody UserStatusUpdateRequest req) {
        String operatorId = (String) request.getAttribute("userId");
        String operatorUserType = (String) request.getAttribute("userType");
        return adminUserService.updateStatus(operatorId, operatorUserType, userId, req);
    }

    /**
     * POST /api/admin/users/{userId}/reset-password —— 重置密码为「该账号后 6 位」。
     *
     * 返回体里**不带**明文密码（照文档口径：管理员只需要知道规则）。
     * ⚠️ 重置密码不会让该用户已发出的 Token 失效（JWT 无状态），要"踢下线"得靠第⑧阶段的 Redis 黑名单。
     */
    @PostMapping("/{userId}/reset-password")
    public Result<Void> resetPassword(HttpServletRequest request, @PathVariable String userId) {
        String operatorId = (String) request.getAttribute("userId");
        String operatorUserType = (String) request.getAttribute("userType");
        return adminUserService.resetPassword(operatorId, operatorUserType, userId);
    }
}
