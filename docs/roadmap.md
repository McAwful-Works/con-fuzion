# Roadmap

The coordinator session keeps this file up to date. GitHub Milestones and Issues are the source of truth;
this file is a one-glance summary. Issues for M2–M7 are written as each milestone approaches. Status values: `todo` · `in progress` · `in review` · `done`.

| Milestone | Work package | Status | Issue / PR |
|---|---|---|---|
| **Setup** | Dev workflow: CLAUDE.md files, agents, skills, hooks, docs, CI checks | done | McAwful-Works/con-fuzion#1 |
| | Repo public, `main`/release rulesets, CodeQL, trunk-based releases (D-007, D-009) | done | #14, #16, #20 |
| **M0** Foundation | Backend build tooling: wrapper jar, Spring Boot 4, Gradle 9, JDK 25 toolchain, Spotless, coverage gate, backend CI | done | #7 → #19 |
| | Backend runtime foundation: Flyway, Valkey sessions, per-user rate limits, safe error bodies, docker-compose | done | #8 → #21 |
| | Frontend scaffold: Vite + React + TS PWA, Tailwind, Vitest, lint, asset check, frontend CI | done | #9 → #15 |
| **M1** Discord auth | API contract for `/api/me` and `/api/me/guilds`, D-010 error envelope, bulk PUT | done | #10 → #25 |
| | Discord sign-in, user upsert, guild sync, CSRF, sessions (+ `open-in-view` off) | done | #11 → #28, #29 |
| | Sign-in and onboarding screens (+ failed-sign-in message, generated logout, case-insensitive sort) | done | #12 → #27, #30 |
| | Serve the built PWA from the backend jar | done | #13 → #24 |
| | Playwright end-to-end tests against the jar with a fake Discord | done | #31 → #32 |
| **M2** Social + events | friends (QR invites), circles, blocks · events CRUD + audiences + visibility + feed | todo | — |
| **M3** FCFS RSVP | atomic claim, waitlist, offers + expiry, `/spots` polling, My schedule | todo | — |
| **M4** Notifications | inbox, Web Push sender, reminders, PWA install flow | todo | — |
| **M5** Safety & admin | reports, admin area, roles + MFA gate, suspend/ban, audit log, maintenance mode | todo | — |
| **M6** AWS deploy | Terraform (VPC, Aurora Serverless v2, ElastiCache Serverless, ECS, ALB, CloudFront+WAF), CD, staging/prod, client IP behind CloudFront | todo | — |
| **M7** Polish + launch gates | accessibility, account deletion, donation links, artist assets, legal pages, load test, security pass | todo | — |
