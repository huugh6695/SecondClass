package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.OperationLogItem;
import com.secondclassroom.dto.PageResult;
import com.secondclassroom.dto.StatusLogItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理员日志查询（文档 §10.6 / 接口 #48、#49）：
 * - GET /api/admin/logs/status-logs    —— 状态流转日志（t_status_log）
 * - GET /api/admin/logs/operation-logs —— 操作审计日志（t_operation_log）
 *
 * 两个接口都只有管理员能调（Service 里判角色）。
 *
 * 为什么这两个接口值得单独做？因为前面所有写操作都老老实实往这两张表里记了一笔，
 * 但一直没有一个"读"的出口 —— 数据全在库里躺着，管理员却看不到。
 * 有了它，答辩时就能现场演示"每一次状态变更、每一次登录改动都查得到"。
 *
 * 两张表的分工（容易混，先记住）：
 *   · t_status_log    —— **工单的状态**怎么变的（业务时间线）→ 前端画进度条用它；
 *   · t_operation_log —— **有谁**动过系统（查账追责）→ 登录、改密码、管 FAQ 都记在这。
 *   同一个动作常常两边都有记录，但"记录的目的"不一样，这不是冗余。
 */
@Service
public class AdminLogService {

    private final JdbcTemplate jdbcTemplate;

    public AdminLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ============ 状态日志（GET /api/admin/logs/status-logs） ============

    /**
     * 状态流转日志列表。
     *
     * @param workOrderId 按工单筛选，可为空（不传就是全部工单）
     * @param page        页码，从 1 开始；默认 1
     * @param pageSize    每页条数；默认 20，上限 50
     */
    public Result<PageResult<StatusLogItem>> listStatusLogs(String userType, String workOrderId,
                                                           Integer page, Integer pageSize) {
        // 1. 角色校验：日志是"查账"用的，只给管理员
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可查看日志");
        }

        // 2. 分页兜底（和工单列表同一套约定：page 默认 1、pageSize 默认 20 上限 50）
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 50);

        // 3. 拼条件。用 WHERE 1=1 起头，后面就能无脑 append " AND ..."
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (workOrderId != null && !workOrderId.isBlank()) {
            where.append(" AND work_order_id = ?");
            params.add(workOrderId);
        }

        // 4. 总数
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_status_log" + where, Long.class, params.toArray());

        // 5. 分页查明细。ORDER BY operation_time DESC = 最新的排最前（管理员最关心最近发生的）
        //    末尾补 log_id 只是为了让"同一秒发生的事件"顺序稳定，不然翻页时可能出现重复/漏掉。
        int offset = (p - 1) * ps;
        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(offset);
        queryParams.add(ps);
        List<StatusLogItem> list = jdbcTemplate.query(
                "SELECT log_id, work_order_id, before_status, after_status, action, operator_id, "
                        + "operator_name, operator_role, remark, operation_time "
                        + "FROM t_status_log" + where + " ORDER BY operation_time DESC, log_id DESC LIMIT ?, ?",
                StatusLogRowMapper.INSTANCE, queryParams.toArray());

        return Result.success("success", PageResult.of(total == null ? 0 : total, p, ps, list));
    }

    // ============ 操作日志（GET /api/admin/logs/operation-logs） ============

    /**
     * 操作审计日志列表。
     *
     * @param operatorId    按操作人筛选（传的是 user_id）
     * @param operationType 按操作类型筛选（如 LOGIN / SUBMIT / CREATE_FAQ）。**故意不校验取值**：
     *                      这列是 varchar，类型会随功能增长，硬编码白名单只会变成维护负担；
     *                      传了库里没有的类型，结果就是空列表，管理员自己看得见。
     * @param startDate     起始日期 "yyyy-MM-dd"（含当天）
     * @param endDate       截止日期 "yyyy-MM-dd"（含当天）
     */
    public Result<PageResult<OperationLogItem>> listOperationLogs(String userType, String operatorId,
                                                                 String operationType,
                                                                 String startDate, String endDate,
                                                                 Integer page, Integer pageSize) {
        // 1. 角色校验
        if (!"ADMIN".equals(userType)) {
            return Result.fail(403, "仅管理员可查看日志");
        }

        // 2. 分页兜底
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 50);

        // 3. 日期校验。这里不把参数声明成 LocalDate 交给 Spring 转换 ——
        //    转换失败抛的是 MethodArgumentTypeMismatchException，全局异常处理器没覆盖它，
        //    会变成 500；手工解析才能给出"日期格式不对"这种能看懂的 400。
        LocalDate start = null;
        LocalDate end = null;
        try {
            if (startDate != null && !startDate.isBlank()) {
                start = LocalDate.parse(startDate);
            }
            if (endDate != null && !endDate.isBlank()) {
                end = LocalDate.parse(endDate);
            }
        } catch (DateTimeParseException e) {
            return Result.fail(400, "日期格式不正确，应为 yyyy-MM-dd");
        }
        if (start != null && end != null && start.isAfter(end)) {
            return Result.fail(400, "起始日期不能晚于截止日期");
        }

        // 4. 拼条件
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (operatorId != null && !operatorId.isBlank()) {
            where.append(" AND user_id = ?");
            params.add(operatorId);
        }
        if (operationType != null && !operationType.isBlank()) {
            where.append(" AND operation_type = ?");
            params.add(operationType);
        }
        if (start != null) {
            // operation_time 是 datetime，直接和日期比会被当成"当天 00:00:00"，
            // 所以起始日用 >= 当天 00:00，截止日用 < 次日 00:00（这样 endDate 那一整天都算在内）
            where.append(" AND operation_time >= ?");
            params.add(start.atStartOfDay());
        }
        if (end != null) {
            where.append(" AND operation_time < ?");
            params.add(end.plusDays(1).atStartOfDay());
        }

        // 5. 总数
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_operation_log" + where, Long.class, params.toArray());

        // 6. 分页查明细（最新在前）
        int offset = (p - 1) * ps;
        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(offset);
        queryParams.add(ps);
        List<OperationLogItem> list = jdbcTemplate.query(
                "SELECT log_id, user_id, user_name, user_role, operation_type, target_type, "
                        + "target_id, operation_content, ip_address, operation_time "
                        + "FROM t_operation_log" + where
                        + " ORDER BY operation_time DESC, log_id DESC LIMIT ?, ?",
                OperationLogRowMapper.INSTANCE, queryParams.toArray());

        return Result.success("success", PageResult.of(total == null ? 0 : total, p, ps, list));
    }

    // ============ 行映射 ============

    /** t_status_log 一行 → StatusLogItem */
    private static class StatusLogRowMapper implements RowMapper<StatusLogItem> {
        static final StatusLogRowMapper INSTANCE = new StatusLogRowMapper();

        @Override
        public StatusLogItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            StatusLogItem item = new StatusLogItem();
            item.setLogId(rs.getString("log_id"));
            item.setWorkOrderId(rs.getString("work_order_id"));
            item.setBeforeStatus(rs.getString("before_status"));
            item.setAfterStatus(rs.getString("after_status"));
            item.setAction(rs.getString("action"));
            item.setOperatorId(rs.getString("operator_id"));
            item.setOperatorName(rs.getString("operator_name"));
            item.setOperatorRole(rs.getString("operator_role"));
            item.setRemark(rs.getString("remark"));
            item.setOperationTime(rs.getObject("operation_time", LocalDateTime.class));
            return item;
        }
    }

    /** t_operation_log 一行 → OperationLogItem */
    private static class OperationLogRowMapper implements RowMapper<OperationLogItem> {
        static final OperationLogRowMapper INSTANCE = new OperationLogRowMapper();

        @Override
        public OperationLogItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            OperationLogItem item = new OperationLogItem();
            item.setLogId(rs.getString("log_id"));
            item.setUserId(rs.getString("user_id"));
            item.setUserName(rs.getString("user_name"));
            item.setUserRole(rs.getString("user_role"));
            item.setOperationType(rs.getString("operation_type"));
            item.setTargetType(rs.getString("target_type"));
            item.setTargetId(rs.getString("target_id"));
            item.setOperationContent(rs.getString("operation_content"));
            item.setIpAddress(rs.getString("ip_address"));
            item.setOperationTime(rs.getObject("operation_time", LocalDateTime.class));
            return item;
        }
    }
}
