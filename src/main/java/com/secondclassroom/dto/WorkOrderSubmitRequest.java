package com.secondclassroom.dto;

import java.util.List;

/**
 * 提交工单请求体：学生 POST /api/work-orders 时传来的 JSON。
 *
 * 对应 API 文档里的请求：
 * {
 *   "category": "学时录入",
 *   "content": "我的XX活动...",
 *   "requestId": "uuid-xxx-xxx",
 *   "attachmentIds": ["123", "124"]
 * }
 *
 * 注意：前端【只能】传这 4 个字段；
 * 工单号 / 状态 / 姓名快照等其余字段都由后端生成（和当时 LoginRequest 一个套路）。
 */
public class WorkOrderSubmitRequest {
    private String category;                  // 分类：学时录入 / 审核驳回 / 活动咨询 / 规则疑问 / 其他
    private String content;                   // 首次问题描述（≤500字）
    private String requestId;                 // 前端生成的 UUID，用于幂等兜底
    private List<String> attachmentIds;       // 附件ID列表（先调 /api/upload 拿到 ID，这里只传 ID）

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public List<String> getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<String> attachmentIds) { this.attachmentIds = attachmentIds; }
}
