package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.FaqItem;
import com.secondclassroom.dto.FaqSaveRequest;
import com.secondclassroom.service.FaqService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FAQ 管理接口（文档 §8 的 #27 / #28 / #29）：/api/admin/faqs
 *
 * 和 FaqController 的区别，就一句话：**这里的每个接口都强制要管理员 Token**。
 * 拦截器把整站都拦了（除了 /api/auth/login 和可选的 /api/faqs），所以"没带 Token"在拦截器就 401 了；
 * "带了 Token 但不是管理员"则由 Service 里那句 `if (!"ADMIN".equals(userType))` 返回 403。
 * 两层缺一不可：拦截器管"你有没有进门"，Service 管"你有没有这个权限"。
 *
 * 三个写操作都往 t_operation_log 记一笔（operation_type = CREATE_FAQ / UPDATE_FAQ / DELETE_FAQ），
 * 这样管理员在后台的每一次改动都能查账。
 */
@RestController
@RequestMapping("/api/admin/faqs")
public class AdminFaqController {

    private final FaqService faqService;

    public AdminFaqController(FaqService faqService) {
        this.faqService = faqService;
    }

    /**
     * POST /api/admin/faqs —— 新增 FAQ。
     * 请求体 {"category","question","answer","keywords"}；status 固定 PUBLISHED、sortNum 固定 0。
     */
    @PostMapping
    public Result<FaqItem> create(HttpServletRequest request, @RequestBody FaqSaveRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return faqService.createFaq(userId, userType, req);
    }

    /**
     * PUT /api/admin/faqs/{faqId} —— 编辑 FAQ（部分更新：传了才改，null 表示不动）。
     * 请求体可只写要改的字段，例如 {"answer":"新的答案"}。
     */
    @PutMapping("/{faqId}")
    public Result<FaqItem> update(HttpServletRequest request,
                                 @PathVariable String faqId,
                                 @RequestBody FaqSaveRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return faqService.updateFaq(userId, userType, faqId, req);
    }

    /**
     * DELETE /api/admin/faqs/{faqId} —— 下架 FAQ。
     * **软删除**：只把 status 改成 UNPUBLISHED，行留在库里（审计日志里还引用着它）。
     */
    @DeleteMapping("/{faqId}")
    public Result<Void> unpublish(HttpServletRequest request, @PathVariable String faqId) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return faqService.unpublishFaq(userId, userType, faqId);
    }
}
