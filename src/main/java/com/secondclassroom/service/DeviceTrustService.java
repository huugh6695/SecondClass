package com.secondclassroom.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
public class DeviceTrustService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final PhoneCryptoService crypto;
    private final Duration trustDuration;

    public DeviceTrustService(
            StringRedisTemplate redis,
            PhoneCryptoService crypto,
            @Value("${app.sms.device-trust-days:180}") long trustDays) {
        this.redis = redis;
        this.crypto = crypto;
        this.trustDuration = Duration.ofDays(trustDays);
    }

    public String issueToken(String userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String key = devicesKey(userId);
        redis.opsForHash().put(key, crypto.hmacHex(token), "1");
        redis.expire(key, trustDuration);
        return token;
    }

    public boolean isTrusted(String userId, String token) {
        if (token == null || token.isBlank()) return false;
        return Boolean.TRUE.equals(
                redis.opsForHash().hasKey(devicesKey(userId), crypto.hmacHex(token)));
    }

    public void revokeAll(String userId) {
        redis.delete(devicesKey(userId));
    }

    private String devicesKey(String userId) {
        return "auth:devices:" + userId;
    }
}
