package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.TemplateItem;
import com.secondclassroom.service.TemplateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 快捷回复模板查询接口（文档 §10.3 的 #41）：/api/templates
 *
 * 🔴 **这个接口需要 Token，但任何角色都能读**（文档标的是「需Token」，角色列写的是"全部"）。
 *
 * 所以它既不像 FAQ/公告那样是「可选 Token」，也不是管理员专属：
 *   · 它不在 /api/admin/** 下，但拦截器默认拦住**所有**路径 → 天然"需要 Token"，
 *     **不用改拦截器**；
 *   · 千万**不要**把 "/api/templates" 加进 JwtInterceptor 的 OPTIONAL_AUTH_PREFIXES ——
 *     模板是给干事回复工单时套用的，游客没有使用场景，不该匿名可见。
 *
 * 为什么学生也能读？因为文档的角色列是"全部"。真要说业务理由：
 * 学生看到答复里用的话术，至少不会觉得"怎么回得这么敷衍"；
 * 而且限制它对学生也没意义（模板本身不含隐私信息）。
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * GET /api/templates —— 模板列表。
     *
     * 默认只返回「ACTIVE」的模板，按 sortNum 升序。
     *
     * @param category 可选，按分类筛；值必须是那 5 个中文分类之一，否则 400。
     * @param status   可选，**只对管理员生效**：DISABLED = 只看已禁用的，ALL = 全部。
     *                 后台需要一个入口把禁用掉的模板翻出来（禁用是软删除），
     *                 否则禁错一个就再也找不到它了。
     */
    @GetMapping
    public Result<List<TemplateItem>> list(HttpServletRequest request,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String status) {
        // 这个接口需要 Token，所以不到 null（拦截器已经放行了带有效 Token 的请求）
        String userType = (String) request.getAttribute("userType");
        return templateService.listTemplates(userType, category, status);
    }
}
