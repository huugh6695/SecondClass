#!/usr/bin/env bash
# ============================================================================
# status.sh —— 一条命令看清「五个版本号分别停在哪一层」
#
# 五个位置（一次上线的本质，就是让它们指向同一个版本）：
#     ① 代码      git HEAD 是什么
#     ② 构建      target/*.jar 是什么时候打的、有没有比源码旧
#     ③ 测试      上一次回归对着哪个 commit 跑的、还算不算数
#     ④ 线上      服务器上跑的是哪个 jar、服务什么时候启动的
#     ⑤ 数据库    本地和线上的 schema 版本各是什么
#
# 用法：
#     bash verify/status.sh              # 人类可读（默认，会连服务器，约 5~10 秒）
#     bash verify/status.sh --offline    # 只查本地，不连服务器（约 1 秒）
#     bash verify/status.sh --json       # 机器可读，给其他脚本 / Agent 用
#
# 退出码：
#     0 = 没有发现不一致
#     1 = 发现不一致（下面会列出来）
#     2 = 用法错误
#
# 环境变量（一般不用改）：
#     SC_SSH_HOST   默认 root@<你的服务器IP>
#     SC_SSH_KEY    默认 ~/.ssh/workbuddy_sc_ed25519
# ============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR" || exit 2

MODE="human"
OFFLINE=0
for arg in "$@"; do
  case "$arg" in
    --json)    MODE="json" ;;
    --offline) OFFLINE=1 ;;
    -h|--help) sed -n '2,23p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$arg（用 --help 看用法）"; exit 2 ;;
  esac
done

# 只有输出到终端才上色；重定向到文件时上色会变成乱码
if [ -t 1 ] && [ "$MODE" = "human" ]; then
  G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; D=$'\033[2m'; N=$'\033[0m'
else
  G=''; R=''; Y=''; D=''; N=''
fi

SSH_HOST="${SC_SSH_HOST:-root@<你的服务器IP>}"
SSH_KEY="${SC_SSH_KEY:-$HOME/.ssh/workbuddy_sc_ed25519}"

# 远程命令统一带超时。
# 🔴 不设超时的后果：服务器关机 / 安全组改了 / 网络不通 时，
#    这条命令会卡 2 分钟才返回 —— 一个"看一眼状态"的工具不该这样。
if command -v timeout >/dev/null 2>&1; then
  TO=(timeout 12)
else
  TO=()
fi

# ─────────────────────────────────────────────────────────────
# ① 代码
# ─────────────────────────────────────────────────────────────
GIT_HEAD="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
GIT_SUBJECT="$(git log -1 --format='%s' 2>/dev/null || true)"
DIRTY="$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"

# ─────────────────────────────────────────────────────────────
# ② 本地构建
# ─────────────────────────────────────────────────────────────
JAR_PATH="$(ls -1 "$PROJECT_DIR"/target/*.jar 2>/dev/null | head -1)"
JAR_TIME=""
NEWER_FILES=0
if [ -n "${JAR_PATH:-}" ] && [ -f "$JAR_PATH" ]; then
  JAR_TIME="$(date -r "$JAR_PATH" '+%Y-%m-%d %H:%M' 2>/dev/null || echo '?')"
  # jar 之后有没有源码被改过（.java / 配置 / 前端静态文件都算）
  NEWER_FILES="$(
    find "$PROJECT_DIR/src/main" -newer "$JAR_PATH" -type f \
         \( -name '*.java' -o -name '*.yml' -o -name '*.css' -o -name '*.js' -o -name '*.html' \) \
         -print 2>/dev/null | wc -l | tr -d ' '
  )"
fi

# ─────────────────────────────────────────────────────────────
# ③ 测试记录（由 run-regression.sh 结束时写）
# ─────────────────────────────────────────────────────────────
LAST_RUN="$SCRIPT_DIR/.last-run.json"
T_COMMIT=""; T_AT=""; T_SCRIPTS=""; T_PASSED=""; T_FAILED=""; T_EXIT=""
if [ -f "$LAST_RUN" ]; then
  # 不依赖 jq（不一定装了），用 grep + sed 取字段。
  # 这个 JSON 是我们自己写的，格式固定，够用。
  #
  # 🔴 sed 这里必须用 `^"[^"]*"` 锚定键名，不能用 `.*:`。
  #    因为 runAt 的值是 2026-10-31T18:31:27+0800 这种带冒号的，
  #    贪婪的 `.*:` 会吃掉时间里的冒号，最后只剩 `27+0800`（已踩）。
  jget() {
    grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"\{0,1\}[^\",}]*" "$LAST_RUN" 2>/dev/null \
      | head -1 | sed 's/^"[^"]*"[[:space:]]*:[[:space:]]*"\{0,1\}//' | tr -d ' '
  }
  T_COMMIT="$(jget commit)"
  T_AT="$(jget runAt)"
  T_SCRIPTS="$(jget scripts)"
  T_PASSED="$(jget passed)"
  T_FAILED="$(jget failed)"
  T_EXIT="$(jget exit)"
fi

# ─────────────────────────────────────────────────────────────
# ④ 线上（--offline 时跳过）
# ─────────────────────────────────────────────────────────────
R_JAR_TIME=""; R_STARTED=""; R_STATE=""; R_HEALTH=""; REACHABLE=0; R_ERR=""

if [ "$OFFLINE" -eq 0 ]; then
  REMOTE_OUT="$(
    "${TO[@]}" ssh -i "$SSH_KEY" -o ConnectTimeout=5 -o BatchMode=yes \
        -o StrictHostKeyChecking=no "$SSH_HOST" '
      JAR=$(ls -1 /srv/second-classroom/*.jar 2>/dev/null | head -1)
      if [ -n "$JAR" ]; then
        echo "JARTIME=$(date -r "$JAR" "+%Y-%m-%d %H:%M" 2>/dev/null)"
      fi
      echo "STARTED=$(systemctl show second-classroom -p ActiveEnterTimestamp --value 2>/dev/null | awk "{print \$2, substr(\$3,1,5)}")"
      echo "STATE=$(systemctl is-active second-classroom 2>/dev/null)"
    ' 2>/dev/null
  )"
  if [ -n "$REMOTE_OUT" ]; then
    REACHABLE=1
    R_JAR_TIME="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^JARTIME=//p' | head -1)"
    R_STARTED="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^STARTED=//p' | head -1)"
    R_STATE="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^STATE=//p' | head -1)"
  else
    R_ERR="连不上服务器（SSH 超时或网络不通）"
  fi

  # 健康检查走公网 HTTP，和 SSH 是两条独立链路 ——
  # 一个通一个不通，正好能区分"Nginx/安全组的问题"还是"进程的问题"
  HEALTH_RAW="$(curl -s -m 6 "http://${SSH_HOST#*@}:8080/health" 2>/dev/null || true)"
  if printf '%s' "$HEALTH_RAW" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
    R_HEALTH="UP"
  elif [ -n "$HEALTH_RAW" ]; then
    R_HEALTH="异常"
  else
    R_HEALTH="无响应"
  fi
fi

# ─────────────────────────────────────────────────────────────
# ⑤ 数据库 schema 版本
# ─────────────────────────────────────────────────────────────
# 本地 mysql 客户端的常见位置都试一遍，都找不到就标注"未知"（不报错）
MYSQL_BIN=""
for p in \
  "D:/MySQL/MySQL Server 8.0/bin/mysql.exe" \
  "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
  "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql" \
  "$(command -v mysql 2>/dev/null || true)" ; do
  if [ -n "$p" ] && [ -x "$p" ]; then MYSQL_BIN="$p"; break; fi
done

# 本地密码从被 gitignore 的 config/application-local.yml 读，不打印、不外传
LOCAL_PW="$(sed -n 's/^ *password: *//p' "$PROJECT_DIR/config/application-local.yml" 2>/dev/null | head -1)"

DB_LOCAL=""
AUTH_COLS_LOCAL=""
if [ -n "$MYSQL_BIN" ] && [ -n "${LOCAL_PW:-}" ]; then
  DB_LOCAL="$(
    MYSQL_PWD="$LOCAL_PW" "$MYSQL_BIN" -u root -N -s --default-character-set=utf8mb4 \
      second_classroom -e \
      "SELECT version FROM t_schema_version ORDER BY applied_at DESC LIMIT 1" 2>/dev/null || true
  )"
  AUTH_COLS_LOCAL="$(
    MYSQL_PWD="$LOCAL_PW" "$MYSQL_BIN" -u root -N -s --default-character-set=utf8mb4 \
      second_classroom -e \
      "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='second_classroom' AND TABLE_NAME='t_user' AND COLUMN_NAME IN ('token_version','phone_hash','phone_ciphertext','phone_last4','phone_verified_at')" 2>/dev/null || true
  )"
fi

DB_REMOTE=""
AUTH_COLS_REMOTE=""
if [ "$OFFLINE" -eq 0 ] && [ "$REACHABLE" -eq 1 ]; then
  DB_OUT="$(
    "${TO[@]}" ssh -i "$SSH_KEY" -o ConnectTimeout=5 -o BatchMode=yes \
        -o StrictHostKeyChecking=no "$SSH_HOST" '
      PW=$(grep "^DB_PASSWORD=" /etc/second-classroom/env 2>/dev/null | cut -d= -f2-)
      M=$(command -v mysql)
      if [ -n "$M" ] && [ -n "$PW" ]; then
        V=$(MYSQL_PWD="$PW" "$M" -uroot -N -s second_classroom -e \
            "SELECT version FROM t_schema_version ORDER BY applied_at DESC LIMIT 1" 2>/dev/null)
        C=$(MYSQL_PWD="$PW" "$M" -uroot -N -s -e \
            "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=\"second_classroom\" AND TABLE_NAME=\"t_user\" AND COLUMN_NAME IN (\"token_version\",\"phone_hash\",\"phone_ciphertext\",\"phone_last4\",\"phone_verified_at\")" 2>/dev/null)
        echo "DBVER=$V"
        echo "AUTHCOLS=$C"
      fi
    ' 2>/dev/null
  )"
  DB_REMOTE="$(printf '%s\n' "$DB_OUT" | sed -n 's/^DBVER=//p' | head -1)"
  AUTH_COLS_REMOTE="$(printf '%s\n' "$DB_OUT" | sed -n 's/^AUTHCOLS=//p' | head -1)"
fi

# ─────────────────────────────────────────────────────────────
# 汇总判断：把"不一致"的地方挑出来
# ─────────────────────────────────────────────────────────────
WARN=()
[ "$DIRTY" -gt 0 ] && WARN+=("工作区有 ${DIRTY} 项未提交改动")
[ "$NEWER_FILES" -gt 0 ] && WARN+=("本地 jar 比源码旧（${NEWER_FILES} 个文件晚于 jar），不包含最近改动")
if [ -z "$T_COMMIT" ]; then
  WARN+=("没有测试记录：还没跑过 run-regression.sh")
elif [ "$T_COMMIT" != "$GIT_HEAD" ]; then
  WARN+=("测试结果是 ${T_COMMIT} 那一版跑的，与当前 HEAD ${GIT_HEAD} 不一致 —— 不可用于本次验收")
elif [ "${T_FAILED:-0}" != "0" ]; then
  WARN+=("最近一次回归有 ${T_FAILED} 个脚本失败")
fi
if [ "$OFFLINE" -eq 0 ]; then
  [ "$REACHABLE" -eq 0 ] && WARN+=("${R_ERR}")
  if [ "$REACHABLE" -eq 1 ] && [ -n "$R_JAR_TIME" ] && [ "$R_JAR_TIME" != "$JAR_TIME" ]; then
    WARN+=("线上 jar（${R_JAR_TIME}）与本地 jar（${JAR_TIME:-无}）不一致 —— 线上不是这一版")
  fi
  [ "$R_HEALTH" != "UP" ] && WARN+=("线上健康检查：${R_HEALTH}")
  if [ -n "${AUTH_COLS_REMOTE:-}" ] && [ "$AUTH_COLS_REMOTE" != "5" ]; then
    WARN+=("线上 t_user 缺认证字段（${AUTH_COLS_REMOTE}/5）—— 新 jar 直接部署会全站 500")
  fi
  [ -z "${DB_REMOTE:-}" ] && [ "$REACHABLE" -eq 1 ] && WARN+=("线上没有 schema 版本记录（未执行 migrate-schema-version.sql）")
fi

# ─────────────────────────────────────────────────────────────
# 渲染
# ─────────────────────────────────────────────────────────────
if [ "$MODE" = "json" ]; then
  printf '{\n'
  printf '  "git": {"head": "%s", "subject": "%s", "dirtyFiles": %s},\n' \
    "$GIT_HEAD" "$(printf '%s' "$GIT_SUBJECT" | sed 's/"/\\"/g')" "$DIRTY"
  printf '  "build": {"jarTime": "%s", "newerSourceFiles": %s},\n' "${JAR_TIME:-}" "$NEWER_FILES"
  printf '  "test": {"commit": "%s", "runAt": "%s", "scripts": "%s", "passed": "%s", "failed": "%s", "exit": "%s"},\n' \
    "$T_COMMIT" "$T_AT" "$T_SCRIPTS" "$T_PASSED" "$T_FAILED" "$T_EXIT"
  printf '  "remote": {"reachable": %s, "jarTime": "%s", "startedAt": "%s", "service": "%s", "health": "%s"},\n' \
    "$REACHABLE" "${R_JAR_TIME:-}" "${R_STARTED:-}" "${R_STATE:-}" "${R_HEALTH:-}"
  printf '  "database": {"localVersion": "%s", "localAuthColumns": "%s", "remoteVersion": "%s", "remoteAuthColumns": "%s"},\n' \
    "${DB_LOCAL:-}" "${AUTH_COLS_LOCAL:-}" "${DB_REMOTE:-}" "${AUTH_COLS_REMOTE:-}"
  if [ ${#WARN[@]} -eq 0 ]; then
    printf '  "warnings": []\n'
  else
    printf '  "warnings": ['
    for i in "${!WARN[@]}"; do
      [ "$i" -gt 0 ] && printf ', '
      printf '"%s"' "$(printf '%s' "${WARN[$i]}" | sed 's/"/\\"/g')"
    done
    printf ']\n'
  fi
  printf '}\n'
  [ ${#WARN[@]} -eq 0 ] && exit 0 || exit 1
fi

echo "════════════════════════════════════════════════════════════════"
echo " 状态：${PROJECT_DIR}"
echo "════════════════════════════════════════════════════════════════"

echo ""
echo "① 代码"
echo "   Git HEAD     ${GIT_HEAD}  ${D}${GIT_SUBJECT}${N}"
if [ "$DIRTY" -eq 0 ]; then
  echo "   工作区       ${G}干净${N}"
else
  echo "   工作区       ${Y}${DIRTY} 项未提交${N}"
fi

echo ""
echo "② 本地构建"
if [ -n "$JAR_TIME" ]; then
  echo "   JAR          ${JAR_TIME}"
  if [ "$NEWER_FILES" -eq 0 ]; then
    echo "   与源码       ${G}是最新的${N}"
  else
    echo "   与源码       ${Y}有 ${NEWER_FILES} 个源文件比 jar 新 —— 需要重新打包${N}"
  fi
else
  echo "   JAR          ${Y}不存在（还没 mvn package）${N}"
fi

echo ""
echo "③ 测试"
if [ -z "$T_COMMIT" ]; then
  echo "   ${Y}没有记录：还没跑过 run-regression.sh${N}"
else
  # 2026-10-31T18:31:27+0800 → 2026-10-31 18:31（人看着舒服，原始值仍在 JSON 里）
  T_AT_SHOW="$(printf '%s' "$T_AT" | sed 's/T/ /; s/:[0-9][0-9][-+].*$//')"
  echo "   上次运行     ${T_AT_SHOW:-未知}"
  echo "   结果         ${T_PASSED:-?}/${T_SCRIPTS:-?} 通过，失败 ${T_FAILED:-?}（退出码 ${T_EXIT:-?}）"
  if [ "$T_COMMIT" = "$GIT_HEAD" ]; then
    echo "   对应 commit  ${T_COMMIT}  ${G}与当前 HEAD 一致${N}"
  else
    echo "   对应 commit  ${T_COMMIT}  ${R}与当前 HEAD ${GIT_HEAD} 不一致 —— 这份结果不算数${N}"
  fi
fi

echo ""
echo "④ 线上"
if [ "$OFFLINE" -eq 1 ]; then
  echo "   ${D}已跳过（--offline）${N}"
elif [ "$REACHABLE" -eq 0 ]; then
  echo "   ${R}${R_ERR}${N}"
else
  echo "   JAR          ${R_JAR_TIME:-未知}"
  echo "   服务启动     ${R_STARTED:-未知}     状态 ${R_STATE:-未知}"
  if [ "$R_HEALTH" = "UP" ]; then
    echo "   健康检查     ${G}UP${N}"
  else
    echo "   健康检查     ${R}${R_HEALTH}${N}"
  fi
  if [ -n "$R_JAR_TIME" ] && [ "$R_JAR_TIME" = "$JAR_TIME" ]; then
    echo "   与本地       ${G}是同一个 jar${N}"
  elif [ -n "$R_JAR_TIME" ]; then
    echo "   与本地       ${Y}不是同一个 jar（线上仍是旧版）${N}"
  fi
fi

echo ""
echo "⑤ 数据库"
if [ -n "$DB_LOCAL" ]; then
  echo "   本地版本     ${DB_LOCAL}   认证字段 ${AUTH_COLS_LOCAL}/5"
else
  echo "   本地版本     ${D}未知（找不到 mysql 客户端或密码）${N}"
fi
if [ "$OFFLINE" -eq 0 ] && [ "$REACHABLE" -eq 1 ]; then
  if [ -n "$DB_REMOTE" ]; then
    echo "   线上版本     ${DB_REMOTE}   认证字段 ${AUTH_COLS_REMOTE}/5"
  else
    echo "   线上版本     ${Y}无记录/未迁移   认证字段 ${AUTH_COLS_REMOTE:-0}/5${N}"
  fi
fi

echo ""
echo "════════════════════════════════════════════════════════════════"
if [ ${#WARN[@]} -eq 0 ]; then
  echo " ${G}没有发现不一致${N}"
  echo "════════════════════════════════════════════════════════════════"
  exit 0
fi

echo " ${Y}注意以下几处：${N}"
for w in "${WARN[@]}"; do
  echo "   · $w"
done
echo "════════════════════════════════════════════════════════════════"
exit 1
