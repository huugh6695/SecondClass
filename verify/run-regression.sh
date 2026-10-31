#!/usr/bin/env bash
# ============================================================================
# 全套接口验收 · 总调度
#
# 用法：
#     bash verify/run-regression.sh 8117
#
# 前置条件（重要）：
#     那个端口上**必须已经跑着一个服务实例**。本脚本只负责跑验收，不负责启动服务。
#     为什么？因为验收要打一个"干净"的实例，而启动方式因人而异
#     （IDEA 里点 Run / java -jar / sb.sh start），脚本不去猜。
#
#     想用临时实例（不打扰 IDEA 里占着的 8080）：
#         bash ~/.workbuddy/skills/spring-boot-local-e2e-verify/scripts/sb.sh start 8117 "$PWD"
#         bash ~/.workbuddy/skills/spring-boot-local-e2e-verify/scripts/sb.sh wait  8117 "$PWD"
#         bash verify/run-regression.sh 8117
#         bash ~/.workbuddy/skills/spring-boot-local-e2e-verify/scripts/sb.sh stop  8117
#
# 退出码：
#     0 = 全部通过（允许有 SKIP）
#     1 = 有脚本失败
#     2 = 用法错误，或服务没起来
#
# 最后一行是 REGRESSION OK 或 REGRESSION FAILED —— 可以直接 grep 这一句判断。
# ============================================================================

set -u

PORT="${1:-}"
if [ -z "$PORT" ]; then
  echo "用法: bash verify/run-regression.sh <端口> [脚本.py ...]"
  echo "例:   bash verify/run-regression.sh 8117"
  echo "      bash verify/run-regression.sh 8117 verify-frontend.py   # 只跑指定脚本"
  exit 2
fi

# 传了脚本名就只跑这几个（改前端时只跑 verify-frontend.py 很常用，省时间）
ONLY=()
if [ $# -ge 2 ]; then
  shift
  ONLY=("$@")
fi

# 脚本自己所在目录（verify/），以及项目根目录。
# ⚠️ 项目路径含空格（"basic codes"），下面所有用到路径的地方都必须加引号。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Python 解释器：默认用 PATH 里的 python，可用 PYTHON 环境变量覆盖。
PYTHON_BIN="${PYTHON:-python}"

# Windows 中文环境下 Python 默认用 GBK 输出，中文会乱码或直接抛
# UnicodeEncodeError。强制 UTF-8，和脚本内部的 reconfigure 双保险。
export PYTHONIOENCODING=utf-8

BASE_URL="http://127.0.0.1:${PORT}"
export SB_BASE="$BASE_URL"

# ─────────────────────────────────────────────────────────────
# 脚本清单（**按依赖顺序**，健康检查永远排第一）
#
# 这里把 20 个脚本全部列出来，但只跑「文件存在」的那些 ——
# 所以以后往 verify/ 里加脚本，不用改这个文件，下次运行自动生效。
# 还没重建的会列入"尚未重建"清单，不算失败。
# ─────────────────────────────────────────────────────────────
ALL_SCRIPTS=(
  verify-health.py
  verify-user.py
  verify-registration.py
  verify-blacklist.py
  verify-detail-display.py
  verify-search.py
  verify-seqid.py
  verify-idempotent.py
  verify-flag.py
  verify-sensitive-word.py
  verify-sensitive-cache.py
  verify-merge.py
  verify-duplicate.py
  verify-statistics.py
  verify-export.py
  verify-admin-logs.py
  verify-faq.py
  verify-announcement.py
  verify-template.py
  verify-attach-bind.py
  verify-frontend.py
)

# 命令行指定了脚本名 → 只跑这几个；否则跑全部
if [ ${#ONLY[@]} -gt 0 ]; then
  ALL_SCRIPTS=("${ONLY[@]}")
fi

echo "════════════════════════════════════════════════════════════════"
echo " 第二课堂学时答疑系统 · 接口验收"
echo " 目标   $BASE_URL"
echo " 项目   $PROJECT_DIR"
echo "════════════════════════════════════════════════════════════════"
echo ""

# ─────────────────────────────────────────────────────────────
# 第一步：先确认服务活着
#
# 不先做这步的话，20 个脚本会各报一次「连不上」，满屏红字里
# 真正的信息（服务没起）反而看不见。
# ─────────────────────────────────────────────────────────────
echo "▶ 预检：服务是否就绪 ..."

PROBE="$("$PYTHON_BIN" - "$BASE_URL" <<'PY' 2>&1
import sys, json, urllib.request, urllib.error
url = sys.argv[1] + "/health"
try:
    with urllib.request.urlopen(url, timeout=15) as r:
        body = json.loads(r.read().decode("utf-8"))
    d = body.get("data") or {}
    print("OK %s db=%s redis=%s" % (r.status, d.get("db"), d.get("redis")))
except urllib.error.HTTPError as e:
    # 503 也算"服务活着"—— 它明确告诉我们依赖挂了，比连不上有用得多
    try:
        body = json.loads(e.read().decode("utf-8"))
        d = body.get("data") or {}
        print("OK %s db=%s redis=%s" % (e.code, d.get("db"), d.get("redis")))
    except Exception:
        print("BAD 服务返回 %s 但不是 JSON" % e.code)
except Exception as ex:
    print("BAD %s" % ex)
PY
)"

echo "  $PROBE"

case "$PROBE" in
  OK*)
    echo "  ✓ 服务就绪"
    ;;
  *)
    echo ""
    echo "✗ 服务没起来（或端口不对）：$BASE_URL"
    echo ""
    echo "  先起一个临时实例（不会占用你 IDEA 的 8080）："
    echo "    bash ~/.workbuddy/skills/spring-boot-local-e2e-verify/scripts/sb.sh start $PORT \"$PROJECT_DIR\""
    echo "    bash ~/.workbuddy/skills/spring-boot-local-e2e-verify/scripts/sb.sh wait  $PORT \"$PROJECT_DIR\""
    echo ""
    echo "  跑完记得停掉："
    echo "    bash ~/.workbuddy/skills/spring-boot-local-e2e-verify/scripts/sb.sh stop $PORT"
    exit 2
    ;;
esac

echo ""

# ─────────────────────────────────────────────────────────────
# 第二步：逐个跑
#
# 每个脚本自己负责打印断言明细和本脚本的小结；
# 调度器只看退出码，并记录名字和耗时。
# ─────────────────────────────────────────────────────────────
PASSED=()
FAILED=()
MISSING=()
START_ALL=$SECONDS

for s in "${ALL_SCRIPTS[@]}"; do
  path="$SCRIPT_DIR/$s"

  if [ ! -f "$path" ]; then
    MISSING+=("$s")
    continue
  fi

  echo "──────────────────────────────────────────────────────────────"
  t0=$SECONDS
  "$PYTHON_BIN" "$path" "$PORT"
  code=$?
  cost=$((SECONDS - t0))

  if [ "$code" -eq 0 ]; then
    PASSED+=("$s")
    echo "✔ $s 通过（${cost}s）"
  else
    FAILED+=("$s:$code")
    echo "✘ $s 失败（退出码 $code，${cost}s）"
  fi
  echo ""
done

# ─────────────────────────────────────────────────────────────
# 第三步：汇总
# ─────────────────────────────────────────────────────────────
TOTAL=$((SECONDS - START_ALL))

echo "════════════════════════════════════════════════════════════════"
echo " 汇总"
echo "════════════════════════════════════════════════════════════════"
echo "  已实现并通过   ${#PASSED[@]} 个"
echo "  已实现但失败   ${#FAILED[@]} 个"
echo "  尚未重建       ${#MISSING[@]} 个"
echo "  总耗时         ${TOTAL} 秒"

if [ ${#FAILED[@]} -gt 0 ]; then
  echo ""
  echo "  失败清单："
  for f in "${FAILED[@]}"; do
    echo "    · $f"
  done
fi

if [ ${#MISSING[@]} -gt 0 ]; then
  echo ""
  echo "  尚未重建（不算失败，只是还没写）："
  echo "    ${MISSING[*]}"
fi

echo ""
if [ ${#FAILED[@]} -eq 0 ]; then
  RESULT=0
  RESULT_TEXT="REGRESSION OK"
else
  RESULT=1
  RESULT_TEXT="REGRESSION FAILED（${#FAILED[@]} 个脚本失败）"
fi

# ─────────────────────────────────────────────────────────────
# 写一份结果存档，给 verify/status.sh 读。
#
# 🔴 为什么一定要记 commit：
#    只写"21 个脚本通过"没有任何意义 —— 不知道是什么时候、对着哪一版代码跑的。
#    提交几次之后，这条记录要么已经过期，要么被误当成"当前代码也通过"。
#    带上 commit，status.sh 才能自己判断「这份结果还算不算数」。
#
# 文件不进版本库（.gitignore 里已忽略），它只代表"最近一次运行"。
# ─────────────────────────────────────────────────────────────
RUN_COMMIT="$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
RUN_DIRTY="$(git -C "$PROJECT_DIR" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
cat > "$SCRIPT_DIR/.last-run.json" <<JSON
{
  "commit": "$RUN_COMMIT",
  "dirtyFiles": $RUN_DIRTY,
  "runAt": "$(date '+%Y-%m-%dT%H:%M:%S%z')",
  "scripts": $(( ${#PASSED[@]} + ${#FAILED[@]} )),
  "passed": ${#PASSED[@]},
  "failed": ${#FAILED[@]},
  "missing": ${#MISSING[@]},
  "seconds": $TOTAL,
  "exit": $RESULT
}
JSON

echo "$RESULT_TEXT"
exit $RESULT
