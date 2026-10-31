"""
验收：敏感词管理（契约 #32–#33）

═══ 敏感词是干什么的 ═══

不是拦人，是**给工单打标记**。学生提交时如果命中词库，工单会带 `isFlagged=true`，
管理员在后台能看到这个标记，决定要不要优先处理（比如涉及投诉、舆情）。

═══ 实测口径 ═══

  · `wordId` 是**整数**（自增主键），不是业务编号
  · 重复新增 → code **0** + 「该词已存在，未重复添加」（幂等，不报错）
  · 批量新增返回 `{total, added, skipped}` —— 三个数字要能对上
  · 批量重复 → added=0、skipped=全部
  · 删除 = **停用**（status 变 DISABLED），不是物理删除
    （历史工单上"当时命中了什么词"要能查得到）
  · 空词 → 400「敏感词不能为空」
  · 学生访问 → 403「仅管理员可管理敏感词」

═══ 用法 ═══

    python verify/verify-sensitive-word.py 8117
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, run,
                 STUDENT_ID, STUDENT_PWD, ADMIN_ID, ADMIN_PWD)


def main():
    c = Checker("敏感词管理")
    adm = login_required(ADMIN_ID, ADMIN_PWD)
    stu = login_required(STUDENT_ID, STUDENT_PWD)

    # ══ 第 1 组：列表 ══
    st, body = http("GET", "/api/admin/sensitive-words", token=adm)
    c.eq(body.get("code"), 0, "管理员能读敏感词列表")
    lst = body.get("data") or []
    c.gt(len(lst), 0, "词库非空")

    if lst:
        w0 = lst[0]
        for f in ["wordId", "word", "status", "creatorName", "createTime"]:
            c.is_true(f in w0, "敏感词包含 %s 字段" % f)
        c.is_true(isinstance(w0.get("wordId"), int),
                  "wordId 是**整数**（自增主键，不是业务编号）")
        # ⚠️ 实测：列表会**包含已停用的词**（管理员要能看到并恢复它们），
        #    所以不能断言"只有 ACTIVE"。只断言状态取值合法。
        statuses = set(x.get("status") for x in lst)
        c.is_true(statuses <= {"ACTIVE", "DISABLED"},
                  "状态取值只有 ACTIVE / DISABLED 两种（实际：%s）" % sorted(statuses))
        c.is_true("ACTIVE" in statuses, "列表里至少有 ACTIVE 的词")

    # ══ 第 2 组：新增 ══
    word = "验收敏感词" + os.urandom(3).hex()
    st, body = http("POST", "/api/admin/sensitive-words", {"word": word}, token=adm)
    c.eq(body.get("code"), 0, "新增敏感词成功")
    c.contains(body.get("message"), "新增", "提示语含「新增」")

    item = body.get("data") or {}
    wid = item.get("wordId")
    c.eq(item.get("word"), word, "返回的词条正确")
    c.eq(item.get("status"), "ACTIVE", "新增默认 ACTIVE")
    c.is_true(isinstance(wid, int), "返回的 wordId 是整数")
    c.is_true(bool(item.get("creatorName")), "记了创建人")

    # ══ 第 3 组：重复新增是幂等的（不报错）══
    st, body = http("POST", "/api/admin/sensitive-words", {"word": word}, token=adm)
    c.eq(body.get("code"), 0, "重复新增**不报错**（code 仍为 0）")
    c.contains(body.get("message"), "已存在", "提示语说明「该词已存在」")

    # 重复新增后，列表里这个词只出现一次
    _, body = http("GET", "/api/admin/sensitive-words", token=adm)
    hits = [x for x in (body.get("data") or []) if x.get("word") == word]
    c.eq(len(hits), 1, "重复新增后列表里只有 1 条（没有插重）")

    # ══ 第 4 组：空词 ══
    st, body = http("POST", "/api/admin/sensitive-words", {"word": ""}, token=adm)
    c.eq(body.get("code"), 400, "空词 → 400")
    c.contains(body.get("message"), "不能为空", "提示语说明「不能为空」")

    st, body = http("POST", "/api/admin/sensitive-words", {"word": "   "}, token=adm)
    c.check(body.get("code") in (400, 0),
            "纯空格词 → 被拒或按空处理（不能悄悄建一条空词）", "400 或 0", body.get("code"))

    # ══ 第 5 组：批量新增 ══
    w2 = "批量词甲" + os.urandom(2).hex()
    w3 = "批量词乙" + os.urandom(2).hex()
    st, body = http("POST", "/api/admin/sensitive-words/batch",
                    {"words": "%s\n%s" % (w2, w3)}, token=adm)
    c.eq(body.get("code"), 0, "批量新增成功")

    r = body.get("data") or {}
    for f in ["total", "added", "skipped"]:
        c.is_true(f in r, "批量结果里有 %s 字段" % f)
    c.eq(r.get("total"), 2, "识别出 2 个词")
    c.eq(r.get("added"), 2, "新增 2 个")
    c.eq(r.get("skipped"), 0, "跳过 0 个")
    c.eq(r.get("added", 0) + r.get("skipped", 0), r.get("total"),
         "added + skipped == total（三个数字自洽）")

    # ══ 第 6 组：批量重复 ══
    st, body = http("POST", "/api/admin/sensitive-words/batch",
                    {"words": "%s\n%s" % (w2, w3)}, token=adm)
    c.eq(body.get("code"), 0, "批量重复提交仍返回成功")
    r2 = body.get("data") or {}
    c.eq(r2.get("added"), 0, "第二次一个都没新增")
    c.eq(r2.get("skipped"), 2, "全部被跳过")
    c.eq(r2.get("added", 0) + r2.get("skipped", 0), r2.get("total"),
         "第二次的 added + skipped 也等于 total")

    # ══ 第 7 组：删除 = 停用 ══
    st, body = http("DELETE", "/api/admin/sensitive-words/%s" % wid, token=adm)
    c.eq(body.get("code"), 0, "删除敏感词成功")
    c.contains(body.get("message"), "禁用", "提示语是「已禁用」（不是「已删除」）")

    _, body = http("GET", "/api/admin/sensitive-words", token=adm)
    hits = [x for x in (body.get("data") or []) if x.get("wordId") == wid]
    c.eq(len(hits), 1, "停用后词条**仍在列表里**（软删除，不是物理删除 —— 历史工单要能追溯）")
    if hits:
        c.eq(hits[0].get("status"), "DISABLED", "停用后状态变成 DISABLED")

    # ══ 第 8 组：权限 ══
    st, body = http("GET", "/api/admin/sensitive-words", token=stu)
    c.eq(body.get("code"), 403, "学生读词库 → 403")
    c.contains(body.get("message"), "管理员", "提示语指向「仅管理员」")

    st, body = http("POST", "/api/admin/sensitive-words", {"word": "学生想加"}, token=stu)
    c.eq(body.get("code"), 403, "学生加词 → 403")

    st, body = http("GET", "/api/admin/sensitive-words")
    c.eq(st, 401, "不带 Token 读词库 → HTTP 401")

    # ══ 第 9 组：不存在的词 ID ══
    st, body = http("DELETE", "/api/admin/sensitive-words/99999999", token=adm)
    c.check(body.get("code") in (400, 404),
            "停用不存在的词 → 400 或 404", "400/404", body.get("code"))

    return c


if __name__ == "__main__":
    run(main)
