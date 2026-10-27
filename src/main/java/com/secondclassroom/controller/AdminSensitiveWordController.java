package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.SensitiveWordAddRequest;
import com.secondclassroom.dto.SensitiveWordBatchRequest;
import com.secondclassroom.dto.SensitiveWordBatchResult;
import com.secondclassroom.dto.SensitiveWordItem;
import com.secondclassroom.service.SensitiveWordService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 敏感词管理接口（文档 §10.2 的 #37~#40）：/api/admin/sensitive-words
 *
 * 四个接口全部仅管理员可用 —— 拦截器管"有没有带 Token"（没带 → 401），
 * Service 里那句 `if (!"ADMIN".equals(userType))` 管"是不是管理员"（不是 → 403）。
 *
 * 敏感词是**给工单打标记**用的，不是提交闸门：
 * 学生提交的内容命中敏感词时，工单会被打上 is_flagged，管理员在后台
 * `GET /api/admin/work-orders/search?isFlagged=true` 里能专门筛出来复核。
 * 所以这张表的作用是"把可疑内容捞出来给人看"，而不是"自动拒绝提交"。
 *
 * ⚠️ 文档里的路径写的是 `/api/admin/sensitive-words` —— 文档自己也把表名写成了复数
 *    (`t_sensitive_words`)，但**库里的表是单数 `t_sensitive_word`**。
 *    接口路径按文档保持复数（因为这是对外的 URL，前后端约定好了），只是别被它带歪去查错表。
 */
@RestController
@RequestMapping("/api/admin/sensitive-words")
public class AdminSensitiveWordController {

    private final SensitiveWordService sensitiveWordService;

    public AdminSensitiveWordController(SensitiveWordService sensitiveWordService) {
        this.sensitiveWordService = sensitiveWordService;
    }

    /**
     * GET /api/admin/sensitive-words —— 敏感词列表。
     * 照文档**不分页**、**不过滤状态**（禁用中的词也要能看到，否则禁错了想恢复都找不到它）。
     */
    @GetMapping
    public Result<List<SensitiveWordItem>> list(HttpServletRequest request) {
        String userType = (String) request.getAttribute("userType");
        return sensitiveWordService.listWords(userType);
    }

    /**
     * POST /api/admin/sensitive-words —— 新增一个敏感词。
     * 请求体 {"word":"违规词"}。已存在的词不会报错，会如实告诉你是"已存在"还是"重新启用"。
     */
    @PostMapping
    public Result<SensitiveWordItem> add(HttpServletRequest request,
                                        @RequestBody SensitiveWordAddRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return sensitiveWordService.addWord(userId, userType, req);
    }

    /**
     * POST /api/admin/sensitive-words/batch —— 批量导入。
     * 请求体 {"words":"词1,词2,词3"}（中文逗号、顿号、分号、换行都认）。
     * 返回 {total, added, skipped}，恒等式 added + skipped == total。
     */
    @PostMapping("/batch")
    public Result<SensitiveWordBatchResult> batchAdd(HttpServletRequest request,
                                                     @RequestBody SensitiveWordBatchRequest req) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return sensitiveWordService.batchAdd(userId, userType, req);
    }

    /**
     * DELETE /api/admin/sensitive-words/{wordId} —— 禁用敏感词（软删除：status='DISABLED'）。
     *
     * ⚠️ wordId 特意声明成 **String** 而不是 int：
     *    声明成 int 的话，路径上写 `/abc` 会让 Spring 在参数绑定阶段抛
     *    MethodArgumentTypeMismatchException，全局异常处理器没覆盖它 → 用户看到 500。
     *    收成 String 自己 parse，才能回一个"敏感词 ID 必须是数字"的 400。
     *    （AdminLogService 的日期参数是同一个坑。）
     */
    @DeleteMapping("/{wordId}")
    public Result<Void> disable(HttpServletRequest request, @PathVariable String wordId) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");
        return sensitiveWordService.disableWord(userId, userType, wordId);
    }
}
