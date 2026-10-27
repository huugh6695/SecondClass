package com.secondclassroom.dto;

import java.util.Map;

/**
 * 统计总览的返回结构（文档 §10.4 / 接口 #45）。
 *
 * 对应文档的响应：
 * {
 *   "totalTickets": 156,
 *   "duplicateRate": 0.12,
 *   "statusDistribution": { "PENDING": 5, "PROCESSING": 10, ... },
 *   "categoryDistribution": { "学时录入": 80, ... },
 *   "avgResponseMinutes": 180,
 *   "avgCloseMinutes": 960
 * }
 *
 * 两点刻意约定（前端做图时能省事）：
 *  · **两个分布图永远给出全部 5 个键**，某档为 0 也不缺席 ——
 *    否则前端画饼图时，某个状态当天没数据就会"少一块"，图例还得自己补。
 *  · **没有数据时平均值给 0**（不是 null）：JSON 结构稳定，前端不用写判空分支。
 */
public class StatisticsOverviewResult {

    private long totalTickets;                       // 工单总量
    private double duplicateRate;                    // 重复率 = 重复单 / 总量（保留 2 位小数）
    private Map<String, Long> statusDistribution;    // 各状态分布（英文枚举为键）
    private Map<String, Long> categoryDistribution;  // 各分类分布（中文分类为键）
    private long avgResponseMinutes;                 // 平均响应时长（分钟）= 提交 → 首次回复
    private long avgCloseMinutes;                    // 平均办结时长（分钟）= 提交 → 完结

    public long getTotalTickets() { return totalTickets; }
    public void setTotalTickets(long totalTickets) { this.totalTickets = totalTickets; }

    public double getDuplicateRate() { return duplicateRate; }
    public void setDuplicateRate(double duplicateRate) { this.duplicateRate = duplicateRate; }

    public Map<String, Long> getStatusDistribution() { return statusDistribution; }
    public void setStatusDistribution(Map<String, Long> statusDistribution) {
        this.statusDistribution = statusDistribution;
    }

    public Map<String, Long> getCategoryDistribution() { return categoryDistribution; }
    public void setCategoryDistribution(Map<String, Long> categoryDistribution) {
        this.categoryDistribution = categoryDistribution;
    }

    public long getAvgResponseMinutes() { return avgResponseMinutes; }
    public void setAvgResponseMinutes(long avgResponseMinutes) { this.avgResponseMinutes = avgResponseMinutes; }

    public long getAvgCloseMinutes() { return avgCloseMinutes; }
    public void setAvgCloseMinutes(long avgCloseMinutes) { this.avgCloseMinutes = avgCloseMinutes; }
}
