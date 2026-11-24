# Security model & per-PR checklist

Summary of the plan's section B11 (security) and B12 (admin). Reviewers (the `reviewer` agent and
`/security-review`) check diffs against the checklist at the bottom. PRs touching any of the areas below get the
`security` label.

## What we protect
| Asset | Why it matters |
|---|---|
| Sessions and accounts | Account takeover lets someone host or join meetups as another person |
| Server membership | Revealing which Discord servers someone is in can out them, so it's used only when the user marks a server discoverable |
| **Where and when people will be** | These are real in-person meetups, so location can be limited to confirmed attendees |
| Attendee and waitlist lists | Visible only to the host and confirmed attendees. Waitlist position is visible only to that user |
| Admin powers | Can hide events and ban users, so they're MFA-gated and audit-logged |
| Secrets | Discord client secret and VAPID private key |

## Threats → controls
| Threat | Control |
|---|---|
| Session theft or fixation | `HttpOnly; Secure; SameSite=Lax` cookie. Session ID rotated on login. Sessions in Valkey with a 14-day idle and 30-day absolute timeout. "Log out everywhere" |
| OAuth CSRF / code interception | Spring `state` + PKCE. Exact redirect URI |
| CSRF | `CookieCsrfTokenRepository` on all mutations |
| XSS | React escaping only. User text is plain text: no HTML or Markdown rendering, no `dangerouslySetInnerHTML`. Strict CSP |
| IDOR / broken authorization | Deny by default. Explicit checks in every service method. Public UUIDs. 404 for invisible resources. Authorization matrix test covering every endpoint × 7 actor types |
| Information leakage | Generic error bodies with a correlation ID. No exception text in responses. No bodies, cookies, tokens, or emails in logs |
| Spam / abuse / floods | Per-user and per-IP rate limits (Valkey). WAF rate rules. Caps (20 events per host per day, 10 reports per user per day). Single-use invites that expire after 24h. Blocks, reports, suspensions, bans |
| Harassment at meetups | Report → moderator queue. Block. Location hidden until confirmed. Hidden events. Bans by Discord ID that survive account deletion |
| Admin compromise | MODERATOR/ADMIN authority only when Discord `mfa_enabled=true`. Role changes purge sessions. Insert-only `audit_log`. The last ADMIN can't be removed |
| Secret leakage | Secrets Manager → ECS. Hooks block writing or printing secret files. gitleaks in CI |
| Vulnerable dependencies | Dependabot. The dependency gate keeps the dependency surface small |
| Infrastructure exposure | Private subnets for tasks, database, and cache. Least-privilege security groups. TLS everywhere. KMS encryption at rest. OIDC deploy role (no static AWS keys). CloudTrail + GuardDuty |

## Required HTTP headers
```
Content-Security-Policy: default-src 'self'; img-src 'self' https://cdn.discordapp.com; connect-src 'self';
                         object-src 'none'; base-uri 'self'; frame-ancestors 'none'
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(self), geolocation=(), microphone=()
```

## Data minimization & retention
- OAuth scopes are `identify guilds` only. No email. Discord tokens aren't stored after the login sync.
- Ended events and their RSVPs are purged after 90 days. Account deletion removes the user and their RSVPs and
  cancels their hosted events. The ban record (Discord ID) is kept.

## Per-PR security checklist
- [ ] Every new endpoint requires authentication, unless it's explicitly public (only health and `/api/meta/support`).
- [ ] Every service method checks ownership or visibility. Invisible resources return 404.
- [ ] `/api/admin/**` checks roles on the server, and every admin action writes `audit_log`.
- [ ] DTOs validated (lengths, enums, ranges). No string-concatenated SQL.
- [ ] No exception or root-cause text in responses. No bodies, tokens, cookies, or emails logged.
- [ ] No user-controlled HTML rendering. CSP is unchanged or tightened.
- [ ] Discord server membership is used only where `discoverable = true`.
- [ ] Location and attendee data respect `location_visibility` and the attendee rules.
- [ ] No secrets in code, config, tests, or fixtures. New config values come from env or Secrets Manager.
- [ ] New dependencies are justified in the PR body.
- [ ] Authorization matrix test updated for new endpoints.
