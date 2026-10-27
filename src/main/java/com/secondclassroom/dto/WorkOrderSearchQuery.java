package com.secondclassroom.dto;

/**
 * 后台工单搜索的查询参数（文档 §10.7 / 接口 #50）。
 *
 * 为什么要一个小对象，而不是在 Controller 上排 13 个 @RequestParam？
 * 因为参数一多，**位置**就成了最容易出错的地方 —— 相邻两个都是 String，
 * 传反了编译器一点都不会提醒你（分页那次 LIMIT 参数写反就是这么栽的）。
 * 装进对象里按**名字**传，读起来也清楚。
 *
 * Spring MVC 对 GET 请求里的这种普通对象参数，会自动按**字段名**去 Query 里取值绑定，
 * 所以 `?userId=2023001001&status=CLOSED` 会直接填进来，不需要写 @RequestParam。
 *
 * ⚠️ 日期和布尔刻意声明成 **String**，不是 LocalDate / Boolean：
 *    让 Spring 转换的话，`?startDate=2026-13-99` 或 `?isFlagged=abc` 抛的是
 *    MethodArgumentTypeMismatchException，全局异常处理器没覆盖 → **500**，
 *    前端只能看到"服务器错误"。收成 String 自己解析，才能给出"格式不对"这种能看懂的 **400**。
 *    （同理：分页参数 page / pageSize 全项目都是 Integer，保持原样，与本项目其他管理端接口一致。）
 */
public class WorkOrderSearchQuery {

    private String workOrderId;   // 工单号，精确
    private String userId;        // 提交人学号，精确
    private String userName;      // 提交人姓名，模糊
    private String handlerId;     // 负责人工号，精确
    private String handlerName;   // 负责人姓名，模糊
    private String status;        // 状态，可逗号分隔多个，如 PENDING,CLOSED
    private String category;      // 分类，精确
    private String keyword;       // 内容关键词，模糊匹配 content
    private String startDate;     // 提交起始日期 yyyy-MM-dd（含当天）
    private String endDate;       // 提交截止日期 yyyy-MM-dd（含当天）
    private String isFlagged;     // true/false：是否命中敏感词
    private String isDuplicate;   // true/false：是否为被合并的重复单
    private Integer page;         // 页码，默认 1
    private Integer pageSize;     // 每页条数，1-50，默认 20

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getHandlerId() { return handlerId; }
    public void setHandlerId(String handlerId) { this.handlerId = handlerId; }

    public String getHandlerName() { return handlerName; }
    public void setHandlerName(String handlerName) { this.handlerName = handlerName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }

    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }

    public String getIsFlagged() { return isFlagged; }
    public void setIsFlagged(String isFlagged) { this.isFlagged = isFlagged; }

    public String getIsDuplicate() { return isDuplicate; }
    public void setIsDuplicate(String isDuplicate) { this.isDuplicate = isDuplicate; }

    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }

    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
}
