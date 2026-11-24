# Backend — Spring Boot 4.x (after #7) / Java 25 (after #7)

Read the root `CLAUDE.md` first. Design reference: `docs/plan.md` sections B2–B6, B11, B12.

## Structure (`app.confuzion.backend`)
```
aspect/       cross-cutting concerns: GlobalControllerExceptionHandler, LoggerAspect, RateLimiterAspect
config/       Spring configuration (SecurityConfig, etc.)
controller/   thin REST controllers. No logic beyond mapping
exception/    domain exceptions, mapped to HTTP only in GlobalControllerExceptionHandler
model/entity/ JPA entities
model/record/ DTOs and response bodies (records)
repository/   Spring Data repositories, plus native SQL where needed (FCFS claim, visibility)
service/      business logic and **authorization checks**
util/         stateless helpers
```

## Code rules (match the existing code)
- **Tabs** for indentation.
- **MIT license header** on every Java file. Copy it verbatim from `aspect/RateLimiterAspect.java`.
- **Javadoc on every class, method, and field**, and every class has `@author`.
- Lombok's `@Generated` excludes trivial code from coverage (`lombok.config` already adds it to Lombok output).
  Don't use it to hide real logic.
- Controllers return the `ResponseRecord` envelope. Register each new body type in `ResponseRecord.BODY_TO_TYPE_MAP`
  and add a matching `ResponseType` value.
- Throw domain exceptions from services. Map them to statuses **only** in `GlobalControllerExceptionHandler`.
  **Never put `e.getMessage()` or root-cause text in a response.** Send a generic message plus a correlation ID,
  and log the details server-side.
- Every service method that touches an event, RSVP, circle, report, or user checks authorization explicitly.
  Return 404 (not 403) for resources the caller isn't allowed to know exist.
- Validate every request DTO with Bean Validation.
- The client IP comes from `request.getRemoteAddr()`, which `forward-headers-strategy=native` resolves behind
  the ALB. Tomcat's RemoteIpValve reads `X-Forwarded-For` right to left, past trusted proxies only. Don't switch to
  `framework`: Spring's `ForwardedHeaderFilter` takes the left-most value, which the client controls. Don't read
  `X-Forwarded-For` directly either, because clients can spoof it.
- Logging: metadata only (method, status, latency, user ID). Never log bodies, cookies, tokens, or emails.

## Database
- Flyway migrations live in `src/main/resources/db/migration/V<yyyyMMddHHmm>__<snake_desc>.sql`. Timestamped
  versions keep parallel branches from colliding. **Never edit a migration that's on `main`**; add a new one instead.
- Postgres is the system of record, and Valkey holds only sessions and rate limits.
- The FCFS claim is a single atomic `UPDATE ... WHERE confirmed_count < capacity RETURNING`. Keep that
  transaction to just the claim and the RSVP insert (plan B4).
- Background jobs are `@Scheduled` and claim work with `FOR UPDATE SKIP LOCKED`, so running several tasks is safe.

## Tests
- Name test classes `<Class>Test`, mirroring the main package.
- Unit tests use plain JUnit + Mockito. Tag integration tests (Testcontainers Postgres/Valkey) with
  `@Tag("integration")`. The Stop hook skips them, and CI and `pr-ready` run them.
- Coverage gate: jacoco at 80% or more. M0 wires `jacocoTestCoverageVerification` into `check` and makes `test`
  honor `-PexcludeTags`. Until then the flag is ignored and coverage isn't enforced.
- Must-pass suites (plan, Verification section): RSVP concurrency, visibility truth table, authorization matrix,
  admin/ban/MFA, and generic error bodies.

## Commands
```
./gradlew test                       # all tests
./gradlew test -PexcludeTags=integration
./gradlew check                      # tests + coverage verification
./gradlew bootRun                    # needs local Postgres/Valkey (infra/docker-compose.yml)
./gradlew bootJar                    # builds the PWA with npm (Node 24) and bundles it at / in the jar
./gradlew bootJar -PskipFrontend     # API-only jar; test and check never need Node
```
On Windows use `gradlew.bat`.
