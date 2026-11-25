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
| Spam / abuse / floods | Per-user and per-IP rate limits (Valkey). The client IP comes from Tomcat's RemoteIpValve, never raw `X-Forwarded-For`; M6 must make CloudFront a trusted hop (plan B10). WAF rate rules. Caps (20 events per host per day, 10 reports per user per day). Single-use invites that expire after 24h. Blocks, reports, suspensions, bans |
| Harassment at meetups | Report → moderator queue. Block. Location hidden until confirmed. Hidden events. Bans by Discord ID that survive account deletion |
| Admin compromise | MODERATOR/ADMIN authority only when Discord `mfa_enabled=true`. Role changes purge sessions. Insert-only `audit_log`. The last ADMIN can't be removed |
| SSRF through push endpoints | The sender POSTs to URLs browsers give us, so a subscription must be `https` on one of four push service hosts (D-014 rule 13), checked on save and again before every send. No redirects, connect and request timeouts. Endpoints are never logged |
| Secret leakage | Secrets Manager → ECS. Hooks block writing or printing secret files. GitHub secret scanning + push protection, plus gitleaks in CI |
| Vulnerable code and dependencies | CodeQL default setup. Dependabot. The dependency gate keeps the dependency surface small |
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

## VAPID keys (Web Push)
The server signs every push with a VAPID key pair (RFC 8292, D-014). The public key is served at `/api/push/key` and
browsers subscribe with it. The private key is a secret: only the owner generates and handles it, never Claude, and
tests make throwaway pairs at run time, so no key or key-shaped fixture is ever committed.

- **Config:** `CONFUZION_VAPID_PUBLIC_KEY` and `CONFUZION_VAPID_PRIVATE_KEY` (`con-fuzion.push.vapid.*`), from Secrets
  Manager in production. Both are base64url: the public key is the uncompressed P-256 point (87 characters), and the
  private key is the raw 32-byte scalar (43 characters). With neither set, push is off: `/api/push/key` is a 404 and
  nothing is sent. Setting only one, a malformed key or a mismatched pair stops startup, and the error never quotes a
  key.
- **The JWT's subject** is `con-fuzion.app.origin`, so production's origin must be the `https` one.
- **Generating a pair** (the owner, on a trusted machine, with OpenSSL 1.1 or later):
  ```sh
  openssl ecparam -name prime256v1 -genkey -noout -out vapid.pem
  # Public key: the last 65 bytes of the SubjectPublicKeyInfo
  openssl ec -in vapid.pem -pubout -outform DER | tail -c 65 | base64 | tr -d '\n=' | tr '/+' '_-'
  # Private key: the 32 bytes after the 7-byte SEC1 header
  openssl ec -in vapid.pem -outform DER | tail -c +8 | head -c 32 | base64 | tr -d '\n=' | tr '/+' '_-'
  ```
  Put both values in Secrets Manager, then delete `vapid.pem` and clear the terminal. Never paste the private key into
  chat, an issue, a commit or a log. `npx web-push generate-vapid-keys` prints the same format, if Node is at hand.
- **Rotating:** see [the incident runbook](runbooks/incident.md#2-rotate-secrets). Browsers subscribed with the old
  public key can't receive pushes signed by the new one, so each must subscribe again. Until the client notices a
  changed key (`docs/backlog.md`), that means the user turning push off and on again in Settings.

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
- [ ] Outbound requests go only to allowlisted hosts, never follow redirects, and have timeouts.
- [ ] New dependencies are justified in the PR body.
- [ ] Authorization matrix test updated for new endpoints.
