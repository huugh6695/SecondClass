"""
验收：认证 + 用户管理（契约 #1–#4 / #34–#36）

═══ 覆盖什么 ═══

  登录 / 查我 / 改密校验 / 登出以外的认证行为
  管理端用户列表（分页、筛选、pageSize 上限）
  禁用与启用（成对，测完恢复原状）
  角色越权

═══ 几条实测出来的口径（写断言前先跑过一遍）═══

  · 登录失败**一律** code=401 + HTTP 200 —— 不区分"账号不存在"和"密码错"，
    这是故意的（防止拿它枚举学号）。
  · 角色不够（学生调管理端）→ **code=403 但 HTTP 仍是 200**。
    只有"账号被禁用"那种才真的把 HTTP 改成 403（拦截器 setStatus）。
  · pageSize 传 99 会被**压成 50**，不是报错。
  · 改密要求 6-20 位且**同时含字母和数字** ⇒ `123456` 这种纯数字改不回去，
    所以这里**只测非法校验，不测"改密成功"** —— 那条会把环境改坏，跑一次废一次。

═══ 用法 ═══

    python verify/verify-user.py 8117
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login, login_required, qs, run,
                 STUDENT_ID, STUDENT_PWD, STAFF_ID, STAFF_PWD,
                 ADMIN_ID, ADMIN_PWD)


def main():
    c = Checker("认证与用户管理")

    # ══ 第 1 组：三个角色都能登录 ══
    roles = [
        ("学生", STUDENT_ID, STUDENT_PWD, "STUDENT"),
        ("干事", STAFF_ID, STAFF_PWD, "STAFF"),
        ("管理员", ADMIN_ID, ADMIN_PWD, "ADMIN"),
    ]
    tokens = {}
    for label, uid, pwd, role in roles:
        st, body = http("POST", "/api/auth/login", {"userId": uid, "password": pwd})
        data = body.get("data") or {}
        c.eq(st, 200, "%s登录：HTTP 200（本项目约定业务失败也是 200）" % label)
        c.eq(body.get("code"), 0, "%s登录成功" % label)
        c.contains(body.get("message"), "成功", "%s登录提示语含「成功」" % label)
        c.is_true(bool(data.get("token")), "%s拿到 token" % label)
        c.eq(data.get("userId"), uid, "%s返回的 userId 正确" % label)
        c.eq(data.get("role"), role, "%s返回的 role 正确" % label)
        c.is_true(isinstance(data.get("needChangePassword"), bool),
                  "%s的 needChangePassword 是布尔值" % label)
        c.is_true(bool(data.get("userName")), "%s返回了 userName" % label)
        tokens[role] = data.get("token")

    # ══ 第 2 组：登录失败的口径 ══
    bad_cases = [
        ({"userId": STUDENT_ID, "password": "错的密码"}, "密码错"),
        ({"userId": "9999999999", "password": "123456"}, "账号不存在"),
        ({"userId": STUDENT_ID}, "缺 password"),
        ({"password": "123456"}, "缺 userId"),
        ({}, "空 body"),
    ]
    for payload, label in bad_cases:
        st, body = http("POST", "/api/auth/login", payload)
        c.eq(st, 200, "%s：HTTP 仍为 200" % label)
        c.eq(body.get("code"), 401, "%s：业务码 401" % label)
        c.is_none(body.get("data"), "%s：不返回 data" % label)

    # 防枚举：账号不存在与密码错的提示语必须一致
    _, b1 = http("POST", "/api/auth/login", {"userId": STUDENT_ID, "password": "错的密码"})
    _, b2 = http("POST", "/api/auth/login", {"userId": "9999999999", "password": "123456"})
    c.eq(b1.get("message"), b2.get("message"),
         "账号不存在与密码错的提示语一致（防止拿登录接口枚举学号）")

    # ══ 第 3 组：GET /api/auth/me ══
    for label, uid, role in [("学生", STUDENT_ID, "STUDENT"),
                             ("干事", STAFF_ID, "STAFF"),
                             ("管理员", ADMIN_ID, "ADMIN")]:
        st, body = http("GET", "/api/auth/me", token=tokens[role])
        data = body.get("data") or {}
        c.eq(st, 200, "查我（%s）：HTTP 200" % label)
        c.eq(body.get("code"), 0, "查我（%s）成功" % label)
        c.eq(data.get("userId"), uid, "查我（%s）：userId 正确" % label)
        c.eq(data.get("role"), role, "查我（%s）：role 正确" % label)
        c.is_true(bool(data.get("userName")), "查我（%s）：有 userName" % label)
        c.is_none(data.get("openId"), "查我（%s）：openId 当前版本恒为 null" % label)
        c.eq(data.get("subscribeStatus"), "UNSUBSCRIBED",
             "查我（%s）：subscribeStatus 当前版本恒为 UNSUBSCRIBED" % label)

    # ══ 第 4 组：未认证的两种 401（提示语不同，HTTP 都是 401）══
    st, body = http("GET", "/api/auth/me")
    c.eq(st, 401, "不带 Token 调 /me：HTTP 401")
    c.eq(body.get("code"), 401, "不带 Token 调 /me：业务码 401")
    c.contains(body.get("message"), "未登录", "不带 Token：提示语指向「未登录」")

    st, body = http("GET", "/api/auth/me", token="bad.token.here")
    c.eq(st, 401, "坏 Token 调 /me：HTTP 401")
    c.eq(body.get("code"), 401, "坏 Token 调 /me：业务码 401")
    c.contains(body.get("message"), "无效", "坏 Token：提示语指向「无效」（与未登录区分开）")

    # ══ 第 5 组：改密校验（只测拒绝，不改环境）══
    stu = login_required(STUDENT_ID, STUDENT_PWD)
    pwd_cases = [
        ("123456", "123", "新密码 3 位"),
        ("123456", "abcdefgh", "新密码纯字母"),
        ("123456", "12345678", "新密码纯数字"),
        ("123456", "abcdefghijklmnopqrstu", "新密码超过 20 位"),
    ]
    for old, new, label in pwd_cases:
        st, body = http("PUT", "/api/auth/password",
                        {"oldPassword": old, "newPassword": new}, token=stu)
        c.eq(body.get("code"), 400, "%s → 被拒（400）" % label)
        c.contains(body.get("message"), "6-20", "%s：提示语说明了 6-20 位要求" % label)

    st, body = http("PUT", "/api/auth/password",
                    {"oldPassword": "肯定不对的旧密码", "newPassword": "abc12345"},
                    token=stu)
    c.eq(body.get("code"), 400, "旧密码错 → 被拒（400）")
    c.contains(body.get("message"), "原密码", "旧密码错：提示语指向「原密码」")

    # 改密接口需要 Token
    st, body = http("PUT", "/api/auth/password",
                    {"oldPassword": STUDENT_PWD, "newPassword": "abc12345"})
    c.eq(st, 401, "不带 Token 改密 → HTTP 401")

    # ══ 第 6 组：管理端用户列表 ══
    adm = login_required(ADMIN_ID, ADMIN_PWD)

    st, body = http("GET", "/api/admin/users", token=adm)
    data = body.get("data") or {}
    c.eq(body.get("code"), 0, "用户列表：管理员可访问")
    c.eq(data.get("page"), 1, "默认页码为 1")
    c.eq(data.get("pageSize"), 20, "默认每页 20 条")
    c.gt(data.get("total"), 2, "总数至少 3（本地库就这 3 个账号）")
    c.is_true(isinstance(data.get("list"), list), "list 是数组")

    st, body = http("GET", "/api/admin/users" + qs(page=1, pageSize=50), token=adm)
    d = body.get("data") or {}
    c.eq(d.get("pageSize"), 50, "pageSize=50 原样接受（上限值）")

    st, body = http("GET", "/api/admin/users" + qs(pageSize=99), token=adm)
    d = body.get("data") or {}
    c.eq(d.get("pageSize"), 50, "pageSize=99 被压成 50（上限生效，不报错）")

    st, body = http("GET", "/api/admin/users" + qs(userType="STAFF"), token=adm)
    d = body.get("data") or {}
    roles = set(x.get("userType") or x.get("role") for x in (d.get("list") or []))
    c.check(roles and roles == {"STAFF"},
            "userType=STAFF 只返回干事", "{'STAFF'}", roles)

    st, body = http("GET", "/api/admin/users" + qs(keyword="测试"), token=adm)
    d = body.get("data") or {}
    c.gt(d.get("total"), 0, "keyword=测试 能搜到账号")

    for label, role in [("学生", "STUDENT"), ("干事", "STAFF")]:
        st, body = http("GET", "/api/admin/users", token=tokens[role])
        c.eq(body.get("code"), 403, "%s调用户列表 → 403（不是管理员）" % label)
        c.eq(st, 200, "%s越权时 HTTP 仍是 200（业务码表达失败）" % label)

    # ══ 第 7 组：禁用 / 启用（成对，结束时必须恢复）══
    try:
        st, body = http("PUT", "/api/admin/users/%s/status" % STUDENT_ID,
                        {"status": "DISABLED"}, token=adm)
        c.eq(body.get("code"), 0, "管理员禁用学生成功")
        c.contains(body.get("message"), "禁用", "禁用提示语含「禁用」")

        st, body = http("GET", "/api/auth/me", token=tokens["STUDENT"])
        c.eq(st, 403, "被禁用后：HTTP 403（拦截器真的改了状态码）")
        c.eq(body.get("code"), 403, "被禁用后：业务码 403")

        st, body = http("POST", "/api/auth/login",
                        {"userId": STUDENT_ID, "password": STUDENT_PWD})
        c.eq(body.get("code"), 403, "被禁用后无法再登录（403，不是 401）")

        st, body = http("PUT", "/api/admin/users/%s/status" % STUDENT_ID,
                        {"status": "ACTIVE"}, token=adm)
        c.eq(body.get("code"), 0, "管理员启用学生成功")

        st, body = http("GET", "/api/auth/me", token=tokens["STUDENT"])
        c.eq(body.get("code"), 0, "启用后学生的旧 Token 立刻恢复可用（拦截器每次查库）")
    finally:
        # 不管上面哪条断言炸了，都必须把学生恢复成 ACTIVE —— 否则后面所有脚本全挂
        http("PUT", "/api/admin/users/%s/status" % STUDENT_ID,
             {"status": "ACTIVE"}, token=adm)

    st, body = http("PUT", "/api/admin/users/%s/status" % ADMIN_ID,
                    {"status": "DISABLED"}, token=adm)
    c.eq(body.get("code"), 400, "不能禁用自己（会把自己锁在系统外）")

    st, body = http("PUT", "/api/admin/users/%s/status" % STUDENT_ID,
                    {"status": "XXX"}, token=adm)
    c.eq(body.get("code"), 400, "非法状态值 → 400")

    st, body = http("PUT", "/api/admin/users/9999999999/status",
                    {"status": "DISABLED"}, token=adm)
    c.eq(body.get("code"), 404, "改不存在账号的状态 → 404")

    # ══ 第 8 组：重置密码的权限门（不触发真正的重置）══
    for label, role in [("学生", "STUDENT"), ("干事", "STAFF")]:
        st, body = http("POST", "/api/admin/users/%s/reset-password" % STUDENT_ID,
                        token=tokens[role])
        c.eq(body.get("code"), 403, "%s重置密码 → 403" % label)

    st, body = http("POST", "/api/admin/users/9999999999/reset-password", token=adm)
    c.eq(body.get("code"), 404, "重置不存在账号的密码 → 404")

    return c


if __name__ == "__main__":
    run(main)
