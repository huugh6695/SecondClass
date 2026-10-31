"""
验收：状态流水与操作日志（契约 #42–#43）

═══ 两张表，两个用途 ═══

  · `t_status_log`（状态流水）—— **业务视角**：这张单经历了什么。
    详情页的时间线就是它。字段：action / beforeStatus / afterStatus / remark。
  · `t_operation_log`（操作日志）—— **审计视角**：谁在什么时候干了什么。
    查问题时用。字段：operationType / targetType / targetId / ipAddress / userName。

为什么不能合成一张：
  状态流水是给学生看的（"我的单被退回过，理由是什么"），
  操作日志是给管理员/审计看的（"谁把这个账号禁用了"）。
  两者可见范围不同，混在一起会泄露内部操作。

═══ 🔴 target_id 的口径（踩过坑）═══

`t_operation_log.target_id` 是 `varchar(64)`，**只放业务主键字符串**：

  · 工单 → `WO202610280001`
  · 敏感词 → `955`（整数主键也要 `String.valueOf`）
  · 批量操作 → 哨兵值 `"BATCH"`

**人读的文字放 `operation_content`**，不要塞进 target_id。
塞进去的话，按 target_id 查"这个对象的所有操作"就查不到了。

═══ 用法 ═══

    python verify/verify-admin-logs.py 8117
"""

import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, new_order, qs, unique, run,
                 STUDENT_ID, STUDENT_PWD, STAFF_ID, STAFF_PWD, ADMIN_ID, ADMIN_PWD)

STATUS_LOG_FIELDS = ["logId", "workOrderId", "action", "beforeStatus",
                     "afterStatus", "operatorId", "operatorName", "operatorRole",
                     "operationTime", "remark"]

OP_LOG_FIELDS = ["logId", "userId", "userName", "userRole", "operationType",
                 "targetType", "targetId", "operationContent", "ipAddress", "operationTime"]


def main():
    c = Checker("状态流水与操作日志")
    adm = login_required(ADMIN_ID, ADMIN_PWD)
    stu = login_required(STUDENT_ID, STUDENT_PWD)
    staff = login_required(STAFF_ID, STAFF_PWD)

    # 造一张走完完整流程的单：SUBMIT → ACCEPT → REPLY → CONFIRM
    wo = new_order(stu, content="日志验收 %s" % unique())
    http("POST", "/api/work-orders/%s/accept" % wo, {}, token=staff)
    http("POST", "/api/work-orders/%s/reply" % wo,
         {"content": "已处理", "requestId": unique("rid")}, token=staff)
    d = http("GET", "/api/work-orders/%s" % wo, token=stu)[1].get("data") or {}
    http("POST", "/api/work-orders/%s/confirm" % wo,
         {"version": d.get("version")}, token=stu)

    # ══ 第 1 组：按单号查状态流水 ══
    st, body = http("GET", "/api/admin/logs/status-logs" + qs(workOrderId=wo), token=adm)
    c.eq(st, 200, "查状态流水：HTTP 200")
    c.eq(body.get("code"), 0, "查询成功")

    data = body.get("data") or {}
    for f in ["total", "page", "pageSize", "list"]:
        c.is_true(f in data, "分页结构包含 %s" % f)

    rows = data.get("list") or []
    c.eq(data.get("total"), len(rows), "按单号查时 total 等于实际条数（就一张单的流水）")

    # 完整流程应该留下 4 条；接单/回复中的系统动作可能多几条，所以用"至少"
    c.check(len(rows) >= 4,
            "这张单至少有 4 条流水（提交/接单/回复/确认）",
            ">= 4", len(rows))

    if rows:
        for f in STATUS_LOG_FIELDS:
            c.is_true(f in rows[0], "状态流水包含 %s 字段" % f)

        actions = [r.get("action") for r in rows]
        for act in ["SUBMIT", "ACCEPT", "REPLY", "CONFIRM"]:
            c.contains(actions, act, "流水里有 %s 这个动作" % act)

        # 每条的状态迁移要能对上
        c.is_true(all(r.get("workOrderId") == wo for r in rows),
                  "每一行都属于这张单（workOrderId 过滤有效）")
        c.is_true(all(r.get("afterStatus") for r in rows),
                  "每一行都有 afterStatus")

        # SUBMIT 那条：before 为空、after 是 PENDING
        sub = [r for r in rows if r.get("action") == "SUBMIT"]
        if sub:
            c.is_none(sub[0].get("beforeStatus"), "SUBMIT 的 beforeStatus 为 null（第一刀）")
            c.eq(sub[0].get("afterStatus"), "PENDING", "SUBMIT 之后是 PENDING")

        # CONFIRM 那条：after 应该是 CLOSED
        con = [r for r in rows if r.get("action") == "CONFIRM"]
        if con:
            c.eq(con[0].get("afterStatus"), "CLOSED", "CONFIRM 之后是 CLOSED")
            c.eq(con[0].get("beforeStatus"), "REPLIED", "CONFIRM 之前是 REPLIED")

        c.is_true(all(r.get("operatorName") for r in rows), "每行都有操作人姓名")
        c.is_true(all(r.get("operatorRole") in ("STUDENT", "STAFF", "ADMIN")
                      for r in rows), "每行的操作人角色都是合法枚举")

    # ══ 第 2 组：状态流水分页 ══
    st, body = http("GET", "/api/admin/logs/status-logs" + qs(pageSize=3), token=adm)
    d2 = body.get("data") or {}
    c.eq(d2.get("pageSize"), 3, "pageSize=3 生效")
    c.eq(len(d2.get("list") or []), 3, "返回 3 条")
    c.gt(d2.get("total"), 3, "总数远大于一页（本地有几千条流水）")

    # 两页不能重复
    _, b_p1 = http("GET", "/api/admin/logs/status-logs" + qs(page=1, pageSize=5), token=adm)
    _, b_p2 = http("GET", "/api/admin/logs/status-logs" + qs(page=2, pageSize=5), token=adm)
    ids1 = [x.get("logId") for x in ((b_p1.get("data") or {}).get("list") or [])]
    ids2 = [x.get("logId") for x in ((b_p2.get("data") or {}).get("list") or [])]
    c.eq(len(set(ids1) & set(ids2)), 0, "状态流水第 1 页与第 2 页无重复")

    # ══ 第 3 组：操作日志 ══
    st, body = http("GET", "/api/admin/logs/operation-logs" + qs(pageSize=5), token=adm)
    c.eq(body.get("code"), 0, "查操作日志成功")
    d3 = body.get("data") or {}
    rows3 = d3.get("list") or []
    c.gt(len(rows3), 0, "操作日志有数据")

    if rows3:
        for f in OP_LOG_FIELDS:
            c.is_true(f in rows3[0], "操作日志包含 %s 字段" % f)
        c.is_true(bool(rows3[0].get("ipAddress")), "记了 IP 地址（审计需要）")
        c.is_true(bool(rows3[0].get("operationContent")), "有可读的操作描述")
        c.in_(rows3[0].get("userRole"), ("STUDENT", "STAFF", "ADMIN"),
              "操作人角色是合法枚举")

    # ══ 第 4 组：按操作类型筛选 ══
    st, body = http("GET", "/api/admin/logs/operation-logs" + qs(operationType="SUBMIT", pageSize=5),
                    token=adm)
    d4 = body.get("data") or {}
    rows4 = d4.get("list") or []
    c.is_true(bool(rows4) and all(r.get("operationType") == "SUBMIT" for r in rows4),
              "按 operationType=SUBMIT 筛选，结果全部是 SUBMIT")

    # ══ 第 5 组：★targetId 只放主键（踩过的坑）══
    #
    # 提交工单之后的日志，targetId 必须是工单号；不能是"提交工单[学时录入]"这种描述。
    _, body = http("GET", "/api/admin/logs/operation-logs" + qs(operationType="SUBMIT", pageSize=5),
                   token=adm)
    for r in ((body.get("data") or {}).get("list") or []):
        tid = r.get("targetId") or ""
        c.check(tid.startswith("WO") or tid == "BATCH",
                "SUBMIT 日志的 targetId 是工单号或 BATCH（不是描述文字）：%s" % tid,
                "WO... 或 BATCH", tid)
        c.check(len(tid) <= 64, "targetId 不超过列宽 64（%d）" % len(tid), "<= 64", len(tid))

    # ══ 第 6 组：参数校验与权限 ══
    st, body = http("GET", "/api/admin/logs/operation-logs" + qs(startDate="2026/01/01"), token=adm)
    c.eq(body.get("code"), 400, "日期格式错 → 400")
    c.contains(body.get("message"), "yyyy-MM-dd", "提示语给出正确格式")

    for label, tk in [("学生", stu), ("干事", staff)]:
        st, body = http("GET", "/api/admin/logs/status-logs", token=tk)
        c.eq(body.get("code"), 403, "%s查日志 → 403" % label)
        c.contains(body.get("message"), "管理员", "提示语指向「仅管理员」")

    st, body = http("GET", "/api/admin/logs/operation-logs")
    c.eq(st, 401, "不带 Token 查日志 → HTTP 401")

    # ══ 第 7 组：查不存在的单号 → 空列表而不是报错 ══
    st, body = http("GET", "/api/admin/logs/status-logs" + qs(workOrderId="WO00000000000000"),
                    token=adm)
    c.eq(body.get("code"), 0, "查不存在的单号不报错")
    c.eq((body.get("data") or {}).get("total"), 0, "返回空列表")

    return c


if __name__ == "__main__":
    run(main)
