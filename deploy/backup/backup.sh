#!/bin/sh
# 每天定时备份 MySQL：mysqldump 一致性快照 → gzip → /backups，按保留天数清理旧文件。
# 由 docker-compose 的 backup 服务运行；也可以手工执行一次：docker compose exec backup /scripts/backup.sh once
set -eu

DB="${MYSQL_DATABASE:-cosmetic_scm}"
HOST="${MYSQL_HOST:-mysql}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-30}"
AT="${BACKUP_AT:-02:30}"          # 每天几点备份（容器时区）
DIR=/backups

dump() {
  ts=$(date +%Y%m%d-%H%M)
  tmp="$DIR/.${DB}-${ts}.sql.gz.part"
  out="$DIR/${DB}-${ts}.sql.gz"
  # --single-transaction：InnoDB 一致性快照，备份期间不锁表，业务照常
  mysqldump -h "$HOST" -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers \
    --set-gtid-purged=OFF --default-character-set=utf8mb4 "$DB" | gzip -9 > "$tmp"
  mv "$tmp" "$out"
  # 校验压缩包完整，坏包直接报错，便于监控发现
  gzip -t "$out"
  echo "$(date '+%F %T') 备份完成：$out（$(du -h "$out" | cut -f1)）"
  # 按文件名里的日期清理过期备份（只用 shell 和 date，不依赖精简镜像里可能没有的 find）
  cutoff=$(date -d "-${KEEP_DAYS} days" +%Y%m%d)
  for f in "$DIR"/"${DB}"-*.sql.gz; do
    [ -e "$f" ] || continue
    day=$(basename "$f" | sed "s/^${DB}-\([0-9]\{8\}\)-.*/\1/")
    if [ "$day" -lt "$cutoff" ]; then
      rm -f "$f" && echo "已清理过期备份：$f"
    fi
  done
}

if [ "${1:-}" = "once" ]; then
  dump
  exit 0
fi

echo "备份服务启动：每天 $AT 备份 $DB，保留 $KEEP_DAYS 天"
while true; do
  now=$(date +%s)
  next=$(date -d "$(date +%F) $AT" +%s)
  [ "$next" -le "$now" ] && next=$(date -d "tomorrow $AT" +%s)
  sleep $((next - now))
  dump || echo "$(date '+%F %T') 备份失败，请检查数据库连接和磁盘空间" >&2
done
