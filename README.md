# profound-baker-server
app for a certain patissier

## Backups and restore

The production stack (`docker-compose.prod.yml`) has a `backup` service (`infra/backup/`). Every night
(`BACKUP_SCHEDULE`, default 02:30 in `BACKUP_TZ`) it writes two files to `BACKUP_HOST_DIR` (default
`./backups` next to the compose file):

- `baker-<UTC time>.dump`: a `pg_dump` of the whole database (every module's schema and its Flyway
  history), custom format, checked with `pg_restore --list` before it is kept.
- `uploads-<UTC time>.tar.gz`: the uploaded item images.

Both are kept `BACKUP_RETENTION_DAYS` (default 14) on the server and, when `BACKUP_S3_BUCKET` is set,
copied to that bucket encrypted with `BACKUP_ENCRYPTION_PASSWORD` (rclone crypt) and kept there just as
long. The service takes a backup at start-up too if none succeeded in the last day.

**Is it working?** `docker compose -f docker-compose.prod.yml ps backup` shows `healthy` while the last
good backup is under 26 hours old. Its log (`docker compose -f docker-compose.prod.yml logs backup`)
lists every run, and warns when there is no off-box copy. Take one by hand with
`docker compose -f docker-compose.prod.yml exec backup backup.sh`.

### Restore the database

Restoring replaces everything in the database with the backup's contents. Orders, invoices and changes
made after the backup are lost, so take a fresh backup first (above) if the database still works.

```bash
cd /path/to/profound-baker-server
docker compose -f docker-compose.prod.yml stop app caddy      # nothing writes while restoring
ls -1 backups/                                                # pick the dump, e.g. baker-20261201T023000Z.dump
docker compose -f docker-compose.prod.yml exec -T backup sh -c \
    'pg_restore --clean --if-exists --no-owner --single-transaction -d "$PGDATABASE" /backups/baker-20261201T023000Z.dump'
docker compose -f docker-compose.prod.yml start app caddy
```

The backup container already has the database's name and credentials (`PG*` variables), hence `sh -c`.
`--single-transaction` makes it all or nothing: if anything fails, the database is left as it was. On
start the app checks the schema against its migrations, and refuses to run if they don't match. Log in
and check the latest orders.

### Restore the images

The backup service mounts the uploads volume read-only, so extract the archive from a one-off app
container, which can write to it:

```bash
docker compose -f docker-compose.prod.yml run --rm --no-deps -v "$PWD/backups:/backups:ro" \
    --entrypoint sh app -c 'tar -xzf /backups/uploads-20261201T023000Z.tar.gz -C /data/uploads'
```

### Restore from the off-box copy (the server is gone)

On the new server, set up the stack and `.env` as before, with the same `BACKUP_S3_*` and
**`BACKUP_ENCRYPTION_PASSWORD`**: without that password the off-box copies can't be decrypted. Then:

```bash
docker compose -f docker-compose.prod.yml up -d postgres backup
docker compose -f docker-compose.prod.yml exec backup rclone ls offsitecrypt:           # list the backups
docker compose -f docker-compose.prod.yml exec backup \
    rclone copy offsitecrypt:baker-20261201T023000Z.dump /backups/
docker compose -f docker-compose.prod.yml exec backup \
    rclone copy offsitecrypt:uploads-20261201T023000Z.tar.gz /backups/
```

Then restore the database and the images as above, and start the rest with
`docker compose -f docker-compose.prod.yml up -d`.

### Practise it

A backup nobody has restored is a hope, not a backup. Every few months, restore the latest dump into a
scratch database and look at it:

```bash
docker compose -f docker-compose.prod.yml exec backup sh -c \
    'createdb restore_check && pg_restore --no-owner -d restore_check "$(ls -1 /backups/baker-*.dump | tail -n 1)" \
     && psql -d restore_check -c "SELECT count(*) FROM ordering.orders" && dropdb restore_check'
```
