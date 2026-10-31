package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.SmsCodeResult;
import com.secondclassroom.util.WebUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;

@Service
public class SmsCodeService {

    private static final Logger log = LoggerFactory.getLogger(SmsCodeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final SmsSender smsSender;
    private final PhoneCryptoService crypto;
    private final int ttlSeconds;
    private final int cooldownSeconds;
    private final int maxAttempts;
    private final int dailyLimit;
    private final int ipHourlyLimit;
    private final boolean exposeDevCode;

    public SmsCodeService(
            StringRedisTemplate redis,
            SmsSender smsSender,
            PhoneCryptoService crypto,
            @Value("${app.sms.code-ttl-seconds:300}") int ttlSeconds,
            @Value("${app.sms.resend-cooldown-seconds:60}") int cooldownSeconds,
            @Value("${app.sms.max-attempts:5}") int maxAttempts,
            @Value("${app.sms.daily-limit:10}") int dailyLimit,
            @Value("${app.sms.ip-hourly-limit:30}") int ipHourlyLimit,
            @Value("${app.sms.expose-dev-code:false}") boolean exposeDevCode) {
        this.redis = redis;
        this.smsSender = smsSender;
        this.crypto = crypto;
        this.ttlSeconds = ttlSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.maxAttempts = maxAttempts;
        this.dailyLimit = dailyLimit;
        this.ipHourlyLimit = ipHourlyLimit;
        this.exposeDevCode = exposeDevCode;
    }

    public Result<SmsCodeResult> sendCode(String scene, String subject, String phone) {
        String normalizedScene = scene.trim().toUpperCase(Locale.ROOT);
        String subjectHash = crypto.hmacHex(normalizedScene + "|" + subject);
        String phoneHash = crypto.hash(phone);
        String cooldownKey = "auth:sms:cooldown:" + subjectHash;
        String dailyKey = "auth:sms:daily:" + phoneHash;
        String ip = WebUtil.getClientIp();
        String ipKey = "auth:sms:ip:" + crypto.hmacHex("IP|" + (ip == null ? "unknown" : ip));

        Boolean allowed = redis.opsForValue()
                .setIfAbsent(cooldownKey, "1", Duration.ofSeconds(cooldownSeconds));
        if (!Boolean.TRUE.equals(allowed)) {
            return Result.fail(429, "验证码发送过于频繁，请稍后再试");
        }

        Long ipCount = redis.opsForValue().increment(ipKey);
        if (ipCount != null && ipCount == 1L) {
            redis.expire(ipKey, Duration.ofHours(1));
        }
        if (ipCount != null && ipCount > ipHourlyLimit) {
            redis.delete(cooldownKey);
            return Result.fail(429, "当前网络请求验证码次数过多，请稍后再试");
        }

        Long dailyCount = redis.opsForValue().increment(dailyKey);
        if (dailyCount != null && dailyCount == 1L) {
            redis.expire(dailyKey, Duration.ofHours(24));
        }
        if (dailyCount != null && dailyCount > dailyLimit) {
            return Result.fail(429, "该手机号今日验证码次数已达上限");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String codeKey = codeKey(normalizedScene, subject);
        String attemptsKey = attemptsKey(normalizedScene, subject);
        redis.opsForValue().set(codeKey, codeHash(normalizedScene, subject, code),
                Duration.ofSeconds(ttlSeconds));
        redis.delete(attemptsKey);

        try {
            smsSender.sendCode(phone, code, normalizedScene, ttlSeconds);
        } catch (RuntimeException e) {
            redis.delete(codeKey);
            redis.delete(cooldownKey);
            log.error("短信发送失败，scene={}", normalizedScene, e);
            return Result.fail(503, "短信发送失败，请稍后再试");
        }

        return Result.success("验证码已发送",
                new SmsCodeResult(cooldownSeconds, exposeDevCode ? code : null));
    }

    public Result<Void> verifyCode(String scene, String subject, String code) {
        if (code == null || !code.matches("^\\d{6}$")) {
            return Result.fail(400, "请输入 6 位短信验证码");
        }
        String normalizedScene = scene.trim().toUpperCase(Locale.ROOT);
        String codeKey = codeKey(normalizedScene, subject);
        String attemptsKey = attemptsKey(normalizedScene, subject);
        String expected = redis.opsForValue().get(codeKey);
        if (expected == null) {
            return Result.fail(400, "验证码已过期，请重新获取");
        }

        String actual = codeHash(normalizedScene, subject, code);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            Long attempts = redis.opsForValue().increment(attemptsKey);
            if (attempts != null && attempts == 1L) {
                redis.expire(attemptsKey, Duration.ofSeconds(ttlSeconds));
            }
            if (attempts != null && attempts >= maxAttempts) {
                redis.delete(codeKey);
                redis.delete(attemptsKey);
                return Result.fail(400, "验证码错误次数过多，请重新获取");
            }
            return Result.fail(400, "验证码错误");
        }

        redis.delete(codeKey);
        redis.delete(attemptsKey);
        return Result.success("验证码校验通过", null);
    }

    private String codeHash(String scene, String subject, String code) {
        return crypto.hmacHex(scene + "|" + subject + "|" + code);
    }

    private String codeKey(String scene, String subject) {
        return "auth:sms:code:" + crypto.hmacHex(scene + "|" + subject);
    }

    private String attemptsKey(String scene, String subject) {
        return "auth:sms:attempts:" + crypto.hmacHex(scene + "|" + subject);
    }
}
