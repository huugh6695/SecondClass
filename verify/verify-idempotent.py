"""
验收：幂等（唯一 requestId）（契约 #5 提交工单）

═══ 问题是什么 ═══

用户点了"提交"，网络慢，他没看到反应，又点了一次。结果**两张一模一样的工单**。
学生以为只提了一次，干事收到两份，统计也算两次。

修法：前端每次提交生成一个 UUID 当 `requestId`，后端记住它。
同一个 requestId 再来，就不再建新单。

═══ 两条铁律（实测口径）═══

  ① 同 requestId + **同内容** → 返回上一次的结果（不重复建单）
  ② 同 requestId + **不同内容** → 409 `IDEMPOTENCY_CONFLICT`
     「请求ID已被其他请求占用」

第 ② 条为什么必须报错而不能"就按新的来"？因为同一个 requestId 代表"同一件事"。
内容变了还复用同一个号，说明前端有 bug（或者有人在重放）。这时候如果沿用旧单，
用户看到的是旧内容；如果覆盖，就丢了旧数据。**报错让他重试**是唯一安全的选择。

═══ 一个容易误解的点（实测）═══

`requestId` **不是必填**。不传也能提交成功 —— 因为它是"防重复提交"的兜底，
不是业务字段。老客户端不传也能用，只是享受不到幂等保护。

═══ 用法 ═══

    python verify/verify-idempotent.py 8117
"""

import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import Checker, http, login_required, run, STUDENT_ID, STUDENT_PWD


def main():
    c = Checker("幂等 requestId")
    stu = login_required(STUDENT_ID, STUDENT_PWD)

    # ══ 第 1 组：同 requestId + 同内容 → 只建一张单 ══
    rid = str(uuid.uuid4())
    content = "幂等验收：同一请求重复提交 %s" % uuid.uuid4()

    _, b1 = http("POST", "/api/work-orders", {
        "category": "其他", "content": content, "requestId": rid}, token=stu)
    wo1 = (b1.get("data") or {}).get("workOrderId")
    c.eq(b1.get("code"), 0, "第一次提交成功")

    _, b2 = http("POST", "/api/work-orders", {
        "category": "其他", "content": content, "requestId": rid}, token=stu)
    wo2 = (b2.get("data") or {}).get("workOrderId")

    c.eq(b2.get("code"), 0, "第二次提交（同 requestId + 同内容）也返回成功")
    c.eq(wo2, wo1, "第二次返回的是**同一张**工单号（没有重复建单）")

    # ══ 第 2 组：同 requestId + 不同内容 → 409 ══
    _, b3 = http("POST", "/api/work-orders", {
        "category": "其他", "content": content + "（内容变了）", "requestId": rid}, token=stu)
    c.eq(b3.get("code"), 409, "同 requestId 换内容 → 409（不会悄悄覆盖，也不会建新单）")
    c.contains(b3.get("message"), "请求ID", "409 的提示语指向「请求ID」")
    c.contains(b3.get("message"), "IDEMPOTENCY_CONFLICT",
               "409 的提示语带错误码 IDEMPOTENCY_CONFLICT（前端据此分支处理）")

    # ══ 第 3 组：不同 requestId + 同内容 → 两张单（幂等只管 requestId，不管内容像不像）══
    rid2 = str(uuid.uuid4())
    _, b4 = http("POST", "/api/work-orders", {
        "category": "其他", "content": content, "requestId": rid2}, token=stu)
    wo4 = (b4.get("data") or {}).get("workOrderId")
    c.eq(b4.get("code"), 0, "换个 requestId 提交同样内容 → 成功")
    c.ne(wo4, wo1, "换 requestId 会建**新单**（幂等只认 requestId，重复检测是另一回事）")

    # ══ 第 4 组：不传 requestId 也能提交（兜底不是必填）══
    _, b5 = http("POST", "/api/work-orders", {
        "category": "其他", "content": "幂等验收：不传 requestId %s" % uuid.uuid4()}, token=stu)
    c.eq(b5.get("code"), 0, "不传 requestId 也能提交成功（它是兜底，不是必填）")
    c.is_true(bool((b5.get("data") or {}).get("workOrderId")), "不传 requestId 也拿到了单号")

    # ══ 第 5 组：回复接口也有 requestId ══
    #
    # 回复同样怕手抖点两下 —— 一句话发两遍，学生会看到两条一样的回复。
    wo = wo1
    http("POST", "/api/work-orders/%s/accept" % wo, {}, token=login_required("2023002001"))
    staff = login_required("2023002001")

    rrid = str(uuid.uuid4())
    reply_body = {"content": "幂等验收回复 %s" % uuid.uuid4(), "requestId": rrid}
    _, r1 = http("POST", "/api/work-orders/%s/reply" % wo, reply_body, token=staff)
    c.eq(r1.get("code"), 0, "第一次回复成功")
    mid1 = (r1.get("data") or {}).get("messageId")

    _, r2 = http("POST", "/api/work-orders/%s/reply" % wo, reply_body, token=staff)
    mid2 = (r2.get("data") or {}).get("messageId")
    c.eq(r2.get("code"), 0, "第二次回复（同 requestId + 同内容）成功")
    c.eq(mid2, mid1, "第二次回复返回**同一条**消息 ID（没有重复发消息）")

    _, r3 = http("POST", "/api/work-orders/%s/reply" % wo,
                 {"content": "内容变了", "requestId": rrid}, token=staff)
    c.eq(r3.get("code"), 409, "回复时同 requestId 换内容 → 409")

    # ══ 第 6 组：追问也有 requestId ══
    frid = str(uuid.uuid4())
    fbody = {"content": "幂等验收追问 %s" % uuid.uuid4(), "requestId": frid}
    _, f1 = http("POST", "/api/work-orders/%s/follow-up" % wo, fbody, token=stu)
    _, f2 = http("POST", "/api/work-orders/%s/follow-up" % wo, fbody, token=stu)
    c.eq(f1.get("code"), 0, "第一次追问成功")
    c.eq(f2.get("code"), 0, "第二次追问（同 requestId）成功")
    c.eq((f2.get("data") or {}).get("messageId"), (f1.get("data") or {}).get("messageId"),
         "第二次追问返回同一条消息 ID（没有重复追问）")

    return c


if __name__ == "__main__":
    run(main)
