package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.OperationLogItem;
import com.secondclassroom.dto.PageResult;
import com.secondclassroom.dto.StatusLogItem;
import com.secondclassroom.service.AdminLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员日志查询（文档 §10.6 / 接口 #48、#49）：/api/admin/logs
 *
 * 两个接口都返回**分页对象**：{ total, page, pageSize, list }。
 *
 * 权限：必须管理员。拦截器先保证"带了有效 Token"，
 * 然后 Service 里再判 `!"ADMIN".equals(userType)` → 403。
 * （这两层缺一不可：拦截器管"进没进门"，Service 管"有没有这个权限"。）
 *
 * 查的是两张表，别混：
 *   · /status-logs    → t_status_log（工单状态怎么变的）
 *   · /operation-logs → t_operation_log（谁动过系统：登录、提交、接单、管 FAQ……）
 */
@RestController
@RequestMapping("/api/admin/logs")
public class AdminLogController {

    private final AdminLogService adminLogService;

    public AdminLogController(AdminLogService adminLogService) {
        this.adminLogService = adminLogService;
    }

    /**
     * GET /api/admin/logs/status-logs —— 状态流转日志。
     * Query：workOrderId（按工单筛选，可选）、page、pageSize。
     */
    @GetMapping("/status-logs")
    public Result<PageResult<StatusLogItem>> statusLogs(HttpServletRequest request,
                                                       @RequestParam(required = false) String workOrderId,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer pageSize) {
        String userType = (String) request.getAttribute("userType");
        return adminLogService.listStatusLogs(userType, workOrderId, page, pageSize);
    }

    /**
     * GET /api/admin/logs/operation-logs —— 操作审计日志。
     * Query：operatorId（操作人）、operationType（操作类型）、startDate / endDate（yyyy-MM-dd，含当天）、
     *        page、pageSize。参数都可选，不传就是全量分页。
     */
    @GetMapping("/operation-logs")
    public Result<PageResult<OperationLogItem>> operationLogs(
            HttpServletRequest request,
            @RequestParam(required = false) String operatorId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        String userType = (String) request.getAttribute("userType");
        return adminLogService.listOperationLogs(userType, operatorId, operationType,
                startDate, endDate, page, pageSize);
    }
}
