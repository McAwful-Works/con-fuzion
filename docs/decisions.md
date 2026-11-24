# Decisions log

Short records of decisions that shape the system. **Any new infrastructure service, or any departure from
`docs/plan.md`, needs an entry here**, and that entry must name the measured trigger (a load-test result or
production metric). Newest entries go at the bottom.

Template:
```
## D-NNN Title (YYYY-MM-DD) — Accepted | Proposed | Superseded by D-XXX
Context · Decision · Consequences · Trigger to revisit
```

## D-001 Social graph without Discord friends lists (2026-11-24) — Accepted
- **Context:** `relationships.read` requires Discord approval, and user tokens can't list a server's members.
- **Decision:** Audiences are built from shared servers (`guilds` scope, opt-in per server), in-app friends
  (QR/invite), circles, and individual users.
- **Consequences:** Hosts can only reach people who use Con-Fuzion.
- **Revisit:** Add a bot for role audiences and DMs after the MVP, if hosts ask for it.

## D-002 FCFS via single-statement atomic counter (2026-11-24) — Accepted
- **Decision:** `UPDATE event SET confirmed_count = confirmed_count + 1 WHERE … AND confirmed_count < capacity
  RETURNING`, with the claim transaction kept minimal.
- **Revisit:** If the M7 load test misses its target (5k joins in 10s, p95 under 1s), add a Valkey `DECR` gate
  in front of the claim.

## D-003 Same-origin SPA served from the backend jar, server-side sessions (2026-11-24) — Accepted
- **Decision:** One deployable, no CORS. Session cookie `HttpOnly; Secure; SameSite=Lax`, CSRF via cookie token.
  Sessions live in Valkey (Spring Session). No JWTs in the browser.

## D-004 Baseline infrastructure (2026-11-24) — Accepted
- **Decision:** CloudFront + WAF → ALB → ECS Fargate (min 2 tasks) → Aurora PostgreSQL Serverless v2 +
  ElastiCache Serverless (Valkey, used only for sessions and rate limits).
- **Not included until triggered:** SSE/WebSockets (polling is enough), read replicas (database CPU above 60%
  sustained), RDS Proxy (connections above 70% of max), message queues (none needed).

## D-005 Development workflow (2026-11-24) — Accepted
- **Decision:**
  - One coordinator session.
  - One work session per issue, ending in one PR.
  - CI + Claude review + a human merges. Claude never merges.
  - Conventional Commits with squash merge.
  - Hooks guard secrets, `main`, dependencies, and art.

## D-006 No AI-generated art; passive donations (2026-11-24) — Accepted
- **Decision:**
  - Original art comes from a hired artist. Licensed icons and fonts are allowed in the meantime.
  - Donation links are config-driven and passive, with no recognition or perks.

## D-007 Code and secret scanning (2026-11-24) — Accepted
- **Context:** While the repo was private, CodeQL and GitHub secret scanning needed paid GitHub Code/Secret Protection.
  The owner then made the repo public, after a full-history scan found no secrets.
- **Decision:** The repo is public, so CodeQL default setup, secret scanning and push protection are on at no cost.
  The gitleaks CI job (pinned and SHA-256-verified) stays as defense in depth, along with Dependabot version updates.
- **Follow-up:** rulesets now protect `main`, `release/*` and `v*` tags (D-009). Dependabot security updates are
  still the owner's call.

## D-008 Spring Boot 4.x on Java 25 LTS, not Boot 3.5 on Java 21 (2026-11-24) — Accepted
- **Context:** The plan said "upgrade to Boot 3.5". By now 3.5's open-source support has ended, and Dependabot is
  already proposing Boot 4.2, Gradle 9 and springdoc 3.
- **Decision:** M0 (#7) upgrades straight to Spring Boot 4.x (latest 4.2.x), with Gradle 9.x and springdoc 3.x,
  following the official migration guide, and moves the toolchain to **Java 25 LTS** (CI uses Temurin 25; M6 uses a
  JRE 25 base image). The `feature/add-discord-oauth` Boot 3.5.3 bump is dropped.
- **Why Java 25:** since JDK 24, `synchronized` no longer pins virtual threads to their carrier (JEP 491), and we rely
  on virtual threads. It's also the longest LTS runway, and Boot 4 supports it.
- **Consequences:** Jackson 3, Spring Framework 7, Jakarta EE 11 and some starter renames land before any domain
  code exists, which is the cheapest time to absorb them. Java 25 needs JaCoCo 0.8.14 or later (the 0.8.13 pin is
  bumped in #7) and Lombok 1.18.40 or later. Dependabot PRs #3–#6 close once #7 merges.

## D-009 Trunk-based development with release tags (2026-11-24) — Accepted
- **Decision:** `main` is the only long-lived branch. Short-lived branches merge into it through squash PRs, and
  releases are SemVer `v*` tags on `main` that only admins create (`v1.0.0` = MVP launch). A `release/X.Y` branch is
  cut from a tag only for a hotfix that can't wait for `main`. Rulesets protect `main`, `release/*` and `v*` tags.
  From M6, `main` deploys to staging and tags deploy to prod. See `docs/releasing.md`.
- **Rejected:** a `develop` branch (GitFlow), because it adds a second integration branch, drift and merge work with
  no benefit for a single-team web app.

## D-010 Errors use the same response envelope as successes (2026-11-24) — Accepted
- **Context:** Since #21, success bodies were `ResponseRecord` envelopes (`responseType`, `body`) while errors were a
  bare `ErrorResponseBodyRecord`. Clients would have to guess which shape a body is from the status code.
- **Decision:** Every `/api` body, error or not, is `{"responseType": ..., "body": ...}`. Errors have
  `responseType: "ERROR"` and a body of `{errorType, errorMessage, correlationId}` with generic, per-status text
  only; the `X-Correlation-Id` header carries the same ID. `ResponseRecord` is generic, so the OpenAPI document
  (`docs/api/openapi.json`, OpenAPI 3.1) names each endpoint's concrete envelope, and every operation lists its
  4xx/5xx responses plus `default` with the error envelope.
- **Rejected:** documenting the bare record as the error schema. It saves one level of nesting but gives clients
  two shapes to parse.
- **Consequences:** Clients parse one structure and switch on `responseType`. A 204 has no body at all. The
  committed document is regenerated by the integration tests, and CI fails when it drifts from the code.
- **Revisit:** if a client has to handle a non-JSON error that Spring Security or the container writes before our
  handlers run. Every such path must still go through `/error` (`ErrorControllerMVC`) so it gets the envelope.
- **Amended by D-011:** `errorType` may also be a domain type that an operation documents for its status (such as
  `INVITE_LIMIT` on a 429), so a client can tell a cap from a rate limit. The message stays generic.

## D-011 Social graph: invite links, pickable people and blocks (2026-11-24) — Accepted
- **Context:** M2 adds in-app friends, circles and blocks (#34). Discord can't tell us who someone's friends are
  (D-001), so the app needs its own rules for who can find and pick whom.
- **Decision (owner):**
  - **QR codes encode a normal invite link** that any phone camera opens: `https://<app origin>/invite/<token>`,
    the SPA's accept page. There is no in-app scanner.
  - **Pickable** people are the user's friends, plus people who share a Discord server where **both** of them are
    discoverable. Never the user themself, and never anyone either of them has blocked. People search
    (`GET /api/people`), circle members and, from #35, individual event audience entries use only pickable people.
  - **Blocking is a full mutual cutoff**, whichever of the two made it. It ends their friendship and removes each
    from the other's circles, in one transaction. From then on neither can find or pick the other, use the other's
    invites, or see the other's events.
  - **A block doesn't void the blocker's other invites** (owner, 2026-11-24): accepting already rejects a blocked pair,
    so links the blocker sent to other people keep working. After an unblock, an unexpired link the blocker had
    already shown the other person works again.
- **Invite rules:**
  - The token is 128 bits from a CSPRNG, base64url without padding (22 characters).
  - Only its SHA-256 is stored (`friend_invite.token_hash`). The raw token appears once, in the
    `POST /api/friends/invites` response's `url`, and never in logs (the request log records the route pattern).
  - An invite expires 24 hours after it is made and works once. A user can have at most **20 active** (unused,
    unexpired) invites; one more is a 429 with errorType `INVITE_LIMIT` (amending D-010).
  - Every unusable token is the **same 404**, with no way to tell the cases apart: malformed, unknown, expired,
    already used, the caller's own, or between two people where either has blocked the other.
  - Accepting when the two are already friends succeeds, returns the friend and uses the invite up.
- **Rules this contract adds (confirmed by the owner, 2026-11-24):**
  - **Signing in from an invite link:** the preview is a 401 for a signed-out visitor. The SPA keeps the token in
    `sessionStorage`, signs them in (through onboarding if it's their first time) and returns to the accept page.
    There is no server-side return URL, so there is no open redirect to defend.
  - **Showing a QR code:** invites can't be listed or revoked, so the client makes a new one only when the user asks
    for a new code, never each time a screen opens.
  - **Removing a friend** also removes each from the other's circles, so an ex-friend stops seeing circle events.
  - **Pickability is checked when someone is added** to a circle and isn't re-checked later. Someone who turns off
    discoverability stays in circles they were already in, until they're removed or blocked.
  - **Unblocking** removes only the caller's own block and restores nothing. `GET /api/blocks` lists only the
    blocks the caller made.
  - Users are always identified by our `app_user` UUID, never a Discord ID. Circles are private to their owner,
    members aren't told, and circle URLs use `circle.public_id`. A circle ID that isn't the caller's is a 404.
  - Removing a friend, a block or a circle member, and blocking an unknown user ID, are all a 204, so they reveal
    nothing about the other user.
- **For the implementation (#36, and #37 for events):**
  - Throw `LimitExceededException` with a `LimitExceededException.Limit` (`INVITE_LIMIT`; #35 adds `EVENT_LIMIT`)
    for a cap. `GlobalControllerExceptionHandler` turns it into the 429 with that errorType, the generic message and
    a correlation ID. New caps add a `Limit` value, never a free string.
  - Accept with one statement, so two accepts can't both win: `UPDATE friend_invite SET used_at = now(),
    accepted_by = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > now() RETURNING inviter_id`, with the
    block and own-invite checks in the same transaction.
  - Enforce the 20-invite cap under a lock on the inviter (for example `SELECT … FROM app_user WHERE id = ? FOR
    UPDATE`), because count-then-insert races.
  - Build the invite `url` from a configured app origin, never from the request's `Host` or `X-Forwarded-Host`.
  - Order a friendship pair in SQL with `LEAST`/`GREATEST`. Postgres orders `uuid` as unsigned bytes, and Java's
    `UUID.compareTo` disagrees for about half of random pairs.
- **Consequences:** a leaked database doesn't yield working invite links. A stranger can't learn whether a token
  ever existed, or whether someone blocked them, from these endpoints.
- **Revisit:** if invite abuse shows up (many accounts accepting one person's links), or if hosts ask to reach
  people they share a server with but who aren't discoverable.

## D-012 Event visibility, the feed and the event cap (2026-11-24) — Accepted
- **Context:** M2 adds events with audiences made of servers, friends, circles and individuals (#35). Plan B3 gives the
  visibility rule; this pins it down for the contract and for #37's single visibility query.
- **Decision (from plan B3 and the issue):**
  - **Visibility.** The host always sees their own events. Anyone else sees an event only when all of these hold:
    - it is `PUBLISHED`;
    - neither of them has blocked the other (D-011);
    - one of its audience entries matches the viewer:
      - `USER`: the viewer;
      - `FRIENDS`: the viewer is the host's friend;
      - `CIRCLE`: the viewer is in that circle (always one of the host's);
      - `GUILD`: the viewer is discoverable in that server.

    Every endpoint uses this one rule, and an event the viewer can't see is a 404.
  - **Typed audience entries.** `event_audience` holds typed references (`guild_id`, `circle_id` or `user_id`, with
    a CHECK that the type names exactly one, or none for `FRIENDS`). This replaces plan B3's free-text `ref_id`, so
    every reference has a foreign key or a snowflake. An event has 1 to 50 distinct entries. A host may target only
    servers they're in, their own circles, and people they can pick (D-011).
  - **Who sees what.** Only the host gets `audience`; everyone else gets `null`. With
    `locationVisibility: CONFIRMED`, everyone but the host gets `location: null` and `locationHidden: true`, until M3
    adds confirmed attendees.
  - **The feed** (`GET /api/events?scope=feed`) lists published events visible to the viewer, their own included, that
    are still upcoming. Upcoming means `coalesce(ends_at, starts_at + 3 hours) > now()`. It is sorted by
    `starts_at`, then ID, and paged with an opaque cursor (`limit` 1 to 50, default 20).
  - **Cancelling** (`DELETE`) keeps the row with `status = CANCELLED`.
  - **The cap:** a host can create at most **20 events a day**. One more is a 429 with errorType `EVENT_LIMIT`, a
    domain errorType as D-011 allows.
- **Rules this contract adds (confirmed by the owner, 2026-11-24):**
  - **A `GUILD` entry needs the host to be discoverable there too**, now, not just when the event was made. Turning
    off discoverability, or leaving the server, stops that server from reaching anyone or exposing the host. The host
    can only add a server where they're discoverable, and while they aren't, that entry's `label` is `null`.
  - **"A day" means any rolling 24 hours**, counted on `created_at`, with cancelled events included.
  - **Changing or cancelling an event the caller doesn't host is a 404**, even when they can see it.
  - **`PATCH` is a full replace:** it takes the whole event, as create does, including the audience. Entries already
    on the event are kept without a re-check (as D-011 does for circles), so sending back the audience the host
    received never fails. Only newly added entries are checked, and one the host can't use is a 404, with nothing
    saved. A start that has passed may be kept, but a start can't be moved into the past.
  - **Cancelled events are visible only to the host**, can't be edited (409), and cancelling twice is still a 204.
    M3 revisits this so confirmed attendees, and M4's "event cancelled" notification, can still open the event.
  - **`scope=hosting`** lists the caller's own upcoming events the same way as the feed, cancelled ones included. A
    cursor works only with the scope that issued it.
  - **Time bounds:** an event lasts at most 7 days (`endsAt <= startsAt + 7 days`), starts within a year of now
    either way, and opens sign-up at most a year before it starts. These keep a decade-long event from sitting at
    the top of every feed, and let the feed query bound `starts_at` from below.
  - **Blocking** also removes each person's `USER` entries from the other's events, as it does for circles (D-011).
    Deleting a circle or a user removes their entries. An event left with no entries is visible only to its host.
  - **An empty or whitespace-only description** is stored as `null`.
- **For the implementation (#37):**
  - Throw `LimitExceededException(Limit.EVENT_LIMIT)` for the cap, and count under a lock on the host (for example
    `SELECT … FROM app_user WHERE id = ? FOR UPDATE`), because count-then-insert races.
  - Bound the feed query with `starts_at > now() - interval '7 days'`, so it can use `event_starts_at_idx`.
  - **Account deletion** (not in M2) must cancel the host's events (M4 notifies attendees) and then delete them,
    because `event.host_id` doesn't cascade and a cancelled row still references the host.
- **Not in M2:** RSVP and attendee views (M3), notifications (M4), and moderation's hidden flag (M5).
- **Revisit:** if the visibility query misses its latency target in the M7 load test, or if hosts ask for audiences
  that don't need the host to be discoverable.
