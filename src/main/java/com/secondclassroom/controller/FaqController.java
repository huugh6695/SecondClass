package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.FaqItem;
import com.secondclassroom.dto.FaqListItem;
import com.secondclassroom.service.FaqService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * FAQ 公开读接口（文档 §8 的 #25 / #26）：/api/faqs
 *
 * 这两个接口是**可选 Token**：没登录也能看（学生/游客查常见问题），
 * 但登录了并且是管理员时，能看到未发布（UNPUBLISHED）的条目。
 *
 * 所以这里取 request 属性时**不能假设它一定有值**：
 *   · 匿名访问时 JwtInterceptor 是放行的，但不会往 request 里塞 userId/userType；
 *   · 这里读出来就是 null —— 正好用 "ADMIN".equals(userType) 这种写法（null 安全）判断。
 * 管理端的增删改在另一个 Controller（AdminFaqController），因为路径前缀不同，
 * 而且那些接口是**必须**带管理员 Token 的，混在一起容易漏掉鉴权。
 */
@RestController
@RequestMapping("/api/faqs")
public class FaqController {

    private final FaqService faqService;

    public FaqController(FaqService faqService) {
        this.faqService = faqService;
    }

    /**
     * GET /api/faqs —— FAQ 列表。
     * Query：category（分类筛选，可选）、keyword（关键词搜索，可选）。
     * 返回 data 是**数组**（这个接口文档没给分页参数，FAQ 总量本来只有几十条）。
     */
    @GetMapping
    public Result<List<FaqListItem>> list(HttpServletRequest request,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String keyword) {
        // 匿名访问时这两个属性是 null，交给 Service 当"非管理员"处理
        String userType = (String) request.getAttribute("userType");
        return faqService.listFaqs(userType, category, keyword);
    }

    /**
     * GET /api/faqs/{faqId} —— FAQ 详情，顺便把浏览量 +1。
     * 未发布的对非管理员返回 404（不暴露存在性）。
     */
    @GetMapping("/{faqId}")
    public Result<FaqItem> detail(HttpServletRequest request, @PathVariable String faqId) {
        String userType = (String) request.getAttribute("userType");
        return faqService.getFaqDetail(faqId, userType);
    }
}
