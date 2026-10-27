package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.WorkOrderSearchItem;
import com.secondclassroom.dto.WorkOrderSearchQuery;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 管理端数据导出（文档 §10.5 / 接口 #47）：GET /api/admin/export/work-orders，仅管理员。
 *
 * 文档要求的四步，这里逐条对上：
 *   ① 查询工单数据（**同列表查询，不分页**）  → 复用 AdminWorkOrderService.listForExport
 *   ② 生成 Excel（xlsx 格式）                → buildXlsx（Apache POI 的 XSSFWorkbook）
 *   ③ 设置响应头 Content-Type                → 在 Controller 里设
 *   ④ INSERT t_operation_log(EXPORT)          → 本类 recordExport
 *   ⑤ 返回文件流                              → 在 Controller 里返回 byte[]
 *
 * ──────────────────────────────────────────────────────────────
 * 几个刻意的设计决定：
 *
 * 1) **导出走的是和 #50 完全同一套筛选条件**（共用 AdminWorkOrderService.buildFilter）。
 *    "列表能筛、导出筛不出来"是这类系统最典型的坑：两处各写一份 SQL，改了 A 忘了 B，
 *    用户拿到的是一份"看着像筛过、其实没筛"的表格 —— 而且**不会报错**，只会安静地给错数据。
 *
 * 2) **审计写在文件真的生成成功之后**。按项目铁律"只在操作真正生效后记"：
 *    生成失败就不该留一条 EXPORT 记录，否则管理员查账会看到"导出了"，但根本没导出。
 *
 * 3) ⚠️ 生成失败时抛的是 **RuntimeException**，不是 IllegalStateException。
 *    因为全局异常处理器把 IllegalStateException 翻译成「工单状态已被他人变更，请刷新后重试」
 *    （409）—— 那是给乐观锁冲突用的。生成 Excel 失败如果也走那条路，
 *    用户会收到一句完全无关的提示，排查方向直接被带偏。
 *
 * 4) **不设导出条数上限**（文档没规定）。全量塞进内存的 XSSFWorkbook 在几万行时会吃大量堆内存，
 *    真到那天要换 SXSSFWorkbook（流式写）+ 分片查询。现在库是几百条量级，先不引入这个复杂度。
 * ──────────────────────────────────────────────────────────────
 */
@Service
public class AdminExportService {

    /** xlsx 的 MIME 类型（文档 §10.5 明确给出） */
    public static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String SHEET_NAME = "工单列表";

    /**
     * 表头。顺序 = 列顺序，**和 WorkOrderSearchItem 的 15 个字段一一对应**
     * （这样"导出的列"和"#50 返回的字段"永远对得上，不用记两套）。
     *
     * 状态给了两列（中文标签 + 英文代码）：标签给人看，代码给机器/审计用。
     * 看着冗余，但导出表经常被拿去做二次统计，只留中文会导致还得反查一次映射。
     */
    private static final String[] HEADERS = {
            "工单号", "提交人学号", "提交人姓名", "分类", "内容", "状态", "状态代码",
            "负责人工号", "负责人姓名", "是否命中敏感词", "重复单指向",
            "提交时间", "受理时间", "首次回复时间", "完结时间"
    };

    /**
     * 列宽（单位是 1/256 个字符宽）。
     * ⚠️ 不用 `autoSizeColumn`：它按字符数估算，中文会被算窄；而且每列都要扫一遍数据，慢。
     *    写死宽度反而更可控 —— 内容是长文本，给宽一点并开启自动换行。
     */
    private static final int[] COLUMN_WIDTHS = {
            18, 14, 12, 12, 48, 10, 12, 14, 12, 16, 18, 20, 20, 20, 20
    };

    /** 内容列的下标（要单独设"自动换行"样式） */
    private static final int COL_CONTENT = 4;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AdminWorkOrderService adminWorkOrderService;
    private final OperationLogService operationLogService;
    private final JdbcTemplate jdbcTemplate;

    public AdminExportService(AdminWorkOrderService adminWorkOrderService,
                              OperationLogService operationLogService,
                              JdbcTemplate jdbcTemplate) {
        this.adminWorkOrderService = adminWorkOrderService;
        this.operationLogService = operationLogService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 生成好的文件：文件名（进 Content-Disposition）+ 内容字节 */
    public record ExportFile(String fileName, byte[] content) {
    }

    /**
     * 导出工单为 .xlsx。
     *
     * @param operatorId   操作人（从 Token 来）
     * @param operatorRole 操作人角色，必须是 ADMIN
     * @param q            筛选条件（复用 #50 的查询对象）
     */
    public Result<ExportFile> exportWorkOrders(String operatorId, String operatorRole, WorkOrderSearchQuery q) {
        // 1. 角色校验
        if (!"ADMIN".equals(operatorRole)) {
            return Result.fail(403, "仅管理员可导出工单");
        }
        if (q == null) {
            q = new WorkOrderSearchQuery();
        }
        // 2. 先查操作人姓名（审计字段要求 NOT NULL）。
        //    放在查数据之前：这个检查几乎不会失败，尽早失败比"查完几百条再告诉他查不了"好。
        String operatorName = loadUserName(operatorId);
        if (operatorName == null) {
            return Result.fail(404, "操作人不存在");
        }

        // 3. 查数据（同列表查询、不分页；条件不合法时这里会返回 400 类错误，直接透传）
        Result<List<WorkOrderSearchItem>> queryResult =
                adminWorkOrderService.listForExport(operatorRole, q);
        if (queryResult.getCode() != 0) {
            return Result.fail(queryResult.getCode(), queryResult.getMessage());
        }
        List<WorkOrderSearchItem> rows = queryResult.getData();

        // 4. 生成 Excel
        byte[] bytes = buildXlsx(rows);

        // 5. 审计（文档 §10.5 第 4 步：operation_type='EXPORT'）
        //    target_id 用哨兵 "BATCH"：这是**批量**导出，没有单一业务主键可放
        //    （审计表的 target_id 是 varchar(64) NOT NULL，只能放主键或哨兵，不能塞描述文字）。
        //    具体的筛选条件写进 operation_content，人读得懂，也方便之后按条件追查。
        operationLogService.record(operatorId, operatorName, operatorRole,
                "EXPORT", "WORK_ORDER", "BATCH",
                buildAuditContent(q, rows.size()));

        // 6. 文件名：work-orders-20260927-215530.xlsx
        String fileName = "work-orders-" + LocalDateTime.now().format(FILE_FMT) + ".xlsx";
        return Result.success("success", new ExportFile(fileName, bytes));
    }

    // ============ Excel 生成 ============

    /**
     * 把工单列表写成 xlsx 字节流。
     *
     * 数据量小，直接用 XSSFWorkbook（全内存）。生成完立刻 close —— POI 的 Workbook
     * 持有临时资源，不关会泄漏。
     */
    private byte[] buildXlsx(List<WorkOrderSearchItem> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet(SHEET_NAME);

            // ---- 表头样式：加粗 + 灰底 + 居中 ----
            CellStyle headStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            headStyle.setFont(boldFont);
            headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headStyle.setAlignment(HorizontalAlignment.CENTER);
            headStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headStyle.setBorderBottom(BorderStyle.THIN);

            Row headRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = headRow.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headStyle);
            }
            // 冻结首行：几百行往下滚时，表头始终看得见
            sheet.createFreezePane(0, 1);

            // ---- 内容列专用的"自动换行 + 顶端对齐"样式 ----
            CellStyle wrapStyle = wb.createCellStyle();
            wrapStyle.setWrapText(true);
            wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);

            // ---- 数据行 ----
            int rowIdx = 1;
            for (WorkOrderSearchItem it : rows) {
                Row row = sheet.createRow(rowIdx++);
                int col = 0;
                put(row, col++, it.getWorkOrderId(), null);
                put(row, col++, it.getUserId(), null);
                put(row, col++, it.getUserName(), null);
                put(row, col++, it.getCategory(), null);
                put(row, col++, it.getContent(), wrapStyle);
                put(row, col++, it.getStatusLabel(), null);
                put(row, col++, it.getStatus(), null);
                put(row, col++, it.getHandlerId(), null);
                put(row, col++, it.getHandlerName(), null);
                put(row, col++, Boolean.TRUE.equals(it.getIsFlagged()) ? "是" : "否", null);
                put(row, col++, it.getDuplicateOfOrderId(), null);
                put(row, col, formatTime(it.getCreateTime()), null);
                put(row, col + 1, formatTime(it.getAcceptTime()), null);
                put(row, col + 2, formatTime(it.getReplyTime()), null);
                put(row, col + 3, formatTime(it.getFinishTime()), null);
            }

            for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
                sheet.setColumnWidth(i, COLUMN_WIDTHS[i] * 256);
            }

            wb.write(out);
            return out.toByteArray();

        } catch (IOException e) {
            // ⚠️ 用 RuntimeException 而**不是** IllegalStateException（见类注释第 3 条）
            throw new RuntimeException("生成 Excel 失败", e);
        }
    }

    /**
     * 写一个单元格。
     *
     * null 一律写成**空单元格**（""），不是字符串 "null" ——
     * 导出的表是要给人看的，出现 "null" 会被当成真实数据。
     */
    private static void put(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        if (style != null) {
            c.setCellStyle(style);
        }
    }

    /** LocalDateTime → "yyyy-MM-dd HH:mm:ss"；为 null（这一步还没走到）时给空串 */
    private static String formatTime(LocalDateTime t) {
        return t == null ? "" : t.format(TIME_FMT);
    }

    /** 把实际生效的筛选条件拼成审计描述，形如 `work_orders: startDate=.. endDate=.. status=.. category=.. rows=12` */
    private static String buildAuditContent(WorkOrderSearchQuery q, int rowCount) {
        return "work_orders: startDate=" + orNull(q.getStartDate())
                + " endDate=" + orNull(q.getEndDate())
                + " status=" + orNull(q.getStatus())
                + " category=" + orNull(q.getCategory())
                + " rows=" + rowCount;
    }

    private static String orNull(String s) {
        return (s == null || s.isBlank()) ? "(未指定)" : s.trim();
    }

    /** 查操作人姓名（审计要求 NOT NULL，所以查不到就直接失败） */
    private String loadUserName(String userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT user_name FROM t_user WHERE user_id = ?", String.class, userId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }
}
