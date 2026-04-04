#!/bin/bash
# Database backup script for production
# Usage: ./scripts/backup-db.sh
# Recommended cron: 0 3 * * * /path/to/backup-db.sh

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/baekjoonrec_${TIMESTAMP}.sql.gz"

mkdir -p "$BACKUP_DIR"

# Run pg_dump inside the postgres container
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U "${DB_USERNAME}" -d baekjoonrec --no-owner --clean | gzip > "$BACKUP_FILE"

echo "Backup created: $BACKUP_FILE ($(du -h "$BACKUP_FILE" | cut -f1))"

# Remove backups older than retention period
find "$BACKUP_DIR" -name "baekjoonrec_*.sql.gz" -mtime +"$RETENTION_DAYS" -delete
echo "Cleaned backups older than ${RETENTION_DAYS} days"
