package com.secondclassroom.dto;

import java.util.List;

/**
 * 回复工单的请求体（对应 POST /api/work-orders/{id}/reply）。
 * content     ：回复内容，必填，≤1000 字（和 t_work_order_message.content 约定一致）
 * requestId   ：前端 UUID，幂等兜底（表上 request_id 有 UNIQUE 约束）；允许不传
 * attachmentIds：附件ID列表，先调 POST /api/upload 拿到 ID 再传；绑定到这条回复消息上
 */
public class WorkOrderReplyRequest {

    private String content;
    private String requestId;
    private List<String> attachmentIds;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public List<String> getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<String> attachmentIds) { this.attachmentIds = attachmentIds; }
}
