"""
验收脚本公共库（只用 Python 标准库，不依赖 requests / pytest）。

═══ 为什么需要这个文件 ═══

20 个验收脚本如果各写各的 HTTP 封装，将来改一次超时时间要改 20 个地方。
所以这里把三件事统一：

  ① HTTP 请求  —— 带 Token、发 JSON、**4xx/5xx 不抛异常**（本项目大量接口用
                  400/401/403 表达业务失败，脚本得能断言这些状态码）
  ② 断言        —— check / eq / contains，失败时打印「期望什么、实际什么」
  ③ 结果汇总    —— 最后打印 N 通过 / M 失败，并用退出码告诉调度器

═══ 一个验收脚本长什么样 ═══

    from lib import Checker, http, run

    def main():
        c = Checker("健康检查")
        st, body = http("GET", "/health")
        c.eq(st, 200, "HTTP 状态码")
        c.eq(body.get("code"), 0, "业务码 code")
        return c

    if __name__ == "__main__":
        run(main)

═══ 和 JUnit 的区别 ═══

这些脚本测的是**黑盒行为**：起一个真实服务实例，用 HTTP 打真实接口，
检查返回的 JSON。它们不碰 Java 内部代码，所以服务必须先在跑
（`bash verify/run-regression.sh <端口>` 不会帮你启动服务）。

═══ 端口怎么传 ═══

    python verify/verify-health.py 8117

不传端口时默认 8080（注意：8080 可能被你 IDEA 里跑着的实例占着）。
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request

# 单次请求超时（秒）。健康检查首次探活实测要 3~5 秒（Lettuce 首次建连开销），
# 所以别设太小，否则会误报「接口超时」。
DEFAULT_TIMEOUT = 20

# 由 run() 根据命令行参数设置
BASE = "http://127.0.0.1:8080"


# ═══════════════════════════════════════════════════════════════
# 断言收集器
# ═══════════════════════════════════════════════════════════════

class Checker:
    """收集断言结果，最后决定这个脚本算不算通过。"""

    def __init__(self, title):
        self.title = title
        self.passed = 0
        self.failed = 0
        self.skipped = 0
        self.fail_labels = []

    # ---- 基础断言 ----

    def check(self, cond, label, expect=None, actual=None):
        """cond 为真则通过；为假时把「期望/实际」一起打出来，省得再去猜。"""
        if cond:
            self.passed += 1
            print("  [OK]   %s" % label)
            return True

        self.failed += 1
        self.fail_labels.append(label)
        print("  [FAIL] %s" % label)
        if expect is not None:
            print("         期望: %s" % _short(expect))
        if actual is not None:
            print("         实际: %s" % _short(actual))
        return False

    def eq(self, actual, expect, label):
        """相等断言。注意顺序是 (实际值, 期望值)，故意跟 unittest 一致。"""
        return self.check(actual == expect, label, expect, actual)

    def ne(self, actual, unexpected, label):
        """不等断言 —— 用来证明「这次的结果和上次不一样」（比如两次单号不能相同）。"""
        return self.check(actual != unexpected, label, "不等于 %s" % _short(unexpected), actual)

    def is_true(self, value, label):
        return self.check(bool(value), label, True, value)

    def is_none(self, value, label):
        return self.check(value is None, label, None, value)

    def contains(self, container, item, label):
        ok = container is not None and item in container
        return self.check(ok, label, "包含 %s" % _short(item), container)

    def in_(self, actual, allowed, label):
        """actual 必须是 allowed 里的某一个。"""
        return self.check(actual in allowed, label, "属于 %s" % _short(list(allowed)), actual)

    def gt(self, actual, floor, label):
        ok = isinstance(actual, (int, float)) and actual > floor
        return self.check(ok, label, "> %s" % floor, actual)

    # ---- 跳过（不是失败）----

    def skip(self, label, reason):
        """
        环境不具备时用它，**不算失败**。

        典型场景：本机没启 Redis，/health 的 redis 字段报 DOWN —— 那是如实反映，
        不是接口坏了。这时候应该 SKIP 而不是 FAIL，否则每次都一片红，
        真出问题时反而看不出来。
        """
        self.skipped += 1
        print("  [SKIP] %s —— %s" % (label, reason))

    # ---- 汇总 ----

    def summary(self):
        print("")
        total = self.passed + self.failed
        line = "%s：%d 通过" % (self.title, self.passed)
        if self.skipped:
            line += "，%d 跳过" % self.skipped
        if self.failed:
            line += "，\033[31m%d 失败\033[0m" % self.failed
        else:
            line += "，\033[32m0 失败\033[0m"
        print(line)
        if self.failed:
            print("  失败的断言：")
            for lbl in self.fail_labels:
                print("    · %s" % lbl)
        return self.failed == 0


def _short(value, limit=300):
    """把长值截断，避免一次打印几百行把终端刷爆。"""
    text = repr(value)
    if len(text) <= limit:
        return text
    return text[:limit] + "...(共 %d 字符)" % len(text)


# ═══════════════════════════════════════════════════════════════
# 标准测试账号
# ═══════════════════════════════════════════════════════════════
#
# 🔴 本地测试库只有这 3 个账号（线上那 95 人是后来导入的，本地没有）。
#    所以凡是"需要两个学生/两个干事"的场景，本地跑不了 —— 别写这种断言。
#
# 🔴 密码是 123456，但注意：**改密接口不允许纯数字**
#    （要求 6-20 位且同时含字母和数字）⇒ 一旦脚本把密码改成功，就再也改不回 123456。
#    所以常规回归里**只测非法改密的校验**，不测"合法改密成功"——
#    那条会污染环境，变成一个跑一次就坏一次的东西。

STUDENT_ID, STUDENT_PWD = "2023001001", "123456"
STAFF_ID, STAFF_PWD = "2023002001", "123456"
ADMIN_ID, ADMIN_PWD = "2023003001", "123456"


def login(user_id, password="123456"):
    """
    登录，返回 (token, 完整响应体)。失败时 token 为 None。

    注意返回体的字段名是 `role`（不是 userType），值 STUDENT / STAFF / ADMIN。
    """
    status, body = http("POST", "/api/auth/login",
                        {"userId": user_id, "password": password})
    data = body.get("data") or {}
    return data.get("token"), body


def login_required(user_id, password="123456"):
    """
    登录，失败直接抛异常。

    用在"这个脚本没有登录就完全没法跑"的地方 —— 与其让后面 30 条断言
    稀里哗啦全红，不如停在这里报一句人话。
    """
    token, body = login(user_id, password)
    if not token:
        raise RuntimeError(
            "前置条件不满足：账号 %s 登录失败（code=%s, %s）。"
            "本地库的账号密码是不是被改过？见 verify/lib.py 顶部说明。"
            % (user_id, body.get("code"), body.get("message")))
    return token


def qs(**kwargs):
    """
    拼查询串，自动 urlencode。

    🔴 为什么不能手拼：中文直接塞进 URL 会让 urllib 用 ascii 编码请求行
    ⇒ `UnicodeEncodeError`，脚本起手就崩、连汇总都打不出来。
    """
    from urllib.parse import urlencode
    parts = [(k, v) for k, v in kwargs.items() if v is not None]
    return ("?" + urlencode(parts)) if parts else ""


# ═══════════════════════════════════════════════════════════════
# 造数据助手
# ═══════════════════════════════════════════════════════════════
#
# 🔴 规矩：断言要**自己造前置数据**，不要依赖"库里本来有一张 PENDING 的单"。
#    这个库现在有 3600+ 张历史工单，靠"恰好有一张"写出来的断言，
#    换台机器跑就红。造完的数据不要删（删了会让统计断言变成负增长），
#    但要保证内容唯一 —— 用 uuid 兜住重复检测。

def new_order(token, category="其他", content=None):
    """提交一张工单，返回 workOrderId。失败直接抛异常。"""
    import uuid
    if content is None:
        content = "验收脚本自动创建 %s" % uuid.uuid4()
    status, body = http("POST", "/api/work-orders", {
        "category": category,
        "content": content,
        "requestId": str(uuid.uuid4()),
    }, token=token)
    wo = (body.get("data") or {}).get("workOrderId")
    if not wo:
        raise RuntimeError("造工单失败：code=%s msg=%s"
                           % (body.get("code"), body.get("message")))
    return wo


def get_order(token, work_order_id):
    """取工单详情，返回 data（dict）。失败返回空 dict。"""
    status, body = http("GET", "/api/work-orders/%s" % work_order_id, token=token)
    return body.get("data") or {}


def unique(prefix="验收"):
    """生成唯一文本，避免撞上重复检测、避免同名数据互相干扰。"""
    import uuid
    return "%s-%s" % (prefix, uuid.uuid4().hex[:12])


# ═══════════════════════════════════════════════════════════════
# HTTP
# ═══════════════════════════════════════════════════════════════

def http(method, path, body=None, token=None, headers=None,
         timeout=DEFAULT_TIMEOUT, raw=False):
    """
    发一个请求，返回 (HTTP 状态码, 响应体)。

    「响应体」的返回规则：
      · 服务端返回 JSON 且能解析 → dict
      · 返回的不是 JSON（比如静态资源、nginx 错误页、空 body）→ {"_raw": "文本"}
      · raw=True → 返回 (状态码, 原始字节, 响应头 dict)，**不解析**
        （verify-export 要验 Content-Type / Content-Disposition，verify-frontend 要读源码，
          两者都必须是三元组；统一成三元组省得调用方记两种情况）

    🔴 4xx / 5xx **不抛异常**。本项目约定「业务失败 HTTP 仍是 200，只有
       401/403/503 是真的非 200」，脚本必须能断言这些状态码 ——
       一旦抛异常就没法测了。所以这里把 HTTPError 当正常响应处理。
    """
    url = path if path.startswith("http") else BASE + path
    data = None
    hdrs = {}

    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        hdrs["Content-Type"] = "application/json; charset=utf-8"
    if token:
        hdrs["Authorization"] = "Bearer " + token
    if headers:
        hdrs.update(headers)

    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    started = time.time()

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = resp.read()
            status = resp.status
            response_headers = dict(resp.headers)
            ctype = resp.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        payload = e.read()
        status = e.code
        response_headers = dict(e.headers) if e.headers else {}
        ctype = response_headers.get("Content-Type", "")
    except urllib.error.URLError as e:
        # 连不上（服务没起 / 端口错）。这不是断言失败，是环境问题，直接抛出去
        # 让调度器报一句「服务没启动」，别让 20 个脚本各刷一遍堆栈。
        raise ConnectionError("连不上 %s —— 服务起了吗？原因：%s" % (url, e.reason))
    finally:
        elapsed = time.time() - started

    if raw:
        return status, payload, response_headers

    text = payload.decode("utf-8", "replace")
    try:
        parsed = json.loads(text)
    except Exception:
        parsed = {"_raw": text, "_content_type": ctype, "_elapsed": elapsed}
    if isinstance(parsed, dict):
        parsed.setdefault("_status", status)
        parsed.setdefault("_elapsed", elapsed)
        parsed.setdefault("_content_type", ctype)
    return status, parsed


def http_bytes(path, timeout=DEFAULT_TIMEOUT):
    """取静态资源原始字节（前端脚本用）。"""
    return http("GET", path, timeout=timeout, raw=True)


# ═══════════════════════════════════════════════════════════════
# 入口
# ═══════════════════════════════════════════════════════════════

def run(main):
    """
    统一入口：解析端口 → 设置 BASE → 跑 main() → 汇总 → 设退出码。

    退出码约定（调度器靠它判断）：
      0 = 全过（可以有 SKIP）
      1 = 有断言失败
      2 = 连不上服务（环境问题，不是代码问题）
    """
    global BASE

    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass

    port = sys.argv[1] if len(sys.argv) > 1 else "8080"
    BASE = os.environ.get("SB_BASE") or ("http://127.0.0.1:%s" % port)

    print("─" * 62)
    print("▶ %s   目标 %s" % (os.path.basename(sys.argv[0]), BASE))
    print("─" * 62)

    try:
        checker = main()
    except ConnectionError as e:
        print("  [环境错误] %s" % e)
        sys.exit(2)
    except Exception as e:
        # 脚本自身写错了（比如 KeyError），也要明确区分开，别混进断言失败里
        import traceback
        print("  [脚本异常] %s: %s" % (type(e).__name__, e))
        traceback.print_exc()
        sys.exit(1)

    sys.exit(0 if checker.summary() else 1)
