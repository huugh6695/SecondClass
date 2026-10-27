package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 工单详情里的状态流转历史（对应 t_status_log 一行）——
 * 前端画"时间线/进度条"用的数据，让用户看到"这单从待接单一路走到已关闭"的每一步。
 *
 * 对应 API 文档响应：
 *   { "action": "SUBMIT", "beforeStatus": null, "afterStatus": "PENDING",
 *     "operatorName": "张三", "operationTime": "2026-08-22 14:30:00" }
 *
 * 注意和"消息时间线"（messages）的区别，别混：
 *   · messages        —— 人说的话（QUESTION / REPLY / FOLLOW_UP）＋系统插的提示语；
 *   · statusHistory   —— **状态怎么变的**（谁把单从 PENDING 推到了 PROCESSING）。
 *   同一个动作（比如接单）两边都会各留一条，这是设计如此：一边给"聊天记录"看，一边给"进度条"用。
 *
 * action 的取值（t_status_log.action ENUM）：
 *   SUBMIT / ACCEPT / REPLY / FOLLOW_UP / CONFIRM / MANUAL_CLOSE / ADMIN_CLOSE / RETURN / MERGE
 *   ⚠️ 注意"再次咨询"虽然是新单，但它的状态日志 action 仍然写 SUBMIT
 *      （因为 action 记的是"状态怎么变的"，而 ENUM 里没有 RE_CONSULTATION 这个值）。
 */
public class StatusHistoryItem {

    private String action;          // 动作
    private String beforeStatus;    // 流转前状态；SUBMIT（工单诞生）时为 null
    private String afterStatus;     // 流转后状态
    private String operatorName;    // 操作人姓名快照
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operationTime;

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getBeforeStatus() { return beforeStatus; }
    public void setBeforeStatus(String beforeStatus) { this.beforeStatus = beforeStatus; }

    public String getAfterStatus() { return afterStatus; }
    public void setAfterStatus(String afterStatus) { this.afterStatus = afterStatus; }

    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }

    public LocalDateTime getOperationTime() { return operationTime; }
    public void setOperationTime(LocalDateTime operationTime) { this.operationTime = operationTime; }
}
