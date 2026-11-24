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

| Variable        | Default                             | Meaning                                                               |
| --------------- | ----------------------------------- | --------------------------------------------------------------------- |
| `E2E_APP_PORT`  | `8089`                              | The jar's port. Not 8080, so a jar you started by hand keeps running. |
| `E2E_STUB_PORT` | `4010`                              | The fake Discord's port.                                              |
| `E2E_JAVA`      | `JAVA_HOME`, then `java`            | The Java 25 launcher.                                                 |
| `E2E_JAR`       | the one jar in `backend/build/libs` | A specific jar.                                                       |

The backend uses the same `CONFUZION_DB_*` and `CONFUZION_REDIS_*` variables as ever (defaults match the compose
file). The suite creates users named `e2e-…` in that database and leaves them there. Sessions end up in Valkey.

## When a test fails

`npx playwright show-report` opens the HTML report; failed tests keep a trace (`npx playwright show-trace <trace.zip>`).
The backend's log for the run is `e2e/.run/backend.log`. CI uploads the report, traces and that log, on failure only.
