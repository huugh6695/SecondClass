"""
验收：单号生成（契约 #5 提交工单的副作用）

═══ 单号规则（实测）═══

    WO + yyyyMMdd + 4 位当日序号        例：WO202610280001
    ↑     ↑          ↑
    前缀  服务器当天  当天第几单，从 0001 开始

为什么用"日期 + 序号"而不是自增主键或 UUID：
  · 自增主键会暴露业务量（`WO1`、`WO2`…对手一眼看出你一个月多少单）；
  · UUID 没法人工口头传达（学生打电话报单号，"WO202610280001"能念出来）；
  · 日期前缀让单号自带时间信息，客服看一眼就知道是哪天的单。

═══ 这个脚本验什么 ═══

  ① 格式对不对（长度、前缀、日期段是不是服务器当天）
  ② 同一天连续提交，序号递增且**不跳号**
  ③ 并发/快速连续提交不会撞号（序号必须唯一）

⚠️ 序号从 Redis 的 INCR 取（不是查库算 MAX+1），所以它**天然防并发**。
   如果这里出现重复单号，说明 INCR 被换成了别的实现。

⚠️ 「跨天序号重置」测不了（不能改服务器时间）。这一条靠代码保证，脚本不断言。

═══ 用法 ═══

    python verify/verify-seqid.py 8117
"""

import os
import re
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import Checker, login_required, new_order, run, STUDENT_ID, STUDENT_PWD

PATTERN = re.compile(r"^WO(\d{8})(\d{4})$")


def main():
    c = Checker("单号生成")
    stu = login_required(STUDENT_ID, STUDENT_PWD)

    # ══ 第 1 组：格式 ══
    wo = new_order(stu, content="单号格式验收 %s" % datetime.now().timestamp())

    m = PATTERN.match(wo)
    c.is_true(bool(m), "单号格式是 WO + 8 位日期 + 4 位序号（实际：%s）" % wo)
    c.eq(len(wo), 14, "单号长度 14")

    if m:
        date_part = m.group(1)
        today = datetime.now().strftime("%Y%m%d")
        c.eq(date_part, today, "日期段就是服务器当天（%s）" % today)

        seq = int(m.group(2))
        c.is_true(seq >= 1, "序号从 1 开始（实际 %d）" % seq)
        c.is_true(seq <= 9999, "序号不超过 4 位（实际 %d）" % seq)

    # ══ 第 2 组：连续提交，序号递增不跳号 ══
    ids = [new_order(stu, content="单号连续性验收 %d %s" % (i, datetime.now().timestamp()))
           for i in range(3)]

    seqs = []
    for i, x in enumerate(ids):
        mm = PATTERN.match(x)
        c.is_true(bool(mm), "第 %d 张单号格式正确（%s）" % (i + 1, x))
        if mm:
            seqs.append(int(mm.group(2)))

    c.eq(len(set(ids)), 3, "连续提交 3 张，单号互不相同")

    if len(seqs) == 3:
        c.eq(seqs[1] - seqs[0], 1, "第 2 张比第 1 张大 1（实际 %d → %d）" % (seqs[0], seqs[1]))
        c.eq(seqs[2] - seqs[1], 1, "第 3 张比第 2 张大 1（实际 %d → %d）" % (seqs[1], seqs[2]))

    # ══ 第 3 组：所有单号的日期段一致 ══
    dates = set()
    for x in ids:
        mm = PATTERN.match(x)
        if mm:
            dates.add(mm.group(1))
    c.eq(len(dates), 1, "三张单的日期段一致（同一天）")

    return c


if __name__ == "__main__":
    run(main)
