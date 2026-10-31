package com.secondclassroom.controller;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.ChangePasswordRequest;
import com.secondclassroom.dto.LoginRequest;
import com.secondclassroom.dto.LoginResult;
import com.secondclassroom.dto.LoginSmsVerifyRequest;
import com.secondclassroom.dto.PhoneChangeRequest;
import com.secondclassroom.dto.PhoneSmsCodeRequest;
import com.secondclassroom.dto.RegisterRequest;
import com.secondclassroom.dto.RegisterSmsCodeRequest;
import com.secondclassroom.dto.ResetPasswordRequest;
import com.secondclassroom.dto.ResetPasswordSmsRequest;
import com.secondclassroom.dto.SmsCodeResult;
import com.secondclassroom.dto.UserInfoResult;
import com.secondclassroom.service.AuthAccountService;
import com.secondclassroom.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 认证相关接口：/api/auth/login、/api/auth/logout、/api/auth/password、/api/auth/me
 * 这一步先只暴露 login，其余三个在 Step 4 加。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthAccountService authAccountService;

    public AuthController(AuthService authService, AuthAccountService authAccountService) {
        this.authService = authService;
        this.authAccountService = authAccountService;
    }

    /** POST /api/auth/login —— 免 Token */
    @PostMapping("/login")
    public Result<LoginResult> login(@RequestBody LoginRequest req) {
        return authService.login(req.getUserId(), req.getPassword(), req.getDeviceToken());
    }

    @PostMapping("/login/sms/verify")
    public Result<LoginResult> verifyLoginSms(@RequestBody LoginSmsVerifyRequest req) {
        return authService.verifyLoginSms(req);
    }

    /** 学生自助注册：角色由服务端固定为 STUDENT，请求体没有角色字段。 */
    @PostMapping("/register")
    public Result<LoginResult> register(@RequestBody RegisterRequest req) {
        return authAccountService.register(req);
    }

    @PostMapping("/register/sms-code")
    public Result<SmsCodeResult> sendRegisterSmsCode(@RequestBody RegisterSmsCodeRequest req) {
        return authAccountService.sendRegisterCode(req);
    }

    @PostMapping("/password/reset/sms-code")
    public Result<SmsCodeResult> sendResetSmsCode(@RequestBody ResetPasswordSmsRequest req) {
        return authAccountService.sendResetCode(req);
    }

    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@RequestBody ResetPasswordRequest req) {
        return authAccountService.resetPassword(req);
    }

    /** GET /api/auth/me —— 需 Token，返回当前登录用户信息 */
    @GetMapping("/me")
    public Result<UserInfoResult> me(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return authService.getUserInfo(userId);
    }

    /** POST /api/auth/logout —— 需 Token，把当前 Token 加入黑名单（顺便记一条审计日志） */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String token = (String) request.getAttribute("token");
        return authService.logout(userId, token);
    }

    /** PUT /api/auth/password —— 需 Token，修改密码 */
    @PutMapping("/password")
    public Result<Void> changePassword(HttpServletRequest request,
                                      @RequestBody ChangePasswordRequest req) {
        String userId = (String) request.getAttribute("userId");
        return authService.changePassword(userId, req.getOldPassword(), req.getNewPassword());
    }

    @PostMapping("/phone/sms-code")
    public Result<SmsCodeResult> sendPhoneSmsCode(HttpServletRequest request,
                                                 @RequestBody PhoneSmsCodeRequest req) {
        String userId = (String) request.getAttribute("userId");
        return authAccountService.sendPhoneCode(userId, req);
    }

    @PutMapping("/phone")
    public Result<Void> changePhone(HttpServletRequest request,
                                    @RequestBody PhoneChangeRequest req) {
        String userId = (String) request.getAttribute("userId");
        return authAccountService.changePhone(userId, req);
    }
}
