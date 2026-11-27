# End-to-end tests

Playwright (Chromium only) drives the **built jar**: the Spring Boot backend with the PWA bundled in, served at one
origin. Discord is replaced by a small fake (`fake-discord.mjs`), because the real one has CAPTCHAs and 2FA and its
terms forbid scripting it. Nothing here touches the real Discord, and no credentials are involved.

## How it fits together

- `fake-discord.mjs` (Node built-ins only) answers the four calls the backend makes: authorize, token (it checks the
  PKCE S256 verifier and the client credentials), `/users/@me` and `/users/@me/guilds?limit=200`. Tests choose who
  signs in next through its `/__e2e/*` control endpoints (see `tests/support.mjs`).
- `application-e2e.properties` points the backend's `spring.security.oauth2.client.provider.discord.*` URIs at the
  fake and sets dummy client credentials. It is passed at startup with `--spring.config.additional-location` and
  lives **outside** `src/main/resources`, so a production jar can never point at the fake.
- `global-setup.mjs` starts the fake and the jar and waits for `/api/actuator/health`. `global-teardown.mjs` stops
  both, pass or fail. It refuses to start if the ports are already taken, so it can't test someone else's server.
- Each test signs in as its **own** Discord user (a fresh ID per test), so no test depends on another and no table
  needs truncating. The fake's state is reset before every test.
- `tests/social.spec.mjs` (M2) needs several signed-in users at once: the `join` fixture (`tests/hooks.mjs`) signs each
  in through the real UI in its own browser context, with its own guild list. Give two users guilds with the same
  IDs and they share a server. Friends, circles and events are made through the UI too (`tests/social.mjs`), and a
  hidden event is asserted both in the feed and as a 404 from `/api/events/{id}`.
- `tests/rsvp.spec.mjs` (M3) uses the same fixture for joining, the waitlist, removal, capacity edits, signup gating,
  the `CONFIRMED` location and My schedule (helpers in `tests/rsvp.mjs`). The spots counts can lag a join by up to
  a second (the backend's counts cache), so counts are read with `expectSpots`, which reloads until they match.
  The create form only takes whole minutes, so the signup-gating test creates its event through the API with the
  CSRF header (`apiWrite`).
- `tests/offers.spec.mjs` (M4) covers offers (accept, decline, expiry), the inbox with its unread badge, and the push
  subscribe call.
  - **Expiry:** an offer's window can't be shorter than 10 minutes (a database CHECK), so the test pushes the open
    offer's deadline into the past with `tests/db.mjs` and relies on the expiry job, which
    `application-e2e.properties` runs every 2 seconds, to offer the spot to the next person. `db.mjs` runs the `psql`
    CLI with the local-only credentials (the `CONFUZION_DB_*` variables, defaulting to the compose file's). With no
    `psql` on the PATH it uses the one inside the compose Postgres container.
  - **Push:** `global-setup.mjs` makes a throwaway VAPID key pair for each run and gives it to the jar in its
    environment, so push is on (`/api/push/key` answers) and no key is ever written to disk. Headless Chromium has no
    push service, so the test replaces the browser's `subscribe` with one that returns a fake subscription on
    `fcm.googleapis.com`. It covers the subscribe call, not delivery. The sender's interval is an hour, so it never runs.
- `tests/moderation.spec.mjs` (M5) covers the MFA gate, role grants, a report from the event page through the queue,
  hiding and resolving, suspensions and bans (helpers in `tests/admin.mjs`).
  - **Roles:** `application-e2e.properties` names one bootstrap admin. Tests sign in as it and grant every other role
    through the app, which signs that user out. Unlike every other user it is the same account in each test and run, so
    no test changes it.
  - **2FA:** the fake reports `mfa_enabled` from the scenario's user, false unless set (`newUser`'s `mfaEnabled`, or
    `signInAgain`'s). The backend reads it at sign-in only.
- `tests/event-page.spec.mjs` (M5.5) checks the event page's layout, which unit tests can't see: the action bar at
  the bottom of a phone screen, and the side card on desktop.
- `tests/host-steps.spec.mjs` (M5.5) hosts a meetup through the phone's five steps. `createEvent` fills the
  desktop page, so this is the one that covers a phone.
- `tests/maintenance.spec.mjs` (M5) starts a second jar with `con-fuzion.maintenance=true` (the flag is read at
  startup), checks it and stops it. Its log is `e2e/.run/maintenance/backend.log`, attached to the report on failure.

## Run it locally

1. Start Postgres and Valkey (leave them running afterwards, they are shared with development):

   ```powershell
   docker compose -f infra/docker-compose.yml up -d
   ```

2. Build the jar. It must run on **Java 25**. If the system Java is older (`java -version`), point `JAVA_HOME` at
   Gradle's toolchain JDK for the build and the tests:

   ```powershell
   $env:JAVA_HOME = "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2"   # adjust the folder name
   cd backend; .\gradlew.bat bootJar; cd ..
   ```

   On macOS or Linux: `cd backend && ./gradlew bootJar`.

3. Install and run (the first time only, `npm ci` and `npx playwright install chromium`):

   ```powershell
   cd e2e
   npm ci; npx playwright install chromium
   npm test
   ```

After a frontend or backend change, repeat step 2 first: the tests run the jar, not the source.

## Settings

| Variable               | Default                             | Meaning                                                               |
| ---------------------- | ----------------------------------- | --------------------------------------------------------------------- |
| `E2E_APP_PORT`         | `8089`                              | The jar's port. Not 8080, so a jar you started by hand keeps running. |
| `E2E_MAINTENANCE_PORT` | `8090`                              | The maintenance test's second jar.                                    |
| `E2E_STUB_PORT`        | `4010`                              | The fake Discord's port.                                              |
| `E2E_JAVA`             | `JAVA_HOME`, then `java`            | The Java 25 launcher.                                                 |
| `E2E_JAR`              | the one jar in `backend/build/libs` | A specific jar.                                                       |

The backend uses the same `CONFUZION_DB_*` and `CONFUZION_REDIS_*` variables as ever (defaults match the compose
file). The suite creates users named `e2e-…` in that database and leaves them there. Sessions end up in Valkey.

## When a test fails

`npx playwright show-report` opens the HTML report; failed tests keep a trace (`npx playwright show-trace <trace.zip>`).
The backend's log for the run is `e2e/.run/backend.log`. CI uploads the report, traces and that log, on failure only.
