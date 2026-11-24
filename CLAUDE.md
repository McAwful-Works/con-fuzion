# Con-Fuzion

Free mobile + desktop web app for convention-goers to host meetups and open them to audiences the host picks
(shared Discord servers, in-app friends, circles, individuals) with limited first-come-first-served spots and an
auto-promoting waitlist. Discord is the only login.

- Product & technical design: [docs/plan.md](docs/plan.md) (the MVP fence is section B1)
- Status: [docs/roadmap.md](docs/roadmap.md) · decisions: [docs/decisions.md](docs/decisions.md) · ideas: [docs/backlog.md](docs/backlog.md)
- Security model and per-PR checklist: [docs/security.md](docs/security.md)

## Layout
```
backend/    Spring Boot 4.x and Java 25 (after #7), Gradle. Serves the REST API at /api and the built SPA at /   → backend/CLAUDE.md
frontend/   React + Vite + TypeScript PWA                                                      → frontend/CLAUDE.md
infra/      Terraform (AWS) and docker-compose for local Postgres + Valkey (added in M0/M6)
docs/       plan, roadmap, decisions, security, runbooks, research
```

## How work happens
- **Coordinator session** plans, writes issues and session briefs, and verifies PRs. It doesn't write feature code.
- **Work session**: exactly one GitHub issue, one branch, one PR. Start with the `start-issue` skill and finish with `pr-ready`.
- **Research session**: answers one bounded question in a `docs/research/<topic>.md` PR. No code.
- A human merges every PR. **Claude never merges**, never commits to `main`, and never runs `terraform apply`.
- **Stop what you start** (every session). Before reporting done, stop every dev server, watcher, `bootRun` or
  other long-running process you started (`npm run dev`, `vite`, `vite preview`, `gradlew bootRun`, `java -jar`).
  Stop `docker compose` services too, unless the owner started them. Say in the PR which ones you ran and stopped.
  Never stop a process you didn't start.
- **Clean up after every merge** (coordinator):
  - Fast-forward local `main` (`git pull --ff-only`).
  - Before archiving a session, check that nothing is still running from its worktree (for example a listener on
    `:5173` or `:8080`). If something is, ask the owner before stopping it.
  - Delete the merged local branch and any `claude/*` placeholder branch.
  - Confirm the remote branch was auto-deleted.
  - Archive the finished work session, which removes its worktree, with the owner's OK.
  - Update `docs/roadmap.md` in the next docs PR.
  - Never delete a branch, worktree or session that has unmerged commits or uncommitted changes.

## Golden rules
1. **Stay inside the issue.** Build only what the acceptance criteria need. Anything else, however good, goes in
   `docs/backlog.md` or a GitHub issue labelled `idea`, never in the current PR.
2. **Keep the footprint small (YAGNI):**
   - No interface that has only one implementation.
   - No generic framework or base class for a single use.
   - No config option for a hypothetical need.
   - No caching or async without a measured problem.
   - Prefer the JDK/Spring/React built-in over a library. Prefer deleting code over adding it.
3. **No new dependency or infrastructure without asking.** Dependency file edits prompt the user. A new service
   (queue, search, SSE, replica, and so on) needs an entry in `docs/decisions.md` that names a measured trigger.
4. **PR budget**: about 400 changed lines or fewer, excluding tests and generated files. Split anything larger.
5. **No AI-generated art.** Never create images, logos, icons, illustrations, or decorative SVGs. Use the `lucide-react`
   icons, `@fontsource` OFL fonts, the official Discord logo per Discord's brand guidelines, Discord CDN avatars, and
   `placeholder-*` boxes. Every non-code asset is listed in `frontend/ASSETS.md`. Original art comes from the hired artist.
6. **Security is part of done.** Deny-by-default authorization, return 404 rather than 403 for things the viewer can't
   see, never send exception text to clients, and never log tokens, cookies, or request/response bodies. Read
   `docs/security.md` before touching auth, sessions, visibility, admin, reports, secrets, or headers, and label
   those PRs `security`.
7. **Privacy by default.** A user's Discord servers are used only when they mark them discoverable. Don't store
   Discord tokens or email. Attendee lists go to the host and confirmed attendees only.
8. **Donations stay passive.** Links appear only on the About/Support page and as a footer link. No prompts or perks.

## Discord facts that shape the design
- Third-party apps **cannot** read friends lists. `relationships.read` needs Discord approval, so don't use it.
- A user OAuth token **cannot** list a server's members. `guilds.members.read` returns only the user's own member record.
- Scopes we use: `identify guilds`. Verify any other Discord claim with the `discord-api-expert` agent.

## Conventions
- **Commits and PR titles** follow Conventional Commits: `type(scope): summary`.
  - Types: `feat fix refactor test docs chore ci build perf`.
  - Scopes (optional): `auth events rsvp social notify admin web infra deps deps-dev docs ci`.
  - PRs are squash-merged, so the PR title becomes the commit.
- **Branches**: trunk-based. Short-lived `feat/<scope>-<slug>`, `fix/…`, `chore/…`, `docs/…`, `research/…` off `main`.
  Releases are `v*` tags that only the owner creates. See [docs/releasing.md](docs/releasing.md). Claude never tags,
  releases, or pushes to `release/*`.
- **PR body**: fill in `.github/pull_request_template.md` and close the issue with `Closes #<n>`.

## Commands
| What | Command |
|---|---|
| Backend tests | `cd backend && ./gradlew test` (Windows: `gradlew.bat test`) |
| Backend full check | `cd backend && ./gradlew check` |
| Export the OpenAPI contract | `cd backend && ./gradlew test --tests '*OpenApiConfigIntegrationTest'` → `docs/api/openapi.json` |
| Frontend (after M0) | `cd frontend && npm run check` |
| Local services (after M0) | `docker compose -f infra/docker-compose.yml up -d` |

## Hooks (`.claude/settings.json`)
These run automatically. Don't work around them; if one is wrong, say so.
- `guard-writes`: blocks secret files and image assets, and asks before dependency-file edits.
- `guard-bash`: blocks commit/push on `main`, pushes to `release/*`, tags and releases, force-push, `gh pr merge`, `terraform apply/destroy`, and printing `.env` files. Asks before adding packages.
- `format`: Prettier on edited frontend files, once `frontend/node_modules` exists. Java is formatted by Spotless at Stop.
- `license-check`: flags Java files that are missing the MIT header or `@author`.
- `stop-checks`: runs Spotless and unit tests (backend) and lint and tests (frontend) when code changed.
