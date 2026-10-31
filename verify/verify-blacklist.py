"""
验收：Token 黑名单（契约 #3 登出的副作用）

═══ 为什么单独一个脚本 ═══

JWT 是**无状态**的：签出去就收不回来，过期前一直有效。要支持「退出登录立刻失效」，
就必须在服务端存一张名单（本项目用 Redis，key 是整条 Token 的 SHA-256）。
这块最容易出的 bug 有三种，都属于"看起来登出了，其实没登出"：

  ① 名单存了，但拦截器没查 —— 登出后照样能调接口
  ② key 算错了（存的和查的不是同一个东西）—— 名单永远查不中
  ③ 用了"按用户"而不是"按 Token"作 key —— **登出一个设备，其他设备全被踢下线**
     （这是 jti 要解决的问题：每次登录的 Token 都不同）

本脚本第 3 组专门验 ③。

═══ 三种 401 的提示语必须能区分（否则排错时看不出是哪一种）═══

  没带 Token     → "未登录或 Token 格式错误"
  带了但是坏的   → "Token 无效或已过期"
  带了但已登出   → "Token 已失效，请重新登录"

前两种 HTTP 401 由格式校验产生，第三种是**查了黑名单才发现**的。

═══ 用法 ═══

    python verify/verify-blacklist.py 8117
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import Checker, http, login_required, run, STUDENT_ID, STUDENT_PWD


def main():
    c = Checker("Token 黑名单")

    # ══ 第 1 组：每次登录的 Token 都不同（jti 的作用）══
    t1 = login_required(STUDENT_ID, STUDENT_PWD)
    t2 = login_required(STUDENT_ID, STUDENT_PWD)

    c.is_true(bool(t1) and bool(t2), "同一账号连续登录两次都能拿到 Token")
    c.ne(t1, t2, "两次登录的 Token **不相同**（JWT 里带 jti，同秒重登也不会撞）")

    # ══ 第 2 组：登出后 Token 立刻失效 ══
    st, body = http("GET", "/api/auth/me", token=t1)
    c.eq(body.get("code"), 0, "登出前：Token 可用")

    st, body = http("POST", "/api/auth/logout", token=t1)
    c.eq(body.get("code"), 0, "登出成功")
    c.contains(body.get("message"), "退出", "登出提示语含「退出」")

    st, body = http("GET", "/api/auth/me", token=t1)
    c.eq(st, 401, "登出后：HTTP 401")
    c.eq(body.get("code"), 401, "登出后：业务码 401")
    c.contains(body.get("message"), "失效",
               "登出后的提示语是「失效」（区别于「无效」和「未登录」）")

    # ══ 第 3 组：只踢掉"这一条" Token，同账号的其他 Token 不受影响 ══
    #
    # 这是 jti 存在的全部理由。如果这里红了，说明黑名单是按 user_id 存的 ——
    # 那样用户在手机上退出登录，电脑上也被踢了。
    st, body = http("GET", "/api/auth/me", token=t2)
    c.eq(body.get("code"), 0,
         "登出 t1 后，同账号的 t2 **仍然可用**（黑名单按 Token 存，不是按用户）")

    # ══ 第 4 组：黑名单对所有接口生效，不只是 /me ══
    for path in ["/api/auth/me", "/api/work-orders", "/api/notifications/unread-count"]:
        st, body = http("GET", path, token=t1)
        c.eq(st, 401, "已登出的 Token 访问 %s → 401" % path)

    # ══ 第 5 组：重复登出幂等 ══
    st, body = http("POST", "/api/auth/logout", token=t1)
    c.eq(body.get("code"), 0, "重复登出同一 Token → 仍返回成功（幂等，不报错）")

    # ══ 第 6 组：三种 401 的提示语可区分 ══
    _, no_token = http("GET", "/api/auth/me")
    _, bad_token = http("GET", "/api/auth/me", token="bad.token.here")
    _, revoked = http("GET", "/api/auth/me", token=t1)

    msgs = [no_token.get("message"), bad_token.get("message"), revoked.get("message")]
    c.check(len(set(msgs)) == 3,
            "三种 401 的提示语互不相同（排错时能一眼看出是哪一种）",
            "3 种不同提示", msgs)

    # ══ 第 7 组：不跨用户 —— A 的登出不影响 B ══
    staff_token = login_required("2023002001", "123456")
    st, body = http("GET", "/api/auth/me", token=staff_token)
    c.eq(body.get("code"), 0, "学生的登出不影响干事的 Token")

    # 收尾：把手里还活着的 token 主动登出，别在 Redis 里留垃圾
    for tk in (t2, staff_token):
        if tk:
            http("POST", "/api/auth/logout", token=tk)

    return c


if __name__ == "__main__":
    run(main)
