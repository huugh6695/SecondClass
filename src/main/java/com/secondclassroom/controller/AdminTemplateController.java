package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.TemplateItem;
import com.secondclassroom.dto.TemplateSaveRequest;
import com.secondclassroom.service.TemplateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 快捷回复模板管理接口（文档 §10.3 的 #42 / #43 / #44）：/api/admin/templates
 *
 * 和 TemplateController 的区别：**这里的每个接口都强制要管理员 Token**。
 * 拦截器管"有没有进门"（没带 Token → 401），
 * Service 里那句 `if (!"ADMIN".equals(userType))` 管"是不是管理员"（→ 403）。
 *
 * 三个写操作都往 t_operation_log 记一笔（operation_type = CREATE_TEMPLATE /
 * UPDATE_TEMPLATE / DELETE_TEMPLATE，target_type = TEMPLATE，
 * target_id = templateId 如 TPL0001）。
 */
@RestController
@RequestMapping("/api/admin/templates")
public class AdminTemplateController {

    private final TemplateService templateService;

    public AdminTemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * POST /api/admin/templates —— 新增模板。
     * 请求体 {"category","title","content","sortNum"}；status 固定 ACTIVE、templateId 自动生成。
     */
    @PostMapping
    public Result<TemplateItem> create(HttpServletRequest request,
                                      @RequestBody TemplateSaveRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return templateService.createTemplate(userId, userType, req);
    }

    /**
     * PUT /api/admin/templates/{templateId} —— 编辑模板（部分更新：传了才改，null 表示不动）。
     * 只想改话术就写 {"content":"新的回复话术"}；
     * 想重新启用被禁用的模板就写 {"status":"ACTIVE"}。
     */
    @PutMapping("/{templateId}")
    public Result<TemplateItem> update(HttpServletRequest request,
                                      @PathVariable String templateId,
                                      @RequestBody TemplateSaveRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return templateService.updateTemplate(userId, userType, templateId, req);
    }

    /**
     * DELETE /api/admin/templates/{templateId} —— 禁用模板。
     * **软删除**：只把 status 改成 DISABLED，行留在库里
     * （审计日志引用着它，而且 PUT status=ACTIVE 就能复活）。
     */
    @DeleteMapping("/{templateId}")
    public Result<Void> disable(HttpServletRequest request, @PathVariable String templateId) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return templateService.disableTemplate(userId, userType, templateId);
    }
}
