package com.secondclassroom.dto;

/**
 * 展示用的附件项 —— 工单详情 / 消息列表里带出来的附件。
 *
 * 对应 API 文档响应里的附件元素：
 *   { "attachmentId": 1, "fileUrl": "https://oss.../img1.jpg", "fileName": "screenshot.jpg", "sortNum": 0 }
 *
 * 为什么只有这 4 个字段，不复用上传接口的 AttachmentUploadResult？
 *   因为"展示"和"上传回调"要的东西不同：上传时要告诉前端文件多大（fileSize），
 *   展示时前端只关心"点哪个链接、显示什么名字、排第几个"。
 *   file_url 里已经带着 attachmentId，前端要下载直接点链接就行。
 *
 * ⚠️ 文档自己不太一致：工单详情的附件元素带 sortNum，消息列表的却没写。
 *    这里统一都带 sortNum（前端多一个字段不会坏），省得维护两套结构。
 */
public class AttachmentItem {

    private String attachmentId;
    private String fileUrl;      // 站点内下载地址：/api/files/{attachmentId}
    private String fileName;     // 原始文件名（已清洗过路径）
    private int sortNum;         // 展示顺序，0 起；绑定时的先后顺序

    public AttachmentItem() {}

    public AttachmentItem(String attachmentId, String fileUrl, String fileName, int sortNum) {
        this.attachmentId = attachmentId;
        this.fileUrl = fileUrl;
        this.fileName = fileName;
        this.sortNum = sortNum;
    }

    public String getAttachmentId() { return attachmentId; }
    public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }

    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public int getSortNum() { return sortNum; }
    public void setSortNum(int sortNum) { this.sortNum = sortNum; }
}
