package com.secondclassroom.dto;

import java.util.List;

/**
 * 列表查询的返回 data：分页三件套 + 列表。
 * 对应 API 文档 GET /api/work-orders 的 data 部分：
 * { "total": 42, "page": 1, "pageSize": 20, "list": [ ... ] }
 */
public class WorkOrderListResult {
    private long total;                       // 符合条件的总条数（不分页）
    private int page;                         // 当前页码
    private int pageSize;                     // 每页条数
    private List<WorkOrderListItem> list;     // 本页的工单

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    public List<WorkOrderListItem> getList() { return list; }
    public void setList(List<WorkOrderListItem> list) { this.list = list; }
}
