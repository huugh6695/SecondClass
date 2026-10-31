"""
验收：数据统计（契约 #37–#38）

═══ 统计最容易出的错：口径不一致 ═══

"未完结"到底是哪几个状态？"重复率"的分母是什么？如果统计页和列表页口径不同，
管理员看到的数字就对不上，然后他就再也不信这个功能了。

所以这个脚本的核心断言是**自洽性**：
  · `totalTickets` 必须等于 `statusDistribution` 各项之和
  · 状态分布的 5 个 key 必须齐全（PENDING/PROCESSING/REPLIED/CLOSED/MERGED）
  · 分类分布的 5 个 key 必须齐全（学时录入/审核驳回/活动咨询/规则疑问/其他）

═══ 实测口径 ═══

  · 日期参数格式 `yyyy-MM-dd`，传错 → 400「日期格式不正确」
  · 带日期筛选后 total 会变小（只统计区间内的）
  · `duplicateRate` 是 0~1 的小数（不是百分比数字）

⚠️ 本地库有 3600+ 张历史工单，所以**绝不写死具体数字**。
   所有断言都写成"关系"而不是"值"。

═══ 用法 ═══

    python verify/verify-statistics.py 8117
"""

import os
import sys
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, new_order, qs, unique, run,
                 STUDENT_ID, STUDENT_PWD, STAFF_ID, STAFF_PWD, ADMIN_ID, ADMIN_PWD)

STATUSES = ["PENDING", "PROCESSING", "REPLIED", "CLOSED", "MERGED"]
CATEGORIES = ["学时录入", "审核驳回", "活动咨询", "规则疑问", "其他"]


def main():
    c = Checker("数据统计")
    adm = login_required(ADMIN_ID, ADMIN_PWD)
    stu = login_required(STUDENT_ID, STUDENT_PWD)
    staff = login_required(STAFF_ID, STAFF_PWD)

    # ══ 第 1 组：overview 形状 ══
    st, body = http("GET", "/api/admin/statistics/overview", token=adm)
    c.eq(st, 200, "管理员能读统计：HTTP 200")
    c.eq(body.get("code"), 0, "统计接口成功")

    d = body.get("data") or {}
    for f in ["totalTickets", "statusDistribution", "categoryDistribution",
              "duplicateRate", "avgResponseMinutes", "avgCloseMinutes"]:
        c.is_true(f in d, "overview 包含 %s 字段" % f)

    # ══ 第 2 组：状态分布口径自洽 ══
    sd = d.get("statusDistribution") or {}
    for s in STATUSES:
        c.is_true(s in sd, "状态分布里有 %s（漏一个前端图表就缺一块）" % s)

    c.eq(len(sd), 5, "状态分布**恰好** 5 个 key（不多不少）")
    c.eq(sum(sd.values()), d.get("totalTickets"),
         "各部分之和 == totalTickets（口径自洽，这是最重要的一条）")
    c.is_true(all(v >= 0 for v in sd.values()), "各状态计数都不为负")

    # ══ 第 3 组：分类分布 ══
    cd = d.get("categoryDistribution") or {}
    for cat in CATEGORIES:
        c.is_true(cat in cd, "分类分布里有「%s」" % cat)
    c.eq(len(cd), 5, "分类分布恰好 5 个 key")
    c.eq(sum(cd.values()), d.get("totalTickets"),
         "分类之和也等于 totalTickets（两个维度的分母必须一致）")

    # ══ 第 4 组：重复率与耗时 ══
    rate = d.get("duplicateRate")
    c.is_true(isinstance(rate, (int, float)), "duplicateRate 是数字")
    c.check(isinstance(rate, (int, float)) and 0 <= rate <= 1,
            "duplicateRate 在 0~1 之间（是小数不是百分数）", "0 <= x <= 1", rate)
    c.is_true(isinstance(d.get("avgResponseMinutes"), (int, float)),
              "avgResponseMinutes 是数字")
    c.is_true(isinstance(d.get("avgCloseMinutes"), (int, float)),
              "avgCloseMinutes 是数字")

    # ══ 第 5 组：造一张单之后，总数应该增加 ══
    before = d.get("totalTickets")
    new_order(stu, content="统计验收 %s" % unique())
    _, body2 = http("GET", "/api/admin/statistics/overview", token=adm)
    after = (body2.get("data") or {}).get("totalTickets")
    c.eq(after, before + 1,
         "新提交 1 张单后 totalTickets 加 1（%s → %s）" % (before, after))

    sd2 = (body2.get("data") or {}).get("statusDistribution") or {}
    c.eq(sd2.get("PENDING"), (sd.get("PENDING") or 0) + 1,
         "新单落在 PENDING 里（PENDING 计数 +1）")

    # ══ 第 6 组：日期筛选 ══
    today = date.today()
    start = (today - timedelta(days=7)).isoformat()
    end = today.isoformat()

    st, body = http("GET", "/api/admin/statistics/overview" + qs(startDate=start, endDate=end),
                    token=adm)
    c.eq(body.get("code"), 0, "带日期区间查询成功")
    d3 = body.get("data") or {}
    c.check(d3.get("totalTickets") <= after,
            "区间内的总数 ≤ 全部总数（筛选真的生效了）",
            "<= %s" % after, d3.get("totalTickets"))
    c.eq(sum((d3.get("statusDistribution") or {}).values()), d3.get("totalTickets"),
         "带日期时状态分布依然自洽")

    # 只传开始日期也应该能跑
    st, body = http("GET", "/api/admin/statistics/overview" + qs(startDate=start), token=adm)
    c.eq(body.get("code"), 0, "只传 startDate 也能查询")

    # ══ 第 7 组：坏日期 ══
    st, body = http("GET", "/api/admin/statistics/overview" + qs(startDate="2026/01/01"), token=adm)
    c.eq(body.get("code"), 400, "日期格式错（2026/01/01）→ 400")
    c.contains(body.get("message"), "yyyy-MM-dd", "提示语给出了正确格式")

    # ══ 第 8 组：员工工作量 ══
    st, body = http("GET", "/api/admin/statistics/staff-workload", token=adm)
    c.eq(body.get("code"), 0, "员工工作量查询成功")
    rows = body.get("data") or []
    c.is_true(isinstance(rows, list), "返回数组")
    c.gt(len(rows), 0, "有数据（本地有干事处理过单）")

    if rows:
        r0 = rows[0]
        for f in ["handlerId", "handlerName", "total", "closedCount", "avgCloseMinutes"]:
            c.is_true(f in r0, "工作量行包含 %s 字段" % f)
        c.is_true(all(r.get("closedCount", 0) <= r.get("total", 0) for r in rows),
                  "每个人的已关闭数 ≤ 处理总数（口径自洽）")

    # ══ 第 9 组：权限 ══
    for label, tk in [("学生", stu), ("干事", staff)]:
        st, body = http("GET", "/api/admin/statistics/overview", token=tk)
        c.eq(body.get("code"), 403, "%s看统计 → 403（仅管理员）" % label)

    st, body = http("GET", "/api/admin/statistics/overview")
    c.eq(st, 401, "不带 Token 看统计 → HTTP 401")

    return c


if __name__ == "__main__":
    run(main)
