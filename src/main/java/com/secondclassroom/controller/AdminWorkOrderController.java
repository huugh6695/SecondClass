package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.PageResult;
import com.secondclassroom.dto.WorkOrderSearchItem;
import com.secondclassroom.dto.WorkOrderSearchQuery;
import com.secondclassroom.service.AdminWorkOrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台工单搜索（文档 §10.7 / 接口 #50）：/api/admin/work-orders
 *
 * 只有一个接口：GET /api/admin/work-orders/search —— 全维度检索，仅管理员。
 *
 * 和学生端的 /api/work-orders 是两套东西（详见 AdminWorkOrderService 的类注释）：
 * 那个是"我的工单、按角色隔离"，这个是"全站检索、管理员查账用"。
 *
 * 权限：拦截器保证带了有效 Token，Service 里再判 `!"ADMIN".equals(userType)` → 403。
 *
 * 📌 这一组的查询参数用 WorkOrderSearchQuery 这个对象接（Spring 会按字段名从 Query 里绑定），
 *    不在方法签名上排 13 个 @RequestParam —— 参数一多，位置就成了最容易写错的地方。
 */
@RestController
@RequestMapping("/api/admin/work-orders")
public class AdminWorkOrderController {

    private final AdminWorkOrderService adminWorkOrderService;

    public AdminWorkOrderController(AdminWorkOrderService adminWorkOrderService) {
        this.adminWorkOrderService = adminWorkOrderService;
    }

    /**
     * GET /api/admin/work-orders/search —— 后台工单搜索。
     *
     * Query（全部可选）：
     *   workOrderId  工单号精确      userId   提交人学号精确    userName  提交人姓名模糊
     *   handlerId    负责人工号精确  handlerName 负责人姓名模糊
     *   status       状态，可逗号分隔多个（PENDING,CLOSED）
     *   category     分类精确        keyword  内容关键词（模糊匹配 content）
     *   startDate / endDate  提交日期区间 yyyy-MM-dd（含当天）
     *   isFlagged    是否命中敏感词（true/false）  isDuplicate 是否被合并的重复单（true/false）
     *   page / pageSize      分页（默认 1 / 20，上限 50）
     *
     * 返回：{ total, page, pageSize, list: [ WorkOrderSearchItem... ] }
     */
    @GetMapping("/search")
    public Result<PageResult<WorkOrderSearchItem>> search(HttpServletRequest request,
                                                          WorkOrderSearchQuery query) {
        String userType = (String) request.getAttribute("userType");
        return adminWorkOrderService.search(userType, query);
    }
}
