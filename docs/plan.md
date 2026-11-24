# Con-Fuzion — Product & technical design

> Source of truth for *what* we build. Workflow rules live in `CLAUDE.md`; changes to this file go through a `docs:` PR approved by the owner.

## Context
Con-Fuzion is a free mobile + desktop web app for convention-goers to host meetups and open them to **audiences the host picks**, with **limited first-come-first-served spots** and an auto-promoting waitlist. Discord is the login and the source of social data.

Starting point (Nov 2026): Spring Boot 3.3 / Java 21 scaffold, being upgraded to Boot 4.x on Java 25 in M0 (D-008), with a global exception handler, logging and rate-limit aspects, a `ResponseRecord` envelope, and a half-configured Discord OAuth2 client. There's no domain code and no frontend.

**Guiding principles:**
- **Small footprint, built to scale out.** The app is stateless and deployed as one container image that scales horizontally.
- **Managed AWS services that scale on demand**, rather than more moving parts.
- **No feature-level extras without a measured need.**

**Scale targets (assumed, confirm or correct):**
- 10k concurrent active users and 100k registered.
- A signup "drop" burst of 5k joins within 10 seconds on a single event.
- Headroom to roughly 10× by adding tasks and database capacity, with no rewrite.

**Hard Discord constraint:** apps can't read friends lists (`relationships.read` needs Discord approval), and a user token can't list a server's members. So the audiences are:
- **Shared servers**: the `guilds` scope, matched among Con-Fuzion users, opt-in per server.
- **In-app friends**: made via QR code or invite link.
- **Circles**: a host's private lists.
- **A bot, post-MVP**: role audiences and DMs.

### Key decisions
| Topic | Decision |
|---|---|
| Audiences | Shared servers (opt-in) + in-app friends + circles + individuals; bot post-MVP |
| Frontend | React + Vite + TypeScript PWA, **served by the Spring Boot jar** (same origin, one deployable) |
| Conventions | No convention entity — events are standalone (free-text location + optional tag) |
| Full events | Waitlist with auto-promotion + confirm-by deadline |
| Notifications | In-app inbox + Web Push (MVP); Discord DMs via bot (post-MVP) |
| Hosting | AWS, autoscaling: CloudFront (+ WAF) → ALB → ECS Fargate (min 2 tasks, 2 AZs) → Aurora PostgreSQL Serverless v2 + ElastiCache Serverless (Valkey) |
| Data/infra | Postgres = system of record (data, jobs, notification queue). Valkey = **sessions + distributed rate limits only**. No message queues, no SSE |
| Art | No AI-generated art. A hired artist makes original graphics; licensed icons and fonts are fine |
| Donations | Passive, config-driven external links. No nagging, no perks |
| Security | Deny-by-default authz, strict CSP, private-by-default social data, generic error bodies, managed secrets, CI security scanning (B11) |
| Admin | Roles USER/MODERATOR/ADMIN. The first admin is bootstrapped from config; later roles are granted in-app, MFA is required, and every action is audit-logged. MVP admin features: moderation + user management + a report queue (B12) |
| Process | A coordinator session; work/research sessions per issue; CI + Claude review + you merge; Conventional Commits |

---

## B1. MVP fence
**In MVP:**
- Discord login and server sync with opt-in discoverability.
- Friends via QR or invite link, and circles.
- Blocking.
- Reporting (events and users) → moderation queue.
- Admin area: moderation, suspensions and bans, role grants, audit log.
- Events: create, edit, cancel, with an option to show the location to confirmed attendees only.
- Audience picker: servers, friends, circles, individuals.
- Feed and "My schedule".
- FCFS RSVP with waitlist, offers, and expiry.
- Signup-opens time.
- In-app notifications and Web Push, with a reminder 1 hour before.
- Account deletion.
- Passive donation links.
- Deploy to AWS.

**Not in MVP** (backlog, each needs its own decision):
- Discord bot (role audiences, DMs, slash commands).
- Live SSE/WebSocket updates (adaptive polling instead).
- Read replicas, RDS Proxy, multi-region.
- Co-hosts and plus-ones.
- Event chat or comments.
- `.ics` export.
- Admin support lookups and ops dashboard (use CloudWatch).
- Analytics.
- Convention directory.
- Native apps.
- i18n.

## B2. Architecture
```
Browser (installable PWA)
   │ HTTPS, one origin
   ▼
CloudFront (+ AWS WAF rate-based rules)      caches /assets/* (hashed, immutable); /api/* passes through uncached
   ▼
ALB ──► ECS Fargate service: Spring Boot container × N    (min 2 across 2 AZs, CPU/req-count autoscaling)
          • serves SPA shell + REST /api • stateless • virtual threads
          ├─► Aurora PostgreSQL Serverless v2   (system of record, Flyway; ACUs scale with con-weekend bursts)
          ├─► ElastiCache Serverless (Valkey)   (Spring Session + bucket4j distributed rate limits)
          └─► Discord API                       (OAuth, /users/@me/guilds)
```
**Why each piece is there (and nothing more):**
- **Stateless app tasks.** Any task can serve any request, so scaling is just a change to the ECS desired count, made by the autoscaler.
- **Valkey for sessions.** At thousands of requests per second, Spring Session JDBC would write `last_access_time` to the hot database on every request. Valkey takes that load. The code uses the Spring Session abstraction, so this is a dependency choice, not custom code.
- **Distributed per-user rate limits** (bucket4j + Lettuce). These replace the current **single global bucket** in `RateLimiterAspect`, which throttles all users together and wouldn't be shared across tasks. WAF rate rules at the edge absorb abusive floods before they reach tasks.
- **CloudFront** serves static assets, so app tasks spend their CPU on the API. It also brings Shield Standard DDoS protection. There's still a single deployable, because the jar contains the SPA.
- **Aurora Serverless v2.** Postgres-compatible (same code and Flyway) and scales capacity automatically for the bursty weekend pattern of conventions. Read replicas are a later, trigger-based addition.
- **Background jobs** (offer expiry, reminders, push sending) are `@Scheduled` on every task, claiming work with `FOR UPDATE SKIP LOCKED` in batches. More tasks means more throughput, with no leader election, ShedLock, or queue.
- **Connection budget.** Hikari pool of about 10 per task, so 20 tasks is about 200 connections. RDS Proxy gets added only if the task count needs it (a documented trigger).
- **Same origin.** No CORS, and iOS-safe cookies (`HttpOnly; Secure; SameSite=Lax`), with CSRF via `CookieCsrfTokenRepository`.
- **Observability.** Actuator + Micrometer → CloudWatch metrics and alarms: p95 latency, 5xx rate, database ACU and connections, Valkey memory, and RSVP claim latency.

**Hot spots and how they're handled:**
| Hot spot | Handling |
|---|---|
| Thousands joining one event at `signup_opens_at` | Single-statement atomic claim (B4) with a very short transaction, holding the row lock for about a millisecond. Per-user rate limit (1 join attempt per second). The client adds random jitter of 0–1.5s to the button unlock so the herd spreads out. **Fallback, built only if the load test fails:** a Valkey `DECR` gate in front of the Postgres claim |
| Many users polling spot counts | Tiny `GET /api/events/{id}/spots` endpoint (a primary-key lookup, `ETag`/304). Per-task 1-second Caffeine cache. **Adaptive polling:** 3s in the 2 minutes around a signup drop, 30s otherwise, paused when the tab is hidden |
| Feed query | Built from the viewer's "audience keys" (their discoverable guild IDs, circle IDs, friend host IDs, own ID) joined to `event_audience`, then to `event` in the upcoming time window. Indexes on `event_audience(type, ref_id)`, `event(starts_at)`, `user_guild(user_id)` |
| Login storm when a con opens | Discord calls are per user token. Honor `429 Retry-After`. Guild sync happens on login only |
| Mass push on cancellation (500+ attendees) | Batch claims from the notification table across all tasks, with parallel sends on virtual threads |

## B3. Data model (Flyway)
| Table | Key columns |
|---|---|
| `app_user` | id uuid, discord_id unique, username, global_name, avatar_hash, mfa_enabled, time_zone, push_enabled, suspended_until, created_at, last_login_at (**no email**, see B11) |
| `user_role` | (user_id, role) with role in MODERATOR/ADMIN; USER is implicit |
| `banned_account` | discord_id PK, reason, banned_by, created_at. Kept even if the account is deleted |
| `report` | id, reporter_id, target_type (EVENT/USER), target_id, reason (HARASSMENT/UNSAFE/SPAM/INAPPROPRIATE/OTHER), note ≤1000 chars, status (OPEN/ACTIONED/DISMISSED), handled_by, handled_at, resolution_note |
| `audit_log` | id, actor_id, action, target_type, target_id, details jsonb, created_at. Insert-only |
| `user_guild` | (user_id, guild_id) PK, guild_name, icon_hash, **discoverable** default false, synced_at |
| `friendship` | (user_a, user_b) with a<b, created_at |
| `friend_invite` | token (128-bit random), inviter_id, expires_at |
| `circle`, `circle_member` | owner_id, name / (circle_id, user_id) |
| `block` | (blocker_id, blocked_id) |
| `event` | id, host_id, title, description, location_text, tag, starts_at, ends_at, time_zone, capacity (null = unlimited), **confirmed_count**, signup_opens_at, status (PUBLISHED/CANCELLED), confirm_window_minutes default 30, location_visibility (VIEWERS/CONFIRMED), hidden_by_moderation, moderation_reason |
| `event_audience` | event_id, type (GUILD/FRIENDS/CIRCLE/USER), ref_id |
| `rsvp` | id bigserial (= queue order), event_id, user_id, status (CONFIRMED/WAITLISTED/OFFERED/LEFT/EXPIRED), offer_expires_at; partial unique index on (event_id, user_id) for active statuses |
| `notification` | id, user_id, type, payload jsonb, read_at, push_status, created_at — this table also acts as the push queue |
| `push_subscription` | user_id, endpoint, p256dh, auth |

Sessions live in Valkey, not Postgres. User IDs are UUIDs; `event` and `rsvp` use bigint IDs plus a public UUID in URLs, so IDs can't be enumerated.

Discord tokens: we only need them at login, to sync guilds. **Don't persist refresh tokens.** A manual "refresh servers" button re-runs OAuth. That cuts both the stored secrets and the code.

**Visibility** (one `EXISTS` query, reused by feed, detail, and RSVP): event is PUBLISHED, no block in either direction, and the viewer is one of:
- the host;
- a `USER` audience row;
- a friend of the host, if the event has a `FRIENDS` row;
- a member of a `CIRCLE` row;
- in a `GUILD` row's server, with `discoverable=true`.

Hosts may only target servers they're in and circles they own.

## B4. FCFS RSVP (the core)
- **Join:** visibility + signup-window check, then
  `UPDATE event SET confirmed_count = confirmed_count + 1 WHERE id=:id AND (capacity IS NULL OR confirmed_count < capacity) RETURNING ...`
  One row updated → CONFIRMED. Zero rows → WAITLISTED. The row lock makes this fair. The unique index absorbs double-taps.
- **Leave or host removal**, in the same transaction:
  - Pick the next waitlisted person with `... ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED`.
  - Make them OFFERED with a deadline, or CONFIRMED directly if the event starts within the window. The spot stays reserved.
  - Insert a `notification`.
- **Expiry job** (every 30s): expired OFFERED → EXPIRED, then promote the next person.
- **Accept:** OFFERED → CONFIRMED.
- The claim transaction contains **only** the `UPDATE` and the `INSERT`. The visibility and window checks run before it, read-only, so the row lock is held as briefly as possible.
- The event page uses adaptive polling against `/spots` (see the hot-spot table).

## B5. Discord auth (M1)
- Fix `application.properties`:
  - scopes `identify,guilds` (no `email`; see B11 data minimization)
  - `https://discord.com/oauth2/authorize`, `https://discord.com/api/oauth2/token`, `https://discord.com/api/users/@me` (`discordapp.com` is legacy)
  - redirect `{baseUrl}/api/login/oauth2/code/discord`
- A custom `OAuth2UserService` upserts `app_user` and syncs guilds once per login (honor `429 Retry-After`).
- Remove the unused `spring-security-taglibs` and let the Boot BOM manage `oauth2-client`.
- Onboarding: the user picks which servers they're discoverable in. Default is off, with a "select all" option.

## B6. API (springdoc at `/api/swagger-ui`)
```
GET/PATCH /api/me · GET /api/me/guilds · PUT /api/me/guilds/{id} · DELETE /api/me (account deletion)
GET  /api/events?scope=feed|hosting|attending · POST /api/events · GET/PATCH/DELETE /api/events/{id}
GET  /api/events/{id}/attendees (host + confirmed only) · GET /api/events/{id}/spots (polling, ETag)
POST/DELETE /api/events/{id}/rsvp · POST /api/events/{id}/rsvp/accept · DELETE /api/events/{id}/rsvps/{userId}
POST /api/friends/invites · POST /api/friends/invites/{token}/accept · GET /api/friends · DELETE /api/friends/{id}
CRUD /api/circles (+ members) · POST/DELETE /api/blocks/{userId}
GET  /api/notifications · POST /api/notifications/read · POST/DELETE /api/push/subscriptions
GET  /api/meta/support (donation links) · POST /api/reports

# MODERATOR+
GET  /api/admin/reports?status · GET /api/admin/reports/{id} · POST /api/admin/reports/{id}/resolve
POST /api/admin/events/{id}/hide · POST /api/admin/events/{id}/unhide · POST /api/admin/events/{id}/cancel
GET  /api/admin/users?q · POST /api/admin/users/{id}/suspend · POST /api/admin/users/{id}/unsuspend
# ADMIN only
POST /api/admin/bans · DELETE /api/admin/bans/{discordId}
GET/PUT/DELETE /api/admin/users/{id}/roles · GET /api/admin/audit
```
Reuse the existing pieces:
- `ResponseRecord` / `ResponseType`: register new body types.
- `GlobalControllerExceptionHandler`: add 403/404/409 handlers.
- `LoggerAspect`: make sure it never logs tokens or emails.
- `HttpUtil.getRemoteAddr`: use `request.getRemoteAddr()` with `server.forward-headers-strategy=native` (#21). Tomcat's RemoteIpValve reads `X-Forwarded-For` right to left and stops at the first untrusted address. `framework` takes the left-most value, which the client controls. Never read `X-Forwarded-For` directly.

New backend dependencies, kept to the minimum:
- `validation`
- `spring-session-data-redis` + `spring-boot-starter-data-redis` (Lettuce, which works with Valkey)
- `bucket4j_jdk17-lettuce` (the bucket4j core is already present)
- `flyway-core` + `flyway-database-postgresql`
- `caffeine`
- `micrometer-registry-cloudwatch2`
- `nl.martijndwars:web-push`
- Testcontainers (test only)

## B7. Frontend
- **Stack:** React Router, TanStack Query, Tailwind, `vite-plugin-pwa`, `openapi-typescript`, `date-fns-tz`.
- **Offline:** cache "My schedule" for read-only use. Mutations need a connection.
- **Screens:**
  - Landing / sign in.
  - Onboarding: servers → notifications → install hint (iOS push requires installing to the home screen).
  - Feed.
  - Event detail: spots, Join, Waitlisted #N, Accept countdown, Leave.
  - Create/Edit with the audience picker.
  - My schedule.
  - Friends: my QR code, scan, list.
  - Circles.
  - Notifications.
  - Settings: servers, push, time zone, blocks, delete account, About/Support.
  - Report sheet, reachable from any event or profile.
  - **`/admin`**: a lazy-loaded chunk, shown only to MODERATOR and ADMIN, with the server enforcing access. Includes the reports queue, report detail with actions, user search with suspend/ban, and (ADMIN only) roles and the audit log.

## B8. Notifications
Triggers:
- offered a spot
- confirmed
- event changed or cancelled
- reminder 1 hour before
- friend invite accepted

Each change inserts a `notification` row in the same transaction. A `@Scheduled` sender pushes rows with a pending `push_status` via VAPID Web Push (keys in AWS Secrets Manager) and deletes dead subscriptions (404/410). A single per-user push toggle; no preference matrix.

## B9. Donations
- External links only, from config: `con-fuzion.support.links[n].label/url`. An empty list hides all support UI.
- They appear only in Settings → About → "Support Con-Fuzion" (a short note about hosting costs plus the links) and as a muted footer link.
- No pop-ups, banners, notifications, goal bars, perks, badges, or donation tracking.

## B10. Milestones
- **M0** Foundation.
- **M1** Discord auth + onboarding.
- **M2** Social graph + events + visibility + feed.
- **M3** FCFS RSVP + waitlist + schedule.
- **M4** Notifications + Web Push + PWA install.
- **M5** Safety & admin: reporting, the admin area, roles with the MFA gate, suspensions and bans, audit log, maintenance mode.
- **M6** AWS deploy with Terraform:
  - VPC across 2 AZs.
  - Aurora Serverless v2 and ElastiCache Serverless.
  - ECS service with autoscaling, ALB, and CloudFront + WAF. The container uses a JRE 25 base image.
  - **Client IP behind CloudFront:** RemoteIpValve trusts only private-range proxies by default, so behind CloudFront → ALB every anonymous user would appear as a CloudFront edge address and share one rate-limit bucket. Fix it in M6: either add CloudFront's published origin-facing IP ranges to `server.tomcat.remoteip.internal-proxies` (kept current from AWS's managed prefix list), or have CloudFront send `CloudFront-Viewer-Address` and resolve the IP from that header, accepting it only from CloudFront. Test it with an end-to-end check that two clients get separate buckets.
  - ACM, Secrets Manager, CloudWatch alarms.
  - CD on merge, with staging and prod environments.
- **M7** Polish + **launch gates**: accessibility, account deletion, donation links, artist asset swap, privacy policy and terms pages, the **load test** and the **security pass** (ZAP + manual review) against staging. Both must pass before launch.
- **→ MVP launch.** Everything else comes from the backlog, by decision.

## B11. Security plan
The full threat model and per-PR checklist live in `docs/security.md`. This is the summary.

**Assets to protect:**
- User sessions and accounts.
- Who is in which server.
- Where people will be, and when (physical safety at in-person meetups).
- Admin powers.
- The Discord client secret and the VAPID key.

| Area | Controls |
|---|---|
| **Authentication** | Discord OAuth2 auth-code flow with Spring's `state` and **PKCE** enabled. Session ID rotated on login (prevents fixation). Session in Valkey: idle timeout 14 days, absolute 30 days. Cookie is `HttpOnly; Secure; SameSite=Lax`. "Log out everywhere" deletes all of a user's sessions by principal index. **Banned** Discord IDs and suspended users are rejected in the OAuth success handler, and their existing sessions are purged |
| **Authorization** | Deny by default (`anyRequest().authenticated()`; `/api/admin/**` requires `hasAnyRole`). Every service method checks ownership or visibility explicitly. Public UUIDs in URLs. Hidden or invisible resources return **404, not 403**, so their existence isn't revealed. An **authorization matrix test** covers every endpoint × (anonymous, stranger, audience member, attendee, host, moderator, admin) |
| **CSRF / XSS / headers** | CSRF tokens on all mutations. React escaping only: event text is **plain text**, with no HTML/Markdown rendering and no `dangerouslySetInnerHTML`. Strict **CSP**: `default-src 'self'; img-src 'self' https://cdn.discordapp.com; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'`. Also HSTS, `nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, and `Permissions-Policy: camera=(self)` for QR scanning |
| **Input** | Bean Validation on every DTO (lengths, enums, time ranges, capacity 1–10,000). Server-side checks on all limits. Parameterized JPA queries only |
| **Privacy by default** | Guild membership is used only when the user marks a server discoverable. Waitlist position is visible only to that user. Attendee lists go to the host and confirmed attendees only. **Location can be hidden until confirmed.** Reporter identity is never shown to the reported user. **Data minimization:** drop the `email` scope (nothing in the MVP uses it), don't store Discord tokens, and purge ended events plus their RSVPs after 90 days |
| **Error handling & logging** | **Fix the existing handler:** `GlobalControllerExceptionHandler` currently returns `getRootCause(e).getMessage()` to clients, which leaks SQL constraint names and Hibernate internals. Replace it with generic messages, a correlation ID, and a catch-all 500 handler, and log the details server-side. `LoggerAspect` logs metadata only in prod (method, status, latency, user ID) and never request or response bodies, cookies, or tokens. JSON logs go to CloudWatch |
| **Abuse** | Per-user and per-IP rate limits (Valkey). WAF managed core rules + rate-based rules. Caps: 20 events per host per day, 10 reports per user per day. Friend invites expire after 24h and are single-use by default. Blocks, reports, suspensions, bans |
| **Secrets** | Discord secret and VAPID key in Secrets Manager, injected into ECS tasks. Never in the repo: hooks block writes and gitleaks runs in CI. Rotation steps in the incident runbook |
| **Infrastructure** | Database, cache, and tasks in **private subnets**, with least-privilege security groups and no public DB or cache. TLS from browser to CloudFront to ALB. Aurora encrypted at rest (KMS) with point-in-time recovery for 14 days. Valkey TLS in transit with auth. Non-root container with ECR image scanning. **GitHub Actions deploys via OIDC role** (no long-lived AWS keys). AWS root account has MFA and no access keys. CloudTrail on, GuardDuty on |
| **Supply chain** | Dependabot (Gradle, npm, Actions, Docker), CodeQL, gitleaks. The dependency gate hook keeps the dependency surface small |
| **Verification** | Authorization matrix tests, header/CSP tests, `/security-review` on labeled PRs, an **OWASP ZAP baseline scan** against staging in CI, and a manual pre-launch security pass in M7 |
| **Incident response** | `docs/runbooks/incident.md` covers: enable maintenance mode (config flag returning 503 for `/api` except health), flush all sessions (Valkey), rotate the Discord secret and VAPID keys, ban accounts, restore with Aurora point-in-time recovery |
| **Legal (your action)** | Privacy policy + terms of service pages (Discord requires a privacy policy URL for OAuth apps). Claude can draft them; you or a lawyer approve them |

## B12. Admin & moderation
- **Roles:**
  - **USER**: implicit.
  - **MODERATOR**: report queue, hide/unhide or cancel events, suspend/unsuspend users.
  - **ADMIN**: everything a moderator can do, plus bans, role grants, and the audit log.
- **Bootstrap:** `con-fuzion.admin.bootstrap-discord-ids` is set in config (Secrets Manager in prod). On login, those accounts get ADMIN if they don't already have it, and the grant is audit-logged. After that, admins grant and revoke roles in-app. Removing a Discord ID from config doesn't revoke an existing role; revocation happens in-app, and the last remaining ADMIN can't be removed.
- **MFA gate:** MODERATOR and ADMIN authorities apply only if Discord reports `mfa_enabled=true` at login. Otherwise the user acts as a plain USER and sees a "turn on Discord 2FA" message.
- **Role changes take effect immediately.** Changing someone's roles or suspending them deletes their sessions, so the new authorities apply on their next request.
- **Audit log:** every admin and moderator action, plus security events (login rejected because of a ban, role change, session purge), inserted into `audit_log`. The app has no update or delete path for it. Only ADMIN can view it.
- **Report flow:**
  1. The user taps Report on an event or profile, picks a reason, adds an optional note, and can also block.
  2. The report gets status OPEN and moderators get an in-app notification + push.
  3. A moderator acts: hide/cancel the event (the host is notified with the reason), suspend (until a date), or escalate to an ADMIN for a ban. They then resolve the report with a note.
  4. The reporter gets a short "we reviewed your report" notification, with no details.
- **Hidden events** are invisible to everyone except the host and moderators. The visibility query adds `NOT hidden_by_moderation`.
- **Bans** are stored by Discord ID, so they survive account deletion and re-signup.

---

## Verification
- **Must-pass tests (Testcontainers Postgres):**
  - **Concurrency:** a capacity-10 event, 200 parallel joins → exactly 10 CONFIRMED and 190 WAITLISTED in insert order, with `confirmed_count=10`. Then 3 leave → 3 OFFERED in order; one expires → the next is offered.
  - **Visibility truth table:** each audience type × the discoverable flag × blocks.
  - **Auth:** unauthenticated `/api/**` → 401 JSON, not a redirect; mutations require CSRF.
  - **Authorization matrix:** every endpoint × the 7 actor types returns the expected 200/404/403/401.
  - **Admin:** a bootstrap ID gets ADMIN on login. A moderator without MFA has no admin access. A banned Discord ID is rejected at login and its sessions are purged. A role change invalidates sessions. Every admin action writes an `audit_log` row.
  - **Errors:** a forced constraint violation returns a generic 409 body with no SQL or Hibernate text.
  - **Headers:** CSP, HSTS, nosniff, and frame-ancestors are present on the SPA and `/api` responses.
- **Security scans:** CodeQL, secret scanning, gitleaks and Dependabot are green, and the ZAP baseline against staging has no high findings.
- **Manual end-to-end:**
  1. `docker compose -f infra/docker-compose.yml up -d` (Postgres + Valkey) and `./gradlew bootRun`, plus `npm run dev`.
  2. Sign in with a Discord test app at `localhost:5173`.
  3. Mark a server discoverable and create an event for that server.
  4. A second Discord account in another browser profile sees the event.
  5. Fill the event → waitlist → leave → promotion + push.
- **Playwright smoke test** (mobile + desktop viewport): create → join → full → waitlist.
- **Multi-instance test:** run two backend instances locally against the same Postgres and Valkey. A session started on one is valid on the other, and the rate limit is shared.
- **Scale gate (k6 on staging, sized like prod; required before launch):**
  - 10k virtual users browsing the feed and polling, with p95 under 300 ms.
  - A drop of 5k joins in 10s on one capacity-50 event: no overbooking, p95 for joins under 1s, no 5xx.
  - Autoscaling adds tasks under load and scales back afterwards.
  - Any miss → a decision entry and the documented fallback (Valkey gate, read replica, RDS Proxy), not ad-hoc fixes.
- **Workflow checks:**
  - Writing `x.env` or `frontend/public/logo.png` is blocked.
  - Editing `package.json` asks first.
  - Committing on `main` and running `gh pr merge` are blocked.
  - A Java file without a header gets the fix-it message.
  - A failing unit test blocks Stop once.
  - A PR titled `Added stuff` fails the title check.
  - The reviewer flags a deliberately out-of-scope change in a sample diff.
