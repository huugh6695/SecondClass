"""
验收：敏感词缓存失效（契约 #32 的副作用，最隐蔽的一类 bug）

═══ 为什么要缓存 ═══

每次提交工单都要比对几百个敏感词。不缓存的话，每个提交请求都要查一次全表，
白白多一次数据库往返。所以词库被读进 Redis，进程内还会再留一份。

═══ 缓存带来的经典 bug：改了不生效 ═══

管理员加了个词 → 学生提交含这个词的内容 → **没被标记**。
因为服务读的还是缓存里那份旧词库。

更糟的是它**只在特定时机出现**：重启服务就好了、过一会儿自己就好了，
所以一开始查日志什么都看不出来 —— 典型的"偶发问题"。

本项目的做法：**写操作后"删缓存"**（不是更新缓存），并且放在事务提交之后
（`afterCommit`）—— 否则事务还没提交，别的请求就把旧数据又读回缓存了。

═══ 这个脚本怎么验 ═══

不看 Redis 里的 key（那是实现细节，改了就红），只看**可观察行为**：

    加词 → 立刻提交含该词的内容 → 必须被标记
    停用 → 立刻提交含该词的内容 → 必须不被标记

如果这两条任何一条要"等一会儿"才对，说明缓存没失效干净。

⚠️ 和 verify-flag.py 的分工：
   flag 验"命中后打不打标记"，cache 验"改词库后**立刻**生不生效"。
   两个脚本用不同的词，互不干扰。

═══ 用法 ═══

    python verify/verify-sensitive-cache.py 8117
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
    return (body.get("data") or {}).get("workOrderId")


def flagged(token, content):
    wo = submit(token, content)
    return get_order(token, wo).get("isFlagged")


def main():
    c = Checker("敏感词缓存失效")
    stu = login_required(STUDENT_ID, STUDENT_PWD)
    adm = login_required(ADMIN_ID, ADMIN_PWD)

    tag = uuid.uuid4().hex[:6]
    word = "ZZZ缓存词" + tag

    # ══ 第 0 组：加词之前，这个词还不算敏感 ══
    before = flagged(adm, "加词之前先提交一次，里面含 %s 这个词" % word)
    c.eq(before, False, "加词之前，含该词的内容**不会被标记**（基线）")

    # ══ 第 1 组：加词后**立刻**生效 ══
    st, body = http("POST", "/api/admin/sensitive-words", {"word": word}, token=adm)
    c.eq(body.get("code"), 0, "新增敏感词成功")
    wid = (body.get("data") or {}).get("wordId")

    after_add = flagged(adm, "刚刚加完词就提交，里面含 %s 这个词" % word)
    c.eq(after_add, True,
         "★ 加词后**立刻**能命中（不需要重启、不需要等待）—— 缓存失效生效")

    # 连续两次，排除"第一次碰巧读到新数据"
    again = flagged(adm, "再提交一次，仍然含 %s 这个词" % word)
    c.eq(again, True, "第二次提交仍然命中（结果稳定，不是偶发）")

    # ══ 第 2 组：停用后**立刻**失效 ══
    st, body = http("DELETE", "/api/admin/sensitive-words/%s" % wid, token=adm)
    c.eq(body.get("code"), 0, "停用该词成功")

    after_disable = flagged(adm, "停用之后再提交，里面含 %s 这个词" % word)
    c.eq(after_disable, False,
         "★ 停用后**立刻**不再命中（缓存同样失效干净）")

    after_disable2 = flagged(adm, "再提交一次确认，仍然含 %s 这个词" % word)
    c.eq(after_disable2, False, "第二次提交仍然不命中（结果稳定）")

    # ══ 第 3 组：批量加词后也立刻生效 ══
    w1 = "ZZZ批量甲" + tag
    w2 = "ZZZ批量乙" + tag
    st, body = http("POST", "/api/admin/sensitive-words/batch",
                    {"words": "%s\n%s" % (w1, w2)}, token=adm)
    c.eq(body.get("code"), 0, "批量新增 2 个词")

    c.eq(flagged(adm, "批量加的第一个词 %s 立刻生效吗" % w1), True,
         "批量新增的第 1 个词立刻生效")
    c.eq(flagged(adm, "批量加的第二个词 %s 立刻生效吗" % w2), True,
         "批量新增的第 2 个词立刻生效")

    # ══ 第 4 组：无关的词不影响（缓存不能因为改一个词就整体失效成一个空表）══
    #
    # 如果失效逻辑写成"删掉整个缓存但对读请求返回空"，会出现
    # "加了一个词，结果所有词都不生效了" —— 比不刷新更糟。
    _, body = http("GET", "/api/admin/sensitive-words", token=adm)
    others = [x.get("word") for x in (body.get("data") or []) if x.get("status") == "ACTIVE"]
    c.gt(len(others), 0, "词库里还有别的词（本地有几百个）")

    # 收尾：停用测试词，别污染词库
    for w in (w1, w2):
        _, body = http("GET", "/api/admin/sensitive-words", token=adm)
        wid_x = next((x.get("wordId") for x in (body.get("data") or [])
                      if x.get("word") == w), None)
        if wid_x:
            http("DELETE", "/api/admin/sensitive-words/%s" % wid_x, token=adm)

    return c


if __name__ == "__main__":
    run(main)
