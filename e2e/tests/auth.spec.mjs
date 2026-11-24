import { apiGuilds, denyNextSignIn, mixedGuilds, newUser, signIn } from './support.mjs';
import { expect, test } from './hooks.mjs';

/** Open Settings from the header (the avatar link) and press one of the Account buttons. */
async function pressAccountButton(page, name) {
  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByRole('button', { name }).click();
}

test('1. landing: no session goes to /welcome, and /api/me is a 401 in the D-010 envelope', async ({
  page,
}) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/welcome$/);
  await expect(page.getByRole('link', { name: 'Sign in with Discord' })).toBeVisible();

  const response = await page.request.get('/api/me');
  expect(response.status()).toBe(401);
  const envelope = await response.json();
  expect(envelope.responseType).toBe('ERROR');
  expect(envelope.body.errorType).toBeTruthy();
  expect(envelope.body.errorMessage).toBeTruthy();
  expect(envelope.body.correlationId).toBeTruthy();
  expect(response.headers()['x-correlation-id']).toBe(envelope.body.correlationId);
});

test('2. failed sign-in: access_denied lands on /welcome with the alert, and the parameter is replaced away', async ({
  page,
}) => {
  await page.goto('/welcome');
  const historyBefore = await page.evaluate(() => history.length);
  await denyNextSignIn();
  await page.getByRole('link', { name: 'Sign in with Discord' }).click();

  await expect(page.getByRole('alert')).toHaveText('Sign-in didn’t complete. Please try again.');
  await expect(page).toHaveURL(/\/welcome$/); // ?login=failed is gone
  // The link click added one entry. A push instead of a replace when removing the parameter would add a second.
  expect(await page.evaluate(() => history.length)).toBe(historyBefore + 1);
  // Nobody is signed in.
  expect((await page.request.get('/api/me')).status()).toBe(401);
  // A reload no longer shows the alert.
  await page.reload();
  await expect(page.getByRole('link', { name: 'Sign in with Discord' })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('3. first sign-in goes to /onboarding, creates the user, and every server starts off', async ({
  page,
}) => {
  const user = newUser('first');
  await signIn(page, user, mixedGuilds(), '/onboarding');

  const me = await page.request.get('/api/me');
  expect(me.status()).toBe(200);
  expect((await me.json()).body.username).toBe(user.username);

  const guilds = await apiGuilds(page.request);
  expect(guilds).toHaveLength(mixedGuilds().length);
  expect(guilds.every((g) => g.discoverable === false)).toBe(true);
  await expect(page.getByRole('switch')).toHaveCount(guilds.length);
  await expect(page.getByRole('switch', { checked: true })).toHaveCount(0);
});

test('4. returning sign-in goes to /', async ({ page }) => {
  const user = newUser('returning');
  await signIn(page, user, mixedGuilds(), '/onboarding');
  await pressAccountButton(page, 'Sign out');
  await expect(page).toHaveURL(/\/welcome$/);

  await signIn(page, user, mixedGuilds(), '/');
  await expect(page.getByRole('heading', { name: 'Events', level: 1 })).toBeVisible();
});

test('9. sign out lands on /welcome and ends the session', async ({ page }) => {
  await signIn(page, newUser('signout'));
  expect((await page.request.get('/api/me')).status()).toBe(200);

  await pressAccountButton(page, 'Sign out');
  await expect(page).toHaveURL(/\/welcome$/);
  await expect(page.getByRole('link', { name: 'Sign in with Discord' })).toBeVisible();
  expect((await page.request.get('/api/me')).status()).toBe(401);
});

test('10. log out everywhere ends the same user’s other session', async ({ browser, baseURL }) => {
  const user = newUser('everywhere');
  const contextA = await browser.newContext({ baseURL });
  const contextB = await browser.newContext({ baseURL });
  try {
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();
    await signIn(pageA, user);
    await signIn(pageB, user, mixedGuilds(), '/');
    expect((await pageB.request.get('/api/me')).status()).toBe(200);

    await pressAccountButton(pageA, 'Log out everywhere');
    await expect(pageA).toHaveURL(/\/welcome$/);

    // B did nothing, yet its session is gone too.
    expect((await pageB.request.get('/api/me')).status()).toBe(401);
    await pageB.goto('/');
    await expect(pageB).toHaveURL(/\/welcome$/);
    await expect(pageB.getByRole('link', { name: 'Sign in with Discord' })).toBeVisible();
  } finally {
    await contextA.close();
    await contextB.close();
  }
});
