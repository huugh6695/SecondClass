package com.secondclassroom.dto;

/**
 * 文件上传接口（POST /api/upload）的返回体 —— 字段严格按设计文档 §6 的响应示例：
 * {@code attachmentId / fileUrl / fileName / fileSize}（不多返回别的）。
 *
 * 上传成功后附件在库里是 `status='TEMP'`（还没归属到任何工单/消息）。
 * 之后调提交工单 / 回复 / 追问 / 再次咨询时，把这个 attachmentId 放进请求体的
 * `attachmentIds` 数组里，它才会被绑成 `BOUND`。
 */
public class AttachmentUploadResult {

    private String attachmentId;
    private String fileUrl;
    private String fileName;
    private Long fileSize;

    public AttachmentUploadResult(String attachmentId, String fileUrl, String fileName, Long fileSize) {
        this.attachmentId = attachmentId;
        this.fileUrl = fileUrl;
        this.fileName = fileName;
        this.fileSize = fileSize;
    }

    public String getAttachmentId() { return attachmentId; }
    public String getFileUrl() { return fileUrl; }
    public String getFileName() { return fileName; }
    public Long getFileSize() { return fileSize; }
}
