package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 提交工单的返回数据：对应 API 文档里 /api/work-orders 响应的 data 部分。
 * {
 *   "workOrderId": "WO202609100001",
 *   "status": "PENDING",
 *   "isDuplicate": false,
 *   "duplicateOfOrderId": null
 * }
 *
 * 说明：`isDuplicate` / `duplicateOfOrderId` 由 {@code DuplicateDetectService}
 * 的检测结果决定（jieba 分词 + 关键词交集 ≥ 2），**只提示、不拦截** ——
 * 命中也会正常建单，只是给个"疑似重复了某张单"的提示。
 * （这两个字段曾经是写死的 false / null，2026-10-14 接上真实检测。）
 */
public class WorkOrderSubmitResult {
    private String workOrderId;          // 后端生成的工单号，如 WO202609100001
    private String status;               // 固定 PENDING
    @JsonProperty("isDuplicate")
    private boolean isDuplicate;         // 是否命中重复工单（检测结果）
    private String duplicateOfOrderId;   // 命中的原工单ID（没命中为 null）

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isDuplicate() { return isDuplicate; }
    public void setDuplicate(boolean duplicate) { isDuplicate = duplicate; }

    public String getDuplicateOfOrderId() { return duplicateOfOrderId; }
    public void setDuplicateOfOrderId(String duplicateOfOrderId) { this.duplicateOfOrderId = duplicateOfOrderId; }
}
