# Incident runbook

The owner runs these steps. Claude may draft commands but doesn't execute anything against production.
Finished in M5 and M6, once maintenance mode and the infrastructure exist.

## 1. Contain
- **Maintenance mode:** set `CONFUZION_MAINTENANCE=true` in the ECS task environment and redeploy. `/api` returns
  503, except health checks.
- **Suspected session compromise:** flush all sessions in ElastiCache (`FLUSHDB` on the session database). Everyone
  has to sign in again.
- **Abusive account:** an ADMIN bans it in-app. That also purges its sessions.
- **Edge flood:** tighten the WAF rate-based rule threshold.

## 2. Rotate secrets
- **Discord client secret:** Discord Developer Portal → OAuth2 → Reset Secret. Update Secrets Manager, then
  redeploy.
- **VAPID keys** (Web Push; format and generation in [docs/security.md](../security.md#vapid-keys-web-push)):
  1. Generate a new pair as `docs/security.md` describes. Don't reuse or derive from the old one.
  2. Update `CONFUZION_VAPID_PUBLIC_KEY` and `CONFUZION_VAPID_PRIVATE_KEY` together in Secrets Manager, then redeploy.
     Startup fails if the two don't match, so a half-done update can't go live.
  3. Every existing subscription was made with the old public key, so push services refuse new pushes to it (the
     sender marks those notifications `FAILED`; the inbox still has them). Each browser gets pushes again once it
     subscribes with the new key on its next visit.
  4. If the old private key leaked, also delete every subscription so nothing signed by the old key is ever accepted
     again: `DELETE FROM push_subscription;`. Users keep their push setting and re-subscribe on their next visit.
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
