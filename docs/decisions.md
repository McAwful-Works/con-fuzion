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

## D-013 RSVP: first come, first served, the waitlist and who sees it (2026-11-24) — Accepted
- **Context:** M3 adds RSVP (#53): limited spots claimed first come, first served (D-002, plan B4), a waitlist in
  queue order, live spot counts and My schedule. This pins the API rules so #54 (backend), #55 (frontend) and #56
  (e2e) can build in parallel. The target is 5,000 joins in 10 seconds on one event with no overbooking (M7 load test).
- **Decision (owner):**
  - **In M3, a freed spot auto-confirms the next person in line.** When a confirmed person leaves or is removed, the
    earliest waitlisted person (lowest `rsvp.id`) becomes `CONFIRMED` in the same transaction. M4 replaces this with
    an offer and a deadline, once push notifications exist.
  - **The host sets the offer window**, 10 minutes to 24 hours with a default of 30, stored in
    `event.confirm_window_minutes` (the CHECK is tightened now). M4 uses it.
  - **The host takes a spot.** `capacity` is the whole headcount, the host included, so it is 2 to 10,000, or null for
    unlimited. Every event has the host's `CONFIRMED` RSVP from creation, so `confirmed_count` starts at 1. The
    migration backfills one for every existing event, and raises any capacity of 1 to 2 before tightening the CHECK.
  - **Capacity can't be cut below the confirmed count.** That `PATCH` is a 409 with errorType
    `CAPACITY_BELOW_CONFIRMED`, and nothing is saved.
- **Domain 409s.** A 409 the client must tell apart throws `ConflictException` with a `ConflictException.Conflict`
  value, which becomes the errorType (as D-010 allows). It's a sibling of `LimitExceededException.Limit`, not a new
  `Limit` value: a `Limit` is a quota that answers 429 ("wait"), and a `Conflict` is a state that answers 409 ("not
  now" or "not you"), so each type still implies its status. The values are `CAPACITY_BELOW_CONFIRMED`,
  `SIGNUP_NOT_OPEN`, `SIGNUP_CLOSED`, `RSVP_REMOVED` and `HOST_CANNOT_LEAVE`. New ones add a value, never a free
  string. `EventNotEditableException` stays a plain 409.
- **D-012 revisited for M3:**
  - **Confirmed attendees can open a cancelled event** (so M4's "event cancelled" notification can link to it). Rule 10
    extends this to waitlisted people.
  - **Confirmed attendees see a `CONFIRMED` location.** Waitlisted people don't.
  - **Only the host and confirmed attendees get the attendee list.** Anyone else gets a 404.
  - **A waitlist position is shown only to its owner** (`position` from a join, `myPosition` from `/spots`). The host
    sees the waitlist as a list in queue order.
- **Rules this contract adds (confirmed by the owner, 2026-11-25):**
  1. **A removed person can't rejoin.** Their join is a 409 `RSVP_REMOVED`, and their `myStatus` is `REMOVED`. They
     still see the event while its audience reaches them. M3 has no undo. Someone who *left* may join again, at the
     back of the queue.
  2. **Joining is open from `signupOpensAt` (or from publication) until the start.** Before that it's a 409
     `SIGNUP_NOT_OPEN`, and from the start a 409 `SIGNUP_CLOSED`. Leaving and host removal work at any time, but nobody
     is promoted once the event has started or is cancelled. So after the start, `/spots` can show spots left and a
     waitlist at once, and the client shows sign-up as closed.
  3. **An unlimited event never has a waitlist.** Every join confirms, `waitlisted` is 0 and `spotsLeft` is null.
  4. **Raising the capacity, or making it unlimited, before the start promotes waitlisted people** in queue order to
     fill the new spots, at once (rule 2: never after the start). No other edit (times, sign-up opening) removes
     anyone.
  5. **Repeats are harmless.** Joining while confirmed or waitlisted returns the current status with a 200 and changes
     nothing, whatever the time. Leaving, or removing someone, who isn't signed up is a 204 that writes nothing: the
     person can still join. A host who wants someone kept out blocks them or drops them from the audience.
  6. **The host can't leave or remove themself** (409 `HOST_CANNOT_LEAVE`); they cancel the event instead. The host
     joining gets `CONFIRMED`.
  7. **Only the host can remove people.** `DELETE /rsvps/{userId}` by anyone else is a 404, even for a confirmed
     attendee who can see the event.
  8. **`/attendees`:** the host and confirmed attendees get `confirmed`, in join order (the host first), as public
     profiles. The host also gets `waitlist` in queue order, and confirmed attendees get `waitlist: null`. The list
     leaves out anyone in a block with the caller, either way. The counts in `/spots` include everyone, so a confirmed
     attendee who compares the two can tell that someone in a block with them is going, though not who. Hiding them
     from the counts would make the counts wrong for everyone, so this contract accepts that.
  9. **`myPosition` and `position` are 1-based:** 1 plus the number of people waitlisted on that event with a lower
     `rsvp.id`. They're null unless the caller is waitlisted.
  10. **An active RSVP is a visibility grant, waitlisted included.** Anyone confirmed or waitlisted keeps seeing the
      event, cancelled or not, and even if its audience stops reaching them (removed from a circle, discoverability
      turned off), unless a block stands between them and the host. The host can remove them.
  11. **A block between the host and an attendee ends the attendee's RSVP** as `LEFT`, in the block's transaction,
      freeing the spot and promoting as for leaving. After an unblock they may join again if the audience reaches them.
  12. **My schedule** (`GET /api/events?scope=attending`) lists the upcoming events where the caller is confirmed or
      waitlisted, their own included (the host always holds a spot), and cancelled ones while they still hold an
      RSVP. Leaving a cancelled event takes it off. Sort, cursor and limit work as for the feed.
  13. **Every `EventResponse` carries `myStatus`** (`NONE`, `CONFIRMED`, `WAITLISTED` or `REMOVED`), so the feed and
      My schedule show it without polling `/spots` for each event. Positions stay in `/spots`.
  14. **The offer window isn't in the API yet.** `confirmWindowMinutes` joins `EventRequest` and `EventResponse` with
      M4's contract, when it does something. Until then every event uses 30 minutes.
  15. **Existing capacities are kept and now count the host.** Only a capacity of 1 changes, to 2. Nothing is live
      yet, so no real event loses a guest spot.
  16. **`/spots`** sends an `ETag` that is specific to the caller (it covers `myStatus` and `myPosition`), and
      `If-None-Match` gets a bodiless 304. It's sent with `Cache-Control: private, no-cache`, so no shared cache keeps
      it. Joins are rate limited to about one attempt per second per user (plan B2).
  17. **A join's checks run in this order:** the event must be visible (otherwise 404); an active RSVP returns its
      status (200, so the host always gets `CONFIRMED`); a `REMOVED` row is a 409 `RSVP_REMOVED`; then the sign-up
      window (`SIGNUP_NOT_OPEN`, `SIGNUP_CLOSED`); then the claim.
- **For the implementation (#54):**
  - **Lock order: the event row first, always.** Every write that changes who holds a spot (join, leave, removal, a
    block, a capacity edit) locks the `event` row before it touches any `rsvp` row, and holds it until it commits.
    That serialises them per event, so a waitlisted insert can't slip past a concurrent leave's promotion, and two
    writers can't deadlock.
  - **The claim** follows plan B4. The visibility, window and removal checks (rule 17) run first, read-only. Then one
    short transaction holds only an update that always matches, and so always takes the lock:
    `UPDATE event SET confirmed_count = confirmed_count + CASE WHEN capacity IS NULL OR confirmed_count < capacity THEN
    1 ELSE 0 END WHERE id = ? RETURNING status, confirmed_count, …` (or `SELECT … FOR NO KEY UPDATE`, then branch). If
    the returned status isn't `PUBLISHED` (cancelled in between), roll back and answer 404. Otherwise `INSERT` the RSVP,
    `CONFIRMED` if the count went up and `WAITLISTED` if not. A unique violation on `rsvp_active_key` is a double tap:
    return the existing RSVP.
  - **Leaving and removal** lock the event row, then set `LEFT` or `REMOVED`. Freeing a confirmed spot on a published
    event before its start promotes the next person (`… WHERE status = 'WAITLISTED' ORDER BY id LIMIT 1`) and keeps
    the count. Otherwise it decrements `confirmed_count`.
  - **The capacity check** runs under the event row lock that `PATCH` already takes. The `event_not_overbooked` CHECK
    is a backstop, not the mechanism.
  - **The concurrency suite** interleaves joins with leaves and capacity raises, and checks that no one is left
    waitlisted while a spot is free before the start.
  - **`/spots` caching:** plan B2's per-task cache may hold only the shared counts, never a caller's body or ETag.
  - **`EventRepository.VISIBLE`** gains the active-RSVP arm, and stays the one predicate.
  - **Account deletion** (M7) must leave every active RSVP first, because the `rsvp.user_id` cascade doesn't touch
    `confirmed_count`.
  - **#55:** the event form's capacity minimum becomes 2 (`frontend/src/features/events/validation.ts` says 1), and
    `npm run gen:api` picks up the new types.
- **As built (#59, #60, #61):**
  - **The claim** uses the second form above. `RsvpRepository.lock` reads the event row `FOR NO KEY UPDATE`, and
    `LockedEvent.hasFreeSpot()` decides `CONFIRMED` or `WAITLISTED` under that lock.
  - One statement then inserts the RSVP and, when it's confirmed, raises `confirmed_count`. The same statement
    re-checks for a block with the host and for a `REMOVED` row, and `ON CONFLICT … DO NOTHING` on `rsvp_active_key`
    absorbs a double tap.
  - Promotion needs no `SKIP LOCKED`, because the event row lock already serialises every writer.
  - **A block** locks both users, then their affected events in ID order. All of these locks are `NO KEY UPDATE`, so
    the `KEY SHARE` locks that the RSVP and audience inserts take on their foreign keys never wait on them.
  - **Delivery:** #54 shipped as three PRs at the owner's request, to fit the PR budget:
    - #59: the claim, leaving, removal, attendees, promotion and the block cutoff;
    - #60: capacity edits and My schedule;
    - #61: `/spots` and the join limit.
- **Consequences:** overbooking fails loudly at the database even if the code is wrong. Clients can tell every 409
  apart, and only the caller ever sees their own waitlist position.
- **Revisit:** in M4, when offers replace auto-confirming. If the M7 load test misses its target, add the Valkey
  `DECR` gate from plan B2 in front of the claim.

## D-014 Offers with a deadline, the inbox and Web Push (2026-11-25) — Accepted
- **Context:** M4 (#64) replaces M3's auto-confirm (D-013) with offers that have a deadline, and adds an in-app inbox,
  notification triggers, reminders and Web Push. This pins the API rules so #65 (offers), #66 (inbox and triggers),
  #67 (push), #68 and #69 (frontend) and #70 (e2e) can build in parallel.
- **Decision (owner):**
  - **Offers replace auto-confirm.** A freed spot is offered to the next person in line for the host's window
    (`event.confirm_window_minutes`, 10 minutes to 24 hours, default 30). The spot is held: it counts in
    `confirmed_count`.
  - **Near the start, confirm at once.** If the event starts within the window, the next person is confirmed
    straight away instead of being offered the spot.
  - **Declining is leaving** (`LEFT`). **An expired offer ends as `EXPIRED`,** and that person may join again at the
    back of the queue. Either way the next person is offered the spot.
  - **Capacity raises send offers,** and several offers can be open at once.
  - **Notification triggers:** offered a spot; confirmed; an event's start, end or location changed; an event was
    cancelled; a reminder 1 hour before, to the host and confirmed attendees; a friend invite was accepted. Nothing
    is sent when the host removes someone.
  - **The lock-screen text** shows the event title and what happened, never the location or anyone's name.
  - **A block deletes the notifications** between the two people. **One push on/off setting** per user (the existing
    `pushEnabled`). **No inbox purge** in M4.
  - **The Web Push library** is `com.zerodeplibs:zerodep-web-push-java` (#67 confirms its release history before
    adding it).
  - **VAPID keys:** the owner generates the production key pair and stores it. Claude never handles the private key.
    Tests generate throwaway keys.
- **Rules this contract adds (confirmed by the owner, 2026-11-25):**
  1. **`confirmWindowMinutes` is optional on `EventRequest`.** Missing or null means 30 on a create, and keeps the
     current window on an edit. This is the one exception to D-012's full-replace `PATCH`: it lets the merged
     frontend, which doesn't send the field until #68, keep working without resetting a host's window. A change
     applies to later offers; open offers keep their deadline. `EventResponse.confirmWindowMinutes` is always set, for
     everyone who can see the event.
  2. **An offer's deadline** is `now + confirmWindowMinutes` when it's made. "The event starts within the window" means
     `starts_at <= now + window`; then the person is confirmed at once and gets `SPOT_CONFIRMED`. As in D-013 rule 2,
     nobody is offered or confirmed once the event has started or is cancelled: the freed spot just frees up.
  3. **An open offer is an active RSVP,** like confirmed and waitlisted: it's the one active RSVP per person per event
     (`rsvp_active_key`), it's on My schedule, and it grants visibility (D-013 rule 10). Joining while offered returns
     `OFFERED` and its `offerExpiresAt` with a 200 and changes nothing. `myStatus` is `OFFERED`. An offered person
     doesn't see a `CONFIRMED`-only location or the attendee list until they accept.
  4. **Accepting** (`POST /api/events/{eventId}/rsvp/accept`) checks, in order: the event is visible (otherwise 404);
     already `CONFIRMED` returns 200 `CONFIRMED` and changes nothing; an offer past its deadline is a 409
     `OFFER_EXPIRED`; then an open offer on a published event becomes `CONFIRMED` (200), and anything else is a 409
     `NO_OFFER` (none was made, they declined, or the event was cancelled). The deadline is compared with the
     statement's time, so an offer the expiry job hasn't reached yet can't be accepted late, and one inside its
     deadline can be accepted even if the event has started since. Accepting sends no notification.
  5. **Declining is `DELETE /api/events/{eventId}/rsvp`** while offered: the RSVP ends as `LEFT`, and the spot goes to
     the next person (rule 2). Host removal of an offered person ends it as `REMOVED`, with no notification. A block
     between the host and an offered person ends it as `LEFT` (D-013 rule 11).
  6. **Offers count as taken spots** everywhere a count is used: `/spots` `confirmed` and `spotsLeft`, and the
     `CAPACITY_BELOW_CONFIRMED` check on a capacity cut. Waitlist positions count only `WAITLISTED` people, so being
     offered a spot takes the person out of the queue.
  7. **The host's attendee list** gains `offered`, in queue order. Confirmed attendees get `offered: null`. As with
     `waitlist`, the list leaves out anyone in a block with the caller.
  8. **Who gets each notification, and its payload.** The payload is the only content a notification has, so it's
     safe for a lock screen: it never holds a location or a name. Each type sets only its own fields.

     | Type | Sent to | Payload |
     |---|---|---|
     | `SPOT_OFFERED` | the person offered a spot | `eventTitle`, `offerExpiresAt` |
     | `SPOT_CONFIRMED` | a person confirmed without accepting an offer (a spot freed, or a capacity raise, within the window before the start) | `eventTitle` |
     | `EVENT_CHANGED` | every active RSVP but the host's, when a `PATCH` changes the start, end or location (one notification listing every change) | `eventTitle`, `startsAt` (after the change), `changes` (`START`, `END`, `LOCATION`) |
     | `EVENT_CANCELLED` | every active RSVP but the host's | `eventTitle` |
     | `EVENT_REMINDER` | the host and every confirmed attendee, about an hour before the start, at most once each | `eventTitle`, `startsAt` |
     | `FRIEND_ACCEPTED` | the inviter | `friendId` (the new friend's user ID; the inbox may look the name up in `/api/friends`, a push never shows it) |

     `eventTitle` is the title when the notification was made. Every type but `FRIEND_ACCEPTED` has an `eventId`.
     Joining, leaving, accepting and host removal send nothing.
  9. **A block deletes**, in the block's transaction, the notifications each of the two got about the other's events
     (through `event.host_id`), and each one's `FRIEND_ACCEPTED` whose `friendId` is the other.
  10. **The inbox** (`GET /api/notifications`) is the caller's own notifications, newest first (highest `id`), with an
      opaque `cursor` and `limit` 1 to 50 (default 20), like the feed. `unreadCount` counts the whole inbox, capped at
      100 so the count stays cheap; the client shows 100 as "99+". Nothing is purged in M4, but the 90-day purge of
      ended events (docs/security.md) cascades to their notifications.
  11. **`POST /api/notifications/read {upTo}`** marks every one of the caller's notifications with `id <= upTo` read,
      and is a 204 whatever matched. The client sends the newest ID it has shown, so anything newer stays unread.
      Notification IDs are bigints that only their owner sees; every query filters by the caller, so another user's ID
      does nothing.
  12. **`pushEnabled` decides `push_status` when a notification is written:** `PENDING` when push is on and the user
      has at least one subscription, `NONE` (inbox only) otherwise. Turning push off keeps the subscriptions, and
      subscribing doesn't turn it on: the client sends both calls.
  13. **Push subscriptions:**
      - The endpoint must be `https` on a known push service host: `fcm.googleapis.com`,
        `updates.push.services.mozilla.com`, `web.push.apple.com` or `*.notify.windows.com`, then a path. Anything else
        is a 400, because the sender POSTs to this URL and an open list would be SSRF. A new browser's host is added
        in a contract PR. Endpoints are at most 2,048 characters, `p256dh` is 65 bytes and `auth` 16 bytes, base64url.
      - An endpoint belongs to whoever subscribed with it last: subscribing again moves it to the caller and replaces
        its keys, so a browser shared between accounts never gets the previous user's pushes.
      - A user has at most 10 subscriptions. An 11th replaces their oldest (lowest `id`), so a user who lost old
        browsers never gets stuck.
      - `DELETE /api/push/subscriptions {endpoint}` removes only the caller's own, and is a 204 either way. The client
        sends it on sign-out and when turning push off.
      - Endpoints work like bearer tokens: never log them.
  14. **`GET /api/push/key`** needs a session, like every API route (docs/security.md allows only health and
      `/api/meta/support` without one). It's a 404 when the server has no VAPID keys, and the client then hides the
      push setting.
  15. **An offer past its deadline is expired at once, whether or not the expiry job has run.** Reads treat
      `offer_expires_at <= statement_timestamp()` as no RSVP: `myStatus` is `NONE`, with no `offerExpiresAt`. A join
      first ends that offer as `EXPIRED` and frees the spot (rule 2), under the event row lock, then joins at the back
      of the queue. An accept is a 409 `OFFER_EXPIRED` while the caller's latest RSVP on the event is a past-deadline
      offer or `EXPIRED`, and `NO_OFFER` otherwise.
  16. **Cancelling leaves open offers alone.** They stay `OFFERED` until their deadline, so the person keeps seeing the
      cancelled event and gets `EVENT_CANCELLED` like every other active RSVP. Accepting is a 409 `NO_OFFER`, and the
      client shows no Accept button on a cancelled event (`EventResponse.status`). At the deadline the expiry job ends
      them as `EXPIRED` without offering the spot again (rule 2).
  17. **Making a capacity unlimited** before the start confirms every waitlisted person and every open offer at once,
      each with `SPOT_CONFIRMED`, because an unlimited event never has a waitlist (D-013 rule 3). Any other raise
      offers each new spot to the next person in line (or confirms them, within the window before the start).
  18. **Signed-out devices stop getting pushes.** On sign-out the client calls `PushSubscription.unsubscribe()` as well
      as `DELETE /api/push/subscriptions`, so the endpoint dies even if the server call fails (for example on an
      expired session); the sender then deletes it on the push service's 404 or 410. "Log out everywhere" also deletes
      all of the user's `push_subscription` rows.
- **For the implementation:**
  - **#65 makes `OFFERED` count** in the M3 code that names the active statuses: `RsvpRepository.ACTIVE`, `findMine`
    and `findStatuses` (which also return `offerExpiresAt`), `lockForBlock`, `EventRepository.VISIBLE`'s active-RSVP
    arm, the feed union and `scope=attending`. #64 widens the indexes only. `RsvpRepository.insert`'s
    `ON CONFLICT (event_id, user_id) WHERE status IN ('CONFIRMED', 'WAITLISTED')` still infers the widened
    `rsvp_active_key` (Postgres proves the narrower predicate implies the index's), and a join conflicts with an open
    offer through it, so it needs no change.
  - **D-013's lock order is unchanged:** every write that changes who holds a spot, the expiry job included, takes the
    event row lock first. The expiry job finds due offers through `rsvp_offer_expiry_idx`, locks each event with
    `SKIP LOCKED` so several tasks share the work, and re-checks the offer under the lock.
  - **Every trigger writes through `NotificationRepository.insert`,** in the same transaction as the change it reports
    (plan B8). It sets `push_status` (rule 12).
  - **#67's sender** re-checks the endpoint's host with `java.net.URI.getHost()` against rule 13's list before each
    send and doesn't follow redirects, so the allowlist holds even if the request pattern is loosened later. A
    `PENDING` row whose user has turned push off, or has no subscription left, by send time ends as `FAILED`. #67
    also makes "log out everywhere" delete the user's subscriptions (rule 18).
  - **#67's delivery is at most once.** One statement claims a batch (`SKIP LOCKED`) and marks it `SENT`, then the
    pushes go out with no row lock held, so inbox reads and blocks never wait on a push service. Rows nobody received
    become `FAILED`; rows only a 429 held back, and a batch interrupted at shutdown, go back to `PENDING`. A task that
    dies mid-send leaves its batch `SENT` without a push; the inbox still has every notification.
  - **#68** runs `npm run gen:api` and sends `confirmWindowMinutes` from the event form. After #68 the field could
    become required; that's a later contract change, not part of M4.
- **Consequences:** a freed spot waits for someone who wants it instead of going to someone who may not have noticed,
  at the cost of up to the window's delay. Every notification is safe to push as it is. The sender can reach only the
  four push services.
- **Revisit:** if the inbox gets slow without a purge (a measured p95), add a retention limit. If a supported browser
  uses a push host outside the list, add it in a contract PR.

## D-015 Roles, reports, moderation and the audit log (2026-11-25) — Accepted
- **Context:** M5 (#83) adds the safety and admin tooling in plan B11/B12: MFA-gated roles, reports and a moderation
  queue, hiding and cancelling events, suspensions and bans, an insert-only audit log, and maintenance mode. This pins
  the API and schema rules so #84 (roles, MFA, audit, maintenance), #85 (reports and event moderation), #86
  (suspensions, bans and user search), #87 and #88 (frontend) and #89 (e2e) can build in parallel.
- **Decision (owner):**
  - **Roles** are USER (implicit), MODERATOR and ADMIN. ADMIN also gets bans, role grants and the audit log.
    MODERATOR and ADMIN apply **only while Discord reports `mfa_enabled=true` at sign-in**; without it the account acts
    as a plain user and sees a "turn on Discord 2FA" note.
  - **The bootstrap IDs** (`con-fuzion.admin.bootstrap-discord-ids`) get ADMIN at sign-in, and the grant is
    audit-logged. The owner settled "first sign-in" against plan B12 on 2026-11-25: **at every sign-in, a bootstrap ID
    with no ADMIN row gets one**, so an existing account added to the config later works, and so does recovering from
    the loss of the last admin. Taking an ID out of the config revokes nothing. **The last ADMIN can't be removed. A role change
    deletes that user's sessions.**
  - **Reports** cover events and users, with the reasons `HARASSMENT`, `UNSAFE`, `SPAM`, `INAPPROPRIATE` and `OTHER`,
    and a note of up to 1,000 characters. At most 10 per user per day. An open duplicate on the same target returns the
    existing report. Every moderator and admin is notified (in-app and push). The reporter later gets "we reviewed your
    report" with no details. The reported user never learns of the report or who made it.
  - **Hiding an event is silent for attendees.** They lose access, the RSVPs stay, and the host gets the reason. To
    tell attendees, a moderator cancels instead: the normal cancel notification (no reason), and the host still gets
    the reason.
  - **A suspension** (until a date) blocks sign-in and deletes the user's sessions. Their events and RSVPs are left
    alone.
  - **A ban** (ADMIN, by Discord ID, kept even if the account goes) blocks sign-in and deletes the user's sessions. It
    cancels their upcoming hosted events and ends their RSVPs as `LEFT`. The account itself is kept.
  - **What moderators can see about a user:** display name, Discord ID, when they joined and last signed in, any
    suspension or ban, and the number of reports against them. Never their servers, friends, circles or RSVPs.
  - **The audit log** covers every moderator and admin action, refused sign-ins, role changes and session purges. It's
    insert-only and readable by ADMIN only, with no retention limit in the MVP.
  - **Maintenance mode is a config flag** (`con-fuzion.maintenance=true`, effective on the next deploy or restart).
    Every `/api` route except health returns 503.
- **Rules this contract adds (confirmed by the owner, 2026-11-25):**
  1. **Who gets 401, 403 and 404 under `/api/admin`.** Signed out is a 401. Signed in without the role a route needs
     is a **403**, the one exception to "404 rather than 403": the admin routes are listed in the public
     `openapi.json`, so a 403 reveals nothing that isn't already known. For a caller with the role, a missing report,
     user or event is a 404. Every other path or method under `/api/admin` is denied (a 403 even for an admin). A write
     without CSRF is a 403 as everywhere. `SecurityConfig` lists each route with its role: ADMIN for bans, roles and
     the audit log, MODERATOR or ADMIN for the rest. The authorities are `ROLE_MODERATOR` and `ROLE_ADMIN`.
  2. **Roles in effect.** `MeResponse.roles` lists the roles in effect for the session (always `USER`).
     `mfaRequired` is true when the user holds a granted role that MFA keeps out of effect; turning 2FA on takes
     effect at the next sign-in. `GrantedRole` (`MODERATOR`, `ADMIN`) is what's stored in `user_role` and sent to and
     from the roles endpoints; `USER` can't be granted (a 400).
  3. **Role changes.** `PUT /api/admin/users/{userId}/roles {roles}` sets exactly that set, and `DELETE` is the
     same as an empty set. Sending the current set is a 200 (204 for `DELETE`) that changes nothing: no session purge
     and no audit row. "The last ADMIN" counts every `user_role` ADMIN row, with or without MFA, so the only admin
     can't remove themself either (409 `LAST_ADMIN`). A bootstrap grant is a `ROLES_CHANGED` row with no actor. While
     an ID is in the bootstrap config, removing its ADMIN lasts only until its next sign-in: take it out of the config
     first.
  4. **Who can be suspended or banned.** A moderator can suspend only users with no granted role; an admin can also
     suspend moderators. Nobody can suspend or ban an account that holds ADMIN: remove the role first. Both are a 409
     `TARGET_HAS_ROLE`, which also covers trying it on yourself. A suspension ends in the future and at most 365 days
     away (400 otherwise); suspending again replaces the end. A ban needs no account: banning a Discord ID before it
     signs up works. Banning a banned ID, unbanning one that isn't, and unsuspending someone who isn't suspended are
     204s that change nothing. Unbanning undoes nothing the ban did.
  5. **A refused sign-in** (a banned Discord ID, or `suspended_until > now()`) lands on `/welcome?login=refused`, the
     same for both, so the page shows one neutral message that doesn't say which or mention reports (#87). It writes
     `SIGN_IN_REFUSED` (no actor, target `DISCORD_ACCOUNT`, `cause` `ACCOUNT_BANNED` or `USER_SUSPENDED`). The user's
     existing sessions were already purged by the suspension or ban.
  6. **Reporting** (`POST /api/reports {targetType, targetId, reason, note}`) is a **204** whether the report is new
     or the caller already has an `OPEN` report on that target; a repeat changes nothing (the first note stands) and
     doesn't count towards the cap. The cap is 10 reports created in the last 24 hours (429 `REPORT_LIMIT`). A `USER`
     target is any existing user but the caller. An `EVENT` target is one the caller can see or has ever had an RSVP
     on, in any status, so a block or a hide never stops someone who was there from reporting it; not one they host.
     Anything else is a 404, and reporting yourself or your own event is a 400. A block in either direction doesn't
     stop a report.
  7. **Report storage.** Event public IDs and user IDs are both UUIDs, so `report.target_id` is one `uuid` column,
     with `target_type` saying which. It has **no foreign key**: a report outlives the 90-day purge of ended events and
     account deletion, so moderation history and a user's report count survive. The API returns `targetLabel` (the
     event's title or the user's display name) as null once the target is gone. `reporter_id` and `handled_by` are
     `ON DELETE SET NULL`, so a deleted reporter or moderator leaves the report anonymised. Moderators see
     `reporterId`; the reported user never sees anything.
  8. **The queue** (`GET /api/admin/reports?status`) defaults to `OPEN`, oldest first, so it's worked in order;
     `ACTIONED` and `DISMISSED` are newest first. Like the inbox it pages with an opaque `cursor` and a `limit` of 1 to
     50 (default 20). Moderators open an event target with `GET /api/events/{eventId}`. To a moderator it shows an
     event they couldn't otherwise see, with its location, **only while that event is the target of a report (in any
     status) or is hidden by moderators** (owner, 2026-11-25); any other event is the usual 404, so a moderator can't
     walk event IDs to find where people will be. The attendee list stays host and confirmed only.
  9. **Resolving** closes one report as `ACTIONED` or `DISMISSED`, with an optional note (1,000 characters, moderators
     only), and sends its reporter, if they still exist, a `REPORT_REVIEWED` that says nothing about the outcome. A
     report already resolved is a 409 `REPORT_RESOLVED`. Moderators act on the target first (hide, cancel, suspend, or
     ask an admin to ban), then resolve.
  10. **Hiding** sets `hidden_by_moderation` and `moderation_reason` together (a CHECK keeps them in step). The event
      becomes a 404 for everyone but its host and moderators (`EventRepository.VISIBLE` adds
      `NOT hidden_by_moderation`, the active-RSVP arm included); attendees keep their RSVPs but can't open, join or
      leave it. While it's hidden, attendees get no `EVENT_CHANGED` or reminder, and nobody is offered a spot (a freed
      spot just frees up, as after the start in D-014 rule 2). A cancel, by the host or a moderator, still sends
      attendees `EVENT_CANCELLED`, so they know the plan is off. The host gets `EVENT_HIDDEN` and sees the reason as `EventResponse.hiddenReason`
      (null for everyone else and for visible events); they can still edit or cancel it, and it stays hidden.
      `Event.moderationReason` is read-only in JPA, so a host's edit never writes it back. Hiding a hidden event is a
      204 that changes nothing. **Unhiding** clears both columns and notifies nobody.
  11. **A moderator's cancel** works like the host's (`DELETE /api/events/{id}`): cancelling a cancelled or ended event
      is a 204 that changes nothing, and attendees get the usual `EVENT_CANCELLED` with no reason. The host also gets
      `EVENT_CANCELLED`, with the reason. A hidden event can be cancelled too (rule 10).
  12. **Who gets each new notification, and its payload.** As in D-014 rule 8, each type sets only its own fields and
      never holds a location or a name; no report notification names the reporter or the target.

      | Type | Sent to | `eventId` | Payload |
      |---|---|---|---|
      | `REPORT_RECEIVED` | every user with MODERATOR or ADMIN in effect (granted, and MFA on at their last sign-in) | none | `reportId`, `targetType` |
      | `REPORT_REVIEWED` | the reporter, when their report is resolved either way | none | nothing (`{}`) |
      | `EVENT_HIDDEN` | the host | the event | `eventTitle`, `reason` |
      | `EVENT_CANCELLED` (moderator) | the host | the event | `eventTitle`, `reason` |

      `reason` is the one payload field that isn't safe for a lock screen: the push text never quotes it
      (`PushSender.text`), only the inbox shows it.
  13. **Caps.** A report note and a resolution note are up to 1,000 characters; a hide, cancel, suspension or ban
      reason is 1 to 500, all plain text. The user search `q` is 2 to 100 characters and returns at most 20 users.
  14. **User search** (`GET /api/admin/users?q`) matches an exact user ID or Discord ID, or part of a username or
      display name, ignoring case, ordered by username. `AdminUserResponse` has exactly the owner's list:
      `discordId`, `username`, `globalName`, `createdAt`, `lastLoginAt`, `suspendedUntil`, `ban` (reason and date) and
      `reportCount` (reports naming them, any status).
  15. **The audit log.** One `AuditLogRepository.insert(actorId, action, targetType, targetId, details)`, called in
      the same transaction as the change it records. A request that changes nothing writes no row.
      - **Storage:** `actor_id` and `target_id` are plain values with **no foreign keys**, so an account deletion or
        the event purge never has to update or delete a row (an `ON DELETE SET NULL` would be an `UPDATE`).
        `target_id` is text: a user ID or event public ID, a report ID, or a Discord ID. A trigger raises on every
        `UPDATE`, `DELETE` and `TRUNCATE`. `actor_id` is null for the system (a bootstrap grant, a refused sign-in).
      - **Actions and details** (`AuditDetails`, JSON `NON_NULL`; details hold IDs, enums, times and moderators' own
        text, never a name, a reporter's note, a token or an IP):

        | Action | Target | Details |
        |---|---|---|
        | `ROLES_CHANGED` | `USER` | `roles` (the new set) |
        | `SESSIONS_PURGED` | `USER` | `cause` (`ROLES_CHANGED`, `USER_SUSPENDED` or `ACCOUNT_BANNED`), `sessions` |
        | `SIGN_IN_REFUSED` | `DISCORD_ACCOUNT` | `cause` (`ACCOUNT_BANNED` or `USER_SUSPENDED`) |
        | `REPORT_RESOLVED` | `REPORT` | `resolution`, `note` |
        | `EVENT_HIDDEN`, `EVENT_CANCELLED` | `EVENT` | `reason` |
        | `EVENT_UNHIDDEN` | `EVENT` | nothing |
        | `USER_SUSPENDED` | `USER` | `until`, `reason` |
        | `USER_UNSUSPENDED` | `USER` | nothing |
        | `ACCOUNT_BANNED` | `DISCORD_ACCOUNT` | `reason` |
        | `ACCOUNT_UNBANNED` | `DISCORD_ACCOUNT` | nothing |

        `SESSIONS_PURGED` is written once per purge that a role change, suspension or ban causes, with the count. A
        user's own "log out everywhere" isn't audited.
      - **Reading** (`GET /api/admin/audit`) is newest first, with an opaque `cursor` and a `limit` of 1 to 50 (default
        20).
  16. **Maintenance mode** answers every `/api` route except `/api/actuator/health` with a 503 in the D-010 envelope
      (`errorType` `SERVICE_UNAVAILABLE`), signed in or not, and the client shows a "back soon" page (#87).
- **For the implementation:**
  - **#84** grants authorities from `user_role` at sign-in (only with MFA), replacing the in-memory bootstrap grant in
    `DiscordOAuth2UserService`, sets `MeResponse.mfaRequired` (now always `false` in `UserService`), and builds the
    roles and audit endpoints, the refused sign-in (rule 5) and the maintenance filter.
  - **#85** builds reporting, the queue, resolve, hide, unhide and the moderator cancel; adds
    `NOT hidden_by_moderation` to `VISIBLE` with the host exception, and the moderator exception of rule 8 for
    reported or hidden events; holds back a hidden event's
    changes, reminders and offers (rule 10); and fans `REPORT_RECEIVED` out in one statement, like `insertForActiveRsvps`.
  - **#86** builds the search, suspensions and bans. A ban's cancels and `LEFT`s take the event row locks in D-013's
    lock order.
  - **Role checks are serialized.** The `LAST_ADMIN` count and the change it guards run after
    `SELECT … FROM user_role WHERE role = 'ADMIN' FOR UPDATE` in the same transaction, so two admins removing each
    other at once can't leave none. A suspension or ban takes `SELECT … FROM user_role WHERE user_id = ? FOR UPDATE`
    (or locks the `app_user` row) before its `TARGET_HAS_ROLE` check, and a role change locks the same rows, so a
    grant can't slip in between.
  - **Every write path** writes its audit row through `AuditLogRepository.insert`, in the same transaction as its
    change, so a change that rolls back leaves no row. The one exception is `SIGN_IN_REFUSED`, which records no change:
    it commits on its own before the sign-in is refused, since the refusal rolls the login back. Nothing else writes
    `audit_log`, and M7's account deletion never touches it, the bans, or the reports' target IDs.
- **Consequences:** moderators can act without seeing anything a user hasn't made public, and attendees are told only
  when an event is really off. The audit log can't be edited from the app, even by a bug, and outlives every account it
  names. Reports and audit rows keep the IDs of deleted accounts and purged events, which name nobody once those are
  gone. Their free text (a report's note, a resolution note, a moderator's reason) is kept too, with no retention limit
  in the MVP, and may name people or places.
- **Revisit:** if the audit log or the report table gets slow without a retention limit (a measured p95), add one in a
  contract PR. If moderators need several reports on one target closed together, add a bulk resolve.
