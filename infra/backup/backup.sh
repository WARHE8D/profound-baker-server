#!/bin/sh
# One backup run (#24): dump the database, archive the uploads, keep BACKUP_RETENTION_DAYS of both on
# the server, copy them off the server, then record the success. Any failure exits non-zero before
# last-success is written, so the container's health check notices a run that broke.
#
# Environment:
#   PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE  the database (libpq's own variables)
#   BACKUP_DIR             where backups are kept on the server (default /backups)
#   UPLOADS_DIR            uploaded images to archive alongside (default /data/uploads; skipped if absent)
#   BACKUP_RETENTION_DAYS  days to keep, here and off-box (default 14)
#   BACKUP_REMOTE          rclone destination for the off-box copy; empty means none (a warning each run)
set -eu

BACKUP_DIR=${BACKUP_DIR:-/backups}
UPLOADS_DIR=${UPLOADS_DIR:-/data/uploads}
RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-14}
REMOTE=${BACKUP_REMOTE:-}

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: $*"; }

case "$RETENTION_DAYS" in
    '' | *[!0-9]* | 0) log "BACKUP_RETENTION_DAYS must be a positive whole number of days"; exit 2 ;;
esac

stamp=$(date -u +%Y%m%dT%H%M%SZ)
dump="$BACKUP_DIR/baker-$stamp.dump"
uploads="$BACKUP_DIR/uploads-$stamp.tar.gz"
mkdir -p "$BACKUP_DIR"

# Written under a temporary name and renamed once complete, so a half-written file is never
# mistaken for a backup; whatever a failed run leaves half-done is removed on exit.
trap 'rm -f "$dump.partial" "$uploads.partial"' EXIT

log "dumping database $PGDATABASE"
# Custom format: compressed, every schema (identity, catalog, ordering) and their Flyway history,
# restorable selectively with pg_restore. --no-owner so it restores under any role.
pg_dump --format=custom --no-owner --file="$dump.partial"
# A dump pg_restore can't read is no backup.
pg_restore --list "$dump.partial" > /dev/null
mv "$dump.partial" "$dump"
log "wrote $(basename "$dump") ($(du -h "$dump" | cut -f1))"

if [ -d "$UPLOADS_DIR" ]; then
    tar -czf "$uploads.partial" -C "$UPLOADS_DIR" .
    mv "$uploads.partial" "$uploads"
    log "wrote $(basename "$uploads") ($(du -h "$uploads" | cut -f1))"
else
    uploads=""
    log "no uploads directory at $UPLOADS_DIR; skipping images"
fi

# -mtime +N matches files more than N whole days old, so +(days - 1) keeps `days` days of backups.
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'baker-*.dump' -o -name 'uploads-*.tar.gz' \) \
    -mtime +$((RETENTION_DAYS - 1)) -print -delete | while read -r old; do log "removed $(basename "$old")"; done

if [ -n "$REMOTE" ]; then
    log "copying off-box"
    rclone copy --no-traverse "$dump" "$REMOTE"
    [ -z "$uploads" ] || rclone copy --no-traverse "$uploads" "$REMOTE"
    rclone delete --min-age "${RETENTION_DAYS}d" "$REMOTE"
else
    log "WARNING: BACKUP_REMOTE is not set; this backup exists only on this server"
fi

date -u +%s > "$BACKUP_DIR/last-success"
log "done"
