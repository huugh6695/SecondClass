package com.secondclassroom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 冒烟测试：只验证 Spring 上下文能装配起来。
 *
 * jwt.secret 用 properties 里这个**仅存在于测试**的假值，不读本地配置，
 * 免得「有没有 config/application-local.yml」影响测试结果。
 * 数据库密码仍走本地配置（Hikari 懒连接，contextLoads 不会真的查库）。
 */
@SpringBootTest(properties = {
		"jwt.secret=test-only-secret-0123456789abcdef0123456789abcdef",
		"app.notification.enabled=false"
})
class SecondClassroomApplicationTests {

	@Test
	void contextLoads() {
	}

}
