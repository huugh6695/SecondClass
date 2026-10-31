package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.common.TokenBlacklist;
import com.secondclassroom.dto.LoginResult;
import com.secondclassroom.dto.LoginSmsVerifyRequest;
import com.secondclassroom.dto.SmsCodeResult;
import com.secondclassroom.dto.UserInfoResult;
import com.secondclassroom.model.User;
import com.secondclassroom.util.JwtUtil;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.UUID;

/**
 * 登录业务逻辑：查用户 → 校验密码 → 发 Token。
 * 这是整个认证模块"最有脑子"的地方。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_LOGIN_FAILURES = 10;
    private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(15);

    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final TokenBlacklist blacklist;
    private final OperationLogService operationLogService;
    private final SmsCodeService smsCodeService;
    private final PhoneCryptoService phoneCryptoService;
    private final DeviceTrustService deviceTrustService;
    private final StringRedisTemplate redis;

    public AuthService(JdbcTemplate jdbcTemplate, JwtUtil jwtUtil, TokenBlacklist blacklist,
                       OperationLogService operationLogService, SmsCodeService smsCodeService,
                       PhoneCryptoService phoneCryptoService,
                       DeviceTrustService deviceTrustService,
                       StringRedisTemplate redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.blacklist = blacklist;
        this.operationLogService = operationLogService;
        this.smsCodeService = smsCodeService;
        this.phoneCryptoService = phoneCryptoService;
        this.deviceTrustService = deviceTrustService;
        this.redis = redis;
    }

    public Result<LoginResult> login(String userId, String password, String deviceToken) {
        // 1. 入参非空校验
        if (userId == null || userId.isBlank() || password == null || password.isBlank()) {
            return Result.fail(401, "账号或密码错误");
        }
        if (loginFailureBlocked(userId)) {
            return Result.fail(429, "登录失败次数过多，请 15 分钟后再试");
        }

        // 2. 按学号查用户（查不到 queryForObject 会抛异常，拦下来当"账号密码错误"）
        User user;
        try {
            user = jdbcTemplate.queryForObject(
                    "SELECT * FROM t_user WHERE user_id = ?",
                    new UserRowMapper(), userId);
        } catch (EmptyResultDataAccessException e) {
            recordLoginFailure(userId);
            return Result.fail(401, "账号或密码错误");
        }

        // 3. 禁用账号拦截
        if ("DISABLED".equals(user.getStatus())) {
            return Result.fail(403, "账号已禁用");
        }

        // 4. bcrypt 比对密码（注意：用 checkpw，不要用 equals 直接比明文）
        if (!BCrypt.checkpw(password, user.getPassword())) {
            recordLoginFailure(userId);
            return Result.fail(401, "账号或密码错误");
        }

        // 5. 绑定过手机的新账号在未知设备上必须做一次短信验证。
        //    历史预置账号没有手机号，继续兼容“学号 + 密码”登录。
        if (hasVerifiedPhone(user)) {
            try {
                if (!deviceTrustService.isTrusted(userId, deviceToken)) {
                    return createLoginChallenge(user);
                }
            } catch (RuntimeException e) {
                log.error("可信设备检查失败", e);
                return Result.fail(503, "登录安全服务暂时不可用，请稍后重试");
            }
        }

        return completeLogin(user, password, null);
    }

    public Result<LoginResult> verifyLoginSms(LoginSmsVerifyRequest req) {
        if (req == null || req.getChallengeId() == null || req.getChallengeId().isBlank()) {
            return Result.fail(400, "登录验证已失效，请重新输入密码");
        }
        String challengeKey = "auth:login:challenge:" + req.getChallengeId();
        String userId = redis.opsForValue().get(challengeKey);
        if (userId == null) {
            return Result.fail(400, "登录验证已失效，请重新输入密码");
        }

        Result<Void> codeResult = smsCodeService.verifyCode(
                "LOGIN", "LOGIN|" + userId, req.getSmsCode());
        if (!codeResult.isSuccess()) {
            return Result.fail(codeResult.getCode(), codeResult.getMessage());
        }

        User user = findUserOrNull(userId);
        if (user == null) {
            redis.delete(challengeKey);
            return Result.fail(401, "账号不存在，请重新登录");
        }
        if ("DISABLED".equals(user.getStatus())) {
            redis.delete(challengeKey);
            return Result.fail(403, "账号已禁用");
        }
        redis.delete(challengeKey);
        String deviceToken = deviceTrustService.issueToken(userId);
        return completeLogin(user, null, deviceToken);
    }

    private Result<LoginResult> createLoginChallenge(User user) {
        String challengeId = UUID.randomUUID().toString();
        String challengeKey = "auth:login:challenge:" + challengeId;
        redis.opsForValue().set(challengeKey, user.getUserId(), Duration.ofMinutes(5));

        String phone = phoneCryptoService.decrypt(user.getPhoneCiphertext());
        Result<?> sendResult = smsCodeService.sendCode(
                "LOGIN", "LOGIN|" + user.getUserId(), phone);
        if (!sendResult.isSuccess()) {
            redis.delete(challengeKey);
            return Result.fail(sendResult.getCode(), sendResult.getMessage());
        }

        LoginResult result = new LoginResult();
        result.setRequiresSms(true);
        result.setChallengeId(challengeId);
        result.setMaskedPhone(phoneCryptoService.maskPhone(phone));
        if (sendResult.getData() instanceof SmsCodeResult smsData) {
            result.setDebugCode(smsData.getDebugCode());
        }
        return Result.success("这是一台新设备，请完成短信验证", result);
    }

    private Result<LoginResult> completeLogin(User user, String plainPassword,
                                              String newDeviceToken) {
        clearLoginFailures(user.getUserId());
        jdbcTemplate.update(
                "UPDATE t_user SET last_login_time = NOW() WHERE user_id = ?",
                user.getUserId());

        operationLogService.record(user.getUserId(), user.getUserName(), user.getUserType(),
                "LOGIN", "USER", user.getUserId(),
                newDeviceToken == null ? "用户登录" : "新设备短信验证登录");

        String defaultPwd = user.getUserId().length() >= 6
                ? user.getUserId().substring(user.getUserId().length() - 6)
                : user.getUserId();
        boolean needChange = plainPassword != null && defaultPwd.equals(plainPassword);

        LoginResult result = new LoginResult();
        result.setToken(jwtUtil.generateToken(
                user.getUserId(), user.getUserType(), user.getTokenVersion()));
        result.setUserId(user.getUserId());
        result.setUserName(user.getUserName());
        result.setRole(user.getUserType());
        result.setNeedChangePassword(needChange);
        result.setDeviceToken(newDeviceToken);
        if (hasVerifiedPhone(user)) {
            result.setMaskedPhone(phoneCryptoService.maskPhone(
                    phoneCryptoService.decrypt(user.getPhoneCiphertext())));
        }
        return Result.success("登录成功", result);
    }

    private boolean hasVerifiedPhone(User user) {
        return user.getPhoneHash() != null
                && user.getPhoneCiphertext() != null
                && user.getPhoneVerifiedAt() != null;
    }

    private boolean loginFailureBlocked(String userId) {
        try {
            String value = redis.opsForValue().get(loginFailureKey(userId));
            return value != null && Integer.parseInt(value) >= MAX_LOGIN_FAILURES;
        } catch (RuntimeException e) {
            // Redis 故障不能让所有历史账号都无法登录；这里降级为不锁，短信验证另有独立保护。
            log.warn("登录失败次数检查不可用: {}", e.getMessage());
            return false;
        }
    }

    private void recordLoginFailure(String userId) {
        try {
            String key = loginFailureKey(userId);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, LOGIN_FAILURE_WINDOW);
            }
        } catch (RuntimeException e) {
            log.warn("登录失败次数记录不可用: {}", e.getMessage());
        }
    }

    private void clearLoginFailures(String userId) {
        try {
            redis.delete(loginFailureKey(userId));
        } catch (RuntimeException e) {
            log.warn("登录失败次数清理不可用: {}", e.getMessage());
        }
    }

    private String loginFailureKey(String userId) {
        return "auth:login:fail:" + phoneCryptoService.hmacHex(userId);
    }

    /** 查询当前登录用户的基本信息（/api/auth/me 用） */
    public Result<UserInfoResult> getUserInfo(String userId) {
        User user;
        try {
            user = jdbcTemplate.queryForObject(
                    "SELECT * FROM t_user WHERE user_id = ?", new UserRowMapper(), userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }
        UserInfoResult info = new UserInfoResult();
        info.setUserId(user.getUserId());
        info.setUserName(user.getUserName());
        info.setRole(user.getUserType());
        info.setOpenId(user.getOpenId());
        info.setSubscribeStatus(user.getSubscribeStatus());
        boolean phoneBound = hasVerifiedPhone(user);
        info.setPhoneBound(phoneBound);
        if (phoneBound) {
            info.setMaskedPhone(phoneCryptoService.maskPhone(
                    phoneCryptoService.decrypt(user.getPhoneCiphertext())));
        }
        return Result.success("success", info);
    }

    /** 修改密码（/api/auth/password 用） */
    public Result<Void> changePassword(String userId, String oldPassword, String newPassword) {
        User user;
        try {
            user = jdbcTemplate.queryForObject(
                    "SELECT * FROM t_user WHERE user_id = ?", new UserRowMapper(), userId);
        } catch (EmptyResultDataAccessException e) {
            return Result.fail(404, "用户不存在");
        }
        // 1. 原密码校验
        if (!BCrypt.checkpw(oldPassword, user.getPassword())) {
            return Result.fail(400, "原密码错误");
        }
        // 2. 新密码格式：6-20 位，且同时含字母和数字
        if (!newPassword.matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,20}$")) {
            return Result.fail(400, "新密码需为 6-20 位，且同时包含字母和数字");
        }
        // 3. 新密码不能和旧密码相同
        if (oldPassword.equals(newPassword)) {
            return Result.fail(400, "新密码不能与旧密码相同");
        }
        // 4. bcrypt 加密后入库
        String hashed = BCrypt.hashpw(newPassword, BCrypt.gensalt(10));
        jdbcTemplate.update(
                "UPDATE t_user SET password = ?, update_time = NOW() WHERE user_id = ?",
                hashed, userId);
        // 5. 记审计日志（只记"改成功了"，原密码错误/格式不合法都在上面 return 掉了）
        operationLogService.record(user.getUserId(), user.getUserName(), user.getUserType(),
                "CHANGE_PASSWORD", "USER", user.getUserId(), "修改密码");
        return Result.success("密码修改成功", null);
    }

    /**
     * 登出（/api/auth/logout 用）：把当前 Token 加入黑名单。
     *
     * 这里比原来多了一个 userId 参数 —— 因为要写审计日志就得知道"是谁登出的"。
     * 登出接口是过了拦截器的，Controller 能直接从 request 属性里把 userId 取出来，白给。
     * 至于姓名和角色，Token 里没有，只能按 userId 再查一次库。
     */
    public Result<Void> logout(String userId, String token) {
        // 1. 把当前 Token 拉黑。
        //    这一步**本身不会抛**：TokenBlacklist 内部对 Redis 异常做了兜底
        //    （降级到内存 Map + 打节流日志），Redis 挂了只是少了一层"跨实例也认得出"的保险。
        blacklist.add(token);

        // 2. 写审计日志 —— 🔴 故意用 try/catch 包住它，这是本方法唯一可能出错的地方。
        //
        //    为什么它会出错：审计**行**写的是 MySQL，但这一行的**主键 log_id 要走 Redis 发号**
        //    （DailySeqId.next(jdbcTemplate, redis, "OLOG", "t_operation_log", "log_id", 6)）。
        //    Redis 连不上时发号会抛 SeqIdException → 500 → 用户看到"登出失败"。
        //
        //    但登出的本质是"客户端把 Token 丢掉"，服务端拉黑只是防 Token 被截获的保险。
        //    它是整个系统里最不该失败的动作：登不出去会让用户卡在一个"看起来还登录着"的界面里，
        //    而实际上第 1 步已经把 Token 作废了 —— 报错纯属误导。
        //    所以这里宁可丢一条审计，也不能丢可用性。Redis 挂的状态 /health 会如实报
        //    （DOWN 或 SKIPPED），运维一眼就知道"这段时间的审计不全"。
        try {
            User user = findUserOrNull(userId);
            if (user != null) {
                operationLogService.record(user.getUserId(), user.getUserName(), user.getUserType(),
                        "LOGOUT", "USER", user.getUserId(), "用户登出");
            }
        } catch (RuntimeException e) {
            log.warn("登出审计写入失败（已忽略，不影响登出本身）：userId={}, 原因={}", userId, e.getMessage());
        }

        // 3. 永远返回成功。这个接口**没有任何失败分支**：
        //    重复登出、Token 已经不认得、审计写不进去 —— 一律 code=0，幂等。
        return Result.success("退出成功", null);
    }

    /** 按 userId 查用户；查不到返回 null（给"记日志"这种可失败场景用，不打扰主流程） */
    private User findUserOrNull(String userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM t_user WHERE user_id = ?", new UserRowMapper(), userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 把数据库查出来的一行，映射成 User 对象 */
    private static class UserRowMapper implements RowMapper<User> {
        @Override
        public User mapRow(ResultSet rs, int rowNum) throws SQLException {
            User u = new User();
            u.setUserId(rs.getString("user_id"));
            u.setUserName(rs.getString("user_name"));
            u.setUserType(rs.getString("user_type"));
            u.setPassword(rs.getString("password"));
            u.setOpenId(rs.getString("open_id"));
            u.setSubscribeStatus(rs.getString("subscribe_status"));
            u.setPhoneCiphertext(rs.getString("phone_ciphertext"));
            u.setPhoneHash(rs.getString("phone_hash"));
            u.setPhoneLast4(rs.getString("phone_last4"));
            u.setPhoneVerifiedAt(rs.getObject("phone_verified_at", LocalDateTime.class));
            u.setStatus(rs.getString("status"));
            u.setTokenVersion(rs.getInt("token_version"));
            u.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            u.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            u.setLastLoginTime(rs.getObject("last_login_time", LocalDateTime.class));
            return u;
        }
    }
}
