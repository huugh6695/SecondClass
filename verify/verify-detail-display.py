"""
验收：工单详情与状态机（契约 #7 详情 / #8–#15 状态流转）

═══ 状态机（实测，改代码前必须记住这个图）═══

    PENDING（待接单）
       │ 干事接单（accept）
       ↓
    PROCESSING（处理中）
       │ 干事回复（reply）
       ↓
    REPLIED（已回复）
       ├─ 学生确认解决（confirm）──→ CLOSED（已关闭）★ 终点
       ├─ 学生追问（follow-up）──→ 回到 PROCESSING
       ├─ 干事关闭（close）──────→ CLOSED
       └─ 干事退回（return）─────→ 回到 PENDING

    PENDING ──管理员强制关闭（admin-close）──→ CLOSED
    任意未完结单 ──合并（merge）──→ MERGED（已合并）★ 终点

═══ 两个最容易搞错的点 ═══

  ① **追问会把状态从 REPLIED 拉回 PROCESSING** ⇒ 追问之后**不能再 confirm**
     （会报 409「只有已回复的工单才能确认解决」）。
     所以"回复 → 追问 → 确认"这个顺序走不通，必须"回复 → 确认"。
  ② **学生不能调 close**，只能调 confirm。close 是干事/管理员用的
     （不传 remark 也行，但学生会先撞 403「仅负责人或管理员可完结工单」）。

═══ 用法 ═══

    python verify/verify-detail-display.py 8117
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, new_order, get_order, unique, run,
                 STUDENT_ID, STUDENT_PWD, STAFF_ID, STAFF_PWD, ADMIN_ID, ADMIN_PWD)

# 详情返回的字段（缺一个前端就可能显示 undefined）
DETAIL_FIELDS = [
    "workOrderId", "userId", "userName", "category", "content", "status", "statusLabel",
    "handlerId", "handlerName", "mainOrderId", "sourceOrderId", "isFlagged",
    "duplicateOfOrderId", "version", "createTime", "acceptTime", "replyTime", "finishTime",
    "messages", "attachments", "statusHistory",
]


def main():
    c = Checker("工单详情与状态机")
    stu = login_required(STUDENT_ID, STUDENT_PWD)
    staff = login_required(STAFF_ID, STAFF_PWD)
    adm = login_required(ADMIN_ID, ADMIN_PWD)

    # ══ 第 1 组：新单的详情形状 ══
    wo = new_order(stu, category="学时录入", content="详情验收 %s" % unique())
    d = get_order(stu, wo)

    for f in DETAIL_FIELDS:
        c.is_true(f in d, "详情返回 %s 字段" % f)

    c.eq(d.get("workOrderId"), wo, "workOrderId 与提交返回的一致")
    c.eq(d.get("userId"), STUDENT_ID, "userId 是提交人")
    c.eq(d.get("status"), "PENDING", "新单状态是 PENDING")
    c.eq(d.get("statusLabel"), "待接单", "PENDING 的中文标签是「待接单」")
    c.eq(d.get("category"), "学时录入", "分类原样返回")
    c.eq(d.get("version"), 0, "新单 version 从 0 开始")
    c.is_none(d.get("handlerId"), "还没接单，handlerId 为 null")
    c.is_none(d.get("acceptTime"), "还没接单，acceptTime 为 null")
    c.is_none(d.get("replyTime"), "还没回复，replyTime 为 null")
    c.is_none(d.get("finishTime"), "还没完结，finishTime 为 null")
    c.is_none(d.get("mainOrderId"), "不是合并来的，mainOrderId 为 null")
    c.is_none(d.get("sourceOrderId"), "不是再次咨询建的，sourceOrderId 为 null")

    # ══ 第 2 组：时间线（messages）══
    msgs = d.get("messages") or []
    c.eq(len(msgs), 1, "新单时间线只有 1 条（首次提问）")

    if msgs:
        m0 = msgs[0]
        c.eq(m0.get("msgType"), "QUESTION", "首条消息类型是 QUESTION")
        c.eq(m0.get("senderType"), "STUDENT", "首条消息的发送方是 STUDENT")
        c.eq(m0.get("senderId"), STUDENT_ID, "首条消息发送人是提交人")
        c.is_true(bool(m0.get("messageId")), "消息有 messageId")
        c.is_true("attachments" in m0, "消息里有 attachments 字段（没附件是空数组）")
        c.is_true(bool(m0.get("createTime")), "消息有 createTime")

    # ══ 第 3 组：状态历史（statusHistory）══
    hist = d.get("statusHistory") or []
    c.eq(len(hist), 1, "新单状态史只有 1 条（SUBMIT）")

    if hist:
        h0 = hist[0]
        c.eq(h0.get("action"), "SUBMIT", "首条状态史的动作是 SUBMIT")
        c.eq(h0.get("afterStatus"), "PENDING", "SUBMIT 之后状态是 PENDING")
        c.is_none(h0.get("beforeStatus"), "SUBMIT 之前没有状态（null）")
        c.is_true(bool(h0.get("operatorName")), "状态史记了操作人姓名")

    # ══ 第 4 组：接单 ══
    before_version = d.get("version")
    st, b = http("POST", "/api/work-orders/%s/accept" % wo, {}, token=staff)
    c.eq(b.get("code"), 0, "干事接单成功")
    c.eq((b.get("data") or {}).get("status"), "PROCESSING", "接单返回状态 PROCESSING")

    d2 = get_order(staff, wo)
    c.eq(d2.get("status"), "PROCESSING", "接单后详情状态 PROCESSING")
    c.eq(d2.get("statusLabel"), "处理中", "PROCESSING 的中文标签是「处理中」")
    c.eq(d2.get("handlerId"), STAFF_ID, "handlerId 记为接单干事")
    c.is_true(bool(d2.get("handlerName")), "handlerName 有值")
    c.is_true(bool(d2.get("acceptTime")), "acceptTime 有值了")
    c.gt(d2.get("version"), before_version, "接单后 version 递增")
    # 接单会**自动插一条 SYSTEM 消息**（「测试干事 已接单」），所以时间线从 1 变 2
    c.eq(len(d2.get("messages") or []), 2,
         "接单后时间线变成 2 条（提问 + 系统「已接单」）")
    sys_msgs = [m for m in (d2.get("messages") or []) if m.get("msgType") == "SYSTEM"]
    c.eq(len(sys_msgs), 1, "其中有 1 条 SYSTEM 消息")
    if sys_msgs:
        c.eq(sys_msgs[0].get("senderType"), "SYSTEM", "系统消息的 senderType 是 SYSTEM")

    # ══ 第 5 组：重复接单被拒 ══
    st, b = http("POST", "/api/work-orders/%s/accept" % wo, {}, token=staff)
    c.eq(b.get("code"), 409, "重复接单 → 409")
    c.contains(b.get("message"), "受理", "重复接单提示语指向「受理」")
    # ⚠️ 别写 "已受理" —— 实际文案是「该工单已被**被**受理或状态不允许受理」，
    #    中间夹了个「被」字，"已受理" 这个子串根本不存在。文本断言要盯实际字符串。

    # ══ 第 6 组：回复 ══
    st, b = http("POST", "/api/work-orders/%s/reply" % wo,
                 {"content": "已为你核对，稍后补录。", "requestId": unique("rid")}, token=staff)
    c.eq(b.get("code"), 0, "回复成功")
    c.eq((b.get("data") or {}).get("status"), "REPLIED", "回复返回状态 REPLIED")
    c.is_true(bool((b.get("data") or {}).get("messageId")), "回复返回 messageId")

    d3 = get_order(stu, wo)
    c.eq(d3.get("status"), "REPLIED", "回复后详情状态 REPLIED")
    c.eq(d3.get("statusLabel"), "已回复", "REPLIED 的中文标签是「已回复」")
    c.is_true(bool(d3.get("replyTime")), "replyTime 有值了")
    # 提问(1) + 系统「已接单」(1) + 回复(1) = 3
    c.eq(len(d3.get("messages") or []), 3,
         "回复后时间线 3 条（提问 + 系统已接单 + 回复）")

    reply_msgs = [m for m in (d3.get("messages") or []) if m.get("msgType") == "REPLY"]
    c.eq(len(reply_msgs), 1, "有 1 条 REPLY 类型消息")
    if reply_msgs:
        c.eq(reply_msgs[0].get("senderType"), "STAFF", "回复消息的发送方是 STAFF")

    # ══ 第 7 组：追问会把状态拉回 PROCESSING（关键！）══
    st, b = http("POST", "/api/work-orders/%s/follow-up" % wo,
                 {"content": "还想确认一下具体时间。", "requestId": unique("rid")}, token=stu)
    c.eq(b.get("code"), 0, "学生追问成功")

    d4 = get_order(stu, wo)
    c.eq(d4.get("status"), "PROCESSING",
         "追问后状态**回到 PROCESSING**（不是 REPLIED）—— 所以要等干事再回复才能确认")

    # 追问后立即确认 → 必须被拒
    st, b = http("POST", "/api/work-orders/%s/confirm" % wo,
                 {"version": d4.get("version")}, token=stu)
    c.eq(b.get("code"), 409, "PROCESSING 状态下确认 → 409（状态门生效）")
    c.contains(b.get("message"), "已回复", "提示语说明了「只有已回复的工单才能确认」")

    # ══ 第 8 组：干事再回复 → 回到 REPLIED ══
    http("POST", "/api/work-orders/%s/reply" % wo,
         {"content": "补录会在 3 个工作日内生效。", "requestId": unique("rid")}, token=staff)
    d5 = get_order(stu, wo)
    c.eq(d5.get("status"), "REPLIED", "干事再回复后状态回到 REPLIED")

    # ══ 第 9 组：版本冲突 ══
    st, b = http("POST", "/api/work-orders/%s/confirm" % wo, {"version": 999}, token=stu)
    c.eq(b.get("code"), 409, "版本号不对 → 409（乐观锁生效）")
    c.contains(b.get("message"), "版本", "版本冲突的提示语指向「版本」")

    # ══ 第 10 组：确认解决 ══
    st, b = http("POST", "/api/work-orders/%s/confirm" % wo,
                 {"version": d5.get("version")}, token=stu)
    c.eq(b.get("code"), 0, "学生确认解决成功")
    c.eq((b.get("data") or {}).get("status"), "CLOSED", "确认后状态 CLOSED")

    d6 = get_order(stu, wo)
    c.eq(d6.get("status"), "CLOSED", "详情状态 CLOSED")
    c.eq(d6.get("statusLabel"), "已关闭", "CLOSED 的中文标签是「已关闭」")
    c.is_true(bool(d6.get("finishTime")), "finishTime 有值了（终点）")

    # ══ 第 11 组：终态不可再动 ══
    st, b = http("POST", "/api/work-orders/%s/reply" % wo,
                 {"content": "已关闭还想回复", "requestId": unique("rid")}, token=staff)
    c.check(b.get("code") in (403, 409),
            "已关闭的单不能再回复（403 或 409 都算，拦住了就行）", "403/409", b.get("code"))

    # ══ 第 12 组：管理员强制关闭（PENDING 单专享）══
    wo2 = new_order(stu, content="管理员关闭验收 %s" % unique())
    d7 = get_order(adm, wo2)
    st, b = http("POST", "/api/work-orders/%s/admin-close" % wo2,
                 {"version": d7.get("version")}, token=adm)
    c.eq(b.get("code"), 400, "admin-close 不填原因 → 400")
    c.contains(b.get("message"), "原因", "提示语要求填写关闭原因")

    st, b = http("POST", "/api/work-orders/%s/admin-close" % wo2,
                 {"version": d7.get("version"), "remark": "长期无人处理，管理员关闭"},
                 token=adm)
    c.eq(b.get("code"), 0, "admin-close 填了原因 → 成功")
    c.eq(get_order(adm, wo2).get("status"), "CLOSED", "强制关闭后状态 CLOSED")

    st, b = http("POST", "/api/work-orders/%s/admin-close" % wo2,
                 {"version": 0, "remark": "再关一次"}, token=adm)
    c.eq(b.get("code"), 409, "重复 admin-close → 409（只能关待接单的）")

    # ══ 第 13 组：退回补充材料 ══
    wo3 = new_order(stu, content="退回验收 %s" % unique())
    http("POST", "/api/work-orders/%s/accept" % wo3, {}, token=staff)
    d8 = get_order(staff, wo3)

    st, b = http("POST", "/api/work-orders/%s/return" % wo3,
                 {"remark": "请补充活动截图"}, token=staff)
    c.eq(b.get("code"), 400, "return 不传 version → 400")
    c.contains(b.get("message"), "version", "提示语要求 version")

    st, b = http("POST", "/api/work-orders/%s/return" % wo3,
                 {"version": d8.get("version"), "remark": "请补充活动截图"}, token=staff)
    c.eq(b.get("code"), 0, "退回成功")
    c.eq(get_order(staff, wo3).get("status"), "PENDING",
         "退回后状态回到 PENDING（等学生补材料）")

    # ══ 第 14 组：不存在 / 越权 ══
    st, b = http("GET", "/api/work-orders/WO00000000000000", token=stu)
    c.eq(b.get("code"), 404, "查不存在的工单 → 404")

    st, b = http("POST", "/api/work-orders/WO00000000000000/accept", {}, token=staff)
    c.eq(b.get("code"), 404, "受理不存在的工单 → 404")

    wo4 = new_order(stu, content="越权验收 %s" % unique())
    st, b = http("POST", "/api/work-orders/%s/accept" % wo4, {}, token=stu)
    c.eq(b.get("code"), 403, "学生自己接单 → 403")

    st, b = http("POST", "/api/work-orders/%s/reply" % wo4,
                 {"content": "没接单就回复", "requestId": unique("rid")}, token=staff)
    c.eq(b.get("code"), 403, "非当前处理人回复 → 403（先撞权限门）")

    return c


if __name__ == "__main__":
    run(main)
