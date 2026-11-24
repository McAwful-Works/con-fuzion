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
| Clean the session principal index without keyspace events | #11 sets `configure-action=none` because ElastiCache refuses `CONFIG`. Expired session IDs accumulate in the per-user index set (harmless, since lookups skip them) |
