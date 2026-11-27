import { join as joinPath } from 'node:path';
import { assertPortFree, runDir, spawnBackend, waitForHealth } from '../global-setup.mjs';
import { stopBackend } from '../global-teardown.mjs';
import { expect, test as base } from './hooks.mjs';
import { errorType } from './rsvp.mjs';
import { newUser, signIn } from './support.mjs';

// M5 (D-015 rule 16): maintenance mode is a flag the backend reads at startup, so this file runs its own jar with the
// flag on, on its own port, next to the suite's normal one.
const port = Number(process.env.E2E_MAINTENANCE_PORT ?? 8090);

const test = base.extend({
  /** The base URL of a second jar in maintenance mode. It is stopped when the test ends, pass or fail. */
  maintenanceUrl: [
    async ({}, use, testInfo) => {
      await assertPortFree(port);
      const jar = spawnBackend({
        port,
        dir: joinPath(runDir, 'maintenance'),
        args: ['--con-fuzion.maintenance=true'],
      });
      try {
        await waitForHealth(jar, port);
        await use(`http://localhost:${port}`);
      } finally {
        await stopBackend(jar);
        // CI uploads the report but not this jar's log, so a failure carries it.
        if (testInfo.status !== testInfo.expectedStatus) {
          await testInfo.attach('maintenance-backend.log', { path: jar.logFile });
        }
      }
    },
    { timeout: 180_000 },
  ],
});

/** A 503 in the D-010 envelope. */
async function expectUnavailable(response, what) {
  expect(response.status(), what).toBe(503);
  expect(await errorType(response)).toBe('SERVICE_UNAVAILABLE');
}

test('42. maintenance mode: every API route but health is a 503, and the app says "Back soon"', async ({
  page,
  request,
  maintenanceUrl,
}) => {
  // Signed in on the normal jar first. The two jars share the session store and cookies ignore the port, so the
  // second one gets a real session: signed in or not, the answer is the same.
  await signIn(page, newUser('maintenance'));
  await expect(page.getByRole('button', { name: 'Skip for now' })).toBeVisible();
  expect((await page.request.get('/api/me')).status()).toBe(200);

  for (const path of [
    '/api/me',
    '/api/events',
    '/api/admin/reports',
    '/api/push/key',
    '/api/oauth2/authorization/discord',
    '/api/no-such-route',
  ]) {
    for (const client of [page.request, request]) {
      await expectUnavailable(await client.get(`${maintenanceUrl}${path}`), path);
    }
  }
  // A write without a CSRF token would be a 403 normally: maintenance answers first.
  await expectUnavailable(await request.post(`${maintenanceUrl}/api/events`, { data: {} }), 'POST');

  const health = await request.get(`${maintenanceUrl}/api/actuator/health`);
  expect(health.status()).toBe(200);
  expect((await health.json()).status).toBe('UP');

  // The app itself still loads, and says so: for the signed-in user, then for a visitor.
  await page.goto(`${maintenanceUrl}/`);
  await expect(page.getByRole('heading', { name: 'Back soon' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
  await page.context().clearCookies();
  await page.goto(`${maintenanceUrl}/welcome`);
  await expect(page.getByRole('heading', { name: 'Back soon' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Sign in with Discord' })).toHaveCount(0);
});
