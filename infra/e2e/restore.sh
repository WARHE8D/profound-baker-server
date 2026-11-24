#!/bin/sh
# Fills the end-to-end stack (docker-compose.e2e.yml) with a copy of production: one step of it per call.
#   restore.sh db       restore the dump into the e2e database, then hand the Owner to the e2e tests
#   restore.sh uploads  replace the e2e uploads volume with the dump's images
#
# The dump is E2E_DUMP (a file name in /backups, or empty for the newest baker-*.dump there). Its images
# are the uploads-<same time>.tar.gz next to it. With no dump at all, both steps do nothing and the stack
# starts empty: the app then creates the e2e Owner itself (APP_OWNER_*).
#
# Environment (db): PGHOST, PGUSER, PGPASSWORD, PGDATABASE (libpq's own), E2E_OWNER_EMAIL,
# E2E_OWNER_PASSWORD. BACKUP_DIR (default /backups) and UPLOADS_DIR (default /data/uploads) are the mounts.
set -eu

BACKUP_DIR=${BACKUP_DIR:-/backups}
UPLOADS_DIR=${UPLOADS_DIR:-/data/uploads}

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) e2e-restore: $*"; }

if [ -n "${E2E_DUMP:-}" ]; then
    dump="$BACKUP_DIR/$(basename "$E2E_DUMP")"
    [ -f "$dump" ] || { log "E2E_DUMP=$E2E_DUMP is not in the backup directory"; exit 1; }
else
    # File names carry a sortable UTC time (baker-20261201T023000Z.dump): the last one is the newest.
    dump=$(ls -1 "$BACKUP_DIR"/baker-*.dump 2>/dev/null | tail -n 1 || true)
fi

case "${1:-}" in
db)
    if [ -z "$dump" ]; then
        log "no dump in the backup directory: starting with an empty database"
        exit 0
    fi
    # `up` on a running stack starts this again: restore once per database (it lives in memory, so
    # `down` + `up` gives a fresh copy), never over the data a test run is using.
    if [ "$(psql -tAc "SELECT count(*) FROM pg_namespace WHERE nspname = 'identity'")" != 0 ]; then
        log "the database already holds data: keeping it (down and up again for a fresh copy)"
        exit 0
    fi
    log "restoring $(basename "$dump")"
    # The database is new, so nothing to clean first. All or nothing.
    pg_restore --no-owner --single-transaction -d "$PGDATABASE" "$dump"

    # The copy keeps the real bakery but not the real login: the tests sign in with the e2e Owner's
    # credentials, and refresh tokens issued by production never work here. pgcrypto hashes the password
    # with BCrypt (cost 12, as the app does), then goes away again so the schema stays as production has it.
    log "giving the Owner the e2e credentials"
    psql -v ON_ERROR_STOP=1 -q \
        -v email="${E2E_OWNER_EMAIL:?}" -v password="${E2E_OWNER_PASSWORD:?}" <<'SQL'
BEGIN;
CREATE EXTENSION pgcrypto SCHEMA public;
UPDATE identity.owners SET email = :'email', password_hash = public.crypt(:'password', public.gen_salt('bf', 12));
DELETE FROM identity.refresh_tokens;
DROP EXTENSION pgcrypto;
COMMIT;
SQL
    log "done"
    ;;
uploads)
    # Runs as the app's own user, so the app can still delete images it no longer uses. The volume
    # outlives `down` (not `down -v`): a marker records which dump it holds, so it is filled only once per
    # dump and images a test run uploaded are not removed by another `up`.
    marker="$UPLOADS_DIR/.e2e-restored"
    wanted=$(basename "${dump:-none}")
    if [ "$(cat "$marker" 2>/dev/null)" = "$wanted" ]; then
        log "uploads already hold $wanted: keeping them"
        exit 0
    fi
    find "$UPLOADS_DIR" -mindepth 1 -delete
    stamp=$(basename "${dump:-none}" .dump)
    archive="$BACKUP_DIR/uploads-${stamp#baker-}.tar.gz"
    if [ -z "$dump" ]; then
        log "no dump in the backup directory: starting without images"
    elif [ ! -f "$archive" ]; then
        log "no $(basename "$archive") next to the dump: starting without images"
    else
        log "extracting $(basename "$archive")"
        tar -xzf "$archive" -C "$UPLOADS_DIR"
    fi
    echo "$wanted" > "$marker"
    log "done"
    ;;
*)
    echo "usage: restore.sh db|uploads" >&2
    exit 2
    ;;
esac
