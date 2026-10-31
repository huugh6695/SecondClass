package com.secondclassroom.service;

import com.secondclassroom.common.Result;
import com.secondclassroom.dto.LoginResult;
import com.secondclassroom.dto.PhoneChangeRequest;
import com.secondclassroom.dto.PhoneSmsCodeRequest;
import com.secondclassroom.dto.RegisterRequest;
import com.secondclassroom.dto.RegisterSmsCodeRequest;
import com.secondclassroom.dto.ResetPasswordRequest;
import com.secondclassroom.dto.ResetPasswordSmsRequest;
import com.secondclassroom.dto.SmsCodeResult;
import com.secondclassroom.model.User;
import com.secondclassroom.util.JwtUtil;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class AuthAccountService {

    private static final String PASSWORD_RULE =
            "密码需为 6-20 位，且同时包含字母和数字";

    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final SmsCodeService smsCodeService;
    private final PhoneCryptoService phoneCryptoService;
    private final DeviceTrustService deviceTrustService;
    private final OperationLogService operationLogService;

    public AuthAccountService(
            JdbcTemplate jdbcTemplate,
            JwtUtil jwtUtil,
            SmsCodeService smsCodeService,
            PhoneCryptoService phoneCryptoService,
            DeviceTrustService deviceTrustService,
            OperationLogService operationLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.smsCodeService = smsCodeService;
        this.phoneCryptoService = phoneCryptoService;
        this.deviceTrustService = deviceTrustService;
        this.operationLogService = operationLogService;
    }

    public Result<SmsCodeResult> sendRegisterCode(RegisterSmsCodeRequest req) {
        String userId = normalizeUserId(req == null ? null : req.getUserId());
        String userName = normalizeUserName(req == null ? null : req.getUserName());
        String phone = phoneCryptoService.normalizePhone(req == null ? null : req.getPhone());

        Result<Void> inputError = validateRegistrationIdentity(userId, userName, phone);
        if (inputError != null) {
            return Result.fail(inputError.getCode(), inputError.getMessage());
        }
        if (findUser(userId) != null) {
            return Result.fail(409, "该学号已注册，请直接登录");
        }
        if (phoneAlreadyBound(phone)) {
            return Result.fail(409, "该手机号已绑定其他账号");
        }

        return smsCodeService.sendCode(
                "REGISTER", registerSubject(userId, phone), phone);
    }

    @Transactional
    public Result<LoginResult> register(RegisterRequest req) {
        String userId = normalizeUserId(req == null ? null : req.getUserId());
        String userName = normalizeUserName(req == null ? null : req.getUserName());
        String phone = phoneCryptoService.normalizePhone(req == null ? null : req.getPhone());
        String password = req == null ? null : req.getPassword();

        Result<Void> identityError = validateRegistrationIdentity(userId, userName, phone);
        if (identityError != null) {
            return Result.fail(identityError.getCode(), identityError.getMessage());
        }
        if (req == null || !req.isAgreedToPrivacy()) {
            return Result.fail(400, "请先同意手机号用于身份验证和密码找回");
        }
        if (!validPassword(password)) {
            return Result.fail(400, PASSWORD_RULE);
        }
        if (findUser(userId) != null) {
            return Result.fail(409, "该学号已注册，请直接登录");
        }
        if (phoneAlreadyBound(phone)) {
            return Result.fail(409, "该手机号已绑定其他账号");
        }

        Result<Void> codeResult = smsCodeService.verifyCode(
                "REGISTER", registerSubject(userId, phone), req.getSmsCode());
        if (!codeResult.isSuccess()) {
            return Result.fail(codeResult.getCode(), codeResult.getMessage());
        }

        String phoneHash = phoneCryptoService.hash(phone);
        String encryptedPhone = phoneCryptoService.encrypt(phone);
        try {
            jdbcTemplate.update(
                    "INSERT INTO t_user (user_id, user_name, user_type, password, "
                            + "phone_ciphertext, phone_hash, phone_last4, phone_verified_at, "
                            + "status, token_version, last_login_time) "
                            + "VALUES (?, ?, 'STUDENT', ?, ?, ?, ?, NOW(), 'ACTIVE', 0, NOW())",
                    userId, userName, BCrypt.hashpw(password, BCrypt.gensalt(10)),
                    encryptedPhone, phoneHash, phone.substring(phone.length() - 4));
        } catch (DuplicateKeyException e) {
            return Result.fail(409, "该学号或手机号已被注册");
        }

        User user = findUser(userId);
        String deviceToken = deviceTrustService.issueToken(userId);
        operationLogService.record(userId, userName, "STUDENT",
                "REGISTER", "USER", userId, "学生自助注册");

        LoginResult result = buildLoginResult(user, false, deviceToken);
        return Result.success("注册成功", result);
    }

    public Result<SmsCodeResult> sendResetCode(ResetPasswordSmsRequest req) {
        String userId = normalizeUserId(req == null ? null : req.getUserId());
        if (userId == null) {
            return Result.fail(400, "请输入学号");
        }
        User user = findUser(userId);
        // 对外统一使用泛化提示，避免暴露某个学号是否已注册。
        if (user == null || !hasVerifiedPhone(user)) {
            return Result.success("如账号已绑定手机号，验证码将发送到绑定手机",
                    new SmsCodeResult(60, null));
        }
        String phone = phoneCryptoService.decrypt(user.getPhoneCiphertext());
        return smsCodeService.sendCode("RESET_PASSWORD", "RESET_PASSWORD|" + userId, phone);
    }

    public Result<Void> resetPassword(ResetPasswordRequest req) {
        String userId = normalizeUserId(req == null ? null : req.getUserId());
        String password = req == null ? null : req.getNewPassword();
        if (userId == null) {
            return Result.fail(400, "请输入学号");
        }
        if (!validPassword(password)) {
            return Result.fail(400, PASSWORD_RULE);
        }
        User user = findUser(userId);
        if (user == null || !hasVerifiedPhone(user)) {
            return Result.fail(400, "账号不存在或尚未绑定手机号");
        }

        Result<Void> codeResult = smsCodeService.verifyCode(
                "RESET_PASSWORD", "RESET_PASSWORD|" + userId, req.getSmsCode());
        if (!codeResult.isSuccess()) {
            return codeResult;
        }

        jdbcTemplate.update(
                "UPDATE t_user SET password = ?, token_version = token_version + 1, "
                        + "update_time = NOW() WHERE user_id = ?",
                BCrypt.hashpw(password, BCrypt.gensalt(10)), userId);
        deviceTrustService.revokeAll(userId);
        operationLogService.record(user.getUserId(), user.getUserName(), user.getUserType(),
                "RESET_PASSWORD", "USER", userId, "通过短信验证重置密码");
        return Result.success("密码已重置，请使用新密码登录", null);
    }

    public Result<SmsCodeResult> sendPhoneCode(String userId, PhoneSmsCodeRequest req) {
        User user = findUser(userId);
        if (user == null) {
            return Result.fail(404, "用户不存在");
        }
        String target = req == null || req.getTarget() == null
                ? "" : req.getTarget().trim().toUpperCase(Locale.ROOT);

        if ("OLD".equals(target)) {
            if (!hasVerifiedPhone(user)) {
                return Result.fail(400, "当前账号尚未绑定手机号");
            }
            String phone = phoneCryptoService.decrypt(user.getPhoneCiphertext());
            return smsCodeService.sendCode(
                    "CHANGE_PHONE_OLD", "CHANGE_PHONE_OLD|" + userId, phone);
        }

        if ("NEW".equals(target)) {
            String newPhone = phoneCryptoService.normalizePhone(req.getNewPhone());
            if (!phoneCryptoService.isValidPhone(newPhone)) {
                return Result.fail(400, "请输入正确的 11 位手机号");
            }
            if (phoneAlreadyBoundToOther(newPhone, userId)) {
                return Result.fail(409, "该手机号已绑定其他账号");
            }
            return smsCodeService.sendCode(
                    "CHANGE_PHONE_NEW", changeNewPhoneSubject(userId, newPhone), newPhone);
        }
        return Result.fail(400, "target 必须是 OLD 或 NEW");
    }

    @Transactional
    public Result<Void> changePhone(String userId, PhoneChangeRequest req) {
        User user = findUser(userId);
        if (user == null) {
            return Result.fail(404, "用户不存在");
        }
        String newPhone = phoneCryptoService.normalizePhone(req == null ? null : req.getNewPhone());
        if (!phoneCryptoService.isValidPhone(newPhone)) {
            return Result.fail(400, "请输入正确的 11 位手机号");
        }
        if (phoneAlreadyBoundToOther(newPhone, userId)) {
            return Result.fail(409, "该手机号已绑定其他账号");
        }

        if (hasVerifiedPhone(user)) {
            Result<Void> oldResult = smsCodeService.verifyCode(
                    "CHANGE_PHONE_OLD", "CHANGE_PHONE_OLD|" + userId, req.getOldSmsCode());
            if (!oldResult.isSuccess()) {
                return oldResult;
            }
        }

        Result<Void> newResult = smsCodeService.verifyCode(
                "CHANGE_PHONE_NEW", changeNewPhoneSubject(userId, newPhone), req.getNewSmsCode());
        if (!newResult.isSuccess()) {
            return newResult;
        }

        try {
            jdbcTemplate.update(
                    "UPDATE t_user SET phone_ciphertext = ?, phone_hash = ?, phone_last4 = ?, "
                            + "phone_verified_at = NOW(), token_version = token_version + 1, "
                            + "update_time = NOW() WHERE user_id = ?",
                    phoneCryptoService.encrypt(newPhone),
                    phoneCryptoService.hash(newPhone),
                    newPhone.substring(newPhone.length() - 4),
                    userId);
        } catch (DuplicateKeyException e) {
            return Result.fail(409, "该手机号已绑定其他账号");
        }
        deviceTrustService.revokeAll(userId);
        operationLogService.record(user.getUserId(), user.getUserName(), user.getUserType(),
                "CHANGE_PHONE", "USER", userId, "修改绑定手机号");
        return Result.success("手机号已更新，请重新登录", null);
    }

    private Result<Void> validateRegistrationIdentity(String userId, String userName, String phone) {
        if (userId == null) {
            return Result.fail(400, "请输入学号");
        }
        if (userName == null) {
            return Result.fail(400, "请输入姓名");
        }
        if (!phoneCryptoService.isValidPhone(phone)) {
            return Result.fail(400, "请输入正确的 11 位手机号");
        }
        return null;
    }

    private String normalizeUserId(String userId) {
        if (userId == null) return null;
        String value = userId.trim();
        return value.matches("^[A-Za-z0-9]{6,32}$") ? value : null;
    }

    private String normalizeUserName(String userName) {
        if (userName == null) return null;
        String value = userName.trim().replaceAll("\\s+", " ");
        return value.length() >= 2 && value.length() <= 50 ? value : null;
    }

    private boolean validPassword(String password) {
        return password != null
                && password.matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,20}$");
    }

    private boolean hasVerifiedPhone(User user) {
        return user != null && user.getPhoneHash() != null
                && user.getPhoneCiphertext() != null && user.getPhoneVerifiedAt() != null;
    }

    private boolean phoneAlreadyBound(String phone) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE phone_hash = ?",
                Integer.class, phoneCryptoService.hash(phone));
        return count != null && count > 0;
    }

    private boolean phoneAlreadyBoundToOther(String phone, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE phone_hash = ? AND user_id <> ?",
                Integer.class, phoneCryptoService.hash(phone), userId);
        return count != null && count > 0;
    }

    private String registerSubject(String userId, String phone) {
        return userId + "|" + phoneCryptoService.hash(phone);
    }

    private String changeNewPhoneSubject(String userId, String phone) {
        return userId + "|" + phoneCryptoService.hash(phone);
    }

    private User findUser(String userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM t_user WHERE user_id = ?", new UserRowMapper(), userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private LoginResult buildLoginResult(User user, boolean needChange, String deviceToken) {
        LoginResult result = new LoginResult();
        result.setToken(jwtUtil.generateToken(
                user.getUserId(), user.getUserType(), user.getTokenVersion()));
        result.setUserId(user.getUserId());
        result.setUserName(user.getUserName());
        result.setRole(user.getUserType());
        result.setNeedChangePassword(needChange);
        result.setDeviceToken(deviceToken);
        result.setMaskedPhone(phoneCryptoService.maskPhone(
                phoneCryptoService.decrypt(user.getPhoneCiphertext())));
        return result;
    }

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
