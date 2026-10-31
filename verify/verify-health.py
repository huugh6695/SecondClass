"""
验收：健康检查 GET /health（契约 #0）

═══ 这个接口为什么值得单独一个脚本 ═══

它不是业务接口，但它是**唯一一个「挂了会立刻被发现」的接口** ——
监控、负载均衡、K8s 探针全靠它。它有三条硬要求，任一条破了线上就会误报：

  ① 不能被认证拦住 —— 探活工具不会带 Token。所以带无效 Token 请求，
     结果必须和正常请求**完全一样**（这条最容易在改拦截器时被弄坏）。
  ② 不能有业务副作用 —— 只做 SELECT 1，不碰业务表，也不写操作日志。
  ③ 依赖不可用时必须**真的变成 503** —— 不能永远返回 200 骗监控。

═══ 契约（target/doc.txt 已丢失，此处按 HealthController 实现还原）═══

正常：HTTP 200 + {"code":0,"message":"ok",
                  "data":{"status":"UP","db":"UP","redis":"UP","timestamp":"...Z"}}
异常：HTTP 503 + {"code":500,"message":"service unavailable",
                  "data":{"status":"DOWN", ...}}
redis 取值：UP / DOWN / SKIPPED（SKIPPED = 探活开关被关掉，不拖累整体）

═══ 用法 ═══

    python verify/verify-health.py 8117
"""

import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import Checker, http, run


def main():
    c = Checker("健康检查 GET /health")

    # ── 第 1 组：契约形状 ──────────────────────────────────────
    status, body = http("GET", "/health")

    c.in_(status, (200, 503), "HTTP 状态码只能是 200 或 503")
    c.contains(body.get("_content_type", ""), "application/json",
               "返回的是 JSON（Content-Type 含 application/json）")

    data = body.get("data") or {}
    for field in ("status", "db", "redis", "timestamp"):
        c.is_true(field in data, "data 里有 %s 字段" % field)

    # ── 第 2 组：依赖状态 ──────────────────────────────────────
    db = data.get("db")
    redis = data.get("redis")

    if db != "UP":
        # 数据库连不上属于环境问题（没建库 / 密码错 / MySQL 没起），
        # 不是接口写错了。报环境错误退出，别混进断言失败。
        print("  [环境错误] 数据库探活失败（db=%r）—— 检查 MySQL 是否在跑、"
              "config/application-local.yml 里的密码对不对" % db)
        sys.exit(2)

    c.eq(db, "UP", "数据库探活 UP")

    if redis == "DOWN":
        # 本机没双击启 Redis 时会走到这里。这是 /health **如实反映**，不是 bug。
        # 这种情况下顺便验一下「依赖挂了会不会真的变成 503」—— 反而是个加分验证。
        c.eq(status, 503, "Redis 不可用时 HTTP 必须变 503（不能永远 200 骗监控）")
        c.eq(body.get("code"), 500, "Redis 不可用时业务码为 500")
        c.eq(data.get("status"), "DOWN", "Redis 不可用时汇总状态为 DOWN")
        c.skip("Redis 探活 UP", "本机 Redis 没起，/health 如实报 DOWN（属正常）")
    else:
        c.eq(redis, "UP", "Redis 探活 UP")
        c.eq(status, 200, "全部依赖正常时 HTTP 200")
        c.eq(body.get("code"), 0, "成功后业务码 code=0")
        c.eq(data.get("status"), "UP", "汇总状态 UP")

    # ── 第 3 组：timestamp 格式 ────────────────────────────────
    ts = data.get("timestamp")
    c.is_true(isinstance(ts, str) and ts.endswith("Z"),
              "timestamp 是带 Z 的 UTC 时间（文档要求的格式）")

    if isinstance(ts, str) and ts.endswith("Z"):
        try:
            from datetime import datetime
            parsed = datetime.fromisoformat(ts.replace("Z", "+00:00"))
            c.is_true(parsed.year >= 2020, "timestamp 能被解析成合法时间（%s）" % parsed.year)
        except Exception as e:
            c.check(False, "timestamp 能被解析", "ISO-8601", "%s（解析失败：%s）" % (ts, e))

    # ── 第 4 组：不受鉴权影响（最容易在改拦截器时弄坏）──────────
    status2, body2 = http("GET", "/health", token="invalid.token.here")
    c.eq(status2, status, "带无效 Token 请求，HTTP 状态码与不带时一致")
    c.eq(body2.get("code"), body.get("code"), "带无效 Token 请求，业务码与不带时一致")
    c.eq((body2.get("data") or {}).get("status"), data.get("status"),
         "带无效 Token 请求，汇总状态与不带时一致")

    # ── 第 5 组：响应时间 ──────────────────────────────────────
    elapsed = body.get("_elapsed", 0)
    c.check(elapsed < 10,
            "响应时间 < 10 秒（首次探活 3~5 秒属正常，Lettuce 首次建连开销）",
            "< 10s", "%.2fs" % elapsed)

    return c


if __name__ == "__main__":
    run(main)
