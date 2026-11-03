#!/bin/sh
# 从备份恢复：会覆盖当前数据库，务必先停后端、确认文件无误。
# 用法：docker compose stop backend
#       docker compose exec backup /scripts/restore.sh /backups/cosmetic_scm-20261103-0230.sql.gz
#       docker compose start backend
set -eu

# 密码走环境变量，不出现在命令行（进程列表里看不到，mysql 也不再告警）
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"

FILE="${1:-}"
DB="${MYSQL_DATABASE:-cosmetic_scm}"
HOST="${MYSQL_HOST:-mysql}"

if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
  echo "用法：restore.sh /backups/备份文件.sql.gz" >&2
  ls -1t /backups/*.sql.gz 2>/dev/null | head -10 >&2 || true
  exit 1
fi
gzip -t "$FILE"
printf '将用 %s 覆盖数据库 %s，输入 YES 继续：' "$FILE" "$DB"
read -r answer
[ "$answer" = "YES" ] || { echo "已取消"; exit 1; }

gunzip -c "$FILE" | mysql -h "$HOST" -u root --default-character-set=utf8mb4 "$DB"
echo "恢复完成。启动后端后请抽查几个批号的追溯，并在审计日志里记录本次恢复。"
