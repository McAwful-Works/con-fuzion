# Backlog (not in MVP)

Ideas live here or in GitHub issues labelled `idea`, never in a feature PR. Moving an item into scope needs the
owner's OK, and if it adds infrastructure, an entry in `docs/decisions.md`.

| Idea | Notes |
|---|---|
| Discord bot: role-based audiences, DM notifications, `/confuzion` slash command, channel announcements | Needs the Server Members intent, and verification past 100 servers |
| Live updates via SSE/WebSockets | Only if adaptive polling proves inadequate (measured) |
| Read replicas, RDS Proxy, multi-region | Triggers are in D-004 |
| Co-hosts, plus-ones | |
| Event chat / comments | Adds moderation load |
| `.ics` calendar export | Small, a good first post-MVP item |
| Admin support lookups, ops dashboard | Use CloudWatch until then |
| Privacy-respecting analytics | |
| Convention directory / convention entity | Rejected for the MVP (events are standalone) |
| Native apps, i18n | |
| 30-day absolute session timeout | `docs/security.md` lists it; #11 ships the 14-day idle timeout only. Needs a session-creation timestamp check |
| Shorter lifetime for abandoned sign-in sessions (M5) | An unfinished sign-in (cancelled or never completed) leaves an anonymous session holding only the OAuth authorization request for the full 14-day timeout. Harmless, but wasteful, and a cheap way to fill Valkey; give those sessions a short TTL |
| `logout-everywhere` access log shows `-> 200 (user anonymous)` | The response is a 204, which the tests check, but `LoggerAspect` reads the status and user after the logout handler has cleared them. Capture the user ID before `proceed()`, and return a `ResponseEntity` so the status is logged correctly |
| Separate local database for e2e runs | Local `e2e` runs add `e2e-…` users to the dev `confuzion` database. Point the suite at `confuzion_e2e` (create it in docker-compose's init) |
| Clean the session principal index without keyspace events | #11 sets `configure-action=none` because ElastiCache refuses `CONFIG`. Expired session IDs accumulate in the per-user index set (harmless, since lookups skip them) |
| Tighten `Permissions-Policy` to `camera=()` | D-011 dropped the in-app QR scanner (invite links open in the phone camera app), so the app no longer needs the camera. Touches `SecurityConfig`, `docs/security.md` and plan B11 |
| List and revoke friend invites | D-011 has no endpoint for them. Lets a "My QR" screen show whether its code was used, and lets a user clear the 20-invite cap early |
| Purge used and expired `friend_invite` rows | They're never deleted, so who-invited-whom is kept forever. A `@Scheduled` delete after a grace period |
| Tighten `GuildResponse.SNOWFLAKE_PATTERN` to `^[1-9][0-9]{16,18}$` | It accepts leading zeros (two strings for one ID, so the uniqueness check misses a duplicate) and 19-digit values above the `bigint` range. Shared with M1's `/api/me/guilds` |
| Signed-out session hash can linger for the full timeout | Sign-out marks the session expired and keeps the hash for 5 minutes. A request still in flight then saves its last-accessed time, which resets the hash's TTL to 14 days. It reads as expired, so it can't be used (#44 tests this), but its attributes stay in Valkey longer than they need to |
| Cap circles per user and members per circle | #36 has no cap, so one account can create unbounded `circle` and `circle_member` rows. Add `Limit` values like `INVITE_LIMIT` |
| Covering indexes for the feed's audience keys | #37's EXPLAIN (200k events) shows the GUILD branch seq-scanning `event_audience` (~10 ms of ~50 ms). `(guild_id) INCLUDE (event_id)`, and the same for `circle_id` and `user_id`, would make those branches index-only. Add only if the M7 load test misses its target (D-012) |
| Page `GET /api/events/{eventId}/attendees` | #53 returns the whole list in one body: up to 10,000 people on a capped event, unbounded on an unlimited one. Add a cursor if a host's list is measurably slow or large (M7 load test) |
| Let a host undo a removal | D-013: a removed person can't rejoin and there's no undo. Add one if hosts ask |
| Remove a waitlisted person, and cache the Hosting tab offline | #55 puts Remove on confirmed attendees only and caches `scope=attending` only (the host holds a spot there, so their own events are included). The API already lets a host remove a waitlisted person, and `scope=hosting` could join the `NetworkFirst` rule |
| Resubscribe after a VAPID key rotation | When push is on, the client compares the existing subscription's `applicationServerKey` with `/api/push/key`, and on a mismatch unsubscribes, resubscribes with the new key and POSTs it. Until then a rotation leaves existing subscribers silent until they toggle push |
| Drop stale pushes | #67 sends every `PENDING` row however old, so after push is off for a while (no VAPID keys, or a long 429 pause) a backlog can push "starts in about an hour" for events already over. Mark rows older than the push TTL (6h) `FAILED` instead; the inbox keeps them |
| Skip rows for a paused push service | #67 keeps a 429 pause in memory per task. Held-back rows are claimed and put back on every run (two updates per row every 5 s), and other tasks don't know the pause. If that shows up in the M7 load test or in table bloat, add a `push_not_before` column, or share the pause in Valkey |
| Retry transient push failures, and mark offers urgent | #67 ends a row `FAILED` on a timeout, an IO error or a 5xx, so one push service outage drops whole batches (the inbox keeps them). Retrying needs an attempts column. An `Urgency: high` header on `SPOT_OFFERED` would also help phones in doze reach a 10-minute offer in time |
| Keep moderators off their own events | #85 hides reports about a moderator, or their events, from their queue, but they can still hide, unhide or cancel their own event through `/api/admin/events`. Audit-logged; refuse it (409) if it's ever abused |
| Keep moderators from lifting a moderator's suspension | D-015 rule 4 limits who can be suspended but not who can unsuspend, and the contract gives `unsuspend` no 409. A moderator can lift a suspension an admin gave another moderator. Audit-logged; add `TARGET_HAS_ROLE` to unsuspend if it's ever abused |
| Open-report count on the Moderation tab and sidebar link | The moderation mockup shows one (#123). The queue pages by cursor and gives no total, so it needs an API change |
| Suspend a host, or lift a suspension, from the admin area | The moderation mockup puts "Suspend host" beside an event report (#123), but the report holds only the event ID, so today a moderator looks the host up. There is no button for `unsuspend` either |
| Keep a moderation result visible after switching reports | On desktop another report is one click away (#123). Switching while a hide, cancel or suspend is still being sent drops its form, so neither its success nor its error is shown. The report stays open in the queue |
| Tell attendees what changed while an event was hidden | D-015 rule 10 holds back `EVENT_CHANGED` while an event is hidden, and unhiding notifies nobody. So a time or place the host changed meanwhile is never announced, and an offer that ran out while hidden is lost. On unhide, send one `EVENT_CHANGED` for the net change, if the owner wants it |
| Dependabot updates for the `Dockerfile`'s base images | #102 pins them by digest, so JDK, JRE and Node patches arrive only when someone moves a digest. Add the `docker` ecosystem to `dependabot.yml` (plan B11 lists it) |
| Console-only logging in the container (#106) | `logback.xml` also writes rolling files under `./logs`, which are useless on Fargate, keep up to 3 GB per appender and rule out a read-only root filesystem. Its two `EvaluatorFilter`s have also warned at startup since logback 1.5.13 removed the Janino evaluator |
| Pin the other workflows' Actions by SHA (#107) | `image.yml` does; the older workflows pin by tag |
| Check out `backend/gradlew` with LF on Windows | The `.gitattributes` rule `/gradlew` matches only the repo root, so the script gets CRLF there. The `Dockerfile` strips it |
| Re-check a suspension or ban when a sign-in saves its session | #84 checks before the upsert, and the upsert's row lock ends at its commit, before the session is saved in Valkey. A suspension or ban that waited on that lock can purge before the new session exists, which then lives until it times out. Re-check under the upsert lock, or when the session is saved. Until then a repeat ban is a no-op, so an admin who sees it can't re-purge by banning again; unban and ban again |
| Email high-severity GuardDuty findings | #103 turns GuardDuty on, but findings only show in its console. An EventBridge rule (severity 7 or above) to the `confuzion-alarms` topic would email them |
| Clearer startup error for a bad bootstrap admin ID | Local testing (2026-11-26): a username in `CONFUZION_ADMIN_BOOTSTRAP_DISCORD_IDS` stops startup with a bare `NumberFormatException`. Failing fast is right; name the setting and the expected format in the message |
| Log the original path when a request is refused | Error-dispatch log lines read `GET /error -> 401 (user anonymous)`, so you can't tell which request was refused. Log the original request's route pattern (as `LoggerAspect` does), never the raw URI: invite tokens are path segments, and D-011 keeps them out of logs |
| Going count on unlimited event cards | The mockup's "14 going · no limit" needs a confirmed count on `EventResponse` (it's already public through `/spots`); add it if hosts or guests ask |
| Apply an appearance change to other open tabs | #112 applies it in the tab where it was chosen; others follow on reload. A `storage` listener in `public/appearance.js` would do it |
| Drop every cached query when a session expires | Sign-out calls `queryClient.clear()`; a 401 forgets only `/api/me`, so events, attendee lists and notifications stay in the tab's memory until a reload. No signed-out screen shows them, but if someone else signs in from another tab, they could show briefly before refetching |
| Send an expired session to sign-in from the invite page | The invite page is outside the auth gate and only watches the preview's 401. A session that ends before **Accept** is tapped shows a generic error instead of the sign-in page (found by reading the code, not reproduced) |
| Land "Change who can see it" on the audience part of the edit form | The event page (#118) sends it and "Edit event" to the same form. #120 rebuilds that form in steps, so it can open the audience step |
