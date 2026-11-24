# profound-baker-server

The backend for **SugarCrumbs**, a bakery run by one baker (the Owner). Customers browse the catalogue and
order as guests, with no accounts: each order gets a private magic link. The Owner signs in to manage items,
opening hours, orders and invoices. It is a REST API for a separate web SPA (storefront and owner dashboard).

**Stack:** Java 21, Spring Boot 4.1, Spring Modulith, PostgreSQL 17 with Flyway, JWT (RS256) auth, and
Docker Compose with Caddy in production, plus Prometheus, Tempo and Grafana for observability.
[CLAUDE.md](CLAUDE.md) holds the architecture, business and security rules in full; `docs/` holds the roadmap.

## Layout

A modular monolith: one deployable, with module boundaries checked by the build (`ModularityTests`), so
a module can later become its own service.

| Module | Owns |
|---|---|
| `common` | Shared kernel: base entity, `ApiError` and the global exception handler. No business rules. |
| `identity` | The Owner: login, refresh tokens, profile, password, time zone. |
| `catalog` | Items, categories, prices, stock and images. |
| `ordering` | Orders, working hours and blocked dates (availability), invoices, dashboard. |
| `app` | The Spring Boot application: security, CORS, rate limits, request ids, observability config. |
| `infra/` | Caddy, the backup job, Prometheus, Tempo and Grafana configuration, and the e2e stack's restore script. |

Each module keeps its tables in its own Postgres schema (`identity`, `catalog`, `ordering`) with its own
migrations in `<module>/src/main/resources/db/migration/<module>/`.

## Run it locally

Needs Docker and JDK 21+ (IntelliJ's bundled JBR works: set `JAVA_HOME` to it).

```bash
cp .env.example .env                      # once: fill in DB_PASSWORD, APP_OWNER_* (git-ignored)
docker compose up -d                      # Postgres on localhost:5432
./mvnw clean install                      # build and run every test (Testcontainers: Docker must be up)
./mvnw -pl app spring-boot:run -Dspring-boot.run.profiles=dev
```

The API is then on <http://localhost:8080>, with Swagger UI at
<http://localhost:8080/swagger-ui.html> (OpenAPI JSON at `/v3/api-docs`). On first start the app creates
the Owner from `APP_OWNER_EMAIL` and `APP_OWNER_INITIAL_PASSWORD`; sign in with those. The `dev` profile
reads `.env`, allows CORS from `http://localhost:5173` (override with `APP_CORS_ALLOWED_ORIGINS`), and
generates a throwaway JWT key, so tokens stop working when the app restarts.

Faster checks: `./mvnw -pl app test -Dtest=ModularityTests` (module boundaries, no database) or
`./mvnw -pl <module> test` for one module.

## Using the API (for the SPA)

Swagger UI is the full reference. The things worth knowing up front:

**Routes.** `/api/public/**` needs no sign-in. `/api/auth/login`, `/api/auth/refresh` and
`/.well-known/jwks.json` are public too. Everything else under `/api/**` is the Owner's and needs
`Authorization: Bearer <accessToken>`: a missing or bad token gets 401, the wrong role 403.

| Area | Public | Owner |
|---|---|---|
| Catalogue | `GET /api/public/catalogue/items`, `/items/{id}`, `/categories` (available items only) | `/api/catalogue/items/**`, `/api/catalogue/categories/**` |
| Availability | `GET /api/public/availability?date=` or `?from=&to=` (at most 62 days) | `/api/availability/working-hours/**`, `/api/availability/blocked-dates/**` |
| Orders | `POST /api/public/orders` (checkout), `GET /api/public/orders/by-token/{token}`, `POST …/by-token/{token}/cancel` | `/api/orders/**`: list and filter, confirm, reject, start-preparation, fulfill, cancel, reschedule, edit lines and notes |
| Invoices | | `/api/invoices/**`, `/api/orders/{id}/invoice`: discount, pay, refund |
| Dashboard | | `GET /api/dashboard/summary`, `GET /api/dashboard/production?date=` |
| Bakery | `GET /api/public/bakery`: `businessName`, `timeZone`, `logoUrl`, `phone`, `address` | |
| Owner | | `/api/auth/me`, `/api/auth/logout`, `/api/owner/profile`, `/api/owner/password` |

**Signing in.** `POST /api/auth/login` with `{"email", "password"}` returns `accessToken` (valid for
`expiresIn` seconds, 15 minutes), `refreshToken` and `refreshTokenExpiresAt` (14 days). Before the access token
expires, `POST /api/auth/refresh` with `{"refreshToken"}` returns a new pair. **Each refresh token works
once:** keep the new one. Sending a used refresh token again counts as theft and signs out every session from
that login. `POST /api/auth/logout` with `{"refreshToken"}` ends the session.

**Checkout and the magic link.** Read the open slots from `/api/public/availability`. Each day lists `slots`
with `startTime`/`endTime` (local times in the bakery's time zone, for display), `startsAt`/`endsAt` (the
same moments as UTC instants), `remainingCapacity` and `bookable`. Send the chosen slot's `startsAt` as
`pickupAt`; don't convert zones in the SPA. `GET /api/public/bakery` gives the `timeZone` for labelling
times and working out the bakery's "today". Checkout sends `pickupAt`, `guestContact` (`name`,
`email`, `phone`), optional `notes` and `lines` (`itemId`, `quantity`). The response's `accessToken` is
the guest's magic-link token. **It is returned only this once**: put it in the link you show the guest
(`/api/public/orders/by-token/{token}` reads the order). Unknown or expired tokens get 404.

**Order statuses:** `PENDING` → `CONFIRMED` (or `REJECTED`, with a reason) → `IN_PROGRESS` → `FULFILLED`.
`CANCELLED` is a customer or system cancellation; pending orders the Owner never answers are cancelled
after 24 hours. Confirming creates the invoice (`INV-YYYY-NNNN`).

**Errors** always have the same body: `timestamp`, `status`, `error`, `message`, `path`, and for 400s
`fieldErrors` (`field`, `message`), ready to show next to form fields. 409 means a conflict (a slot just
filled up, or someone else changed the record first: reload and retry). 429 means rate-limited (login 5 a
minute, checkout 10 and magic links 30 per 10 minutes per IP). Wait for `Retry-After` seconds.

**Lists** are paginated with `?page=0&size=20&sort=field,desc` (`size` at most 100). The page JSON is
still Spring Data's default shape (`content`, `totalElements`, `totalPages`, …). It will change to
`content` + `page{}`, so read it through one helper in the SPA.

**Images.** Items carry `imageUrls` such as `/uploads/items/<uuid>.jpg` and, in the same order,
`thumbnailUrls` (at most 400 px a side, for lists and cards; the full image is at most 2000 px). They are relative to the API
origin: in production Caddy serves them, in dev the app does. Upload with
`POST /api/catalogue/items/{id}/images/upload`, `multipart/form-data` with a `file` part: JPEG, PNG or
WebP, at most 10 MB and 10 000 px a side. PNGs stay PNG, and JPEG and WebP are stored as JPEG, without EXIF.

**Request ids.** Every response has an `X-Request-Id` header. Show it in error messages, and it leads
straight to the server's logs and trace (see [Observability](#observability)).

## Deploy

On the server (any Linux host with Docker), from a clone of this repository:

```bash
cp .env.example .env    # fill in every value; see the comments in the file
docker compose -f docker-compose.prod.yml up -d --build
```

`.env` must set at least `DB_PASSWORD`, `APP_DOMAIN` (the public host name; Caddy fetches its TLS
certificate), `APP_CORS_ALLOWED_ORIGINS` (`https://<APP_DOMAIN>`: the SPA is served from the same domain), `APP_JWT_PRIVATE_KEY` (generate it with the
command in `.env.example`), `APP_OWNER_EMAIL` and `APP_OWNER_INITIAL_PASSWORD` (first start only; remove the
password afterwards), and `GRAFANA_ADMIN_PASSWORD`. Generate passwords rather than typing them, for example:

```bash
grep -q '^GRAFANA_ADMIN_PASSWORD=' .env || echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24)" >> .env
```

The stack includes the web app: the `web` service runs the image
[profound-baker-web](https://github.com/WARHE8D/profound-baker-web) publishes to GHCR on every merge to its
`main`. Caddy sends `/api/*`, `/.well-known/*` and the OpenAPI docs to the app, `/uploads/*` to the files,
and everything else to `web`. To update only the web app:
`docker compose -f docker-compose.prod.yml pull web && docker compose -f docker-compose.prod.yml up -d web`.

Only Caddy is reachable from the internet (ports 80 and 443). Grafana listens on the server's loopback
(3000). The app's management port, Postgres, Prometheus and Tempo stay on the internal Docker network.
To update, `git pull` and run the same `up -d --build`. Migrations run when the app starts.

To try the whole stack on your own machine, set `APP_DOMAIN=localhost` (Caddy then uses its own local
certificate, so `curl -k https://localhost/...`).

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
good backup is under 26 hours old, and the `BackupStale` alert fires once it is older (see
[Observability](#observability)). Its log (`docker compose -f docker-compose.prod.yml logs backup`)
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

## End-to-end stack for the web app

profound-baker-web's end-to-end tests run against the production stack with `docker-compose.e2e.yml`
layered on top, filled with a **copy** of production's data. It is never run against production itself:
the tests rewrite the bakery's profile and working hours, add an item and place orders. It runs as its
own project (`sugarcrumbs-e2e`), so it can run beside the production stack without touching it. It has
no backups or observability services, and no ports 80/443.

| URL | What |
|---|---|
| <http://localhost:8080> | The API, for the SPA on `vite preview` (`http://localhost:4173`). |
| <http://localhost:8081> | The site as deployed: web app and API behind the production Caddyfile, on plain HTTP. Use `localhost`, not `127.0.0.1`: Caddy only answers for its site address. |

```bash
E2E="docker compose -f docker-compose.prod.yml -f docker-compose.e2e.yml --env-file .env.e2e"
$E2E up -d --build --wait          # restore the newest dump in ./backups and start everything
$E2E logs -f app                   # or db-restore / uploads-restore: which dump was loaded
$E2E down                          # the database lives in memory: the next `up` restores afresh
$E2E down -v                       # also drop the e2e uploads volume
```

**Which data.** On start, `db-restore` loads the newest `baker-*.dump` in `E2E_BACKUP_DIR` (default
`./backups`, where production's backup job writes), or the file `E2E_DUMP` names. `uploads-restore` then
extracts the `uploads-*.tar.gz` taken with it. With no dump the bakery starts empty and the app creates the
e2e Owner itself. Either way the tests sign in as `owner@e2e.test` / `e2e-owner-password` (`.env.e2e`,
matching profound-baker-web's `e2e/support/env.ts`): on a copy, `infra/e2e/restore.sh` gives the real
Owner that login and deletes production's refresh tokens. `up` on a running stack keeps the loaded data.
To load a newer dump, run `down` and then `up`.

To test on a dev machine, copy a dump from the server:

```bash
scp '<user>@<server>:/path/to/profound-baker-server/backups/baker-<UTC time>.dump' \
    '<user>@<server>:/path/to/profound-baker-server/backups/uploads-<UTC time>.tar.gz' backups/
```

Dumps hold guests' names, phone numbers and emails. `backups/` is git-ignored. Keep copies off shared
machines, and delete them when you're done.

**An unpublished web build.** By default `web` is the published
`ghcr.io/warhe8d/profound-baker-web:${WEB_IMAGE_TAG:-latest}`. To build a checkout instead, add the third
file:

```bash
WEB_DIR=../profound-baker-web docker compose -f docker-compose.prod.yml -f docker-compose.e2e.yml \
    -f docker-compose.e2e-web.yml --env-file .env.e2e up -d --build --wait
```

CI starts this stack empty on every push (`e2e-stack` job), so a change to the production compose file
that breaks it fails here.

## Observability

The production stack also runs Prometheus, Tempo and Grafana (#26). Their configuration is in
`infra/` and is loaded from those files on every start: change a file and redeploy, never the UI.

| What | Where it comes from |
|---|---|
| Metrics | Prometheus scrapes the app's `/actuator/prometheus` on the internal management port (9080) every 15s, and the backup job's last-success time from `backup:9101`. Kept `PROMETHEUS_RETENTION` (default 30 days). |
| Traces | The app sends every request's trace to Tempo over OTLP, with a span for each database connection and query (SQL with `?` placeholders, never the values). Kept 7 days. Tempo turns them into the service graph (`user → baker → baker-db`) and request metrics. |
| Logs | One JSON object per line on the app container's stdout (`docker compose -f docker-compose.prod.yml logs app`), with `traceId`, `spanId` and `requestId` fields. |
| Dashboards | Grafana, folder *SugarCrumbs*: **Service overview** (traffic, 4xx/5xx by route, p50/p95/p99 latency, JVM, database pool, queries and query time) and **Business** (orders by status, pending backlog, auto-cancelled orders, revenue, failed logins, last backup). |
| Alerts | `infra/prometheus/alerts.yml`: a service down, 5xx rate over 5%, a growing pending backlog, auto-cancelled orders, a burst of failed logins, a stale or failed backup. They show in Grafana under *Alerting*. Nothing sends them anywhere yet: that needs an Alertmanager. |

**Open Grafana.** It listens on the server's loopback only, never on the internet. Tunnel to it, then
browse to <http://localhost:3000> and sign in as `GRAFANA_ADMIN_USER` (default `admin`) with
`GRAFANA_ADMIN_PASSWORD`:

```bash
ssh -L 3000:127.0.0.1:3000 <user>@<server>
```

**From an error to its cause.** Every response has an `X-Request-Id` header (the SPA may also send one,
8-64 letters, digits, `.`, `_` or `-`). Find its log lines with
`docker compose -f docker-compose.prod.yml logs app | grep <request id>`; each line also has the
`traceId`. In Grafana, *Explore > Tempo* finds the trace by trace id, or by the `request.id` span
attribute. Latency panels show exemplar dots that open the trace of that request.

**Change the alert rules** together with their tests in `infra/prometheus/alerts.test.yml`; CI runs
them with promtool (`AlertRulesTests`), or by hand:

```bash
docker run --rm -v ./infra/prometheus:/etc/prometheus -w /etc/prometheus --entrypoint promtool \
    prom/prometheus:v3.9.1 test rules alerts.test.yml
```
