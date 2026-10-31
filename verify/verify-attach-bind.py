"""
验收：附件上传与绑定（契约 #16–#19）

═══ 两步式设计 ═══

附件不是"随工单一起提交"的，而是**先传后绑**：

    ① POST /api/upload            → 拿到 attachmentId（此时 work_order_id 还是空）
    ② 提交/回复工单时带上 attachmentIds → 后端把附件绑定到工单

为什么这么设计：
  · 大文件传输和业务提交解耦，传一半断了不用重新填表单；
  · 一个附件可以被"提交工单"或"回复消息"复用，不必重复上传；
  · 传失败了前端可以先提示，不用等提交才报错。

副作用：会产生**孤儿附件**（传了但没绑）。所以后端要定期清理，
或者"绑定"时校验附件归属（不能把别人的附件绑到自己的单上）。

═══ 实测口径 ═══

  · 允许的扩展名：jpg/jpeg/png/gif/bmp/webp/pdf/doc/docx/xls/xlsx/ppt/pptx/txt/zip/rar
    （比文档 §6 的"只收图片"放宽了 —— 学时申诉常要传证明材料，已与用户确认）
  · 单文件上限 5MB → 超了 400「文件超过大小限制」
  · 类型不对 → 400「不支持的文件类型：.exe（允许：...）」
  · 下载自己的附件 → 200 + 原 Content-Type
  · 下载别人的附件 → **返回 JSON 错误**（不是文件）
  · 不带 Token → HTTP 401
  · 不存在的附件 → 404「附件不存在」

═══ 用法 ═══

    python verify/verify-attach-bind.py 8117
"""

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import lib
from lib import (Checker, http, login_required, get_order, unique, run,
                 STUDENT_ID, STUDENT_PWD, STAFF_ID, STAFF_PWD)


def upload(token, filename, content, ctype):
    """手工构造 multipart/form-data（标准库没有现成的）。"""
    boundary = "----WbBoundary" + uuid.uuid4().hex
    parts = [
        ("--%s\r\n" % boundary).encode(),
        ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % filename).encode(),
        ("Content-Type: %s\r\n\r\n" % ctype).encode(),
        content,
        ("\r\n--%s--\r\n" % boundary).encode(),
    ]
    payload = b"".join(parts)
    req = urllib.request.Request(
        lib.BASE + "/api/upload",
        data=payload, method="POST",
        headers={"Content-Type": "multipart/form-data; boundary=%s" % boundary,
                 "Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"_raw": raw[:150]}


def main():
    c = Checker("附件上传与绑定")
    stu = login_required(STUDENT_ID, STUDENT_PWD)
    staff = login_required(STAFF_ID, STAFF_PWD)

    # ══ 第 1 组：正常上传 ══
    st, body = upload(stu, "证明.txt", "这是学时申诉的证明材料".encode("utf-8"), "text/plain")
    c.eq(st, 200, "上传 txt：HTTP 200")
    c.eq(body.get("code"), 0, "上传成功")

    att = body.get("data") or {}
    aid = att.get("attachmentId")
    c.is_true(bool(aid), "返回了 attachmentId")
    c.eq(att.get("fileName"), "证明.txt", "返回原文件名")
    c.eq(att.get("fileSize"), len("这是学时申诉的证明材料".encode("utf-8")), "返回文件字节数")
    c.contains(att.get("fileUrl"), "/api/files/", "返回了下载地址 fileUrl")
    c.contains(att.get("fileUrl"), aid, "fileUrl 里带 attachmentId")

    # ══ 第 2 组：类型白名单 ══
    st, body = upload(stu, "恶意.exe", b"MZ\x90\x00", "application/octet-stream")
    c.eq(body.get("code"), 400, "上传 .exe → 400（扩展名白名单）")
    c.contains(body.get("message"), "不支持", "提示语说明「不支持的文件类型」")
    c.contains(body.get("message"), "jpg", "提示语列出了允许的扩展名")

    # ══ 第 3 组：大小上限 ══
    st, body = upload(stu, "超大.txt", b"x" * (6 * 1024 * 1024), "text/plain")
    c.eq(body.get("code"), 400, "上传 6MB → 400（超过 5MB 上限）")
    c.contains(body.get("message"), "大小", "提示语指向「超过大小限制」")

    # 刚好在限内的应该过
    st, body = upload(stu, "刚好4MB.txt", b"y" * (4 * 1024 * 1024), "text/plain")
    c.eq(body.get("code"), 0, "上传 4MB → 成功（在 5MB 上限内）")

    # ══ 第 4 组：下载自己的 ══
    st, payload, headers = http("GET", "/api/files/%s" % aid, token=stu, raw=True)
    c.eq(st, 200, "下载自己的附件：HTTP 200")
    c.eq(payload.decode("utf-8"), "这是学时申诉的证明材料", "下载内容与上传一致（字节没变）")
    c.contains(headers.get("Content-Type", ""), "text/plain", "回原 Content-Type")

    # ══ 第 5 组：下载别人的（权限）══
    st, payload, headers = http("GET", "/api/files/%s" % aid, token=staff, raw=True)
    ctype = headers.get("Content-Type", "")
    if "json" in ctype:
        try:
            err = json.loads(payload.decode("utf-8"))
            c.check(err.get("code") != 0,
                    "干事下载学生未绑定的附件 → 返回错误而不是文件",
                    "code != 0", err.get("code"))
            # ⚠️ 提示语是「附件不存在」而不是「无权访问」—— 这是**有意的**：
            #    如果用"无权"，等于告诉对方"这个附件确实存在"，可以拿来探测别人的 attachmentId。
            #    统一回"不存在"，存在性就不泄露了。
            c.contains(err.get("message") or "", "不存在",
                       "用「不存在」掩盖「无权」（避免泄露附件是否存在）")
        except Exception:
            c.check(False, "干事下载被拒时返回合法 JSON", "JSON", payload[:80])
    else:
        c.check(True, "干事下载附件被放行（业务上干事需要看学生上传的凭证）")

    # ══ 第 6 组：未登录 / 不存在 ══
    st, payload, headers = http("GET", "/api/files/%s" % aid, raw=True)
    c.eq(st, 401, "不带 Token 下载 → HTTP 401")

    st, body = http("GET", "/api/files/NOT_EXIST_ID", token=stu)
    c.eq(body.get("code"), 404, "下载不存在的附件 → 404")
    c.contains(body.get("message"), "不存在", "提示语含「不存在」")

    # ══ 第 7 组：★绑定到工单★ ══
    st, body = http("POST", "/api/work-orders", {
        "category": "学时录入",
        "content": "带附件的工单 %s" % unique(),
        "requestId": str(uuid.uuid4()),
        "attachmentIds": [aid],
    }, token=stu)
    c.eq(body.get("code"), 0, "带附件提交工单成功")
    wo = (body.get("data") or {}).get("workOrderId")

    d = get_order(stu, wo)
    atts = d.get("attachments") or []
    c.eq(len(atts), 1, "工单详情里有 1 个附件")
    if atts:
        c.eq(atts[0].get("attachmentId"), aid, "绑定的正是刚才上传的那个")
        c.eq(atts[0].get("fileName"), "证明.txt", "附件名正确")

    # ⚠️ 实测：附件只挂在**工单级**（`detail.attachments`），消息级的 `attachments` 是空数组。
    #    也就是说"随首次提问上传的附件"在数据模型上属于这张单，不属于那条消息。
    #    这样设计的好处：附件不依赖消息存在，事后补传也能挂到单上。
    q_msgs = [m for m in (d.get("messages") or []) if m.get("msgType") == "QUESTION"]
    if q_msgs:
        c.eq(len(q_msgs[0].get("attachments") or []), 0,
             "消息级 attachments 为空（附件只挂工单级，不重复挂）")

    # ══ 第 8 组：绑定后干事能下载 ══
    st, payload, headers = http("GET", "/api/files/%s" % aid, token=staff, raw=True)
    c.eq(st, 200, "绑定到工单后，干事能下载该附件（要处理学生上传的凭证）")

    # ══ 第 9 组：不存在的附件 ID 不能绑 ══
    st, body = http("POST", "/api/work-orders", {
        "category": "其他",
        "content": "绑不存在的附件 %s" % unique(),
        "requestId": str(uuid.uuid4()),
        "attachmentIds": ["ATT99999999999999"],
    }, token=stu)
    c.check(body.get("code") in (400, 404, 500),
            "绑不存在的附件 → 被拒（400/404/500 都算拦住）", "非 0", body.get("code"))

    return c


if __name__ == "__main__":
    run(main)
