package com.secondclassroom.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Web 请求相关的小工具。
 *
 * 目前只有一个用途：给操作审计日志（t_operation_log.ip_address）取"这次操作是从哪台机器发起的"。
 *
 * 为什么不在 Controller 里把 HttpServletRequest 一层层传到 Service？
 * 因为那样每个 Service 方法都要多一个参数，签名会变得很啰嗦。
 * Spring 提供了一个"当前请求"的线程变量（RequestContextHolder），
 * 同一次请求的处理线程里随时能把它取出来 —— 所以这里直接取，不用改任何方法签名。
 *
 * ⚠️ 只有"正在处理 HTTP 请求的线程"里才有值。如果哪天在异步线程/定时任务里调用，
 *    取不到就返回 null，绝不抛异常（审计信息丢了可以接受，业务被带崩不行）。
 */
public final class WebUtil {

    /** 工具类不允许被 new，也不允许被继承 */
    private WebUtil() {
    }

    /**
     * 取客户端 IP。
     *
     * 顺序很重要：先看反向代理塞进来的头，最后才用 remoteAddr。
     * 因为一旦前面挂了 Nginx，getRemoteAddr() 拿到的是 Nginx 自己的 IP，不是真实用户。
     *
     * 返回值可能是 IPv6（比如 ::1，本机访问就是它），所以数据库里 ip_address 给了 VARCHAR(45)。
     */
    public static String getClientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }

        // 1. X-Forwarded-For 是标准代理头，格式是 "真实IP, 代理1, 代理2"，第一个才是用户
        String ip = firstValidIp(request.getHeader("X-Forwarded-For"));
        if (ip != null) {
            return ip;
        }
        // 2. 其他常见代理头（Nginx 常用 X-Real-IP，一些老网关用 Proxy-Client-IP）
        ip = firstValidIp(request.getHeader("X-Real-IP"));
        if (ip != null) {
            return ip;
        }
        ip = firstValidIp(request.getHeader("Proxy-Client-IP"));
        if (ip != null) {
            return ip;
        }
        ip = firstValidIp(request.getHeader("WL-Proxy-Client-IP"));
        if (ip != null) {
            return ip;
        }
        // 3. 实在没有代理头，就用 TCP 连接本身的来源地址（本地直连时是本机 ::1 / 127.0.0.1）
        return firstValidIp(request.getRemoteAddr());
    }

    /** 取当前线程正在处理的请求；不在请求线程里则返回 null */
    private static HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }

    /**
     * 代理头里常见这两种"占位垃圾值"：unknown、空串 —— 都当没有处理。
     * 另外只要第一个逗号前的部分（X-Forwarded-For 会带一串）。
     */
    private static String firstValidIp(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String value = headerValue.trim();
        if (value.isEmpty() || "unknown".equalsIgnoreCase(value)) {
            return null;
        }
        int comma = value.indexOf(',');
        if (comma >= 0) {
            value = value.substring(0, comma).trim();
        }
        return (value.isEmpty() || "unknown".equalsIgnoreCase(value)) ? null : value;
    }
}
