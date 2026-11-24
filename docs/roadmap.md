# Roadmap

The coordinator session keeps this file up to date. GitHub Milestones and Issues are the source of truth;
this file is a one-glance summary. Status values: `todo` · `in progress` · `in review` · `done`.

| Milestone | Work package | Status | Issue / PR |
|---|---|---|---|
| **Setup** | Dev workflow: CLAUDE.md files, agents, skills, hooks, docs, CI checks | in review | — |
| **M0** Foundation | backend-foundation: commit the missing Gradle wrapper jar, Boot 3.5 (bring over the `feature/add-discord-oauth` build.gradle bump), foojay/JDK 21, `-PexcludeTags` + jacoco wired into `check`, Spotless, Flyway, Testcontainers base, per-user Valkey rate limiter, Spring Session Redis, generic error bodies, Micrometer, backend CI | todo | — |
| | frontend-scaffold: Vite+React+TS PWA, Tailwind, Vitest, Prettier/ESLint, `/api` proxy, SPA bundled into the jar, `ASSETS.md` + asset check, `infra/docker-compose.yml` | todo | — |
| **M1** Discord auth | contract: `/api/me`, `/api/me/guilds` DTOs + stubs + migration + `openapi.json` | todo | — |
| | backend: OAuth config fix, SecurityConfig, user upsert, guild sync, CSRF, sessions | todo | — |
| | frontend: landing, sign in, onboarding (discoverable servers) | todo | — |
| **M2** Social + events | friends (QR invites), circles, blocks · events CRUD + audiences + visibility + feed | todo | — |
| **M3** FCFS RSVP | atomic claim, waitlist, offers + expiry, `/spots` polling, My schedule | todo | — |
| **M4** Notifications | inbox, Web Push sender, reminders, PWA install flow | todo | — |
| **M5** Safety & admin | reports, admin area, roles + MFA gate, suspend/ban, audit log, maintenance mode | todo | — |
| **M6** AWS deploy | Terraform (VPC, Aurora Serverless v2, ElastiCache Serverless, ECS, ALB, CloudFront+WAF), CD, staging/prod | todo | — |
| **M7** Polish + launch gates | accessibility, account deletion, donation links, artist assets, legal pages, load test, security pass | todo | — |
