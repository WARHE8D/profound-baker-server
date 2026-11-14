# CLAUDE.md — profound-baker-server

Backend for **SugarCrumbs**, a business run by **one baker (the Owner)**. Customers browse items and place
orders as guests (no customer accounts). The Owner manages the catalog, accepts or rejects orders, and controls
when they are available.

The system is **single-tenant**: there is exactly one Owner. Don't add multi-tenant abstractions.

## 0. Source of truth for planning

- **Roadmap and design:** `docs/PLAN.md` and `docs/ISSUES.md`. They're currently only on branch
  `claude/fervent-mayer-4hj5c9` and need merging into `main`.
- **Work items:** GitHub issues **#4–#26**, titled `[P<phase>.<n>]`. Each PR should close exactly one issue
  (`Closes #N`). Use `gh issue view N` to read the details before starting.
- **This file overrides PLAN.md on architecture:** the system is a **modular monolith** built so each module
  can be extracted into a microservice later (section 3), instead of PLAN.md's single unstructured app. Everything else in PLAN.md still applies unless this file says otherwise:
  domain decisions, business rules, and phases.

---

## 1. Product scope

### Must have (current priority)
1. **Security first.** Every Owner/admin endpoint needs authentication. Public endpoints are read-only, except
   guest checkout, which is rate-limited and validated.
2. **Catalog / storefront.** The Owner shows static items (cakes, bread, pastries…) with categories, prices,
   images, lead time and optional stock. The public front page reads these.
3. **Orders.** The Owner sees all orders (filter by date range, status and search; paginated).
4. **Accept / reject orders.** `PENDING → CONFIRMED` (accept) or `PENDING → REJECTED` (reject, with reason).
   `REJECTED` is a separate status from `CANCELLED`, so reports can tell owner rejections apart from
   customer or system cancellations.
5. **Availability.** Weekly working hours with slot length and capacity, plus blocked or "not available" dates
   (single dates, ranges, yearly recurring). Orders can only go into open slots.
6. **Observability.** Prometheus metrics, Grafana dashboards for errors, latency and traffic, and distributed
   tracing so calls between services can be seen as a service graph (issue #26, expanded).

### Product decisions (from PLAN.md, keep them)
| Topic | Decision |
|---|---|
| Client | Separate web SPA (public storefront + owner dashboard) using this REST API |
| Owner auth | JWT: short-lived access token + rotating refresh token |
| Order model | Multi-item **Order** with **OrderLine**s (name/price snapshot) and one pickup slot |
| Payments | Offline only (`CASH` / `BANK_TRANSFER`). The Owner marks invoices paid |
| Guest access | Magic link: a secret token per order to view or cancel it. No guest accounts |
| Notifications | None yet. Publish domain events so email or WhatsApp can be added later |
| Deployment | Single VPS with Docker Compose. Caddy handles TLS and reverse proxy. Images are stored on local disk |

### Already built and worth keeping
- Booking state machine guarded inside the entity (`entity/Booking.java`). It becomes `Order` in #14.
- Slot availability engine (`service/AvailabilityService.java`): working hours + blocked dates + current
  bookings give per-slot remaining capacity. Range queries are capped at 62 days.
- Stale-pending auto-cancel job (`config/StaleBookingCleanupJob.java`, `app.booking.pending-hold-hours`).
- Item stock tracking (`stockQuantity == null` means not tracked). Decremented on order, released on cancel.
- Image upload behind the `ImageStorageService` interface, with a content-type allowlist and sanitized paths.
  Files are served at `/uploads/**` (Caddy from the read-only volume in prod, `UploadsResourceConfig` in the
  app otherwise) and deleted from disk after commit when removed from an item or when the item is deleted.
- `Invoice` entity (modeled, no API yet; see #20 and #21). `Owner` entity with `passwordHash` + `Address`.
- `BaseEntity`: UUID ids, audit timestamps, `@Version` optimistic locking, correct JPA equals/hashCode.
- Uniform `ApiError` body via `GlobalExceptionHandler`.

---

## 2. Current state (as of 2026-11-11)

A **modular monolith** (Spring Boot 4.1.1, Java 21, Spring Modulith 2.1) split into Maven modules `common`,
`identity`, `catalog`, `ordering` and `app` (step A, done). Uses PostgreSQL 17 (docker-compose), JPA, MapStruct
and springdoc. The `app` module is packaged as an executable jar, `app/target/baker.jar`. See §3 for the module rules.

Known problems (see the PLAN.md §2 table; the issue that fixes each one is in brackets):
- **Spring Security is commented out** in `pom.xml`. Every endpoint is public, and `GET /api/bookings` leaks
  guests' contact details. [#10, #13]
- No way to create the Owner, so availability and booking fail until a row is inserted by hand. [#11]
- **Overbooking race:** capacity is checked, then the booking is inserted, with no lock in between. [#15]
- Orders for times in the past are accepted when an item has `leadTimeHours = 0`. The lead-time check also
  uses `toHours()`, which rounds down. [#15]
- No price snapshot, so repricing an item rewrites historical orders. [#14]
- `BusinessTimeZone.ZONE = ZoneId.systemDefault()`. It should come from `Owner.timeZone`. [#12]

---

## 3. Architecture: modular monolith now, microservices later

**Decision (2026-11-11):** build a **modular monolith** first and extract services only when there's a
concrete reason (independent scaling, separate deploy cadence, team boundaries). For one baker on one VPS,
running 4–5 services from day one adds operational cost (network failures, distributed transactions, more
deployables to monitor) with no benefit yet. What makes a later split cheap is **hard module boundaries now**,
and those are enforced by the build.

### Stage 1 (now): modules in one deployable

```
profound-baker-server/
├── pom.xml          # baker-parent: versions, annotation processors, module list
├── common/          # com.sugarcrumbs.common    shared kernel (OPEN module): BaseEntity, JpaAuditingConfig,
│                    #                           ApiError + GlobalExceptionHandler, Conflict/NotFound exceptions,
│                    #                           BusinessTimeZone. No business rules.
├── identity/        # com.sugarcrumbs.identity  the Owner                  → future auth-service
├── catalog/         # com.sugarcrumbs.catalog   items, categories, images  → future catalog-service
├── ordering/        # com.sugarcrumbs.ordering  bookings, availability,    → future order-service
│                    #                           invoices
├── app/             # com.sugarcrumbs           BakerApplication, application.properties, ModularityTests.
│                    #                           The only bootable module; no business code.
├── docs/            # PLAN.md, ISSUES.md
└── CLAUDE.md
```

Allowed dependencies (declared in each module's root `package-info.java` via `@ApplicationModule`):
`ordering → catalog, identity, common`, `catalog → common`, `identity → common`. **Nothing may depend on
`ordering`, and no cycles are allowed.**

### Module rules (enforced by `app/src/test/java/com/sugarcrumbs/ModularityTests.java`)
1. **A module's published API is its root package only** (e.g. `com.sugarcrumbs.catalog.CatalogApi`,
   `ItemSnapshot`, `ItemReferenceGuard`; `com.sugarcrumbs.identity.OwnerApi`). Every sub-package (`domain`,
   `application`, `persistence`, `web`, `storage`, …) is internal. Spring Modulith fails the build if another
   module touches it.
2. **Published APIs use plain records and ids, never entities.** Design every API method as if it were an
   HTTP call: coarse-grained, batch-friendly (`getItems(Collection<UUID>)`), no lazy proxies, and no reliance on
   the caller's transaction for correctness.
3. **No JPA relations across modules.** Store the other module's id as a plain `UUID` column
   (`Booking.itemId`, `WorkingHours.ownerId`, `BlockedDate.ownerId`), with no `@ManyToOne` and no FK constraint
   in new migrations.
4. **Each module owns its schema.** Every module has its own Postgres schema (`identity`, `catalog`,
   `ordering`; entities declare `@Table(schema = …)`), its own migrations in
   `<module>/src/main/resources/db/migration/<module>/`, and its own Flyway history table. `app`'s
   `FlywayModulesConfig` runs one Flyway per module. Never query another module's schema, even though they
   share a database today. Migrations use unqualified table names (Flyway sets `search_path`).
5. **Reverse questions use extension points, not dependencies.** When catalog needs to know something only
   ordering knows (e.g. "is this item referenced?"), catalog publishes an interface (`ItemReferenceGuard`) and
   ordering implements it. For notifications, prefer domain events (`ApplicationEventPublisher`, Spring Modulith
   `@ApplicationModuleListener`) so they can become broker messages later.
6. **Adding a module:** create a Maven module, add a root `package-info.java` with `@ApplicationModule(
   allowedDependencies = …)`, add it to the parent `<modules>` and `dependencyManagement`, and to `app`'s
   dependencies.

### Code layout inside a module
```
com.sugarcrumbs.<module>/
├── <Module>Api.java, *Snapshot.java   # published API (root package): interfaces + records only
├── package-info.java                  # @ApplicationModule(allowedDependencies = …)
├── domain/        # entities, enums, value objects, invariants, domain events
├── application/   # services (@Transactional boundaries), <Module>Api implementation, scheduled jobs
├── persistence/   # Spring Data repositories, specifications
├── web/           # controllers; web/dto (request/response records); web/mapper (MapStruct)
└── config/        # module-specific @Configuration
```
Inside a module, packages may use each other freely. The layering is a convention, not enforced.

### Stage 2 (later): extracting a module into a service
Each step is mechanical when the rules above have been followed:
1. Give the module its own Spring Boot application class and Dockerfile under `services/<name>/`.
2. Expose its `<Module>Api` as `/internal/**` endpoints that delegate to the existing implementation.
3. In the consuming module, replace the in-process bean with a `RestClient` implementation of the same interface
   (timeouts, Resilience4j). Callers don't change.
4. Move the module's schema to its own database (`pg_dump -n <schema>`, history table included) and point
   the service's standard `spring.flyway.*` config at it (`schemas=<schema>`, same migration location).
5. Add the gateway (Spring Cloud Gateway) and routes. Move events to a broker if anything consumes them across
   processes.

Target services once extracted (route prefixes follow #13):

| Service | Owns (DB) | Public | Owner-only (`ROLE_OWNER`) |
|---|---|---|---|
| **auth-service** | `owner`, `refresh_tokens` | `POST /api/auth/login`, `/refresh`, `GET /.well-known/jwks.json` | `/api/auth/logout`, `/api/auth/me`, `/api/owner/profile`, `/api/owner/password` |
| **catalog-service** | `items`, `categories`, image files | `GET /api/public/catalogue/**` (only available items) | `/api/catalogue/**`: item and category CRUD, price, stock, availability toggle, image upload |
| **order-service** | `orders`, `order_lines`, `working_hours`, `blocked_dates`, `invoices` | `GET /api/public/availability`, `POST /api/public/orders`, `/api/public/orders/by-token/{token}/**` | `/api/orders/**` (list/filter, confirm/reject/start/fulfil/cancel/reschedule, edit lines while `PENDING`), `/api/availability/**`, `/api/invoices/**`, `/api/dashboard/**` |

**Why availability stays with orders (module and service alike):** slot capacity is computed from orders, and
checkout must lock the slot and count capacity in the same DB transaction (#15). Splitting them would need
distributed locking, which is more complexity than a single baker needs.

**The Owner's time zone** belongs to identity (#12). Ordering reads it through `OwnerApi` (and later through
`GET /internal/owner/settings`, cached).

### Cross-module contracts (same rules apply in-process today and over HTTP later)
- **Item lookup** (ordering → catalog): today `CatalogApi.getItem` / `getItems`; once extracted,
  `POST /internal/items/batch`. Ordering validates each item (orderable, lead time, quantity limits), copies
  `itemName` and `unitPrice` into the `OrderLine` (#14), and stores `itemId` as a plain UUID.
- **Stock**: today `CatalogApi.reserveStock` / `releaseStock`, joining the caller's transaction. Before
  extraction they must become **idempotent**, keyed by order id (`POST /internal/stock/reserve` and `/release`).
  Reserve must succeed before the order commits; if the order fails afterward, release it (a small
  compensation step). Concurrent stock changes return 409, never 500.
- **Item deletion**: catalog asks every `ItemReferenceGuard` (ordering implements one). This goes away once
  orders keep snapshots (#14) and items are soft-deleted.
- The gateway (stage 2) **never** routes `/internal/**` or `/actuator/**`. Internal calls carry a service JWT
  (client-credentials token from auth-service) and are only reachable on the internal Docker network.
- HTTP clients use Spring `RestClient` with connect and read timeouts, plus Resilience4j: a circuit breaker,
  and retries only on idempotent calls.
- **Domain events** (#17): `OrderPlaced/Confirmed/Rejected/Cancelled/Rescheduled/Fulfilled` are published after
  commit. They stay in-process for now, and are the hook for a future `notification-service`.

---

## 4. Business rules (from PLAN.md §3)

- **Capacity:** one order takes one unit of its slot's `maxConcurrentBookings`, however many lines it has.
- **Lead time:** the order's lead time is the longest lead time among its lines. `pickupAt` must be in the
  future and on an open, bookable slot.
- **Concurrency:** create and reschedule take a `PESSIMISTIC_WRITE` lock on that day's `WorkingHours` row before
  counting capacity. A concurrency test must show that N parallel checkouts into a capacity-1 slot give
  exactly one success.
- **Reference:** each order gets a human-readable reference such as `SC-7F3K2`.
- **Magic link:** a 32-byte random token, returned **once** in the checkout response. Only its SHA-256 hash is
  stored, and lookups use constant-time comparison. Unknown or expired tokens return 404. The token expires 30
  days after the order reaches a final state (fulfilled, rejected or cancelled). Guests may cancel only while
  `PENDING`/`CONFIRMED` and more than `app.order.guest-cancel-cutoff-hours` (default 24) before pickup.
- **Invoice:** created on confirm, with the subtotal taken from the line snapshots. Numbers are `INV-YYYY-NNNN`
  from a DB sequence. The invoice is voided if the order is cancelled while it's still pending. The Owner can
  apply a discount, mark it paid (`CASH`/`BANK_TRANSFER` + reference), or refund it.
- **Stale pending orders** are auto-cancelled after the hold window, which releases their capacity and stock.

---

## 5. Security rules (non-negotiable)

- auth-service issues **RS256 JWTs**: access tokens last 15 minutes and carry `sub = ownerId` and
  `roles=[OWNER]`. **Refresh tokens** last 14 days and rotate on every use. They're stored hashed, and reusing a
  revoked token revokes the whole chain.
- **Validate tokens everywhere:** the gateway validates JWTs, **and** every service is also an OAuth2 Resource
  Server checking the JWKS. Never rely on "the gateway already checked".
- Hash passwords with BCrypt (cost ≥ 12) via `PasswordEncoder`. Never log or return `passwordHash`.
- **Owner bootstrap** (#11): on first start with no Owner, create it from `APP_OWNER_EMAIL` and
  `APP_OWNER_INITIAL_PASSWORD`. If neither exists, fail fast. There is no public signup endpoint, ever.
- Rate-limit login, checkout and magic-link endpoints per IP (gateway `RequestRateLimiter` or Bucket4j).
  Allow CORS only from the configured SPA origin.
- 401 and 403 responses use the `ApiError` shape. Public responses never expose other guests' data.
- Validate all input with Bean Validation on request records. Never bind entities directly from a request.
- Images: check magic bytes rather than trusting Content-Type, cap size and dimensions, strip EXIF, use random
  filenames, and delete the file from disk when an image is removed (#8, #25).
- Secrets come from env vars only. Commit a `.env.example`, and keep `.env` git-ignored.
- Actuator runs on a separate management port and exposes only `health`, `info` and `prometheus`.
- Errors never leak stack traces or SQL. Log the details server-side with the request/trace id.
- Keep CodeQL green.

---

## 6. Observability (#26, expanded)

- The deployable (`app` today, each service after extraction) includes actuator,
  `micrometer-registry-prometheus`, and `micrometer-tracing-bridge-otel` with the OTLP exporter. Tracing pays off
  from day one: spans show module calls in-process now and service-to-service calls after the split.
  Spring Modulith's `spring-modulith-observability` adds per-module spans.
- **Prometheus** scrapes `/actuator/prometheus` on each management port. **Tempo** receives OTLP traces, and
  Grafana's service graph shows gateway → service → service calls.
- **Grafana** datasources and dashboards are **provisioned from files** in `infra/grafana/`. Don't configure
  them by hand in the UI. Dashboards:
  - *Service overview*: request rate, 4xx and 5xx by route, p50/p95/p99 latency, JVM, HikariCP, circuit breaker state.
  - *Business*: orders by status, pending backlog, auto-cancelled orders, login failures, invoice revenue.
- Custom metrics go in application services, with names such as `baker_orders_total{status}`,
  `baker_orders_pending`, `baker_stale_orders_cancelled_total`, `baker_auth_login_failures_total`.
- Prod logs are JSON and include `traceId`, `spanId` and `requestId`. Log WARN with a stack trace for every 5xx.
  Don't log PII (emails, phones) at INFO.
- Prometheus alert rules in `infra/prometheus/alerts.yml`: 5xx rate, a service down, a growing pending backlog,
  backup job failure.

---

## 7. Coding conventions

- Java 21, Spring Boot 4.1.x. Use the Spring Cloud release train that matches Boot 4.1; check compatibility
  before bumping either one.
- Follow the module rules in §3. Put new code in the module that owns the concept; never add business logic to
  `common` or `app`.
- `app` is an executable **jar** (embedded Tomcat). The root `Dockerfile` is a multi-stage build (JDK + Maven, then a
  layered jar on `eclipse-temurin:21-jre`) and runs as a non-root user. Tests don't run inside the image build;
  CI runs them.
- **Rich domain entities:** no public setters. State changes go through intention-revealing methods
  (`confirm()`, `reject(reason)`, `reprice(...)`) that enforce invariants. Keep the builder pattern.
- Requests and responses are `record` DTOs, mapped with MapStruct. Entities never leave the service layer.
- `@Transactional` goes on application services, with `readOnly = true` on read paths.
- Schema changes go **only** through Flyway, in the owning module
  (`<module>/src/main/resources/db/migration/<module>/V<n>__<desc>.sql`; versions are per module), with
  `ddl-auto=validate`. Never edit an applied migration. A new module needs its schema in `FlywayModulesConfig`.
- Money is `BigDecimal` in `NUMERIC(10,2)` columns. Times are stored as `Instant` and converted with the Owner's
  time zone.
- Errors (`common`'s `GlobalExceptionHandler`, which extends `ResponseEntityExceptionHandler`): `IllegalArgument`/
  `IllegalState`, validation, type mismatch, missing parameter/part and unreadable body return 400. `NotFoundException`
  and unknown paths return 404. `ConflictException`, `DataIntegrityViolationException` and every
  `ConcurrencyFailureException` (optimistic and pessimistic lock failures) return 409. Other Spring MVC errors keep
  their own status (405, 415, 413…). Everything else returns a generic 500 and is logged at WARN with its stack
  trace. All errors use `ApiError`, and messages never include SQL or stack traces.
- Paginate every list endpoint, with a max page size. Document every endpoint with springdoc.
- Comments explain *why*, not *what*. Match the existing javadoc style.

## 8. Testing (#9)

- **`ModularityTests`** (in `app`) runs `ApplicationModules.verify()` and writes module diagrams to
  `app/target/spring-modulith-docs/`. It must stay green; don't weaken a module's `allowedDependencies` to
  silence it without a design reason.
- Module tests live in that module (`<module>/src/test`). Use `@ApplicationModuleTest` (Spring Modulith) to
  boot one module in isolation, with other modules' APIs mocked.
- Unit tests for invariants: Order, Invoice and Item state and stock rules, and WorkingHours slot math.
- Integration tests use **Testcontainers PostgreSQL** (never H2), with a shared `AbstractIntegrationTest` (test-jar
  in `common`). Required tests: Flyway applies cleanly, checkout concurrency, refresh rotation and reuse detection,
  and magic-link access control.
- A security matrix test for each route: public, owner (401 with no token, 403 with the wrong role), and
  magic-link.
- Contract tests for `CatalogApi` and `OwnerApi` behaviour, so the future HTTP implementations can be checked
  against the same suite.
- GitHub Actions runs `./mvnw -B verify` on every push and PR. It must pass before merging.
- Spring context tests extend `com.sugarcrumbs.common.test.AbstractIntegrationTest` (from the `baker-common`
  test-jar; add it as a `test-jar` test dependency). It starts **one** Postgres container per test JVM, shared by
  every context, so the database is shared across test classes: tests create their own data and never assume
  empty tables. Redeclare `@SpringBootTest` on the subclass to change its attributes. They need Docker running,
  not a local database. `SchemaMigrationTests` checks that migrations apply, that Hibernate validation passes,
  and that no FK crosses module schemas.
- A test guarding against a hang uses `@Timeout(threadMode = SEPARATE_THREAD)`: a same-thread timeout only
  interrupts, so a loop that never checks for interrupts hangs the build instead of failing.

## 9. Commands

Windows dev machine. Use `mvnw.cmd` in PowerShell or `./mvnw` in Git Bash. No JDK is on PATH; IntelliJ's
bundled runtime works: `export JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2026.3/jbr"` (JDK 25,
compiling with `--release 21`).

```bash
cp .env.example .env                                  # once; fill in local values (git-ignored)
docker compose up -d                                  # Postgres (root docker-compose.yml for now)
./mvnw clean install                                  # build + test all modules
./mvnw -pl app spring-boot:run -Dspring-boot.run.profiles=dev   # run the app (after install)
./mvnw -pl app test -Dtest=ModularityTests            # boundary check only, no DB needed
gh issue list                                         # roadmap work items

# Production stack (Caddy + app + Postgres), also runnable locally with APP_DOMAIN=localhost:
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml down        # add -v to also drop its volumes
```

Ports: app 8080, Actuator 9080 (management port; internal only, never proxied by Caddy), Postgres 5432.
In prod only Caddy publishes ports (80/443). Planned: Prometheus 9090, Grafana 3000, Tempo 3200/4317. After the split:
gateway 8080, auth 8081, catalog 8082, order 8083.

> ⚠️ Stop any locally installed PostgreSQL service before starting the Docker one, or both will fight over
> port 5432 (see `challenges.txt`).

---

## 10. Execution order

Follow the PLAN.md phases, with steps added for the modular structure. One PR per step or issue.

| Step | Work | Issues |
|---|---|---|
| A ✅ | `docs/` merged in; modular monolith (`common`, `identity`, `catalog`, `ordering`, `app`); cross-module JPA relations replaced by ids + `CatalogApi`/`OwnerApi`; `ModularityTests` | — |
| P0 | Flyway baseline (one migration location per module), external config, jar + Docker + compose, error cleanup, serving uploads, Testcontainers + CI | #4–#9 |
| B | Observability: actuator + Micrometer + tracing in `app`, `infra/` with Prometheus, Grafana, Tempo | #26 (moved earlier) |
| P1 | Security in `identity` (JWT, bootstrap, profile + time zone); route split, CORS, rate limits | #10–#13 |
| P2 | Order + OrderLine, checkout rules and slot lock, owner order management with `REJECTED`, domain events | #14–#17 |
| P3 | Magic-link issuance and guest endpoints | #18, #19 |
| P4 | Invoice lifecycle and owner invoice API | #20, #21 |
| P5 | Production list and dashboard stats | #22, #23 |
| P6 | Backups and image pipeline hardening | #24, #25 |

Stage 2 (extract modules into services plus a gateway, per §3) happens after P2 at the earliest, and only when
there's a concrete need.

Later and out of scope for now: notification-service (email/WhatsApp from the domain events), online payments,
custom-cake quotes, guest accounts.
