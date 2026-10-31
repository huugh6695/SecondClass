"""
验收：公告（契约 #24–#27）

═══ 和 FAQ 的区别 ═══

FAQ 是"长期有效的问答"，公告是"有生命周期的通知"（比如"11 月 1 日系统维护"）。
所以公告多了两个概念：

  · `expireTime` 过期时间 —— 到点就自动不再展示
  · `status` 只有两个值：**ACTIVE / ARCHIVED**

⚠️ 状态枚举容易记错：不是 PUBLISHED/UNPUBLISHED（那是 FAQ 的），
   公告是 ACTIVE/ARCHIVED。实测传 UNPUBLISHED 会报
   400「状态不合法，必须是：ACTIVE / ARCHIVED」。

═══ 权限 ═══

  · 读：游客可读（首页的公告栏）
  · 写：仅管理员

═══ 用法 ═══

    python verify/verify-announcement.py 8117
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, unique, run,
                 STUDENT_ID, STUDENT_PWD, ADMIN_ID, ADMIN_PWD)


def main():
    c = Checker("公告")
    adm = login_required(ADMIN_ID, ADMIN_PWD)
    stu = login_required(STUDENT_ID, STUDENT_PWD)

    # ══ 第 1 组：游客可读 ══
    st, body = http("GET", "/api/announcements")
    c.eq(st, 200, "游客读公告列表：HTTP 200")
    c.eq(body.get("code"), 0, "游客能拿到公告列表")
    lst = body.get("data") or []
    c.is_true(isinstance(lst, list), "返回数组")
    c.gt(len(lst), 0, "公告列表非空")

    if lst:
        a0 = lst[0]
        for f in ["noticeId", "title", "content", "status", "sortNum",
                  "publishTime", "publisherName"]:
            c.is_true(f in a0, "公告包含 %s 字段" % f)
        c.is_true(all(x.get("status") == "ACTIVE" for x in lst),
                  "公开列表只含 ACTIVE（已归档的不给游客看）")

    # ══ 第 2 组：新建 ══
    title = "验收公告 %s" % unique()
    st, body = http("POST", "/api/admin/announcements", {
        "title": title,
        "content": "这是验收脚本创建的公告正文。",
        "sortNum": 5,
    }, token=adm)
    c.eq(body.get("code"), 0, "管理员新建公告成功")
    c.contains(body.get("message"), "发布", "新建提示语含「发布」")

    a = body.get("data") or {}
    nid = a.get("noticeId")
    c.is_true(bool(nid), "返回了 noticeId")
    c.eq(a.get("title"), title, "标题与提交的一致")
    c.eq(a.get("status"), "ACTIVE", "新建默认 ACTIVE")
    c.eq(a.get("sortNum"), 5, "排序号原样返回")
    c.is_true(bool(a.get("publishTime")), "有发布时间")
    c.is_true(bool(a.get("publisherName")), "有发布人姓名")

    # ══ 第 3 组：出现在游客列表里 ══
    _, body = http("GET", "/api/announcements")
    ids = [x.get("noticeId") for x in (body.get("data") or [])]
    c.contains(ids, nid, "新建的公告出现在游客列表里")

    # ══ 第 4 组：修改 ══
    st, body = http("PUT", "/api/admin/announcements/%s" % nid, {
        "title": title + "（已修改）",
        "content": "修改后的正文。",
        "sortNum": 1,
    }, token=adm)
    c.eq(body.get("code"), 0, "修改公告成功")
    c.eq((body.get("data") or {}).get("sortNum"), 1, "修改后的排序号生效")

    # ══ 第 5 组：状态枚举是 ACTIVE / ARCHIVED ══
    st, body = http("PUT", "/api/admin/announcements/%s" % nid,
                    {"title": title, "content": "x", "status": "UNPUBLISHED"}, token=adm)
    c.eq(body.get("code"), 400, "传 FAQ 那套 UNPUBLISHED → 400（公告用的是 ACTIVE/ARCHIVED）")
    c.contains(body.get("message"), "ACTIVE", "提示语里列出了合法的两个值")

    # ══ 第 6 组：归档 ══
    st, body = http("DELETE", "/api/admin/announcements/%s" % nid, token=adm)
    c.eq(body.get("code"), 0, "归档公告成功")
    c.contains(body.get("message"), "归档", "提示语含「归档」")

    _, body = http("GET", "/api/announcements")
    ids = [x.get("noticeId") for x in (body.get("data") or [])]
    c.check(nid not in ids, "归档后不再出现在游客列表里", "不包含", "包含")

    # ══ 第 7 组：参数校验 ══
    st, body = http("POST", "/api/admin/announcements", {"content": "缺标题"}, token=adm)
    c.eq(body.get("code"), 400, "缺 title → 400")
    c.contains(body.get("message"), "标题", "提示语指向「标题」")

    # ══ 第 8 组：权限 ══
    st, body = http("POST", "/api/admin/announcements",
                    {"title": "学生想发公告", "content": "x"}, token=stu)
    c.eq(body.get("code"), 403, "学生发公告 → 403")

    st, body = http("POST", "/api/admin/announcements",
                    {"title": "x", "content": "y"})
    c.eq(st, 401, "不带 Token 发公告 → HTTP 401")

    # ══ 第 9 组：不存在的公告 ══
    st, body = http("PUT", "/api/admin/announcements/NOT_EXIST",
                    {"title": "x", "content": "y"}, token=adm)
    c.check(body.get("code") in (400, 404),
            "改不存在的公告 → 400 或 404", "400/404", body.get("code"))

    return c


if __name__ == "__main__":
    run(main)
