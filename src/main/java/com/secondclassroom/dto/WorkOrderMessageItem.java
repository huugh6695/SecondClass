package com.secondclassroom.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单详情 / 消息列表里，消息时间线中的单条消息（对应 t_work_order_message 一行）。
 *
 * attachments 是**这条消息**自带的附件（回复/追问时传的 attachmentIds，target_type='MESSAGE'）。
 * 系统消息（sender_type='SYSTEM'）永远是空列表 —— 系统不会带附件。
 */
public class WorkOrderMessageItem {

    private String messageId;
    private String senderId;        // 发送人ID；系统消息为 null
    private String senderName;      // 发送人姓名；系统消息为 "系统"
    private String senderType;      // STUDENT / STAFF / SYSTEM
    private String msgType;         // QUESTION / REPLY / FOLLOW_UP / SYSTEM
    private String content;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    private List<AttachmentItem> attachments;  // 这条消息的附件

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getSenderType() { return senderType; }
    public void setSenderType(String senderType) { this.senderType = senderType; }

    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public List<AttachmentItem> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentItem> attachments) { this.attachments = attachments; }
}
