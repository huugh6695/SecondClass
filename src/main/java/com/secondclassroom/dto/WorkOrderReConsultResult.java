package com.secondclassroom.dto;

/**
 * 再次咨询的返回数据：对应 API 文档里 /api/work-orders/{id}/re-consult 响应的 data 部分。
 * {
 *   "workOrderId": "WO202608220002",   // 新建出来的那张单
 *   "sourceOrderId": "WO202608220001"  // 它是由哪张已完结的单引出来的
 * }
 *
 * 前端拿到 sourceOrderId 就能在详情页显示"本单由 XXX 再次咨询而来"，
 * 并从新单跳回去看老单的完整处理过程。
 */
public class WorkOrderReConsultResult {

    private String workOrderId;
    private String sourceOrderId;

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getSourceOrderId() { return sourceOrderId; }
    public void setSourceOrderId(String sourceOrderId) { this.sourceOrderId = sourceOrderId; }
}
