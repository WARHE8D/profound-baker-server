#!/bin/sh
# Starts the backup scheduler (#24): configures the off-box remote from the environment, takes a
# backup now unless one succeeded in the last day, then runs backup.sh on BACKUP_SCHEDULE with crond.
#
# Off-box storage is any S3-compatible bucket (Backblaze B2, Cloudflare R2, Wasabi, Hetzner...).
# Backups hold guests' names, emails and phone numbers, so they leave the server only encrypted
# (rclone crypt, file names included) with BACKUP_ENCRYPTION_PASSWORD; without it, nothing is sent.
set -eu

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: $*"; }

if [ -n "${BACKUP_S3_BUCKET:-}" ]; then
    if [ -z "${BACKUP_ENCRYPTION_PASSWORD:-}" ]; then
        log "BACKUP_S3_BUCKET is set but BACKUP_ENCRYPTION_PASSWORD is not: refusing to send unencrypted backups"
        exit 1
    fi
    # rclone reads remotes from RCLONE_CONFIG_<NAME>_<OPTION> variables: no config file, no secret on disk.
    export RCLONE_CONFIG_OFFSITE_TYPE=s3
    export RCLONE_CONFIG_OFFSITE_PROVIDER=Other
    export RCLONE_CONFIG_OFFSITE_ENDPOINT="${BACKUP_S3_ENDPOINT:?Set BACKUP_S3_ENDPOINT}"
    export RCLONE_CONFIG_OFFSITE_REGION="${BACKUP_S3_REGION:-auto}"
    export RCLONE_CONFIG_OFFSITE_ACCESS_KEY_ID="${BACKUP_S3_ACCESS_KEY_ID:?Set BACKUP_S3_ACCESS_KEY_ID}"
    export RCLONE_CONFIG_OFFSITE_SECRET_ACCESS_KEY="${BACKUP_S3_SECRET_ACCESS_KEY:?Set BACKUP_S3_SECRET_ACCESS_KEY}"
    export RCLONE_CONFIG_OFFSITECRYPT_TYPE=crypt
    export RCLONE_CONFIG_OFFSITECRYPT_REMOTE="offsite:$BACKUP_S3_BUCKET/sugarcrumbs"
    RCLONE_CONFIG_OFFSITECRYPT_PASSWORD=$(rclone obscure "$BACKUP_ENCRYPTION_PASSWORD")
    export RCLONE_CONFIG_OFFSITECRYPT_PASSWORD
    export BACKUP_REMOTE="offsitecrypt:"
    log "off-box copies go to bucket $BACKUP_S3_BUCKET, encrypted"
else
    log "WARNING: BACKUP_S3_BUCKET is not set; backups stay on this server only"
fi

# crond starts jobs with an empty environment, so the job reads this one back. Only root can read it.
umask 077
export -p > /run/backup.env

schedule=${BACKUP_SCHEDULE:-30 2 * * *}
echo "$schedule . /run/backup.env && /usr/local/bin/backup.sh > /proc/1/fd/1 2>&1" > /etc/crontabs/root
log "scheduled: $schedule (TZ=${TZ:-UTC})"

# Prometheus scrapes the last good backup's time from here (#26, alert BackupStale); only on the internal
# network, the port is never published. Serves the file backup.sh writes, starting with what is on disk.
/usr/local/bin/backup.sh --metrics-only
httpd -p 9101 -h "${METRICS_DIR:-/run/metrics}"
log "metrics on :9101/metrics"

# A fresh deployment (or one down past its slot) gets a backup now rather than tomorrow night.
last=$(cat "${BACKUP_DIR:-/backups}/last-success" 2>/dev/null || echo 0)
if [ $(( $(date +%s) - last )) -ge 86400 ]; then
    /usr/local/bin/backup.sh || log "WARNING: the start-up backup failed; the next scheduled run will retry"
fi

exec crond -f -l 8
