package com.secondclassroom.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "log", matchIfMissing = true)
public class LogSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LogSmsSender.class);

    @Override
    public void sendCode(String phone, String code, String scene, int expiresInSeconds) {
        String masked = phone.length() == 11
                ? phone.substring(0, 3) + "****" + phone.substring(7)
                : "手机号";
        log.info("[开发短信] {} 场景={} 验证码={} 有效期={}秒",
                masked, scene, code, expiresInSeconds);
    }
}
