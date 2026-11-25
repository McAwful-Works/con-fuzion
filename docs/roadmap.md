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
| **M2** Social + events | API contracts: social graph (D-011) and events (D-012) | done | #34 → #41, #35 → #42 |
| | Backend social: invite links, people search, circles, block cutoff (+ blocks keep other invites) | done | #36 → #47, #49 |
| | Backend events: create/edit/cancel, the one visibility rule, feed | done | #37 → #50 |
| | Frontend social: friends, QR invites, invite accept page, circles, blocking | done | #38 → #45 |
| | Frontend events: feed + Hosting, create/edit with audience picker, event detail | done | #39 → #48 |
| | End-to-end tests for M2 flows (block break check) | done | #40 → #51 |
| | Fixes found along the way: e2e rate limits; session-ID race on sign-in (500s for up to 14 days) | done | #43, #44 → #46 |
| **M3** FCFS RSVP | API contract: joining, the waitlist, attendees, spots, My schedule (D-013) | done | #53 → #57 |
| | Backend: atomic claim, waitlist, auto-promotion, block cutoff, capacity edits, My schedule, `/spots`, join limit (split in three) | done | #54 → #59, #60, #61 |
| | Frontend: join/leave, waitlist, live spots, host attendee list, My schedule | done | #55 → #58 |
| | End-to-end tests for M3 flows (capacity-guard break check) | done | #56 → #62 |
| **M4** Notifications | API contract: offers, the inbox, push subscriptions (D-014) | done | #64 → #71 |
| | Backend offers: a deadline, accept and decline, the expiry job, capacity raises send offers | done | #65 → #76 |
| | Backend inbox: event-change and cancel triggers, friend-accepted, 1-hour reminders, block cleanup | done | #66 → #75 |
| | Backend Web Push: VAPID keys and subscriptions, then the sender (split in two) | done | #67 → #79, #80 |
| | Frontend: offers with a countdown and the offer window, then the inbox and unread badge (split in two) | done | #68 → #73, #74 |
| | Frontend: push opt-in and service worker push handler, then the install hint (split in two) | done | #69 → #77, #78 |
| | End-to-end tests for M4 flows (expiry-job break check) | done | #70 → #81 |
| **M5** Safety & admin | reports, admin area, roles + MFA gate, suspend/ban, audit log, maintenance mode | todo | — |
| **M6** AWS deploy | Terraform (VPC, Aurora Serverless v2, ElastiCache Serverless, ECS, ALB, CloudFront+WAF), CD, staging/prod, client IP behind CloudFront | todo | — |
| **M7** Polish + launch gates | accessibility, account deletion, donation links, artist assets (including the 192px and 512px manifest icons, without which browsers never offer #78's Install button), legal pages, load test, security pass, a manual push check on staging (a real iPhone from the Home Screen, plus Chrome and Firefox, with the production VAPID keys) | todo | — |
