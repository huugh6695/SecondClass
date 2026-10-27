package com.secondclassroom.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 站内通知（channel = PLATFORM）的发送器。
 *
 * 「站内推送」在这个项目里没有外部通道 —— 通知本身就是直接写在 t_notification_log 里、
 * 由前端每 15 秒轮询一次通知列表拉走的。所以这里的"发送"动作就是打一条日志，
 * 表示这条通知已经可以被前端拉到了。
 *
 * 那为什么还要单独一个类、不直接把 log 写在派发服务里？
 * 见 {@link NotificationSender} 的说明：给 BROWSER / WECHAT 留口子。
 *
 * ⚠️ BROWSER / WECHAT **故意不实现**。schema 里保留了这两个枚举值，但课程文档没有要求实现。
 *    没注册的渠道在派发时会被判成「渠道未实现」→ 走失败重试链路。
 *    这比"假装成功"诚实：数据没真发出去，状态就该是失败的。
 *    （顺带说一句，这也刚好给了我们一个不用改生产代码就能验证重试逻辑的抓手。）
 */
@Component
public class PlatformNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(PlatformNotificationSender.class);

    @Override
    public String channel() {
        return "PLATFORM";
    }

    @Override
    public SendResult send(Task task) {
        // 站内通知的"发送"= 让这条记录可被前端轮到，所以这里只记日志、恒成功。
        // 真要失败（比如以后接了邮件/微信、对方接口超时），就在这里 return SendResult.fail("...")。
        log.info("[站内推送] notifyId={} 收件人={} 类型={} 标题={}",
                task.notifyId(), task.userId(), task.type(), task.title());
        return SendResult.ok();
    }
}
