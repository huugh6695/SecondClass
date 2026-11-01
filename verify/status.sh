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
# 另加 ⑥ 交接单：前五项比的是「版本一致不一致」，⑥ 查的是
# 「人写的判断过期了没有」——两种问题，不该混在一起数。
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
    -h|--help) sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

# 规模：这些数每次加表加接口都会变，所以不让任何人手抄进文档。
# 端点数是「注解处数」，近似值（一个 @RequestMapping(method=...) 会被漏掉），
# 只用来建立印象，不作为验收依据。
COUNT_TABLES="$(grep -c 'CREATE TABLE' "$PROJECT_DIR/schema.sql" 2>/dev/null | tr -d ' ')"
COUNT_ENDPOINTS="$(
  grep -rhoE '@(Get|Post|Put|Delete|Patch)Mapping' "$PROJECT_DIR/src/main/java" 2>/dev/null \
    | wc -l | tr -d ' '
)"
COUNT_SCRIPTS="$(ls -1 "$SCRIPT_DIR"/verify-*.py 2>/dev/null | wc -l | tr -d ' ')"

# ─────────────────────────────────────────────────────────────
# ② 本地构建
# ─────────────────────────────────────────────────────────────
# 🔴 为什么不再用「有没有源文件比 jar 新」当主判据（2026-10-31 改）：
#    mtime 会被 git checkout / 编辑器批量保存 / 解压 刷成当前时间，
#    于是永远报「需要重新打包」——喊狼来了太多次，真需要重打时也没人信。
#
#    改成两条：
#      · 一定旧：jar 时间 < 最后一次提交时间 ⇒ 逻辑上不可能包含该提交（可靠，不会误报）
#      · 只能证伪：jar 晚于提交 只说明「不比最后一次提交旧」，**不能**保证内容一致
#    最后那个「内容到底一不一样」，交给 jar 的 SHA-256（见 ④ 与本地哈希）。
JAR_PATH="$(ls -1 "$PROJECT_DIR"/target/*.jar 2>/dev/null | head -1)"
JAR_TIME=""; JAR_SHA=""; JAR_MTIME=0; NEWER_FILES=0
HEAD_TS="$(git log -1 --format=%ct 2>/dev/null || echo 0)"
# 🔴 别用 `git log --format='%m-%d %H:%M'`：%m / %d / %H 都是 git 的占位符
#    （boundary mark / ref 名 / 完整哈希），会输出成 `>- (HEAD -> main) 604b33a…`
#    精确到分钟的时间显示，一律从 epoch 转。
HEAD_TIME="$(date -d "@${HEAD_TS:-0}" '+%m-%d %H:%M' 2>/dev/null || true)"
[ "${HEAD_TS:-0}" -le 0 ] && HEAD_TIME=""

# 只认「动过运行相关源码」的那次提交。
#   纯文档提交（docs/、README、AGENTS.md）不会改变 jar 的内容，
#   拿它当判据会导致「每写一句文档就说 jar 过期了」——喊多了就没人看了。
SRC_COMMIT_TS="$(git log -1 --format=%ct -- src/main pom.xml 2>/dev/null || echo 0)"
SRC_COMMIT_TIME="$(date -d "@${SRC_COMMIT_TS:-0}" '+%m-%d %H:%M' 2>/dev/null || true)"
[ "${SRC_COMMIT_TS:-0}" -le 0 ] && SRC_COMMIT_TIME=""
if [ -n "${JAR_PATH:-}" ] && [ -f "$JAR_PATH" ]; then
  JAR_TIME="$(date -r "$JAR_PATH" '+%Y-%m-%d %H:%M' 2>/dev/null || echo '?')"
  JAR_MTIME="$(date -r "$JAR_PATH" '+%s' 2>/dev/null || echo 0)"
  JAR_SHA="$(sha256sum "$JAR_PATH" 2>/dev/null | cut -c1-16)"
  # 这个计数只保留给「工作区有脏改动」时用：脏文件确实可能比 jar 新
  NEWER_FILES="$(
    find "$PROJECT_DIR/src/main" -newer "$JAR_PATH" -type f \
         \( -name '*.java' -o -name '*.yml' -o -name '*.css' -o -name '*.js' -o -name '*.html' \) \
         -print 2>/dev/null | wc -l | tr -d ' '
  )"
fi
JAR_STALE_BY_COMMIT=0
if [ "${JAR_MTIME:-0}" -gt 0 ] && [ "${SRC_COMMIT_TS:-0}" -gt 0 ]; then
  [ "$JAR_MTIME" -lt "$SRC_COMMIT_TS" ] && JAR_STALE_BY_COMMIT=1
fi

# ─────────────────────────────────────────────────────────────
# ③ 测试记录（由 run-regression.sh 结束时写）
# ─────────────────────────────────────────────────────────────
LAST_RUN="$SCRIPT_DIR/.last-run.json"
T_COMMIT=""; T_AT=""; T_SCRIPTS=""; T_PASSED=""; T_FAILED=""; T_EXIT=""; T_FP=""

# 当前「运行相关源码」指纹。算法只有一份，在 source-fingerprint.sh 里 ——
# 这里再写一遍迟早会漂移，然后天天误报。
SRC_FP="$(bash "$SCRIPT_DIR/source-fingerprint.sh" 2>/dev/null || echo unknown)"

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
  T_FP="$(jget sourceFingerprint)"
  T_SCRIPTS="$(jget scripts)"
  T_PASSED="$(jget passed)"
  T_FAILED="$(jget failed)"
  T_EXIT="$(jget exit)"
fi

# ─────────────────────────────────────────────────────────────
# ④ 线上（--offline 时跳过）
# ─────────────────────────────────────────────────────────────
R_JAR_TIME=""; R_STARTED=""; R_STATE=""; R_HEALTH=""; REACHABLE=0; R_ERR=""
R_JAR_SHA=""; R_JAR_MTIME=0; R_START_EPOCH=0

if [ "$OFFLINE" -eq 0 ]; then
  REMOTE_OUT="$(
    "${TO[@]}" ssh -i "$SSH_KEY" -o ConnectTimeout=5 -o BatchMode=yes \
        -o StrictHostKeyChecking=no "$SSH_HOST" '
      JAR=$(ls -1 /srv/second-classroom/*.jar 2>/dev/null | head -1)
      if [ -n "$JAR" ]; then
        echo "JARTIME=$(date -r "$JAR" "+%Y-%m-%d %H:%M" 2>/dev/null)"
        echo "JARSHA=$(sha256sum "$JAR" 2>/dev/null | cut -c1-16)"
        echo "JARMT=$(date -r "$JAR" "+%s" 2>/dev/null)"
      fi
      echo "STARTED=$(systemctl show second-classroom -p ActiveEnterTimestamp --value 2>/dev/null | awk "{print \$2, substr(\$3,1,5)}")"
      echo "STARTEPOCH=$(date -d "$(systemctl show second-classroom -p ActiveEnterTimestamp --value 2>/dev/null)" "+%s" 2>/dev/null)"
      echo "STATE=$(systemctl is-active second-classroom 2>/dev/null)"
    ' 2>/dev/null
  )"
  if [ -n "$REMOTE_OUT" ]; then
    REACHABLE=1
    R_JAR_TIME="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^JARTIME=//p' | head -1)"
    R_JAR_SHA="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^JARSHA=//p' | head -1)"
    R_JAR_MTIME="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^JARMT=//p' | head -1)"
    R_STARTED="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^STARTED=//p' | head -1)"
    R_START_EPOCH="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^STARTEPOCH=//p' | head -1)"
    R_STATE="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^STATE=//p' | head -1)"
    # 空值统一成 0，后面才能做整数比较（set -u 下空串比较会炸）
    : "${R_JAR_MTIME:=0}"; : "${R_START_EPOCH:=0}"
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
# ⑥ 交接单（SESSION.md 是不是过期了）
# ─────────────────────────────────────────────────────────────
# 为什么不直接在 status.sh 里读 SESSION.md：
#   过期规则是交接单自己的事，写在生成它的脚本旁边才改得动。
#   这里只负责把它的结论翻译成一行字。
H_STATE=""; H_DETAIL=""; H_AHEAD=""; H_DAYS=""; H_COMMIT=""
if [ -f "$SCRIPT_DIR/session.sh" ]; then
  H_RAW="$(bash "$SCRIPT_DIR/session.sh" check --raw 2>/dev/null || true)"
  hget() { printf '%s\n' "$H_RAW" | sed -n "s/^$1=//p" | head -1; }
  H_STATE="$(hget STATE)"
  H_DETAIL="$(hget DETAIL)"
  H_COMMIT="$(hget COMMIT)"
  H_AHEAD="$(hget AHEAD)"
  H_DAYS="$(hget DAYS)"
fi

# ─────────────────────────────────────────────────────────────
# 汇总判断：把"不一致"的地方挑出来
# ─────────────────────────────────────────────────────────────
WARN=()
[ "$DIRTY" -gt 0 ] && WARN+=("工作区有 ${DIRTY} 项未提交改动")

if [ -z "$JAR_TIME" ]; then
  WARN+=("本地还没有 jar，需要先 mvn package")
elif [ "$JAR_STALE_BY_COMMIT" -eq 1 ]; then
  WARN+=("本地 jar 一定旧：jar 是 ${JAR_TIME}，早于最后一次动代码的提交（${SRC_COMMIT_TIME}）—— 逻辑上不可能包含它")
elif [ "$NEWER_FILES" -gt 0 ] && [ "$DIRTY" -gt 0 ]; then
  WARN+=("工作区有改动，且有 ${NEWER_FILES} 个源文件比 jar 新 —— 需要重新打包")
fi

if [ -z "$T_COMMIT" ]; then
  WARN+=("没有测试记录：还没跑过 run-regression.sh")
elif [ -n "${T_FP:-}" ] && [ "${T_FP:-}" != "unknown" ]; then
  # 有指纹就以指纹为准 —— 它才代表「影响运行的东西变没变」。
  # 纯文档提交不会让它变，所以测试结果不会被白白作废。
  if [ "$T_FP" != "$SRC_FP" ]; then
    WARN+=("运行相关源码在测试之后改过（测试时 ${T_FP}，现在 ${SRC_FP}）—— 这份结果不算数")
  elif [ "${T_FAILED:-0}" != "0" ]; then
    WARN+=("最近一次回归有 ${T_FAILED} 个脚本失败")
  fi
elif [ "$T_COMMIT" != "$GIT_HEAD" ]; then
  # 旧记录没有指纹，才退回按 commit 比（会有一批假警报，跑一次回归就升级）
  WARN+=("测试结果是 ${T_COMMIT} 那一版跑的，与当前 HEAD ${GIT_HEAD} 不一致 —— 不可用于本次验收")
elif [ "${T_FAILED:-0}" != "0" ]; then
  WARN+=("最近一次回归有 ${T_FAILED} 个脚本失败")
fi
if [ "$OFFLINE" -eq 0 ]; then
  [ "$REACHABLE" -eq 0 ] && WARN+=("${R_ERR}")
  # 比哈希，不比时间。时间戳会被同分钟构建、复制、时钟差骗过；哈希不会。
  if [ "$REACHABLE" -eq 1 ] && [ -n "$R_JAR_SHA" ] && [ -n "$JAR_SHA" ]; then
    if [ "$R_JAR_SHA" != "$JAR_SHA" ]; then
      WARN+=("线上 jar 与本地 jar 内容不同（哈希 ${R_JAR_SHA} vs ${JAR_SHA}）—— 线上不是这一版")
    fi
  elif [ "$REACHABLE" -eq 1 ] && [ -n "$R_JAR_TIME" ] && [ "$R_JAR_TIME" != "$JAR_TIME" ]; then
    WARN+=("线上 jar（${R_JAR_TIME}）与本地（${JAR_TIME:-无}）时间不同 —— 拿不到哈希，只能按时间粗判")
  fi
  # 白捡的一条：jar 比服务启动还晚 ⇒ jar 被换过却没重启，进程还在跑旧的
  if [ "${R_JAR_MTIME:-0}" -gt 0 ] && [ "${R_START_EPOCH:-0}" -gt 0 ] \
     && [ "$R_JAR_MTIME" -gt "$R_START_EPOCH" ]; then
    WARN+=("线上 jar 在服务启动之后被替换过（jar $(date -d "@$R_JAR_MTIME" '+%m-%d %H:%M' 2>/dev/null || echo '?') 晚于启动时间）—— 进程可能还在跑旧 jar，要重启才生效")
  fi
  [ "$R_HEALTH" != "UP" ] && WARN+=("线上健康检查：${R_HEALTH}")
  if [ -n "${AUTH_COLS_REMOTE:-}" ] && [ "$AUTH_COLS_REMOTE" != "5" ]; then
    WARN+=("线上 t_user 缺认证字段（${AUTH_COLS_REMOTE}/5）—— 新 jar 直接部署会全站 500")
  fi
  [ -z "${DB_REMOTE:-}" ] && [ "$REACHABLE" -eq 1 ] && WARN+=("线上没有 schema 版本记录（未执行 migrate-schema-version.sql）")
fi

# 交接单"没有"不算问题（新克隆的仓库本来就没有，它也不进版本库）；
# 只有"有但过期"才值得提醒 —— 否则这条会变成每次开机都响的假警报。
if [ "$H_STATE" = "stale" ] || [ "$H_STATE" = "unknown" ]; then
  WARN+=("交接单已过期：${H_DETAIL}")
fi

# ─────────────────────────────────────────────────────────────
# 渲染
# ─────────────────────────────────────────────────────────────
if [ "$MODE" = "json" ]; then
  printf '{\n'
  printf '  "git": {"head": "%s", "subject": "%s", "dirtyFiles": %s},\n' \
    "$GIT_HEAD" "$(printf '%s' "$GIT_SUBJECT" | sed 's/"/\\"/g')" "$DIRTY"
  printf '  "scale": {"tables": %s, "endpointAnnotations": %s, "verifyScripts": %s},\n' \
    "${COUNT_TABLES:-0}" "${COUNT_ENDPOINTS:-0}" "${COUNT_SCRIPTS:-0}"
  printf '  "build": {"jarTime": "%s", "jarSha256": "%s", "staleByCommit": %s, "newerSourceFiles": %s, "headCommitTime": "%s", "sourceCommitTime": "%s"},\n' \
    "${JAR_TIME:-}" "${JAR_SHA:-}" "$JAR_STALE_BY_COMMIT" "$NEWER_FILES" "${HEAD_TIME:-}" "${SRC_COMMIT_TIME:-}"
  printf '  "test": {"commit": "%s", "runAt": "%s", "sourceFingerprint": "%s", "currentSourceFingerprint": "%s", "scripts": "%s", "passed": "%s", "failed": "%s", "exit": "%s"},\n' \
    "$T_COMMIT" "$T_AT" "$T_FP" "$SRC_FP" "$T_SCRIPTS" "$T_PASSED" "$T_FAILED" "$T_EXIT"
  printf '  "remote": {"reachable": %s, "jarTime": "%s", "jarSha256": "%s", "startedAt": "%s", "service": "%s", "health": "%s"},\n' \
    "$REACHABLE" "${R_JAR_TIME:-}" "${R_JAR_SHA:-}" "${R_STARTED:-}" "${R_STATE:-}" "${R_HEALTH:-}"
  printf '  "database": {"localVersion": "%s", "localAuthColumns": "%s", "remoteVersion": "%s", "remoteAuthColumns": "%s"},\n' \
    "${DB_LOCAL:-}" "${AUTH_COLS_LOCAL:-}" "${DB_REMOTE:-}" "${AUTH_COLS_REMOTE:-}"
  printf '  "handover": {"state": "%s", "commit": "%s", "ahead": %s, "days": %s, "detail": "%s"},\n' \
    "${H_STATE:-missing}" "${H_COMMIT:-}" "${H_AHEAD:-0}" "${H_DAYS:-0}" \
    "$(printf '%s' "${H_DETAIL:-}" | sed 's/"/\\"/g')"
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
echo "   规模         ${COUNT_TABLES:-?} 张表 / ${COUNT_ENDPOINTS:-?} 处端点注解 / ${COUNT_SCRIPTS:-?} 个验收脚本 ${D}（实时统计，别抄进文档）${N}"

echo ""
echo "② 本地构建"
if [ -n "$JAR_TIME" ]; then
  echo "   JAR          ${JAR_TIME}   ${D}sha256 ${JAR_SHA:-?}…${N}"
  if [ "$JAR_STALE_BY_COMMIT" -eq 1 ]; then
    echo "   与源码       ${Y}一定旧：早于最后一次动代码的提交（${SRC_COMMIT_TIME}）${N}"
  elif [ "$NEWER_FILES" -gt 0 ]; then
    echo "   与源码       ${Y}工作区有 ${NEWER_FILES} 个源文件比 jar 新${N}"
  else
    echo "   与源码       ${G}看起来是最新的${N}${D}（晚于最后一次动代码的提交、工作区无更新）${N}"
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
  if [ -n "${T_FP:-}" ] && [ "${T_FP:-}" != "unknown" ]; then
    if [ "$T_FP" = "$SRC_FP" ]; then
      echo "   源码指纹     ${T_FP}  ${G}与现在一致${N}"
      if [ "$T_COMMIT" != "$GIT_HEAD" ]; then
        echo "   对应 commit  ${T_COMMIT}  ${D}当前 HEAD ${GIT_HEAD}，之后只动了文档/元数据 —— 结果仍有效${N}"
      fi
    else
      echo "   源码指纹     ${T_FP}  ${R}与现在（${SRC_FP}）不一致 —— 这份结果不算数${N}"
    fi
  elif [ "$T_COMMIT" = "$GIT_HEAD" ]; then
    echo "   对应 commit  ${T_COMMIT}  ${G}与当前 HEAD 一致${N}${D}（旧记录没有指纹）${N}"
  else
    echo "   对应 commit  ${T_COMMIT}  ${Y}与当前 HEAD ${GIT_HEAD} 不一致${N}"
    echo "   ${D}旧记录没有源码指纹，只能按 commit 判；跑一次回归就升级了${N}"
  fi
fi

echo ""
echo "④ 线上"
if [ "$OFFLINE" -eq 1 ]; then
  echo "   ${D}已跳过（--offline）${N}"
elif [ "$REACHABLE" -eq 0 ]; then
  echo "   ${R}${R_ERR}${N}"
else
  echo "   JAR          ${R_JAR_TIME:-未知}   ${D}sha256 ${R_JAR_SHA:-?}…${N}"
  echo "   服务启动     ${R_STARTED:-未知}     状态 ${R_STATE:-未知}"
  if [ "$R_HEALTH" = "UP" ]; then
    echo "   健康检查     ${G}UP${N}"
  else
    echo "   健康检查     ${R}${R_HEALTH}${N}"
  fi
  if [ -n "$R_JAR_SHA" ] && [ -n "$JAR_SHA" ]; then
    if [ "$R_JAR_SHA" = "$JAR_SHA" ]; then
      echo "   与本地       ${G}是同一个 jar（哈希一致）${N}"
    else
      echo "   与本地       ${Y}不是同一个 jar（哈希不同，线上仍是旧版）${N}"
    fi
  elif [ -n "$R_JAR_TIME" ]; then
    echo "   与本地       ${Y}拿不到哈希，只能按时间粗判：${R_JAR_TIME} vs ${JAR_TIME:-无}${N}"
  fi
  if [ "${R_JAR_MTIME:-0}" -gt 0 ] && [ "${R_START_EPOCH:-0}" -gt 0 ] \
     && [ "$R_JAR_MTIME" -gt "$R_START_EPOCH" ]; then
    echo "   ${Y}注意${N}         jar 比服务启动还晚 —— 换过 jar 但没重启"
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
echo "⑥ 交接单"
if [ -z "$H_STATE" ]; then
  echo "   ${D}未检查（找不到 verify/session.sh）${N}"
elif [ "$H_STATE" = "missing" ]; then
  echo "   ${D}没有（新克隆的仓库本来就没有，不算问题）${N}"
elif [ "$H_STATE" = "ok" ]; then
  echo "   SESSION.md   ${G}与当前 HEAD 一致${N}"
elif [ "$H_STATE" = "behind" ]; then
  echo "   SESSION.md   写于 ${H_COMMIT}，之后有 ${H_AHEAD} 个提交（仍可参考）"
else
  echo "   SESSION.md   ${Y}${H_DETAIL}${N}"
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
