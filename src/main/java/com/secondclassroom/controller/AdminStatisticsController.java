package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.StaffWorkloadItem;
import com.secondclassroom.dto.StatisticsOverviewResult;
import com.secondclassroom.service.AdminStatisticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端数据统计（文档 §10.4 / 接口 #45、#46）：/api/admin/statistics
 *
 * 两个接口，都只有管理员能调：
 *   GET /api/admin/statistics/overview        统计总览（总量 / 重复率 / 分布 / 平均时长）
 *   GET /api/admin/statistics/staff-workload  干事工作量（每人多少单 / 完结多少 / 平均办理多久）
 *
 * 权限：拦截器保证带了有效 Token，Service 里再判 `!"ADMIN".equals(userType)` → 403。
 *
 * 📌 日期参数**故意声明成 String**，不是 LocalDate：
 *    用 LocalDate 接参时，格式一错 Spring 会在"进方法之前"就抛
 *    MethodArgumentTypeMismatchException，而全局异常处理器没覆盖它 —— 结果是 **500**，
 *    明明是用户传错了参数，却看起来像服务器崩了。收 String 自己解析 → 能给干净的 400。
 *    （这是项目里既有约定，范本见 AdminLogService.listOperationLogs）
 */
@RestController
@RequestMapping("/api/admin/statistics")
public class AdminStatisticsController {

    private final AdminStatisticsService adminStatisticsService;

    public AdminStatisticsController(AdminStatisticsService adminStatisticsService) {
        this.adminStatisticsService = adminStatisticsService;
    }

    /**
     * GET /api/admin/statistics/overview —— 统计总览。
     *
     * Query（都可选，都不传 = 统计全部工单）：
     *   startDate  起始日期 yyyy-MM-dd（含当天）
     *   endDate    截止日期 yyyy-MM-dd（含当天 —— 截止日整天都算在内）
     *
     * 返回 data：{ totalTickets, duplicateRate, statusDistribution, categoryDistribution,
     *             avgResponseMinutes, avgCloseMinutes }
     */
    @GetMapping("/overview")
    public Result<StatisticsOverviewResult> overview(HttpServletRequest request,
                                                     @RequestParam(required = false) String startDate,
                                                     @RequestParam(required = false) String endDate) {
        String userType = (String) request.getAttribute("userType");
        return adminStatisticsService.overview(userType, startDate, endDate);
    }

    /**
     * GET /api/admin/statistics/staff-workload —— 干事工作量。
     *
     * Query 同 overview（startDate / endDate，可选）。
     *
     * 返回 data：**一个数组**（不是分页对象 —— 文档没给这个接口分页参数，
     * 干事人数本来就是个位数，一次全返回正合适）：
     *   [ { handlerId, handlerName, total, closedCount, avgCloseMinutes }, ... ]
     */
    @GetMapping("/staff-workload")
    public Result<List<StaffWorkloadItem>> staffWorkload(HttpServletRequest request,
                                                         @RequestParam(required = false) String startDate,
                                                         @RequestParam(required = false) String endDate) {
        String userType = (String) request.getAttribute("userType");
        return adminStatisticsService.staffWorkload(userType, startDate, endDate);
    }
}
