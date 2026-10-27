package com.secondclassroom.dto;

import java.util.List;

/**
 * 通用的分页返回结构：{ "total": 42, "page": 1, "pageSize": 20, "list": [ ... ] }
 *
 * 为什么抽成泛型？因为分页的"外壳"（总数 / 页码 / 每页条数 / 本页数据）跟里面装什么完全无关。
 * 管理员日志查询有两个列表（状态日志、操作日志），加上后面的管理后台还有一堆分页接口，
 * 每来一个就照抄一遍三个字段 + 四个 getter 太啰嗦，而且容易漏一个字段。
 *
 * 用法：`Result<PageResult<StatusLogItem>>`。
 * （注意：工单列表用的还是它自己的 WorkOrderListResult，那是早期写的，字段一模一样，
 *   这里不动它 —— 改一个已经验证过的接口只为了"风格统一"，风险大于收益。）
 */
public class PageResult<T> {

    private long total;      // 符合条件的总条数（不分页）
    private int page;        // 当前页码，从 1 开始
    private int pageSize;    // 每页条数
    private List<T> list;    // 本页数据

    public PageResult() {}

    public PageResult(long total, int page, int pageSize, List<T> list) {
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
        this.list = list;
    }

    /** 便捷构造：省得每个调用点都 new + 四个 setter */
    public static <T> PageResult<T> of(long total, int page, int pageSize, List<T> list) {
        return new PageResult<>(total, page, pageSize, list);
    }

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    public List<T> getList() { return list; }
    public void setList(List<T> list) { this.list = list; }
}
