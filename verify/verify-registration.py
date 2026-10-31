"""验收：学生自助注册、手机号登录验证、找回密码和更换手机号。"""

import time
import uuid

from lib import Checker, http, login, run


def main():
    c = Checker("学生自助注册与手机认证")
    suffix = str(int(time.time() * 1000))[-8:]
    user_id = "REG" + uuid.uuid4().hex[:10].upper()
    user_name = "注册验收学生"
    phone = "139" + suffix
    new_phone = "138" + suffix
    password = "RegPass123"
    new_password = "ResetPass123"

    st, body = http("POST", "/api/auth/register/sms-code", {
        "userId": user_id, "userName": user_name, "phone": phone
    })
    code = (body.get("data") or {}).get("debugCode")
    c.eq(st, 200, "注册验证码接口 HTTP 200")
    c.eq(body.get("code"), 0, "注册验证码发送成功")
    c.is_true(bool(code) and len(str(code)) == 6, "开发模式返回 6 位验证码")

    st, body = http("POST", "/api/auth/register/sms-code", {
        "userId": user_id, "userName": user_name, "phone": phone
    })
    c.eq(body.get("code"), 429, "同一注册验证码不能立即重复发送")

    st, body = http("POST", "/api/auth/register", {
        "userId": user_id, "userName": user_name, "phone": phone,
        "smsCode": code, "password": password, "agreedToPrivacy": False
    })
    c.eq(body.get("code"), 400, "不同意手机号使用说明不能注册")

    st, body = http("POST", "/api/auth/register", {
        "userId": user_id, "userName": user_name, "phone": phone,
        "smsCode": code, "password": password, "agreedToPrivacy": True,
        "role": "ADMIN"
    })
    data = body.get("data") or {}
    c.eq(st, 200, "学生注册 HTTP 200")
    c.eq(body.get("code"), 0, "学生注册成功")
    c.eq(data.get("role"), "STUDENT", "即使请求传 role=ADMIN，服务端仍固定创建学生")
    c.is_true(bool(data.get("token")), "注册成功自动登录")
    register_token = data.get("token")
    device_token = data.get("deviceToken")
    c.is_true(bool(device_token), "注册成功创建可信设备令牌")
    c.eq(data.get("maskedPhone"), phone[:3] + "****" + phone[-4:], "返回脱敏手机号")

    st, body = http("POST", "/api/auth/login", {
        "userId": user_id, "password": password, "deviceToken": device_token
    })
    data = body.get("data") or {}
    c.eq(body.get("code"), 0, "可信设备使用学号和密码正常登录")
    c.eq(data.get("requiresSms"), False, "可信设备不再要求短信")
    trusted_token = data.get("token")

    st, body = http("POST", "/api/auth/password/reset/sms-code", {"userId": user_id})
    reset_code = (body.get("data") or {}).get("debugCode")
    c.eq(body.get("code"), 0, "找回密码验证码发送成功")
    c.is_true(bool(reset_code), "找回密码返回开发验证码")

    st, body = http("POST", "/api/auth/password/reset", {
        "userId": user_id, "smsCode": reset_code, "newPassword": new_password
    })
    c.eq(body.get("code"), 0, "短信验证后重置密码成功")

    st, body = http("GET", "/api/auth/me", token=trusted_token)
    c.eq(st, 401, "密码重置后旧 Token 立即失效")

    st, body = http("POST", "/api/auth/login", {
        "userId": user_id, "password": password, "deviceToken": device_token
    })
    c.eq(body.get("code"), 401, "旧密码不能继续登录")

    st, body = http("POST", "/api/auth/login", {
        "userId": user_id, "password": new_password, "deviceToken": device_token
    })
    data = body.get("data") or {}
    c.eq(body.get("code"), 0, "新密码通过密码校验")
    c.eq(data.get("requiresSms"), True, "密码重置撤销设备后要求短信验证")
    c.eq(data.get("maskedPhone"), phone[:3] + "****" + phone[-4:], "验证手机号脱敏显示")
    challenge_id = data.get("challengeId")
    login_code = data.get("debugCode")
    c.is_true(bool(challenge_id) and bool(login_code), "新设备登录返回挑战和开发验证码")

    st, body = http("POST", "/api/auth/login/sms/verify", {
        "challengeId": challenge_id, "smsCode": "000000"
    })
    c.eq(body.get("code"), 400, "错误短信验证码不能登录")

    st, body = http("POST", "/api/auth/login/sms/verify", {
        "challengeId": challenge_id, "smsCode": login_code
    })
    verified = body.get("data") or {}
    c.eq(body.get("code"), 0, "正确短信验证码完成新设备登录")
    c.is_true(bool(verified.get("token")), "验证成功返回新 Token")
    c.is_true(bool(verified.get("deviceToken")), "验证成功绑定新的可信设备")
    verified_token = verified.get("token")

    st, body = http("POST", "/api/auth/phone/sms-code", {
        "target": "OLD"
    }, token=verified_token)
    old_code = (body.get("data") or {}).get("debugCode")
    c.eq(body.get("code"), 0, "原手机号验证码发送成功")

    st, body = http("POST", "/api/auth/phone/sms-code", {
        "target": "NEW", "newPhone": new_phone
    }, token=verified_token)
    new_code = (body.get("data") or {}).get("debugCode")
    c.eq(body.get("code"), 0, "新手机号验证码发送成功")

    st, body = http("PUT", "/api/auth/phone", {
        "newPhone": new_phone, "oldSmsCode": old_code, "newSmsCode": new_code
    }, token=verified_token)
    c.eq(body.get("code"), 0, "双重短信验证后更换手机号成功")

    st, body = http("GET", "/api/auth/me", token=verified_token)
    c.eq(st, 401, "更换手机号后旧 Token 立即失效")

    admin_token, admin_login = login("2023003001", "123456")
    c.is_true(bool(admin_token), "管理员后台预置账号仍可正常登录")
    c.eq((admin_login.get("data") or {}).get("role"), "ADMIN", "管理员角色保持不变")

    for _ in range(10):
        http("POST", "/api/auth/login", {
            "userId": user_id, "password": "WrongPass999", "deviceToken": verified.get("deviceToken")
        })
    st, body = http("POST", "/api/auth/login", {
        "userId": user_id, "password": new_password, "deviceToken": verified.get("deviceToken")
    })
    c.eq(body.get("code"), 429, "连续错误密码后触发登录失败限流")
    return c


if __name__ == "__main__":
    run(main)
