package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.WorkOrderSearchQuery;
import com.secondclassroom.service.AdminExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 管理端数据导出（文档 §10.5 / 接口 #47）：/api/admin/export/work-orders，仅管理员。
 *
 * ⚠️ 这是项目里**第二个"成功时不返回 Result"的接口**（响应体是 xlsx 字节流）：
 *    另一个是附件下载 GET /api/files/{id}。两个都是"Success 返回文件、失败仍然返回 Result JSON"，
 *    前端按 Content-Type 判断拿到的是文件还是错误 JSON。
 *
 * 📌 查询参数直接复用 #50 的 WorkOrderSearchQuery（Spring 按字段名自动绑定），
 *    好处是导出和搜索的**筛选条件永远一致**、不会出现"列表能筛导出筛不了"。
 *    ⚠️ 代价：它同时接受 workOrderId / userId / keyword / isFlagged 等 #50 才有的参数
 *    （文档 §10.5 只列了 startDate / endDate / status / category 四个）。
 *    这是**有意的超集**：多接受几个可选筛选不影响文档定义的用法，
 *    但能避免"同一套条件要维护两个类"。另外 page / pageSize 传了也不生效 —— 导出不分页。
 */
@RestController
@RequestMapping("/api/admin/export")
public class AdminExportController {

    private final AdminExportService adminExportService;

    public AdminExportController(AdminExportService adminExportService) {
        this.adminExportService = adminExportService;
    }

    /**
     * GET /api/admin/export/work-orders —— 导出工单为 .xlsx。
     *
     * Query（都可选，不传 = 导出全部）：
     *   startDate  提交起始日期 yyyy-MM-dd（含当天）
     *   endDate    提交截止日期 yyyy-MM-dd（含当天 —— 截止日整天都算）
     *   status     状态筛选，可逗号分隔多个（PENDING,CLOSED）
     *   category   分类精确
     *   （以及 #50 支持的其它筛选维度，见类注释）
     *
     * 成功：200 + Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
     *      + 附件文件名 work-orders-yyyyMMdd-HHmmss.xlsx
     * 失败：200 + Result JSON（code 非 0，如 400 参数错 / 403 非管理员）
     */
    @GetMapping("/work-orders")
    public ResponseEntity<Object> exportWorkOrders(HttpServletRequest request,
                                                  WorkOrderSearchQuery query) {
        String userId = (String) request.getAttribute("userId");
        String userType = (String) request.getAttribute("userType");

        Result<AdminExportService.ExportFile> result =
                adminExportService.exportWorkOrders(userId, userType, query);

        if (result.getCode() != 0) {
            // 失败：按项目约定返回 Result（HTTP 200，真正的错在 body 的 code 里）
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
        }

        AdminExportService.ExportFile file = result.getData();
        // ContentDisposition.attachment() 会按 RFC 5987 处理文件名编码，避免中文/特殊字符乱码
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(AdminExportService.XLSX_CONTENT_TYPE))
                .body(file.content());
    }
}
