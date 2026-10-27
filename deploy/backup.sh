#!/usr/bin/env bash
#
# 第二课堂学时答疑工单系统 —— 每日备份（数据库 + 附件）
#
# 🔴 为什么数据库和 uploads 必须**一起**备份、而且是同一个时刻：
#    t_attachment 表里存的是文件**路径**，文件本体在 UPLOAD_DIR 那块磁盘上。
#      · 只备份数据库 → 恢复后附件全变成打不开的死链；
#      · 只备份 uploads → 恢复后没有任何工单/消息引用这些文件，等于一堆孤儿。
#    这两样是"一份备份"的两个半边，缺一半等于没备份。
#
# 安装（详见 DEPLOY.md §12）：
#   1) 先让 mysqldump 不用在命令行里写密码：
#        sudo nano /root/.my.cnf
#        ---8<---
#        [client]
#        user=root
#        password=你的数据库root密码
#        ---8<---
#        sudo chmod 600 /root/.my.cnf
#   2) 放好脚本并加执行权限：
#        sudo cp backup.sh /usr/local/bin/second-classroom-backup.sh
#        sudo chmod +x /usr/local/bin/second-classroom-backup.sh
#   3) 定时（每天凌晨 3:30）：
#        sudo crontab -e
#        加一行：
#        30 3 * * * /usr/local/bin/second-classroom-backup.sh >> /var/log/sc-backup.log 2>&1
#
# 手动跑一次验证：
#   sudo /usr/local/bin/second-classroom-backup.sh
#
set -euo pipefail

DB_NAME="${DB_NAME:-second_classroom}"
UPLOAD_DIR="${UPLOAD_DIR:-/srv/second-classroom/uploads}"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/second-classroom}"
KEEP_DAYS="${KEEP_DAYS:-14}"
MYSQL_DEFAULTS="${MYSQL_DEFAULTS:-/root/.my.cnf}"

STAMP="$(date +%F_%H%M%S)"
DAY_DIR="$BACKUP_ROOT/$STAMP"

log() { echo "[$(date '+%F %T')] $*"; }

log "===== 备份开始（$STAMP）====="
mkdir -p "$DAY_DIR"

# ---------- 1. 数据库 ----------
if [ ! -r "$MYSQL_DEFAULTS" ]; then
  log "!! 读不到 $MYSQL_DEFAULTS，mysqldump 会在命令行要密码（cron 里必然失败）"
  log "   请先按脚本顶部注释第 1 步建好它"
  exit 1
fi

DB_FILE="$DAY_DIR/db-$DB_NAME.sql.gz"
log "1/3 导出数据库 -> $DB_FILE"
# --defaults-file 必须是**第一个**参数，写在后面会被忽略
# --single-transaction：InnoDB 下不锁表，用户在用也不影响（本项目 13 张表全是 InnoDB）
# --no-tablespaces：避免需要 PROCESS 权限
mysqldump --defaults-file="$MYSQL_DEFAULTS" \
  --single-transaction --quick --no-tablespaces \
  --routines --triggers --events \
  --default-character-set=utf8mb4 \
  "$DB_NAME" | gzip -9 > "$DB_FILE"

# 完整性自检：gzip -t 校验压缩流没坏；再确认解出来确实有内容
if ! gzip -t "$DB_FILE"; then
  log "!! $DB_FILE 压缩流校验失败，这次备份不可信"
  exit 1
fi
if ! gzip -dc "$DB_FILE" | tail -c 200 | grep -q "Dump completed"; then
  log "!! 备份内容不像完整的 mysqldump 输出（末尾没有 'Dump completed'）"
  exit 1
fi

# ---------- 2. 附件目录 ----------
if [ -d "$UPLOAD_DIR" ]; then
  UP_FILE="$DAY_DIR/uploads.tar.gz"
  log "2/3 打包附件目录 -> $UP_FILE"
  # -C 进父目录再打相对路径：恢复时直接解到目标目录即可
  tar -czf "$UP_FILE" -C "$(dirname "$UPLOAD_DIR")" "$(basename "$UPLOAD_DIR")"
  if ! tar -tzf "$UP_FILE" > /dev/null; then
    log "!! $UP_FILE 归档校验失败"
    exit 1
  fi
else
  log "2/3 附件目录 $UPLOAD_DIR 不存在 —— 跳过（上传接口会自己建，可能还没人传过东西）"
fi

# ---------- 3. 清理过期备份 ----------
log "3/3 清理超过 $KEEP_DAYS 天的备份"
find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime +"$KEEP_DAYS" -print -exec rm -rf {} +

log "本次备份占用："
du -sh "$DAY_DIR"
log "当前保留："
du -sh "$BACKUP_ROOT"
log "===== 备份结束 ====="
