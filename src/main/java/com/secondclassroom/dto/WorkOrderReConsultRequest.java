package com.secondclassroom.dto;

import java.util.List;

/**
 * 再次咨询的请求体：POST /api/work-orders/{id}/re-consult
 *
 * 注意它**和提交工单长得很像**，因为语义上就是"再提一张新单"。区别只有一个：
 * 这里没有 version —— 因为新单是新记录，没有"旧版本"要防并发覆盖；
 * 原单只被读、不被改（它已经是 CLOSED 终态），所以不需要乐观锁。
 *
 * 对应 API 文档里的请求：
 * {
 *   "category": "学时录入",
 *   "content": "之前的工单已完结，但学时又出了新问题...",
 *   "requestId": "uuid-xxx",
 *   "attachmentIds": ["127"]
 * }
 */
public class WorkOrderReConsultRequest {

    private String category;              // 分类，和原单可以不同（问题变了分类也可能变）
    private String content;               // 新问题描述（≤500 字，与提交工单同一套校验）
    private String requestId;             // 前端生成的 UUID，建新单时写入 t_work_order.request_id
    private List<String> attachmentIds;   // 附件ID列表（绑在**新单**的工单级，不是原单）

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public List<String> getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<String> attachmentIds) { this.attachmentIds = attachmentIds; }
}
