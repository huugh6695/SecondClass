"""
验收：工单合并（契约 #14）

═══ 合并是干什么的 ═══

同一个学生（或几个学生）针对同一件事提了多张单。干事不想回三遍，
就把重复的单**挂到一张主单上**：子单变 `MERGED`，只读；所有对话留在主单。

为什么不是直接删掉重复单：
  · 学生提过的东西不能凭空消失（他会以为系统吞了）；
  · 统计口径要能对上（删了就少一条，合并能标记"这是重复的"）；
  · 子单保留 `mainOrderId`，学生点进去会被引导到主单。

═══ 实测口径 ═══

  · 合并后子单 status = `MERGED`，`mainOrderId` 指向主单
  · 主单时间线会多一条 SYSTEM 消息「工单 XXX 已合并至此」
  · 重复合并同一子单 → 409「该工单已关闭或已合并」（WO_STATUS_INVALID）
  · 自己合并自己 → 400「不能把工单合并到自己」（WO_CANNOT_MERGE_SELF）
  · `version` 必传，缺了 → 400「缺少 version 参数」
  · 已合并的子单变成只读：追问/回复都会被拦

═══ 用法 ═══

    python verify/verify-merge.py 8117
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, new_order, get_order, unique, run,
                 STUDENT_ID, STUDENT_PWD, STAFF_ID, STAFF_PWD, ADMIN_ID, ADMIN_PWD)


def main():
    c = Checker("工单合并")
    stu = login_required(STUDENT_ID, STUDENT_PWD)
    staff = login_required(STAFF_ID, STAFF_PWD)
    adm = login_required(ADMIN_ID, ADMIN_PWD)

    # ══ 第 1 组：正常合并 ══
    main_wo = new_order(stu, content="合并验收-主单 %s" % unique())
    sub_wo = new_order(stu, content="合并验收-子单 %s" % unique())

    d_sub = get_order(staff, sub_wo)
    c.eq(d_sub.get("status"), "PENDING", "子单初始是 PENDING")

    st, body = http("POST", "/api/work-orders/%s/merge" % sub_wo,
                    {"mainOrderId": main_wo, "version": d_sub.get("version")},
                    token=staff)
    c.eq(body.get("code"), 0, "干事合并成功")
    c.contains(body.get("message"), "合并", "提示语含「合并」")

    data = body.get("data") or {}
    c.eq(data.get("workOrderId"), sub_wo, "返回的是子单号")
    c.eq(data.get("mainOrderId"), main_wo, "返回的主单号正确")
    c.eq(data.get("status"), "MERGED", "返回状态 MERGED")
    c.eq(data.get("statusLabel"), "已合并", "MERGED 的中文标签是「已合并」")
    c.is_true(bool(data.get("mergeId")), "返回了 mergeId（合并关系表的主键）")
    c.is_true(bool(data.get("mergeTime")), "返回了合并时间")

    # ══ 第 2 组：子单变成 MERGED 且指向主单 ══
    d2 = get_order(stu, sub_wo)
    c.eq(d2.get("status"), "MERGED", "子单详情状态 MERGED")
    c.eq(d2.get("mainOrderId"), main_wo, "子单的 mainOrderId 指向主单（前端据此跳转）")

    # ══ 第 3 组：主单时间线多了系统消息 ══
    d_main = get_order(stu, main_wo)
    sys_msgs = [m for m in (d_main.get("messages") or []) if m.get("msgType") == "SYSTEM"]
    c.is_true(len(sys_msgs) >= 1, "主单时间线有 SYSTEM 消息（通知「已合并」这件事）")

    merge_msg = [m for m in sys_msgs if sub_wo in (m.get("content") or "")]
    c.is_true(len(merge_msg) >= 1,
              "系统消息里提到了子单号 %s" % sub_wo)

    c.eq(d_main.get("status"), "PENDING", "主单状态不受影响（还是 PENDING）")

    # ══ 第 4 组：子单变只读 ══
    st, body = http("POST", "/api/work-orders/%s/follow-up" % sub_wo,
                    {"content": "已合并还能追问吗", "requestId": unique("rid")}, token=stu)
    c.check(body.get("code") in (403, 409),
            "已合并的子单不能追问（403 或 409）", "403/409", body.get("code"))

    st, body = http("POST", "/api/work-orders/%s/reply" % sub_wo,
                    {"content": "已合并还能回复吗", "requestId": unique("rid")}, token=staff)
    c.check(body.get("code") in (403, 409),
            "已合并的子单不能回复（403 或 409）", "403/409", body.get("code"))

    # ══ 第 5 组：重复合并 —— 干事撞权限门，管理员撞状态门 ══
    #
    # 这两条顺序很关键（requests.http 的 G7/G8 就是专门验它的）：
    #   干事 → 403：子单已 MERGED，干事对它没有处置权 ⇒ **权限门先拦**
    #   管理员 → 409：权限够了，再往下一层才撞到「已合并」⇒ **状态门**
    # 如果两个都返回 403，说明状态门被权限门"吃掉"了，业务错误码就没意义了。
    st, body = http("POST", "/api/work-orders/%s/merge" % sub_wo,
                    {"mainOrderId": main_wo, "version": 0}, token=staff)
    c.eq(body.get("code"), 403, "干事重复合并 → 403（权限门先拦）")
    c.contains(body.get("message"), "负责", "提示语指向「只能合并自己负责的工单」")

    st, body = http("POST", "/api/work-orders/%s/merge" % sub_wo,
                    {"mainOrderId": main_wo, "version": 0}, token=adm)
    c.eq(body.get("code"), 409, "管理员重复合并 → 409（权限够了，撞状态门）")
    c.contains(body.get("message"), "WO_STATUS_INVALID", "错误码是 WO_STATUS_INVALID")

    # ══ 第 6 组：自己合并自己 ══
    wo3 = new_order(stu, content="合并验收-自己 %s" % unique())
    d3 = get_order(staff, wo3)
    st, body = http("POST", "/api/work-orders/%s/merge" % wo3,
                    {"mainOrderId": wo3, "version": d3.get("version")}, token=staff)
    c.eq(body.get("code"), 400, "自己合并自己 → 400")
    c.contains(body.get("message"), "WO_CANNOT_MERGE_SELF", "错误码是 WO_CANNOT_MERGE_SELF")

    # ══ 第 7 组：参数缺失 ══
    wo4 = new_order(stu, content="合并验收-参数 %s" % unique())
    st, body = http("POST", "/api/work-orders/%s/merge" % wo4, {"version": 0}, token=staff)
    c.eq(body.get("code"), 400, "缺 mainOrderId → 400")
    c.contains(body.get("message"), "mainOrderId", "提示语点名 mainOrderId")

    st, body = http("POST", "/api/work-orders/%s/merge" % wo4,
                    {"mainOrderId": main_wo}, token=staff)
    c.eq(body.get("code"), 400, "缺 version → 400")
    c.contains(body.get("message"), "version", "提示语点名 version")

    # ══ 第 8 组：不存在的主单 ══
    st, body = http("POST", "/api/work-orders/%s/merge" % wo4,
                    {"mainOrderId": "WO00000000000000", "version": 0}, token=staff)
    c.eq(body.get("code"), 404, "主单不存在 → 404")

    # ══ 第 9 组：权限 ══
    wo5 = new_order(stu, content="合并验收-权限 %s" % unique())
    st, body = http("POST", "/api/work-orders/%s/merge" % wo5,
                    {"mainOrderId": main_wo, "version": 0}, token=stu)
    c.eq(body.get("code"), 403, "学生合并 → 403（只有干事/管理员能合并）")

    st, body = http("POST", "/api/work-orders/%s/merge" % wo5,
                    {"mainOrderId": main_wo, "version": 0})
    c.eq(st, 401, "不带 Token 合并 → HTTP 401")

    return c


if __name__ == "__main__":
    run(main)
