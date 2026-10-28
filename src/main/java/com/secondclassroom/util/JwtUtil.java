package com.secondclassroom.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具类：生成 / 解析 / 校验 Token。
 * 被登录接口和拦截器共用，是整个认证模块的“单一事实来源”。
 */
@Component
public class JwtUtil {

    /**
     * 由配置文件 jwt.secret 生成的密钥（构造时一次性生成）。
     * ⚠️ 签名算法不是写死的，而是由密钥长度决定：我们的 secret 是 64 字节 ⇒ HS512。
     *    （之前注释写 HS256 是错的，容易被 Token 头里的 alg 打脸。）
     */
    private final SecretKey key;
    /** Token 有效期（秒），来自配置文件 jwt.expiration */
    private final long expirationSeconds;

    /**
     * 构造器注入配置项。
     * 因为只有一个构造器，Spring 会自动用它来装配 @Value 的值。
     */
    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration}") long expirationSeconds) {
        // 启动即校验，把「配置缺失」变成一个能直接照着修的报错，
        // 而不是 jjwt 里那句难懂的 WeakKeyException。
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret 未配置：本地请复制 config/application-local.yml.example 为 "
                            + "config/application-local.yml 并填入密钥；生产请用环境变量 JWT_SECRET 注入。");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // HS256/HS512 都要求密钥至少 256 位（32 字节）
            throw new IllegalStateException(
                    "jwt.secret 太短：至少需要 32 字节，当前只有 " + keyBytes.length
                            + " 字节。可用 `openssl rand -base64 64` 生成一个。");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * 生成 Token。
     * - subject（主题）存 userId（学号）
     * - claim（自定义字段）存 userType（角色），这样拦截器就不用再查库就能拿到角色
     * - jti（JWT ID）存一个 **UUID**：让"每一次登录"拿到的 Token 都是独一份
     *
     * ⚠️ 为什么必须有 jti（2026-10-11 加；证据见 target/probe-token-reuse.py）：
     *    payload 里原来只有 sub / userType / iat / exp，而 **iat 是秒级的**
     *    （JWT 规范里 NumericDate 的单位就是秒），HMAC 签名又是确定性的 ——
     *    于是"同一秒内、同一个用户、登录两次"会拿到**一字不差**的同一个 Token 字符串。
     *
     *    后果很阴险：用户登出后在同一秒内又登录一次，拿到的还是那个刚被拉黑的同一个字符串，
     *    拦截器一查黑名单 → 401，看起来像"登录接口坏了 / 黑名单误伤"，
     *    其实是因为**前后两个 Token 根本是同一个**。
     *
     *    加了 jti 之后每个 Token 都不一样 ⇒ 登出只作废"这一个"，
     *    新登录拿到的那份不受牵连（黑名单 key 是 Token 的哈希，哈希不同就互不干扰）。
     */
    public String generateToken(String userId, String userType) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expirationSeconds * 1000L);
        return Jwts.builder()
                .subject(userId)
                .claim("userType", userType)
                .id(UUID.randomUUID().toString())   // jti：每次登录一个新 UUID ⇒ Token 唯一
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)          // 算法由密钥长度决定（见类注释），不是写死的 HS256
                .compact();             // 生成最终字符串
    }

    /**
     * 解析 Token，返回里面的 Claims（载荷）。
     * 若 Token 被篡改 / 已过期 / 格式错误，jjwt 会抛 JwtException。
     */
    public Claims parseToken(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(key)        // 用同一把密钥校验签名
                .build()
                .parseSignedClaims(token);
        return jws.getPayload();
    }

    /** 从 Token 中取出 userId（学号） */
    public String getUserId(String token) {
        return parseToken(token).getSubject();
    }

    /** 从 Token 中取出角色 userType */
    public String getUserType(String token) {
        return parseToken(token).get("userType", String.class);
    }

    /**
     * 这把密钥用的是哪个算法？（HS256 / HS384 / HS512）
     * 只给日志和排查用 —— 遇到"算法对不上"的怪问题时，一眼就能看出是哪边错了。
     */
    public String getAlgorithmName() {
        return key.getAlgorithm();
    }

    /**
     * Token 还剩多少秒失效（登出时用它来定黑名单的 TTL）。
     *
     * 为什么不干脆用配置里的 jwt.expiration（7 天）？
     *   因为那个是"从签发那一刻算起"的总寿命。用户可能登录 6 天 23 小时之后才登出，
     *   这时 Token 只剩 1 小时可活，黑名单却存 7 天 —— 多出来的 6 天 23 小时纯属垃圾。
     *   存"剩余寿命"之后：Token 自然过期的那一刻，黑名单项也正好一起消失，不多不少。
     *
     * 返回 0 表示"没有剩余寿命"（已过期 / 格式坏 / 签名不对）—— 这种 Token 本来就用不了，
     * 调用方可以直接跳过，不必写黑名单。
     */
    public long getRemainingSeconds(String token) {
        try {
            Date exp = parseToken(token).getExpiration();
            return (exp.getTime() - System.currentTimeMillis()) / 1000L;
        } catch (JwtException | IllegalArgumentException e) {
            return 0;
        }
    }

    /**
     * 校验 Token 是否合法（签名正确且未过期）。
     * 返回 true / false，不抛异常，方便在拦截器里直接判断。
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
