"""
验收：回复模板（契约 #28–#31）

═══ 为什么需要模板 ═══

干事的回复 80% 是重复的（"学时会在 3-5 个工作日内录入"）。
模板让干事一键套用，再改几个字发出去 —— 把"写回复"变成"选回复"。

═══ 实测口径 ═══

  · 状态枚举：`ACTIVE` / `DISABLED`（停用不是删除，历史记录还要能对上）
  · 缺 title → 400「模板名称不能为空」
  · 读：登录用户都能读（干事要选，管理员要管）
  · 写：仅管理员

═══ 用法 ═══

    python verify/verify-template.py 8117
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, unique, run,
                 STUDENT_ID, STUDENT_PWD, STAFF_ID, STAFF_PWD, ADMIN_ID, ADMIN_PWD)


def main():
    c = Checker("回复模板")
    adm = login_required(ADMIN_ID, ADMIN_PWD)
    staff = login_required(STAFF_ID, STAFF_PWD)
    stu = login_required(STUDENT_ID, STUDENT_PWD)

    # ══ 第 1 组：干事能读到模板 ══
    st, body = http("GET", "/api/templates", token=staff)
    c.eq(body.get("code"), 0, "干事能读模板列表")
    lst = body.get("data") or []
    c.gt(len(lst), 0, "模板列表非空")

    if lst:
        t0 = lst[0]
        for f in ["templateId", "category", "title", "content", "status", "sortNum"]:
            c.is_true(f in t0, "模板包含 %s 字段" % f)
        c.is_true(all(x.get("status") == "ACTIVE" for x in lst),
                  "默认只返回 ACTIVE 的模板（停用的不该出现在选择列表里）")

    # 学生也能读（学生端不展示模板，但接口没做角色限制）
    st, body = http("GET", "/api/templates", token=stu)
    c.eq(body.get("code"), 0, "学生也能读模板列表（接口没按角色限制读）")

    # ══ 第 2 组：新建 ══
    title = "验收模板 %s" % unique()
    st, body = http("POST", "/api/admin/templates", {
        "category": "学时录入",
        "title": title,
        "content": "已为你完成学时补录，请到「我的工单」里确认。",
        "sortNum": 3,
    }, token=adm)
    c.eq(body.get("code"), 0, "管理员新建模板成功")
    c.contains(body.get("message"), "新增", "新建提示语含「新增」")

    t = body.get("data") or {}
    tid = t.get("templateId")
    c.is_true(bool(tid), "返回了 templateId")
    c.eq(t.get("title"), title, "标题正确")
    c.eq(t.get("status"), "ACTIVE", "新建默认 ACTIVE")
    c.is_true(bool(t.get("creatorName")), "记了创建人")

    # ══ 第 3 组：出现在干事的选择列表里 ══
    _, body = http("GET", "/api/templates", token=staff)
    ids = [x.get("templateId") for x in (body.get("data") or [])]
    c.contains(ids, tid, "新建的模板能被干事看到")

    # ══ 第 4 组：按分类筛选 ══
    from lib import qs
    st, body = http("GET", "/api/templates" + qs(category="学时录入"), token=staff)
    lst = body.get("data") or []
    c.eq(body.get("code"), 0, "按分类筛选成功")
    c.is_true(bool(lst) and all(x.get("category") == "学时录入" for x in lst),
              "筛选结果的分类全是「学时录入」")

    # ══ 第 5 组：修改 ══
    st, body = http("PUT", "/api/admin/templates/%s" % tid, {
        "title": title + "（改）",
        "content": "改过之后的模板内容。",
    }, token=adm)
    c.eq(body.get("code"), 0, "修改模板成功")
    c.contains((body.get("data") or {}).get("title"), "改", "修改生效")

    # ══ 第 6 组：停用 ══
    st, body = http("DELETE", "/api/admin/templates/%s" % tid, token=adm)
    c.eq(body.get("code"), 0, "停用模板成功")
    c.contains(body.get("message"), "禁用", "提示语含「禁用」")

    _, body = http("GET", "/api/templates", token=staff)
    ids = [x.get("templateId") for x in (body.get("data") or [])]
    c.check(tid not in ids, "停用后不再出现在干事的选择列表里", "不包含", "包含")

    # ══ 第 7 组：参数校验 ══
    st, body = http("POST", "/api/admin/templates", {"category": "学时录入"}, token=adm)
    c.eq(body.get("code"), 400, "缺 title → 400")
    c.contains(body.get("message"), "名称", "提示语指向「名称」")

    # ══ 第 8 组：权限 ══
    for label, tk in [("学生", stu), ("干事", staff)]:
        st, body = http("POST", "/api/admin/templates",
                        {"category": "其他", "title": "x", "content": "y"}, token=tk)
        c.eq(body.get("code"), 403, "%s新建模板 → 403（仅管理员）" % label)

    st, body = http("POST", "/api/admin/templates",
                    {"category": "其他", "title": "x", "content": "y"})
    c.eq(st, 401, "不带 Token 新建模板 → HTTP 401")

    return c


if __name__ == "__main__":
    run(main)
