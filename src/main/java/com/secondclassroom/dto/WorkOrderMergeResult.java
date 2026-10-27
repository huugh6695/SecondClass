package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 合并工单的返回结构。
 *
 * workOrderId 指的是**被合并的那个子工单**（URL 里的 {id}），合并后它变成 MERGED 终态；
 * mainOrderId 是它归属的主工单，前端拿这个字段做"跳转查看主工单"。
 */
public class WorkOrderMergeResult {

    private String workOrderId;      // 子工单，合并后恒为 MERGED
    private String status;           // 合并后恒为 MERGED
    private String statusLabel;      // 中文标签「已合并」
    private String mainOrderId;      // 归属的主工单
    private String mergeId;          // 合并关系记录号 MRG+yyyyMMdd+4位
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime mergeTime;
    private int version;             // 子工单合并后 version 已 +1

    public String getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(String workOrderId) { this.workOrderId = workOrderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }

    public String getMainOrderId() { return mainOrderId; }
    public void setMainOrderId(String mainOrderId) { this.mainOrderId = mainOrderId; }

    public String getMergeId() { return mergeId; }
    public void setMergeId(String mergeId) { this.mergeId = mergeId; }

    public LocalDateTime getMergeTime() { return mergeTime; }
    public void setMergeTime(LocalDateTime mergeTime) { this.mergeTime = mergeTime; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
