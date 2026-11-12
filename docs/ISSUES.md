# Roadmap issues (drafts)

One section per GitHub issue. See `docs/PLAN.md` for the design behind each one.

---

## [P0.1] Flyway baseline migration; switch to ddl-auto=validate
- [ ] `db/migration/V1__baseline.sql` matching current entities (named FKs, unique constraints, indexes)
- [ ] `spring.jpa.hibernate.ddl-auto=validate`
- [ ] Testcontainers test: migrations apply and Hibernate validation passes

## [P0.2] Externalise configuration and secrets
- [ ] `application.properties` reads DB URL/user/password, storage path, CORS origin and JWT secret from env vars
- [ ] `application-dev.properties` (show-sql, local defaults) and `application-prod.properties`
- [ ] `.env.example`; remove hard-coded `admin/admin`

## [P0.3] Jar packaging, real Dockerfile, production compose
- [ ] `packaging` → `jar`; remove `ServletInitializer` and provided Tomcat
- [ ] Multi-stage Dockerfile (Maven build → `eclipse-temurin:21-jre`), non-root user
- [ ] `docker-compose.prod.yml`: app + postgres + caddy, volumes for DB and uploads
- [ ] Add Actuator; expose `/actuator/health` only

## [P0.4] Error-handling cleanup
- [ ] Delete `DemoController` and `ResourceNotFoundException`
- [ ] `ObjectOptimisticLockingFailureException` → 409
- [ ] `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`, `HttpMessageNotReadableException`, `MissingServletRequestPartException` → 400
- [ ] `NoResourceFoundException` → 404
- [ ] Log unexpected exceptions with stack trace in the catch-all handler

## [P0.5] Serve uploaded images
- [ ] Dev: `WebMvcConfigurer` resource handler `/uploads/**` → `app.storage.local-path`
- [ ] Prod: Caddy serves the uploads volume read-only at `/uploads`
- [ ] Delete the file from disk when an image is removed from an item

## [P0.6] Test infrastructure and CI
- [ ] Add Testcontainers (postgresql, junit-jupiter); shared `AbstractIntegrationTest`
- [ ] Unit tests for the `Booking`, `Item` and `WorkingHours` invariants
- [ ] GitHub Actions workflow: `./mvnw -B verify` on push and PR

## [P1.1] Spring Security with JWT access and refresh tokens
- [ ] Re-enable `spring-boot-starter-security` (+ test)
- [ ] `POST /api/auth/login` → access token (15 min) + refresh token (14 days)
- [ ] `POST /api/auth/refresh` rotates the refresh token; tokens are stored hashed in a `refresh_tokens` table; reusing a revoked token revokes the whole chain
- [ ] `POST /api/auth/logout`, `GET /api/auth/me`
- [ ] Stateless filter chain, BCrypt, 401/403 bodies in `ApiError` shape

## [P1.2] Owner bootstrap from environment
- [ ] On startup with no owner: create one from `APP_OWNER_EMAIL` / `APP_OWNER_INITIAL_PASSWORD`, else fail fast
- [ ] `OwnerService.getTheOwner()` keeps working for public flows; owner endpoints resolve from the principal

## [P1.3] Owner profile API and business timezone
- [ ] `GET/PUT /api/owner/profile`, `POST /api/owner/password`
- [ ] Add `Owner.timeZone` (migration, default e.g. `Asia/Karachi` or as configured); replace `BusinessTimeZone.ZONE` usages

## [P1.4] Public/owner route split, CORS and rate limiting
- [ ] Move public reads to `/api/public/catalogue/**` and `/api/public/availability`; checkout to `POST /api/public/orders`
- [ ] Every other `/api/**` route requires `ROLE_OWNER`
- [ ] CORS for the configured SPA origin
- [ ] Bucket4j rate limits on login and checkout
- [ ] Web-slice tests for the route security matrix

## [P2.1] Multi-item Order model
- [ ] Rename `Booking` → `Order` (`orders` table) with `pickupAt` and a human-readable `reference` (e.g. `SC-7F3K2`)
- [ ] `OrderLine`: item FK (nullable), `itemName` and `unitPrice` snapshots, quantity, lineTotal
- [ ] Migration: each existing booking becomes an order with one line
- [ ] DTOs and mappers updated

## [P2.2] Checkout rules and overbooking fix
- [ ] Reject `pickupAt` in the past; lead time = max across lines
- [ ] Per-line orderable and stock checks; stock decremented per line and released on cancel
- [ ] Limits on number of lines and per-line quantity
- [ ] `PESSIMISTIC_WRITE` lock on that day's `WorkingHours` row in create and reschedule before counting capacity
- [ ] Concurrency integration test: N parallel checkouts for a capacity-1 slot → exactly 1 succeeds

## [P2.3] Owner order management API
- [ ] Calendar/list with date range, status and search filters
- [ ] Confirm / start preparation / fulfil / cancel / reschedule
- [ ] Edit lines and notes while `PENDING`

## [P2.4] Domain events
- [ ] Publish `OrderPlaced`, `OrderConfirmed`, `OrderCancelled`, `OrderRescheduled` and `OrderFulfilled` after commit (`@TransactionalEventListener`-ready)
- [ ] No listeners yet; this is the hook for future notifications

## [P3.1] Magic-link token issuance
- [ ] Generate a 32-byte random token on checkout, store its SHA-256 hash, return the raw token once in the response
- [ ] Expiry 30 days after the order reaches a terminal state

## [P3.2] Guest order endpoints
- [ ] `GET /api/public/orders/by-token/{token}`: order view without other guests' data
- [ ] `POST /api/public/orders/by-token/{token}/cancel`: only `PENDING`/`CONFIRMED` and before `app.order.guest-cancel-cutoff-hours`
- [ ] Constant-time comparison; 404 for unknown or expired tokens; rate-limited
- [ ] Scheduled job that purges expired token hashes

## [P4.1] Invoice lifecycle
- [ ] Create the invoice when an order is confirmed (subtotal from line snapshots)
- [ ] Sequential number `INV-YYYY-NNNN` (DB sequence)
- [ ] Void a pending invoice when its order is cancelled

## [P4.2] Owner invoice API (offline payments)
- [ ] List/filter invoices; get by order
- [ ] Apply discount; mark paid (`CASH` / `BANK_TRANSFER` + reference); refund
- [ ] Trim `PaymentMethod` to offline methods

## [P5.1] Daily production list
- [ ] `GET /api/dashboard/production?date=`: per-item total quantities across confirmed and in-progress orders

## [P5.2] Dashboard summary stats
- [ ] Today's pickups, pending count, revenue by month from paid invoices

## [P6.1] Database backups
- [ ] Nightly `pg_dump` with 14-day rotation and an off-box copy; restore runbook in the README

## [P6.2] Image pipeline hardening
- [ ] Verify magic bytes (don't trust Content-Type), strip EXIF, generate a thumbnail, cap dimensions

## [P6.3] Observability
- [ ] JSON logs in prod, request-ID filter (MDC), Actuator metrics
