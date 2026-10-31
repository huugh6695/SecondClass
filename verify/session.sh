#!/usr/bin/env bash
# ============================================================================
# session.sh —— 会话交接单：生成 / 查看 / 检查是否过期
#
# 为什么需要它：
#   HANDOVER.md 写的是「十年不变」的东西（目录结构、怎么启动）；
#   交接单写的是「只活到下次会话」的东西（停在哪、下一步、为什么没做）。
#   两者混在一起，就会变成 HANDOVER 那种情况——顶部一句话里三处全错。
#
# 用法：
#   bash verify/session.sh           # 看当前交接单
#   bash verify/session.sh new       # 生成/覆盖一份骨架（自动填时间 + commit）
#   bash verify/session.sh check     # 检查是否过期（status.sh 也调它）
#   bash verify/session.sh check --raw   # 给脚本读的 KEY=VALUE
#
# 过期判定（两条独立门槛，任一命中就提醒）：
#   · HEAD 已比交接单里记的 commit 领先 ≥ 5 个提交
#   · 交接单写完已经 ≥ 7 天
#
# 退出码（check）：
#   0 = 最新 / 小幅领先 / 还没有交接单（新克隆的仓库本来就该没有）
#   1 = 已过期（上面两条门槛之一命中）
#   2 = 用法错误
# ============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR" || exit 2

SESSION_FILE="$PROJECT_DIR/SESSION.md"
PREV_FILE="$SCRIPT_DIR/.session-prev.md"
TEMPLATE="$SCRIPT_DIR/SESSION-TEMPLATE.md"

# 门槛：改这两个数字就能调松紧
STALE_COMMITS=5
STALE_DAYS=7

if [ -t 1 ]; then
  G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; D=$'\033[2m'; N=$'\033[0m'
else
  G=''; R=''; Y=''; D=''; N=''
fi

GIT_HEAD="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

# ─────────────────────────────────────────────────────────────
# 从交接单第 1 行读出机器标记
#   <!-- handover: commit=f704cb2 at=2026-10-31T21:40:00+0800 -->
# ─────────────────────────────────────────────────────────────
parse_marker() {
  local line
  line="$(grep -m1 -o 'handover:[^>]*' "$SESSION_FILE" 2>/dev/null || true)"
  H_COMMIT="$(printf '%s' "$line" | sed -n 's/.*commit=\([^ ]*\).*/\1/p')"
  H_AT="$(printf '%s' "$line" | sed -n 's/.*at=\([^ ]*\).*/\1/p')"
}

# ─────────────────────────────────────────────────────────────
# new —— 生成骨架
# ─────────────────────────────────────────────────────────────
cmd_new() {
  if [ ! -f "$TEMPLATE" ]; then
    echo "找不到模板：$TEMPLATE" >&2
    return 2
  fi

  # 旧的那份先留一版。只留一版：交接单不该越攒越多，
  # 否则三个月后有二十份互相矛盾的文件，谁也不知道该信哪个。
  if [ -s "$SESSION_FILE" ]; then
    cp "$SESSION_FILE" "$PREV_FILE" 2>/dev/null || true
    echo "（上一版已存到 verify/.session-prev.md）"
  fi

  local now now_show dirty dirty_text
  now="$(date '+%Y-%m-%dT%H:%M:%S%z')"
  now_show="$(date '+%m-%d %H:%M')"
  dirty="$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
  if [ "$dirty" -eq 0 ]; then
    dirty_text="干净"
  else
    dirty_text="有 ${dirty} 项未提交"
  fi

  # 🔴 用 | 做 sed 分隔符：值里含 / 和 : ，用 / 会把命令切碎
  # 只替换三个占位符。「工作区是否干净」故意不写进文件——
  # 那种东西每次 git status 都会变，写进去就是制造下一个过期点。
  sed -e "s|{{COMMIT}}|${GIT_HEAD}|g" \
      -e "s|{{NOW}}|${now}|g" \
      -e "s|{{TIME_SHOW}}|${now_show}|g" \
      "$TEMPLATE" > "$SESSION_FILE" || return 2

  echo "${G}已生成${N} SESSION.md   写于 ${now_show} · commit ${GIT_HEAD} · 工作区${dirty_text}"
  echo ""
  echo "${D}下面这些只是给你写「停在哪」时参考的，不用抄进文件：${N}"
  echo "${D}  想看清全貌：bash verify/status.sh --offline${N}"
  echo ""
  echo "接下来要做的：把 5 个小节里的括号说明换成真话。"
  echo "写不出来的小节就留一个「无」——空白比编造好。"
}

# ─────────────────────────────────────────────────────────────
# check —— 是否过期
# ─────────────────────────────────────────────────────────────
H_COMMIT=""; H_AT=""; H_STATE=""; H_AHEAD=""; H_DAYS=""; H_DETAIL=""

cmd_check() {
  local raw=0
  [ "${1:-}" = "--raw" ] && raw=1

  if [ ! -f "$SESSION_FILE" ]; then
    H_STATE="missing"
    H_DETAIL="没有交接单（新克隆的仓库本来就没有，不算问题）"
    if [ "$raw" -eq 1 ]; then
      printf 'STATE=%s\nCOMMIT=\nAT=\nAHEAD=\nDAYS=\nDETAIL=%s\n' "$H_STATE" "$H_DETAIL"
    else
      echo "${D}${H_DETAIL}${N}"
      echo "生成一份：bash verify/session.sh new"
    fi
    return 0
  fi

  parse_marker

  if [ -z "$H_COMMIT" ]; then
    H_STATE="unknown"
    H_DETAIL="交接单第 1 行的 handover 标记被改坏了，读不出 commit"
    [ "$raw" -eq 1 ] && printf 'STATE=%s\nCOMMIT=\nAT=\nAHEAD=\nDAYS=\nDETAIL=%s\n' "$H_STATE" "$H_DETAIL"
    [ "$raw" -eq 0 ] && echo "${Y}${H_DETAIL}${N}"
    return 1
  fi

  # 领先多少个提交。注意：commit 不存在（比如 rebase 掉了）时 rev-list 会失败
  H_AHEAD=0
  if [ "$H_COMMIT" != "$GIT_HEAD" ]; then
    if git cat-file -e "${H_COMMIT}^{commit}" >/dev/null 2>&1; then
      H_AHEAD="$(git rev-list --count "${H_COMMIT}..HEAD" 2>/dev/null || echo 0)"
    else
      H_AHEAD="-1"   # 这个 commit 在仓库里已经找不到了
    fi
  fi

  # 放了几天
  H_DAYS=0
  if [ -n "$H_AT" ]; then
    local at_ts now_ts
    at_ts="$(date -d "$H_AT" +%s 2>/dev/null || echo 0)"
    now_ts="$(date +%s)"
    if [ "$at_ts" -gt 0 ]; then
      H_DAYS=$(( (now_ts - at_ts) / 86400 ))
    fi
  fi

  if [ "$H_AHEAD" = "-1" ]; then
    H_STATE="stale"
    H_DETAIL="交接单记的 commit ${H_COMMIT} 在仓库里已经不存在了（可能 rebase 或强推过）"
  elif [ "$H_AHEAD" -ge "$STALE_COMMITS" ]; then
    H_STATE="stale"
    H_DETAIL="交接单写在 ${H_COMMIT}，当前已经是 ${GIT_HEAD}（落后 ${H_AHEAD} 个提交）—— 里面的「下一步」很可能已经做完了"
  elif [ "$H_DAYS" -ge "$STALE_DAYS" ]; then
    H_STATE="stale"
    H_DETAIL="交接单是 ${H_DAYS} 天前写的 —— 环境可能已经变了"
  elif [ "$H_AHEAD" -gt 0 ]; then
    H_STATE="behind"
    H_DETAIL="写在 ${H_COMMIT}，之后有 ${H_AHEAD} 个提交（没超过 ${STALE_COMMITS} 个，仍可参考）"
  else
    H_STATE="ok"
    H_DETAIL="与当前 HEAD ${GIT_HEAD} 一致"
  fi

  if [ "$raw" -eq 1 ]; then
    printf 'STATE=%s\nCOMMIT=%s\nAT=%s\nAHEAD=%s\nDAYS=%s\nDETAIL=%s\n' \
      "$H_STATE" "$H_COMMIT" "$H_AT" "$H_AHEAD" "$H_DAYS" "$H_DETAIL"
  else
    if [ "$H_STATE" = "ok" ] || [ "$H_STATE" = "behind" ]; then
      echo "${G}✓${N} ${H_DETAIL}"
    else
      echo "${Y}⚠${N} ${H_DETAIL}"
      echo "  ${D}确认里面写的还成立，就刷新时间戳：bash verify/session.sh new${N}"
    fi
  fi

  [ "$H_STATE" = "stale" ] && return 1 || return 0
}

# ─────────────────────────────────────────────────────────────
# show —— 看当前交接单
# ─────────────────────────────────────────────────────────────
cmd_show() {
  if [ ! -f "$SESSION_FILE" ]; then
    echo "还没有交接单。生成一份：bash verify/session.sh new"
    return 0
  fi
  cat "$SESSION_FILE"
  echo ""
  echo "─────────────────────────────────────────────────"
  cmd_check
}

case "${1:-show}" in
  new)    cmd_new ;;
  check)  shift; cmd_check "${1:-}" ;;
  show|"") cmd_show ;;
  -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  *) echo "未知参数：$1（用 --help 看用法）" >&2; exit 2 ;;
esac
