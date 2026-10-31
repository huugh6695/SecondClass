"""
验收：敏感词命中标记（契约 #5 + #32 的联动）

═══ 命中之后发生什么 ═══

不是拦人，是**给工单打个标记** `isFlagged = true`。管理员在后台能筛出这些单，
决定要不要优先处理。学生那边看不出任何区别 —— 提交照常成功。

═══ 🔴 字段名有个经典坑 ═══

Java 里写 `private Boolean isFlagged;` 时：

    public Boolean getIsFlagged()  →  JSON 输出 "isFlagged"   ✅
    public Boolean isFlagged()     →  JSON 输出 "flagged"     ❌

前端按 `isFlagged` 取值，写成第二种就变 `undefined`，而且**接口不报错**——
页面上那个"疑似敏感"的小红点就是不显示，谁也不知道为什么。

这个项目里已有专门约定（`getIsFlagged()`），本脚本断言的就是输出键名。

═══ 用法 ═══

    python verify/verify-flag.py 8117
"""

import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, get_order, run,
                 STUDENT_ID, STUDENT_PWD, ADMIN_ID, ADMIN_PWD)


def submit(token, content):
    st, body = http("POST", "/api/work-orders", {
        "category": "其他", "content": content, "requestId": str(uuid.uuid4())}, token=token)
    return (body.get("data") or {}).get("workOrderId"), body


def main():
    c = Checker("敏感词命中标记")
    stu = login_required(STUDENT_ID, STUDENT_PWD)
    adm = login_required(ADMIN_ID, ADMIN_PWD)

    # 造一个绝不会跟别的词撞车的标记词
    word = "ZZZ标记验收" + uuid.uuid4().hex[:5]

    st, body = http("POST", "/api/admin/sensitive-words", {"word": word}, token=adm)
    c.eq(body.get("code"), 0, "先把测试词加进词库")
    wid = (body.get("data") or {}).get("wordId")

    # ══ 第 1 组：命中 ══
    wo_hit, _ = submit(stu, "这段话里故意包含 %s 这个词，看看会不会被标记" % word)
    d = get_order(adm, wo_hit)

    c.is_true("isFlagged" in d, "详情里有 isFlagged 字段")
    c.check("flagged" not in d,
            "JSON 键名是 isFlagged，**不是 flagged**"
            "（getter 写成 isFlagged() 会输出 flagged，前端就读不到）",
            "无 flagged 键", "出现了 flagged 键")
    c.eq(d.get("isFlagged"), True, "命中敏感词的工单 isFlagged = true")
    c.eq(d.get("status"), "PENDING", "被打标记**不影响状态**（还是正常待接单）")

    # ══ 第 2 组：干净内容 ══
    wo_clean, _ = submit(stu, "这段话很干净，没有任何需要标记的内容 %s" % uuid.uuid4())
    d2 = get_order(adm, wo_clean)
    c.eq(d2.get("isFlagged"), False, "干净内容的工单 isFlagged = false")
    c.is_true(d2.get("isFlagged") is not None, "isFlagged 是 false 不是 null（三种状态要分清）")

    # ══ 第 3 组：打标记不影响提交 ══
    c.eq(d.get("status"), "PENDING", "被标记的单和平常单一样进入待接单队列")
    c.is_true(bool(wo_hit), "被标记的单有自己的单号（没被吞）")

    # ══ 第 4 组：标记只在提交时算一次，后续不变 ══
    #
    # 这是有意的：如果每次读详情都重算，那词库一改，历史工单的标记会集体变化，
    # 管理员昨天看到的红点今天消失了 —— 没法解释。
    staff = login_required("2023002001")
    http("POST", "/api/work-orders/%s/accept" % wo_hit, {}, token=staff)
    d3 = get_order(adm, wo_hit)
    c.eq(d3.get("isFlagged"), True, "接单后标记保持不变（不是每次读都重算）")

    # ══ 第 5 组：管理端搜索能按标记查出来 ══
    st, body = http("GET", "/api/admin/work-orders/search?pageSize=20", token=adm)
    c.eq(body.get("code"), 0, "管理端工单搜索可用")
    lst = (body.get("data") or {}).get("list") or []
    if lst:
        c.is_true("isFlagged" in lst[0], "搜索结果的每一项也带 isFlagged（列表要能显示红点）")
        c.is_true("duplicateOfOrderId" in lst[0], "搜索结果也带 duplicateOfOrderId")

    # ══ 第 6 组：多词命中也是 true ══
    word2 = "ZZZ第二词" + uuid.uuid4().hex[:5]
    http("POST", "/api/admin/sensitive-words", {"word": word2}, token=adm)
    wo_both, _ = submit(stu, "同时包含 %s 和 %s 两个词的内容" % (word, word2))
    d4 = get_order(adm, wo_both)
    c.eq(d4.get("isFlagged"), True, "命中多个词也是 true（布尔值不是计数）")

    # 收尾：把造的词停用，别污染词库
    if wid:
        http("DELETE", "/api/admin/sensitive-words/%s" % wid, token=adm)
    _, body = http("GET", "/api/admin/sensitive-words", token=adm)
    wid2 = next((x.get("wordId") for x in (body.get("data") or [])
                 if x.get("word") == word2), None)
    if wid2:
        http("DELETE", "/api/admin/sensitive-words/%s" % wid2, token=adm)

    return c


if __name__ == "__main__":
    run(main)
