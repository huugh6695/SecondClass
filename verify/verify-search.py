"""
验收：工单列表与筛选（契约 #6 列表）

═══ 这个脚本重点抓什么 ═══

列表接口最容易出的不是"报错"，是**悄悄返回错的数据**。所以断言分三类：

  ① **分页数学**：page/pageSize/offset 三者关系。
     🔴 经典的坑是 `LIMIT ?,?` 写成 `LIMIT pageSize, offset`（顺序反了）——
     不报错，只是数据不对。这里用"两页不能有交集"来抓。

  ② **筛选有效性**：加了 status 筛选，结果里**每一条**都得符合。
     🔴 经典假绿：筛选结果为空时 `all([])` 返回 True，
     所以必须写成"列表非空 且 全部符合"。

  ③ **参数容错**：`page=0` / `page=-1` 会归一化成 1，`pageSize=99` 会压成 50，
     而 `status=瞎写` 直接 400。三种不同的处理方式都要验。

═══ 实测口径 ═══

  · 列表默认 page=1、pageSize=20，上限 50
  · `status` 是**严格校验**的枚举：非法值 → code 400
  · `category` **不校验**：非法值 → code 0 但列表为空
    （不对称是有意的：状态是系统内部枚举，分类是业务数据）
  · 本地库有 3600+ 张历史工单 ⇒ **绝不断言绝对数量**，只断言相对关系

═══ 用法 ═══

    python verify/verify-search.py 8117
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, new_order, qs, unique, run,
                 STUDENT_ID, STUDENT_PWD, STAFF_ID, STAFF_PWD)

LIST_FIELDS = ["workOrderId", "category", "content", "status", "statusLabel",
               "userName", "handlerName", "createTime", "unreadCount",
               "duplicateOfOrderId", "version"]


def fetch(token, **params):
    st, body = http("GET", "/api/work-orders" + qs(**params), token=token)
    return body.get("code"), (body.get("data") or {})


def main():
    c = Checker("工单列表与筛选")
    stu = login_required(STUDENT_ID, STUDENT_PWD)
    staff = login_required(STAFF_ID, STAFF_PWD)

    # 先造一点自己的数据，保证筛选有东西可选
    wo_x = new_order(stu, category="规则疑问", content="搜索验收专用关键字ZZZ %s" % unique())

    # ══ 第 1 组：默认分页 ══
    code, data = fetch(stu)
    c.eq(code, 0, "学生能拿到工单列表")
    c.eq(data.get("page"), 1, "默认 page=1")
    c.eq(data.get("pageSize"), 20, "默认 pageSize=20")
    c.gt(data.get("total"), 0, "total > 0")
    c.eq(len(data.get("list") or []), 20, "默认返回 20 条")

    # ══ 第 2 组：列表字段 ══
    if data.get("list"):
        item = data["list"][0]
        for f in LIST_FIELDS:
            c.is_true(f in item, "列表项包含 %s 字段" % f)

    # ══ 第 3 组：pageSize 上限与非法值 ══
    code, data = fetch(stu, pageSize=50)
    c.eq(data.get("pageSize"), 50, "pageSize=50 原样接受")

    code, data = fetch(stu, pageSize=99)
    c.eq(data.get("pageSize"), 50, "pageSize=99 被压成 50")
    c.eq(len(data.get("list") or []), 50, "返回条数也跟着变成 50")

    code, data = fetch(stu, pageSize=1)
    c.eq(len(data.get("list") or []), 1, "pageSize=1 只返回 1 条")

    # ══ 第 4 组：页码容错（0 和负数归一化成 1）══
    _, base = fetch(stu, page=1, pageSize=5)
    code, data = fetch(stu, page=0, pageSize=5)
    c.eq(data.get("page"), 1, "page=0 归一化成 1")
    c.eq([x.get("workOrderId") for x in (data.get("list") or [])],
         [x.get("workOrderId") for x in (base.get("list") or [])],
         "page=0 返回的内容与 page=1 一致")

    code, data = fetch(stu, page=-1, pageSize=5)
    c.eq(data.get("page"), 1, "page=-1 也归一化成 1")

    # ══ 第 5 组：分页数学（两页不能有交集）══
    _, p1 = fetch(stu, page=1, pageSize=5)
    _, p2 = fetch(stu, page=2, pageSize=5)
    ids1 = [x.get("workOrderId") for x in (p1.get("list") or [])]
    ids2 = [x.get("workOrderId") for x in (p2.get("list") or [])]

    c.eq(len(ids1), 5, "第 1 页返回 5 条")
    c.eq(len(ids2), 5, "第 2 页返回 5 条")
    c.eq(len(set(ids1) & set(ids2)), 0,
         "第 1 页与第 2 页**没有任何重复**（抓 LIMIT offset/pageSize 写反）")
    c.eq(p1.get("total"), p2.get("total"), "两页的 total 一致（total 是总数，不受分页影响）")

    # ══ 第 6 组：status 筛选 ══
    for status, label in [("PENDING", "待接单"), ("CLOSED", "已关闭")]:
        code, data = fetch(stu, status=status, pageSize=20)
        items = data.get("list") or []
        c.eq(code, 0, "status=%s 查询成功" % status)
        c.is_true(bool(items), "status=%s 有数据（本地库有几千张单）" % status)
        c.is_true(bool(items) and all(x.get("status") == status for x in items),
                  "status=%s 的结果里**每一条**都是 %s（不是空列表蒙混过关）" % (status, status))

    # ══ 第 7 组：非法 status → 400（严格校验）══
    code, data = fetch(stu, status="NOT_A_REAL_STATUS")
    c.eq(code, 400, "非法 status → code 400（枚举严格校验）")

    # ══ 第 8 组：category 筛选（中文）══
    code, data = fetch(stu, category="规则疑问", pageSize=20)
    items = data.get("list") or []
    c.eq(code, 0, "category=规则疑问 查询成功")
    c.is_true(bool(items), "category=规则疑问 有数据")
    c.is_true(bool(items) and all(x.get("category") == "规则疑问" for x in items),
              "结果里每一条的分类都是「规则疑问」")

    # 刚造的那张单必须能被 category 筛出来
    got = [x.get("workOrderId") for x in items]
    c.contains(got, wo_x, "刚创建的「规则疑问」工单出现在筛选结果里")

    # ══ 第 9 组：非法 category 不报错，只是空 ══
    code, data = fetch(stu, category="根本不存在的分类XYZ")
    c.eq(code, 0, "非法 category **不报错**（分类是业务数据，不是枚举）")
    c.eq(data.get("total"), 0, "非法 category 返回空列表")
    c.eq(len(data.get("list") or []), 0, "list 是空数组")

    # ══ 第 10 组：keyword 搜索 ══
    code, data = fetch(stu, keyword="搜索验收专用关键字ZZZ", pageSize=20)
    items = data.get("list") or []
    c.eq(code, 0, "keyword 查询成功")
    c.gt(data.get("total"), 0, "keyword 能搜到刚造的单")
    got = [x.get("workOrderId") for x in items]
    c.contains(got, wo_x, "搜到的是刚才那张单")

    # 搜不到的关键词应该是空
    code, data = fetch(stu, keyword="绝对搜不到的词QQQQQQ")
    c.eq(data.get("total"), 0, "搜不到的关键词返回空")

    # ══ 第 11 组：组合筛选（status + category + keyword）══
    code, data = fetch(stu, status="PENDING", category="规则疑问",
                       keyword="搜索验收专用关键字ZZZ")
    c.eq(code, 0, "三个条件组合查询成功")
    for x in (data.get("list") or []):
        c.eq(x.get("status"), "PENDING", "组合筛选：状态符合")
        c.eq(x.get("category"), "规则疑问", "组合筛选：分类符合")

    # ══ 第 12 组：干事视角也能查 ══
    code, data = fetch(staff, pageSize=5)
    c.eq(code, 0, "干事能拿到工单列表")
    c.gt(data.get("total"), 0, "干事列表 total > 0")
    c.eq(len(data.get("list") or []), 5, "干事列表分页生效")

    code, data = fetch(staff, status="PENDING", pageSize=5)
    items = data.get("list") or []
    c.is_true(bool(items) and all(x.get("status") == "PENDING" for x in items),
              "干事按 PENDING 筛选，结果全部是待接单")

    # ══ 第 13 组：未登录 ══
    st, body = http("GET", "/api/work-orders")
    c.eq(st, 401, "不带 Token 查列表 → HTTP 401")

    return c


if __name__ == "__main__":
    run(main)
