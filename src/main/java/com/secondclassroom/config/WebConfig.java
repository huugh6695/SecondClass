package com.secondclassroom.config;

import com.secondclassroom.interceptor.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把 JwtInterceptor 注册到 Spring MVC，并配置哪些路径要拦、哪些放行。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    public WebConfig(JwtInterceptor jwtInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")                  // 拦截所有请求
                .excludePathPatterns(
                        "/api/auth/login",               // 登录接口本来就不需要 Token
                        "/api/auth/login/sms/verify",    // 新设备短信验证
                        "/api/auth/register",            // 学生自助注册
                        "/api/auth/register/sms-code",   // 注册手机验证码
                        "/api/auth/password/reset",      // 忘记密码
                        "/api/auth/password/reset/sms-code",
                        "/health",                       // 健康检查要放行：监控/负载均衡探活不会带 Token

                        // ---------- 前端静态资源（⑩ 前端阶段新增）----------
                        // 前端是**纯静态 HTML + 原生 JS**，放在 src/main/resources/static/ 下，
                        // 由 Spring Boot 自己托管。这些文件里**没有任何敏感信息**：
                        // 真正的数据全部由 /api/** 提供，而 /api/** 仍然是全拦的 ——
                        // 所以放行静态资源 ≠ 开后门，只是让"登录页"能被人打开而已。
                        //
                        // ⚠️ 不加这几条会怎样：浏览器请求 `/` 会被拦截器拦成 401，
                        //    前端页面**根本打不开**（这是个很容易卡半天的坑）。
                        "/",
                        "/index.html",
                        "/favicon.ico",
                        "/css/**",
                        "/js/**",
                        "/img/**"
                );
    }
}
