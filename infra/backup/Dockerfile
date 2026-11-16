# Nightly database (and uploads) backups for the production stack (#24).
# The same Postgres major version as the database, so pg_dump/pg_restore always match the server.
FROM postgres:17-alpine

# rclone copies each backup off the server (S3-compatible storage, encrypted with rclone's crypt).
RUN apk add --no-cache rclone tzdata

COPY backup.sh entrypoint.sh /usr/local/bin/
RUN chmod 0755 /usr/local/bin/backup.sh /usr/local/bin/entrypoint.sh

# Unhealthy once the last good backup is more than 26 hours old: a nightly job that silently stopped
# shows up in `docker compose ps` (and later in the alerting, #26).
HEALTHCHECK --interval=15m --timeout=10s --start-period=30m \
    CMD test $(( $(date +%s) - $(cat /backups/last-success 2>/dev/null || echo 0) )) -lt 93600

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
