# Roadmap

The coordinator session keeps this file up to date. GitHub Milestones and Issues are the source of truth;
this file is a one-glance summary. Issues for M2–M7 are written as each milestone approaches. Status values: `todo` · `in progress` · `in review` · `done`.

| Milestone | Work package | Status | Issue / PR |
|---|---|---|---|
| **Setup** | Dev workflow: CLAUDE.md files, agents, skills, hooks, docs, CI checks | done | McAwful-Works/con-fuzion#1 |
| **M0** Foundation | Backend build tooling: wrapper jar, Spring Boot 4, Gradle 9, JDK 21 toolchain, Spotless, coverage gate, backend CI | todo | #7 |
| | Backend runtime foundation: Flyway, Valkey sessions, per-user rate limits, safe error bodies, docker-compose (after #7) | todo | #8 |
| | Frontend scaffold: Vite + React + TS PWA, Tailwind, Vitest, lint, asset check, frontend CI | in progress | #9 |
| **M1** Discord auth | API contract for `/api/me` and `/api/me/guilds` (after #8) | todo | #10 |
| | Discord sign-in, user upsert, guild sync, CSRF, sessions (after #10) | todo | #11 |
| | Sign-in and onboarding screens (after #9 and #10) | todo | #12 |
| | Serve the built PWA from the backend jar (after #7 and #9) | todo | #13 |
| **M2** Social + events | friends (QR invites), circles, blocks · events CRUD + audiences + visibility + feed | todo | — |
| **M3** FCFS RSVP | atomic claim, waitlist, offers + expiry, `/spots` polling, My schedule | todo | — |
| **M4** Notifications | inbox, Web Push sender, reminders, PWA install flow | todo | — |
| **M5** Safety & admin | reports, admin area, roles + MFA gate, suspend/ban, audit log, maintenance mode | todo | — |
| **M6** AWS deploy | Terraform (VPC, Aurora Serverless v2, ElastiCache Serverless, ECS, ALB, CloudFront+WAF), CD, staging/prod | todo | — |
| **M7** Polish + launch gates | accessibility, account deletion, donation links, artist assets, legal pages, load test, security pass | todo | — |
