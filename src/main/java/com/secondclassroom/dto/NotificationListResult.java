package com.secondclassroom.dto;

import java.util.List;

/**
 * 通知列表的返回结构（对应 API 文档 GET /api/notifications 的 data）。
 * 结构刻意和工单列表 WorkOrderListResult 保持一致（total / page / pageSize / list），
 * 这样前端的分页组件可以复用同一套逻辑。
 */
public class NotificationListResult {

    private long total;
    private int page;
    private int pageSize;
    private List<NotificationListItem> list;

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    public List<NotificationListItem> getList() { return list; }
    public void setList(List<NotificationListItem> list) { this.list = list; }
}
