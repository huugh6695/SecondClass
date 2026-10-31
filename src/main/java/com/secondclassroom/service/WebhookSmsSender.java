package com.secondclassroom.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用短信网关适配器。生产环境由外部网关完成运营商发送。
 *
 * POST webhook-url
 * Authorization: Bearer <SMS_WEBHOOK_TOKEN>（可选）
 * {"phone":"...","code":"...","scene":"REGISTER","expiresInSeconds":300}
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "webhook")
public class WebhookSmsSender implements SmsSender {

    private final RestClient restClient = RestClient.create();
    private final String webhookUrl;
    private final String webhookToken;

    public WebhookSmsSender(@Value("${app.sms.webhook-url}") String webhookUrl,
                            @Value("${app.sms.webhook-token:}") String webhookToken) {
        this.webhookUrl = webhookUrl;
        this.webhookToken = webhookToken;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException(
                    "SMS_PROVIDER=webhook 时必须配置 SMS_WEBHOOK_URL");
        }
    }

    @Override
    public void sendCode(String phone, String code, String scene, int expiresInSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("code", code);
        body.put("scene", scene);
        body.put("expiresInSeconds", expiresInSeconds);

        RestClient.RequestBodySpec request = restClient.post().uri(webhookUrl);
        if (webhookToken != null && !webhookToken.isBlank()) {
            request.header("Authorization", "Bearer " + webhookToken);
        }
        request.body(body).retrieve().toBodilessEntity();
    }
}
