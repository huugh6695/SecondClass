"""
验收：FAQ（契约 #20–#23）

═══ 特点 ═══

FAQ 是**唯一对游客开放的业务数据**（不登录也能看）—— 因为学生要先自己查一遍，
查不到才提工单。所以它的权限边界要格外清楚：

  · 读：**任何人都能读**（游客、学生、干事）
  · 写：**只有管理员**（学生/干事 → 403「仅管理员可管理 FAQ」）

═══ 实测口径 ═══

  · 公开列表只返回 `status=PUBLISHED` 的；管理员列表包含未发布的
  · 详情：未发布或不存在 → code 404「FAQ 不存在或未发布」**这个提示语把两种情况合并了**，
    是故意的（游客不该知道"有这个东西但没发布"）
  · 状态枚举：`PUBLISHED` / `UNPUBLISHED`
  · 缺 question → 400「问题标题不能为空」

═══ 用法 ═══

    python verify/verify-faq.py 8117
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, unique, run,
                 STUDENT_ID, STUDENT_PWD, STAFF_ID, STAFF_PWD, ADMIN_ID, ADMIN_PWD)


def main():
    c = Checker("FAQ")
    adm = login_required(ADMIN_ID, ADMIN_PWD)
    stu = login_required(STUDENT_ID, STUDENT_PWD)
    staff = login_required(STAFF_ID, STAFF_PWD)

    # ══ 第 1 组：游客可读（不需要 Token）══
    st, body = http("GET", "/api/faqs")
    c.eq(st, 200, "游客访问 FAQ 列表：HTTP 200（不需要登录）")
    c.eq(body.get("code"), 0, "游客能拿到 FAQ 列表")
    c.is_true(isinstance(body.get("data"), list), "返回的是数组")

    public_list = body.get("data") or []
    c.gt(len(public_list), 0, "公开列表非空")
    if public_list:
        c.is_true(all(x.get("status") == "PUBLISHED" for x in public_list),
                  "公开列表**只含 PUBLISHED**（未发布的不该给游客看）")
        f0 = public_list[0]
        for f in ["faqId", "category", "question", "status", "sortNum", "viewCount"]:
            c.is_true(f in f0, "FAQ 包含 %s 字段" % f)
        c.is_true("answer" not in f0 or f0.get("answer") is None,
                  "列表**不返回答案正文**（详情才给，省流量）")

    # ══ 第 2 组：管理员列表包含未发布 ══
    st, body = http("GET", "/api/faqs", token=adm)
    all_list = body.get("data") or []
    c.eq(body.get("code"), 0, "管理员能读 FAQ 列表")
    c.is_true(len(all_list) >= len(public_list),
              "管理员看到的条数 ≥ 游客（能看到未发布的）")

    # ══ 第 3 组：新建 ══
    q = "验收问题 %s" % unique()
    st, body = http("POST", "/api/admin/faqs", {
        "category": "学时录入",
        "question": q,
        "answer": "这是验收脚本创建的答案内容。",
        "keywords": "验收,自动化",
    }, token=adm)
    c.eq(body.get("code"), 0, "管理员新建 FAQ 成功")
    c.contains(body.get("message"), "新增", "新建提示语含「新增」")

    faq = body.get("data") or {}
    faq_id = faq.get("faqId")
    c.is_true(bool(faq_id), "返回了 faqId")
    c.eq(faq.get("question"), q, "返回的问题标题与提交的一致")
    c.eq(faq.get("status"), "PUBLISHED", "新建默认是 PUBLISHED")
    c.is_true(bool(faq.get("creatorName")), "记了创建人姓名")

    # ══ 第 4 组：详情（游客可看已发布的）══
    st, body = http("GET", "/api/faqs/%s" % faq_id)
    d = body.get("data") or {}
    c.eq(body.get("code"), 0, "游客能看已发布的 FAQ 详情")
    c.eq(d.get("question"), q, "详情的问题标题正确")
    c.contains(d.get("answer"), "验收脚本创建", "详情**包含答案正文**（列表里没有）")

    # ══ 第 5 组：修改 ══
    st, body = http("PUT", "/api/admin/faqs/%s" % faq_id, {
        "question": q + "（改过）",
        "answer": "改过之后的答案。",
    }, token=adm)
    c.eq(body.get("code"), 0, "修改 FAQ 成功")

    _, body = http("GET", "/api/faqs/%s" % faq_id, token=adm)
    c.contains((body.get("data") or {}).get("question"), "改过", "修改生效了")

    # ══ 第 6 组：下架后游客看不到 ══
    st, body = http("PUT", "/api/admin/faqs/%s" % faq_id,
                    {"question": q, "answer": "答案", "status": "UNPUBLISHED"}, token=adm)
    c.eq(body.get("code"), 0, "改成 UNPUBLISHED 成功")

    st, body = http("GET", "/api/faqs/%s" % faq_id)
    c.eq(body.get("code"), 404, "下架后游客查详情 → 404")
    c.contains(body.get("message"), "不存在", "提示语是「不存在或未发布」（故意合并两种原因）")

    _, body = http("GET", "/api/faqs")
    ids = [x.get("faqId") for x in (body.get("data") or [])]
    c.check(faq_id not in ids, "下架后不再出现在公开列表里", "不包含 %s" % faq_id, "包含")

    # 管理员还能看到
    _, body = http("GET", "/api/faqs", token=adm)
    ids = [x.get("faqId") for x in (body.get("data") or [])]
    c.contains(ids, faq_id, "管理员列表里还能看到这张（只是对游客隐藏）")

    # ══ 第 7 组：下架接口 ══
    st, body = http("DELETE", "/api/admin/faqs/%s" % faq_id, token=adm)
    c.eq(body.get("code"), 0, "调用下架接口成功")
    c.contains(body.get("message"), "下架", "提示语含「下架」")

    # ══ 第 8 组：参数校验与权限 ══
    st, body = http("POST", "/api/admin/faqs", {"category": "学时录入"}, token=adm)
    c.eq(body.get("code"), 400, "缺 question → 400")
    c.contains(body.get("message"), "标题", "提示语指向「标题」")

    for label, tk in [("学生", stu), ("干事", staff)]:
        st, body = http("POST", "/api/admin/faqs",
                        {"category": "其他", "question": "x", "answer": "y"}, token=tk)
        c.eq(body.get("code"), 403, "%s新建 FAQ → 403（仅管理员）" % label)

    st, body = http("POST", "/api/admin/faqs",
                    {"category": "其他", "question": "x", "answer": "y"})
    c.eq(st, 401, "不带 Token 新建 FAQ → HTTP 401")

    # ══ 第 9 组：不存在的 ID ══
    st, body = http("GET", "/api/faqs/NOT_EXIST_ID", token=adm)
    c.eq(body.get("code"), 404, "查不存在的 FAQ → 404")

    st, body = http("PUT", "/api/admin/faqs/NOT_EXIST_ID",
                    {"question": "x", "answer": "y"}, token=adm)
    c.check(body.get("code") in (400, 404),
            "改不存在的 FAQ → 400 或 404（拦住了就行）", "400/404", body.get("code"))

    return c


if __name__ == "__main__":
    run(main)
