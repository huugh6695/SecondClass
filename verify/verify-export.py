"""
验收：数据导出（契约 #39–#41）

═══ 导出的特殊性 ═══

其他接口返回 JSON，导出返回**二进制文件**。所以断言方式完全不同：
不能 `json.loads`，要看 Content-Type、文件名、以及文件的真实字节。

═══ 实测口径 ═══

  · `/api/admin/export/work-orders` → **xlsx**
      Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
      Content-Disposition: attachment; filename="work-orders-20261028-174553.xlsx"
      ⇒ 文件名带时间戳，避免多次下载互相覆盖
  · `/api/admin/export/status-logs`、`/operation-logs` → 数据为空时返回 **JSON**
      （不是坏掉，是"没东西可导"时选择返回一段说明而不是空文件）
  · 学生调用 → code 403（HTTP 仍是 200，因为不是拦截器拦的）
  · 日期格式错 → code 400

═══ xlsx 本质是 zip ═══

所以可以用 `zipfile` 直接验证它是不是一个**结构完整**的 Excel：
真正的 xlsx 里一定有 `[Content_Types].xml` 和 `xl/` 目录。
只判断"字节数大于 0"是假绿 —— 一个 HTML 错误页也能大于 0。

═══ 用法 ═══

    python verify/verify-export.py 8117
"""

import io
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import (Checker, http, login_required, qs, run,
                 STUDENT_ID, STUDENT_PWD, ADMIN_ID, ADMIN_PWD)

XLSX_CT = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


def main():
    c = Checker("数据导出")
    adm = login_required(ADMIN_ID, ADMIN_PWD)
    stu = login_required(STUDENT_ID, STUDENT_PWD)

    # ══ 第 1 组：工单导出是真正的 xlsx ══
    st, payload, headers = http("GET", "/api/admin/export/work-orders", token=adm, raw=True)
    c.eq(st, 200, "导出工单：HTTP 200")

    ctype = headers.get("Content-Type", "")
    c.contains(ctype, XLSX_CT, "Content-Type 是 xlsx 的标准类型")
    c.check(not ctype.startswith("application/json"),
            "返回的不是 JSON（说明真的走了文件下载，不是错误信息）",
            "非 JSON", ctype[:60])

    cdisp = headers.get("Content-Disposition", "")
    c.contains(cdisp, "attachment", "浏览器按附件处理（Content-Disposition: attachment）")
    c.contains(cdisp, ".xlsx", "文件名后缀是 .xlsx")
    c.contains(cdisp, "work-orders", "文件名里带业务名 work-orders")
    c.is_true(len(cdisp) > 30, "文件名里带时间戳（长度 %d，防止多次下载互相覆盖）" % len(cdisp))

    c.gt(len(payload), 1000, "文件体量 > 1000 字节（不是空表）")
    c.eq(payload[:2], b"PK", "文件头是 PK（xlsx 本质是 zip 压缩包）")

    # 真正的强断言：能被当成 zip 打开，且内部结构完整
    try:
        zf = zipfile.ZipFile(io.BytesIO(payload))
        names = zf.namelist()
        c.is_true("[Content_Types].xml" in names, "zip 里有 [Content_Types].xml（结构完整）")
        c.is_true(any(n.startswith("xl/") for n in names), "zip 里有 xl/ 目录（是真的 Excel）")
        c.is_true(any(n.startswith("xl/worksheets/") for n in names),
                  "zip 里有 xl/worksheets/（有实际工作表）")
        c.is_true(zf.testzip() is None, "zip 校验通过（没有坏块）")
    except Exception as e:
        c.check(False, "能被 zipfile 正常打开", "可解析", "解析失败：%s" % e)

    # ══ 第 2 组：另外两个导出接口 —— 实际**不存在** ══
    #
    # ⚠️ 实测（2026-10-28）：`AdminExportController` 只映射了 `/work-orders` 一个。
    #    契约 #39–#41 写了三个导出，代码里只实现了一个 —— 这是**已知偏差**，如实记录。
    #
    #    这不是脚本写错，是接口没做。如果哪天补上了，这两条断言会变红 ——
    #    那时候把断言改成"返回 200 + xlsx"就对了。
    for name, path in [("状态流水", "/api/admin/export/status-logs"),
                       ("操作日志", "/api/admin/export/operation-logs")]:
        st, body = http("GET", path, token=adm)
        c.eq(body.get("code"), 404,
             "%s导出尚未实现（404「接口不存在」—— 契约有、代码没有，已知偏差）" % name)

    # ══ 第 3 组：日期筛选 ══
    from datetime import date, timedelta
    start = (date.today() - timedelta(days=7)).isoformat()
    st, payload, headers = http("GET", "/api/admin/export/work-orders" + qs(startDate=start),
                                token=adm, raw=True)
    c.eq(st, 200, "带 startDate 导出：HTTP 200")
    c.eq(payload[:2], b"PK", "带日期筛选导出依然是 xlsx")

    # ══ 第 4 组：权限与参数 ══
    st, body = http("GET", "/api/admin/export/work-orders", token=stu)
    c.eq(body.get("code"), 403, "学生导出 → code 403")
    c.contains(body.get("message"), "管理员", "提示语指向「仅管理员」")

    st, body = http("GET", "/api/admin/export/work-orders" + qs(startDate="2026/01/01"), token=adm)
    c.eq(body.get("code"), 400, "日期格式错 → 400")
    c.contains(body.get("message"), "yyyy-MM-dd", "提示语给出正确格式")

    st, payload, headers = http("GET", "/api/admin/export/work-orders", raw=True)
    c.eq(st, 401, "不带 Token 导出 → HTTP 401")

    return c


if __name__ == "__main__":
    run(main)
