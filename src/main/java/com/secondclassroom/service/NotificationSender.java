package com.secondclassroom.service;

/**
 * 通知「推送」的抽象 —— 把"具体怎么把一条通知送出去"从"什么时候送、失败了怎么办"里拆出来。
 *
 * 为什么要有这层：
 *   t_notification_log.channel 有 PLATFORM / BROWSER / WECHAT 三种取值，
 *   三种渠道的发送方式完全不同（站内就是让前端拉到；微信要调模板消息接口）。
 *   如果全塞在派发服务里写 if-else，以后接一个渠道就要改一次派发逻辑。
 *   抽成接口后：新增渠道 = 新增一个 implements NotificationSender 的组件，
 *   派发服务一行都不用动（它按 {@link #channel()} 自动路由）。
 *
 * 实现类在 {@code service/PlatformNotificationSender}，目前只有 PLATFORM 一种。
 */
public interface NotificationSender {

    /** 本发送器负责的渠道，取值与 t_notification_log.channel 的枚举值一致。 */
    String channel();

    /** 真正执行一次推送。 */
    SendResult send(Task task);

    /**
     * 一条待推送的通知 —— 只带推送需要的字段，不把整行（is_read/create_time 等）都捞出来。
     *
     * @param notifyId    通知主键
     * @param userId      收件人
     * @param workOrderId 关联工单（可能为 null，比如以后 FAQ / 公告类通知）
     * @param type        通知类型（NEW_TICKET / REPLY / ...）
     * @param title       标题
     * @param content     正文
     * @param targetId    点击后跳转的目标 id（注意 MERGE 时它是**主工单号**）
     * @param retryCount  已经失败过几次（0 表示首次尝试）
     */
    record Task(String notifyId, String userId, String workOrderId,
                String type, String title, String content, String targetId, int retryCount) {
    }

    /**
     * 推送结果。
     * 失败必须带上原因，它会被截断后写进 t_notification_log.fail_reason，方便事后排查。
     */
    record SendResult(boolean success, String failReason) {

        public static SendResult ok() {
            return new SendResult(true, null);
        }

        public static SendResult fail(String failReason) {
            return new SendResult(false, failReason);
        }
    }
}
