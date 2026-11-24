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
