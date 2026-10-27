package com.secondclassroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @EnableScheduling 打开 Spring 的定时任务能力。
 * 项目里目前只有一处用到：NotificationDispatchService 每 10 秒扫一轮
 * t_notification_log 里 PENDING 的通知去推送（异步通知服务）。
 * 不加这个注解，@Scheduled 方法只是普通方法、永远不会被调用。
 */
@SpringBootApplication
@EnableScheduling
public class SecondClassroomApplication {

	public static void main(String[] args) {
		SpringApplication.run(SecondClassroomApplication.class, args);
	}

}
