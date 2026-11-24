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

## D-007 Code scanning on a private repo (2026-11-24) — Proposed
- **Context:** The repo is private in an org. CodeQL and GitHub secret scanning on private repos need paid
  GitHub Code/Secret Protection. The `gitleaks-action` needs a license for orgs.
- **Decision for now:** Dependabot (free), plus the gitleaks binary (pinned and checksum-verified) in CI.
- **Open:** The owner chooses between enabling GitHub Code Security, adding Semgrep CE (free) as a SAST step,
  or deferring SAST until the M7 security pass.

## D-008 Spring Boot 4.x, not 3.5 (2026-11-24) — Accepted
- **Context:** The plan said "upgrade to Boot 3.5". By now 3.5's open-source support has ended, and Dependabot is
  already proposing Boot 4.2, Gradle 9 and springdoc 3.
- **Decision:** M0 (#7) upgrades straight to Spring Boot 4.x (latest 4.2.x), with Gradle 9.x and springdoc 3.x,
  following the official migration guide. The `feature/add-discord-oauth` Boot 3.5.3 bump is dropped.
- **Consequences:** Jackson 3, Spring Framework 7, Jakarta EE 11 and some starter renames land before any domain
  code exists, which is the cheapest time to absorb them. Dependabot PRs #3–#6 close once #7 merges.
