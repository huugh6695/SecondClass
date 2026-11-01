#!/usr/bin/env bash
# ============================================================================
# selftest.sh —— 验证「验证工具本身」还能不能用
#
# 为什么需要它（2026-11-01 踩出来的）：
#     `verify/` 下的脚本是产出结论的工具，却不在任何验证范围里 ——
#     源码指纹只覆盖 src/main、pom.xml、schema.sql、verify-*.py、lib.py，
#     改了 status.sh / run.ps1 / run-regression.sh 没有任何信号。
#     结果就是：把 run.ps1 改坏（中文写进 Write-Host、文件又没有 BOM），
#     它在 Windows PowerShell 5.1 下解析失败、零输出、退出码仍为 0，
#     看起来像「跑通了，只是没吭声」。
#     详见 docs/architecture/ENVIRONMENT.md §1.1。
#
# 这个脚本不跑接口、不连数据库、不起服务，只做五件事，2 秒内跑完：
#     ① 所有 .sh 过一遍 bash -n（语法检查）
#     ② 所有 .ps1 必须以 UTF-8 BOM 开头（EF BB BF）
#     ③ source-fingerprint.sh 还能算出指纹
#     ④ status.sh --offline --json 还能输出完整 JSON
#     ⑤ session.sh check --raw 还能算出交接单状态
#
# 用法：
#     bash verify/selftest.sh
#
# 退出码：
#     0 = 全部通过
#     1 = 有检查项失败（下面会列出来）
#     2 = 不在仓库里，跑不了
#
# 它被 run-regression.sh 当作「第 0 步」调用 —— 工具坏了就直接中止，
# 不然后面跑出来的全是不可信的结果。
# ============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR" || exit 2

G=""; Y=""; R=""; N=""
if [ -t 1 ]; then
  G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; N=$'\033[0m'
fi

PASS=0
FAIL=0
FAILED_ITEMS=()

ok() { PASS=$((PASS + 1)); printf '  %s✓%s %s\n' "$G" "$N" "$1"; }
ng() { FAIL=$((FAIL + 1)); FAILED_ITEMS+=("$1 :: $2"); printf '  %s✗%s %s\n      %s\n' "$R" "$N" "$1" "$2"; }

echo "════════════════════════════════════════════════════════════════"
echo " 验证工具自检（selftest）"
echo " 项目   $PROJECT_DIR"
echo "════════════════════════════════════════════════════════════════"

# ─────────────────────────────────────────────────────────────
# ① shell 语法检查
#
# 等价于 .ps1 那套「解析失败」问题在 bash 侧的样子：
# 语法错了，脚本一行都不执行，但外面看不出任何异常。
# `bash -n` 只解析不执行，零副作用、毫秒级。
# ─────────────────────────────────────────────────────────────
echo ""
echo "▶ ① shell 语法检查（bash -n）"

SH_FILES=()
while IFS= read -r f; do SH_FILES+=("$f"); done < <(
  find verify deploy -maxdepth 1 -type f -name '*.sh' 2>/dev/null | sort
)

if [ ${#SH_FILES[@]} -eq 0 ]; then
  ng "shell 语法" "一个 .sh 都没找到（是不是不在仓库根目录？）"
else
  SYNTAX_BAD=0
  for f in "${SH_FILES[@]}"; do
    if ! ERR="$(bash -n "$f" 2>&1)"; then
      SYNTAX_BAD=$((SYNTAX_BAD + 1))
      ng "语法错误：$f" "${ERR//$'\n'/ ; }"
    fi
  done
  [ "$SYNTAX_BAD" -eq 0 ] && ok "${#SH_FILES[@]} 个 .sh 语法正常"
fi

# ─────────────────────────────────────────────────────────────
# ② .ps1 必须带 UTF-8 BOM
#
# 没有 BOM 时 Windows PowerShell 5.1 按系统 ANSI（中文机 = GBK）解码。
# 只要**字符串字面量**里有中文，汉字的后半个字节就会吃掉行尾的引号，
# 整个脚本解析失败 —— 而且解析失败不执行任何语句，$LASTEXITCODE
# 保持原值 0，外面看到的是「零输出 + 退出码 0」的假成功。
# 中文只出现在注释里不致命（乱码注释仍然是注释），但统一要求带 BOM，
# 免得下次往字符串里加中文时又踩一遍。
# ─────────────────────────────────────────────────────────────
echo ""
echo "▶ ② .ps1 编码（必须以 EF BB BF 开头）"

PS1_FILES=()
while IFS= read -r f; do PS1_FILES+=("$f"); done < <(
  find . -name '*.ps1' -not -path './target/*' -not -path './.git/*' 2>/dev/null | sort
)

if [ ${#PS1_FILES[@]} -eq 0 ]; then
  ok "仓库里没有 .ps1，跳过"
else
  BOM_BAD=0
  for f in "${PS1_FILES[@]}"; do
    HEAD3="$(od -An -tx1 -N3 "$f" 2>/dev/null | tr -d ' \n')"
    if [ "$HEAD3" != "efbbbf" ]; then
      BOM_BAD=$((BOM_BAD + 1))
      ng "缺 BOM：$f" "前 3 字节是 [$HEAD3]，应为 efbbbf（用编辑器另存为 UTF-8 with BOM）"
    fi
  done
  [ "$BOM_BAD" -eq 0 ] && ok "${#PS1_FILES[@]} 个 .ps1 都带 BOM"
fi

# ─────────────────────────────────────────────────────────────
# ③ 源码指纹还能算出来
#
# status.sh 和 run-regression.sh 都依赖它判断「测试结果还算不算数」。
# 它坏了不会报错，只会静默退化成 unknown，然后所有判定都变宽松。
# ─────────────────────────────────────────────────────────────
echo ""
echo "▶ ③ 源码指纹（source-fingerprint.sh）"

FP="$(bash "$SCRIPT_DIR/source-fingerprint.sh" 2>/dev/null | tr -d '\r\n ')"
if [[ "$FP" =~ ^[0-9a-f]{12}$ ]]; then
  ok "指纹 = $FP"
else
  ng "指纹算不出来" "得到 [$FP]，应为 12 位小写十六进制"
fi

# ─────────────────────────────────────────────────────────────
# ④ status.sh 还能输出完整 JSON
#
# 只看「有没有炸」不够 —— 半路死掉也会返回 0。所以检查结构：
# 开头是 {、结尾是 }、七个顶层键都在、行数不少于 7。
# ─────────────────────────────────────────────────────────────
echo ""
echo "▶ ④ status.sh --offline --json"

STATUS_JSON="$(bash "$SCRIPT_DIR/status.sh" --offline --json 2>/dev/null)"
STATUS_CODE=$?

# status.sh：0 = 没有不一致，1 = 有不一致。两个都算「跑通了」。
if [ "$STATUS_CODE" -ne 0 ] && [ "$STATUS_CODE" -ne 1 ]; then
  ng "status.sh 退出码异常" "得到 $STATUS_CODE（应为 0 或 1）"
else
  STATUS_BAD=""
  case "$STATUS_JSON" in
    \{*) : ;;
    *) STATUS_BAD="输出不是以 { 开头" ;;
  esac
  [ -z "$STATUS_BAD" ] && [ "$(printf '%s' "$STATUS_JSON" | tail -c 1)" != "}" ] && STATUS_BAD="输出不是以 } 结尾"
  for k in '"git"' '"scale"' '"build"' '"test"' '"remote"' '"database"' '"handover"' '"warnings"'; do
    case "$STATUS_JSON" in
      *"$k"*) : ;;
      *) STATUS_BAD="缺少顶层键 $k" ;;
    esac
  done
  LINES="$(printf '%s\n' "$STATUS_JSON" | wc -l | tr -d ' ')"
  [ -z "$STATUS_BAD" ] && [ "$LINES" -lt 7 ] && STATUS_BAD="只有 $LINES 行，像是半路断了"

  if [ -z "$STATUS_BAD" ]; then
    ok "JSON 结构完整（$LINES 行，退出码 $STATUS_CODE）"
  else
    ng "status.sh 输出不完整" "$STATUS_BAD"
  fi
fi

# ─────────────────────────────────────────────────────────────
# ⑤ session.sh 还能算出交接单状态
#
# 同样不看退出码看内容：必须真的吐出 STATE=，且取值在已知集合里。
# （missing 是正常情况：交接单不进 Git，新克隆天然没有。）
# ─────────────────────────────────────────────────────────────
echo ""
echo "▶ ⑤ session.sh check --raw"

SESSION_RAW="$(bash "$SCRIPT_DIR/session.sh" check --raw 2>/dev/null)"
STATE="$(printf '%s\n' "$SESSION_RAW" | sed -n 's/^STATE=//p')"
case "$STATE" in
  ok|behind|stale|missing|unknown) ok "STATE=$STATE" ;;
  "") ng "session.sh 没有输出 STATE" "得到空值，脚本可能解析失败" ;;
  *) ng "session.sh 输出了未知状态" "STATE=$STATE" ;;
esac

# ─────────────────────────────────────────────────────────────
# 汇总
# ─────────────────────────────────────────────────────────────
echo ""
if [ "$FAIL" -eq 0 ]; then
  printf '%sSELFTEST OK%s（%d 项全过）\n' "$G" "$N" "$PASS"
  exit 0
fi

printf '%sSELFTEST FAILED%s（%d 项失败 / 共 %d 项）\n' "$R" "$N" "$FAIL" "$((PASS + FAIL))"
echo "  失败清单："
for item in "${FAILED_ITEMS[@]}"; do
  echo "    · $item"
done
exit 1
