# Incident runbook

The owner runs these steps. Claude may draft commands but doesn't execute anything against production.
Finished in M5 and M6, once maintenance mode and the infrastructure exist.

## 1. Contain
- **Maintenance mode** (D-015 rule 16): set `CONFUZION_MAINTENANCE=true` (`con-fuzion.maintenance`) in the ECS task
  environment and redeploy. It's read at startup, so it takes effect only as each task restarts.
  - Every `/api` route answers 503 with `errorType` `SERVICE_UNAVAILABLE`, signed in or not, and the app shows its
    "back soon" page. That includes sign-in, logout and every write, so nothing reaches the database or the session
    store from the API.
  - `/api/actuator/health` still answers, so the load balancer keeps the tasks. The SPA's static files are still
    served.
  - Sessions aren't touched: users are still signed in when it's turned off again. To also sign everyone out, flush
    the sessions as below.
  - To turn it off, set `CONFUZION_MAINTENANCE=false` (or remove it) and redeploy.
  - Check it from outside: `curl -i https://<host>/api/me` returns `503`, and `curl -i https://<host>/api/actuator/health`
    returns `200`.
- **Suspected session compromise:** flush all sessions in ElastiCache (`FLUSHDB` on the session database). Everyone
  has to sign in again.
- **Compromised moderator or admin account:** an ADMIN removes the role in-app
  (`DELETE /api/admin/users/{userId}/roles`). That ends all of that user's sessions at once and is audit-logged. If the
  account's Discord ID is in `CONFUZION_ADMIN_BOOTSTRAP_DISCORD_IDS`, take it out of the config and redeploy **first**:
  a bootstrap ID gets ADMIN back at its next sign-in. The last ADMIN can't be removed (409 `LAST_ADMIN`), so grant
  ADMIN to a trusted account before removing it from the compromised one.
- **No working admin** (the last admin lost their Discord account or MFA): add a trusted Discord ID to
  `CONFUZION_ADMIN_BOOTSTRAP_DISCORD_IDS` and redeploy. It gets ADMIN at its next sign-in, audit-logged with no actor,
  and the role is in effect only while that Discord account has 2FA on.
- **Review what happened:** an ADMIN reads `GET /api/admin/audit` (newest first). It has every role change, session
  purge, refused sign-in and moderator action. A database trigger refuses every `UPDATE`, `DELETE` and `TRUNCATE` on
  it, so the app can't change it, even by a bug.
- **Abusive account:** an ADMIN bans it in-app. That also purges its sessions.
- **Edge flood:** tighten the WAF rate-based rule threshold.

## 2. Rotate secrets
- **Discord client secret:** Discord Developer Portal → OAuth2 → Reset Secret. Update Secrets Manager, then
  redeploy.
- **VAPID keys** (Web Push; format and generation in [docs/security.md](../security.md#vapid-keys-web-push)):
  1. Generate a new pair as `docs/security.md` describes. Don't reuse or derive from the old one.
  2. Update `CONFUZION_VAPID_PUBLIC_KEY` and `CONFUZION_VAPID_PRIVATE_KEY` together in Secrets Manager, then redeploy.
     Startup fails if the two don't match, so a half-done update can't go live.
  3. Every existing subscription was made with the old public key, and browsers keep it: the client doesn't yet
     notice that `/api/push/key` changed (see `docs/backlog.md`). Push services refuse the new key's pushes to those
     subscriptions, so the sender marks those notifications `FAILED` (the inbox still has them), and deletes a
     subscription only if its push service answers 404 or 410. Until the client check exists, each user has to turn
     push off and on again in Settings to subscribe with the new key; tell users to do that.
  4. If the old private key leaked, also delete every subscription, `DELETE FROM push_subscription;`, so the sender
     stops trying old-key subscriptions. That doesn't stop the push services accepting the old key: whoever holds it
     and a browser's endpoint can push to that browser until it unsubscribes, which today happens only when the user
     turns push off (or signs out). Users keep their push setting but get no pushes until they turn push off and on
     again.
  - **To turn push off** in an emergency, unset both values and redeploy: `/api/push/key` becomes a 404 and nothing is
    sent. Pending notifications wait in the queue.
- **AWS:** deploys use GitHub OIDC (no static keys). If a human's credentials leaked, deactivate them in IAM and
  review CloudTrail.

## 3. Recover
- **Data corruption:** Aurora point-in-time restore (14-day window) into a new cluster, verify, then repoint
  the service.
- **Bad deploy:** roll back to the previous ECS task definition revision.

## 4. Follow up
- Write a short post-incident note in `docs/research/incident-YYYY-MM-DD.md`.
- If personal data was exposed, the owner decides on user notification and any legal obligations.
