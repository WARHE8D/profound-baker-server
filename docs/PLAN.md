# SugarCrumbs Server — Review & Roadmap

_Last updated: 2026-11-11_

## 1. Decisions

| Topic | Decision |
|---|---|
| Client | Separate web SPA (public storefront + owner dashboard) consuming this REST API |
| Owner auth | JWT: short-lived access token + rotating refresh token |
| Order model | Multi-item **Order** with line items (price snapshot) and one pickup slot |
| Payments | Offline only (cash / bank transfer); owner marks invoices paid |
| Guest access | Magic link: secret token per order to view / cancel, no guest accounts |
| Notifications | None for now; emit domain events so email can be added later without touching core logic |
| Deployment | Single VPS, Docker Compose (app + Postgres + reverse proxy), local-disk image storage |

## 2. Current state (review summary)

**Built:** catalogue (items, categories, images, stock, lead time), owner availability (weekly hours,
slot size and capacity, blocked dates, availability computation), single-item bookings with a status
state machine, rescheduling, and an auto-cancel job for stale pending bookings. Entities are well-encapsulated
(behaviour methods, no setters, optimistic locking via `@Version`), and errors share one `ApiError` shape.

**Problems found**

| # | Severity | Issue |
|---|---|---|
| 1 | Blocker | Spring Security disabled; every owner endpoint is public. `GET /api/bookings` leaks all guests' name/email/phone. |
| 2 | Blocker | No way to create the `Owner`; availability and booking fail until a row is inserted by hand. |
| 3 | Blocker | Overbooking race: slot capacity is checked and then inserted without a lock. |
| 4 | Bug | Bookings in the past are accepted for items with `leadTimeHours = 0` (no "not in the past" check). |
| 5 | Bug | No price snapshot on bookings, so repricing an item changes historical orders. |
| 6 | Bug | Concurrent stock changes raise `ObjectOptimisticLockingFailureException` and return 500 instead of 409. |
| 7 | Bug | `ResourceNotFoundException` is unhandled (500); `DemoController` is a leftover. |
| 8 | Bug | Uploaded images are written to `./uploads` but never served, so returned URLs 404. |
| 9 | Risk | Business timezone = `ZoneId.systemDefault()`, so slots shift if the server runs in another zone. |
| 10 | Gap | `Invoice` entity exists but has no service or API. |
| 11 | Gap | Single item per booking. |
| 12 | Infra | Flyway is a dependency but there are no migrations; schema comes from `ddl-auto=update`. |
| 13 | Infra | Placeholder Dockerfile (`top -b`), `war` packaging, DB credentials hard-coded in `application.properties`. |
| 14 | Infra | No tests beyond `contextLoads`; CI runs CodeQL only. |
| 15 | Hygiene | Unexpected exceptions are swallowed without logging; unknown query params / type mismatches return 500. |

## 3. Target architecture

```
          ┌───────────── VPS ─────────────────────────────────────────┐
 SPA ───► │ Caddy (TLS, /uploads static, reverse proxy /api → app)    │
          │   │                                                       │
          │   ▼                                                       │
          │ Spring Boot jar (Java 21)  ──►  Postgres 17 (volume)      │
          │   └─ uploads volume (shared with Caddy, read-only there)  │
          │ nightly pg_dump → off-box backup                          │
          └───────────────────────────────────────────────────────────┘
```

### API surface split

| Prefix | Who | Auth |
|---|---|---|
| `/api/auth/**` | Owner login / refresh / logout | public (rate-limited) |
| `/api/public/catalogue/**`, `/api/public/availability` | Shoppers | public, read-only |
| `POST /api/public/orders` | Guest checkout | public (rate-limited) |
| `/api/public/orders/by-token/{token}/**` | Guest view / cancel own order | magic-link token |
| everything else under `/api/**` | Owner dashboard | `Bearer` access token, role `OWNER` |

### Domain model changes

```
Order (was Booking)            OrderLine                    Invoice
- reference ("SC-7F3K2")       - order_id                   - order_id (1:1)
- pickupAt (slot start)        - item_id (nullable FK)      - number (INV-2026-0001)
- status (state machine)       - itemName  (snapshot)       - subtotal / discount / total
- guestContact (embedded)      - unitPrice (snapshot)       - status, method, paidAt
- notes, cancellationReason    - quantity
- accessTokenHash (SHA-256)    - lineTotal
- accessTokenExpiresAt

Owner (+ timeZone)             RefreshToken
                               - owner_id, tokenHash, expiresAt, revokedAt, replacedBy
```

### Key rules
- **Capacity:** one order consumes one unit of its slot's `maxConcurrentBookings`, however many lines it has.
- **Lead time:** order lead time = max lead time across its lines; `pickupAt` must also be in the future.
- **Concurrency:** create and reschedule take a `PESSIMISTIC_WRITE` lock on that day's `WorkingHours` row before
  counting capacity, which serializes bookings per day (plenty for one bakery). Stock still relies on `@Version`;
  the conflict maps to 409.
- **Stock:** decremented per line on create, released on cancel (existing behaviour, extended to lines).
- **Invoice:** created when the owner confirms an order (subtotal from the line snapshots); voided if a pending
  invoice's order is cancelled; owner applies discount and marks paid with `CASH` or `BANK_TRANSFER`.
- **Magic link:** a 32-byte random token is returned **once** in the `POST /api/public/orders` response. Only its
  SHA-256 hash is stored. Because there is no email yet, the SPA must show the link prominently and offer
  copy/bookmark. Tokens expire 30 days after fulfilment or cancellation. Guests may cancel only while
  `PENDING`/`CONFIRMED` and more than `app.order.guest-cancel-cutoff-hours` (default 24h) before pickup.
- **Owner bootstrap:** on first start, if no owner exists and `APP_OWNER_EMAIL` + `APP_OWNER_INITIAL_PASSWORD`
  are set, create the owner (BCrypt) and log that the password must be changed. With no owner and no env, fail fast.
- **Domain events:** `OrderPlaced`, `OrderConfirmed`, `OrderCancelled`, ... published through
  `ApplicationEventPublisher` with no listeners yet, ready for future email/WhatsApp.

## 4. Roadmap

Each item maps to one GitHub issue. Phases are sequential; items inside a phase can mostly be parallelised.

### Phase 0: Foundations & hygiene
- **0.1 Flyway baseline.** `V1__baseline.sql` generated from the current entities; switch to `ddl-auto=validate`.
- **0.2 Externalised config.** Profiles `dev`/`prod`, all secrets via env vars, `.env.example`; remove hard-coded credentials.
- **0.3 Packaging & Docker.** Switch to `jar` (drop `ServletInitializer`/provided Tomcat), multi-stage Dockerfile,
  compose with app + Postgres + Caddy + volumes, Actuator health check.
- **0.4 Error-handling cleanup.** Remove `DemoController`/`ResourceNotFoundException`; map optimistic-lock failure → 409,
  type mismatch / missing param / unreadable body → 400; log unexpected exceptions.
- **0.5 Serve uploads.** Caddy serves `/uploads` in prod; a Spring resource handler covers dev.
- **0.6 Test infra & CI.** Testcontainers Postgres base test class; GitHub Actions `mvn verify` on PRs.

### Phase 1: Owner auth & onboarding
- **1.1 Spring Security + JWT.** Login, refresh (rotating, hashed, reuse detection), logout, `/api/auth/me`; BCrypt.
- **1.2 Owner bootstrap** from env (see rules) and `OwnerService` resolving from the principal.
- **1.3 Owner profile API.** Get/update profile, change password, `timeZone` field replacing `BusinessTimeZone`.
- **1.4 Route split, CORS & rate limiting.** Public vs owner prefixes, CORS for the SPA origin, Bucket4j on login and checkout.

### Phase 2: Multi-item orders
- **2.1 Order + OrderLine model.** Rename Booking → Order, add lines with price/name snapshots and a human-readable reference;
  migrate existing bookings into one-line orders.
- **2.2 Checkout rules.** Not in the past, max lead time across lines, per-line stock, max lines/quantity, slot locking (fixes the race).
- **2.3 Owner order management.** Calendar/list/filter, state transitions, reschedule, edit lines while `PENDING`.
- **2.4 Domain events** published on every transition.

### Phase 3: Guest magic link
- **3.1 Token issuance** on checkout (hash stored, returned once).
- **3.2 Guest endpoints.** View order and cancel within the cutoff; expiry job.

### Phase 4: Invoicing (offline payments)
- **4.1 Invoice lifecycle.** Auto-create on confirm, sequential numbering, void on cancel.
- **4.2 Owner invoice API.** List/filter, discount, mark paid (cash/transfer + reference), refund.

### Phase 5: Owner dashboard support
- **5.1 Production list.** Per day, aggregated item quantities across confirmed orders.
- **5.2 Summary stats.** Today's pickups, pending count, revenue by month (paid invoices).

### Phase 6: Hardening & ops
- **6.1 Backups.** Nightly `pg_dump` with rotation and an off-box copy; documented restore.
- **6.2 Image pipeline.** Enforce size, strip EXIF, generate a thumbnail; delete files from disk when removed.
- **6.3 Observability.** Structured logs, request IDs, Actuator metrics.

### Later (out of scope now)
Email/WhatsApp notifications (event listeners), online payments/deposits, custom-cake quote requests, guest accounts.

## 5. Testing strategy
- **Unit:** entity state machines and invariants (Order, Invoice, Item stock, WorkingHours slot maths).
- **Integration (Testcontainers):** availability + checkout including a concurrent-booking test proving no overbooking,
  auth flows (refresh rotation and reuse detection), magic-link access control, Flyway migrations applying cleanly.
- **Web slice:** security rules per route (public vs owner vs token).
