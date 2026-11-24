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
- **VAPID keys:** generate a new pair and update Secrets Manager. Existing push subscriptions become invalid, and
  clients re-subscribe on their next visit.
- **AWS:** deploys use GitHub OIDC (no static keys). If a human's credentials leaked, deactivate them in IAM and
  review CloudTrail.

## 3. Recover
- **Data corruption:** Aurora point-in-time restore (14-day window) into a new cluster, verify, then repoint
  the service.
- **Bad deploy:** roll back to the previous ECS task definition revision.

## 4. Follow up
- Write a short post-incident note in `docs/research/incident-YYYY-MM-DD.md`.
- If personal data was exposed, the owner decides on user notification and any legal obligations.
