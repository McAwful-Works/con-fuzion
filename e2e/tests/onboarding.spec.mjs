import {
  apiGuilds,
  manyGuilds,
  mixedGuilds,
  newUser,
  recordGuildPuts,
  signIn,
} from './support.mjs';
import { expect, test } from './hooks.mjs';

/** The server list's switches, in the order the page shows them. "Turn on all" isn't one of them. */
const switches = (page) => page.getByRole('list', { name: 'Your servers' }).getByRole('switch');

test('5. time zone: the browser’s zone is stored by PATCH /api/me on first load', async ({
  browser,
  baseURL,
}) => {
  const context = await browser.newContext({
    baseURL,
    timezoneId: 'Pacific/Auckland',
  });
  try {
    const page = await context.newPage();
    const patched = page.waitForRequest(
      (r) => r.method() === 'PATCH' && new URL(r.url()).pathname === '/api/me',
    );
    await signIn(page, newUser('timezone'));
    expect((await patched).postDataJSON()).toEqual({
      timeZone: 'Pacific/Auckland',
    });

    await expect
      .poll(async () => (await (await page.request.get('/api/me')).json()).body.timeZone)
      .toBe('Pacific/Auckland');
  } finally {
    await context.close();
  }
});

test('6. sort order: servers are sorted case-insensitively, emoji and lowercase names included', async ({
  page,
}) => {
  await signIn(page, newUser('sort'), mixedGuilds());
  const expected = ['🎮 Game Night', 'Alpha Squad', 'bravo con', 'Charlie', 'zeta lounge'];
  await expect(switches(page)).toHaveCount(expected.length);
  for (const [i, name] of expected.entries()) {
    await expect(switches(page).nth(i)).toHaveAccessibleName(`${name} Make me discoverable here`);
  }
});

test('7. single toggle persists across a reload and sends exactly one PUT /api/me/guilds/{id}', async ({
  page,
}) => {
  await signIn(page, newUser('toggle'), mixedGuilds());
  const puts = recordGuildPuts(page);
  const alpha = page.getByRole('switch', { name: /^Alpha Squad/ });

  const saved = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && r.url().includes('/api/me/guilds/'),
  );
  await alpha.click();
  expect((await saved).status()).toBe(200);
  await expect(alpha).toBeChecked();

  expect(puts).toEqual([
    { path: '/api/me/guilds/200000000000000001', body: { discoverable: true } },
  ]);

  await page.reload();
  await expect(page.getByRole('switch', { name: /^Alpha Squad/ })).toBeChecked();
  await expect(page.getByRole('switch', { checked: true })).toHaveCount(1);
  expect((await apiGuilds(page.request)).filter((g) => g.discoverable).map((g) => g.name)).toEqual([
    'Alpha Squad',
  ]);
  expect(puts).toHaveLength(1); // the reload sent no write
});

test('8. turn on all, then off again: one bulk PUT /api/me/guilds each, never one per server', async ({
  page,
}) => {
  const guilds = manyGuilds(160);
  await signIn(page, newUser('bulk'), guilds);
  await expect(switches(page)).toHaveCount(guilds.length);
  const puts = recordGuildPuts(page);

  // Click, then wait for the bulk response. A request for a single server fails the test on the spot.
  const all = page.getByRole('switch', { name: 'Turn on all' });
  const clickForBulk = async () => {
    const bulk = page.waitForResponse(
      (r) => r.request().method() === 'PUT' && new URL(r.url()).pathname === '/api/me/guilds',
    );
    const perServer = new Promise((_, reject) =>
      page.on('request', (request) => {
        if (
          request.method() === 'PUT' &&
          new URL(request.url()).pathname.startsWith('/api/me/guilds/')
        ) {
          reject(
            new Error(
              `"Turn on all" must send ONE bulk PUT /api/me/guilds, but sent ${request.method()} ${request.url()}`,
            ),
          );
        }
      }),
    );
    perServer.catch(() => {}); // only the race below reports it
    await all.click();
    return Promise.race([bulk, perServer]);
  };

  // On for all.
  let response = await clickForBulk();
  expect(response.status()).toBe(200);
  await expect(all).toBeChecked();
  expect(puts).toHaveLength(1);
  expect(puts[0].path).toBe('/api/me/guilds');
  expect(puts[0].body.guilds).toHaveLength(guilds.length);
  expect(puts[0].body.guilds.every((g) => g.discoverable === true)).toBe(true);

  await page.reload();
  await expect(switches(page)).toHaveCount(guilds.length);
  await expect(switches(page).and(page.getByRole('switch', { checked: true }))).toHaveCount(
    guilds.length,
  );
  expect((await apiGuilds(page.request)).every((g) => g.discoverable)).toBe(true);

  // Off for all.
  response = await clickForBulk();
  expect(response.status()).toBe(200);
  await expect(all).not.toBeChecked();
  expect(puts).toHaveLength(2);
  expect(puts[1].path).toBe('/api/me/guilds');
  expect(puts[1].body.guilds.every((g) => g.discoverable === false)).toBe(true);

  await page.reload();
  await expect(switches(page)).toHaveCount(guilds.length);
  await expect(page.getByRole('switch', { checked: true })).toHaveCount(0);
  expect((await apiGuilds(page.request)).some((g) => g.discoverable)).toBe(false);

  // Whatever else happened, no request went to a single server.
  expect(puts.filter((p) => p.path !== '/api/me/guilds')).toEqual([]);
});

test('45. the first sign-in is a guided setup with no navigation; a visit from inside the app is the servers alone', async ({
  page,
}) => {
  await signIn(page, newUser('setup'), mixedGuilds());
  const navigation = page.getByRole('navigation', { name: 'Main' });
  const skip = page.getByRole('button', { name: 'Skip for now' });
  // This jar has push keys, and headless Chromium never offers to install.
  await expect(page.getByRole('list', { name: 'Setup steps' }).getByRole('listitem')).toHaveText([
    '1 · Servers',
    '2 · Notifications',
  ]);
  await expect(navigation).toHaveCount(0);
  await skip.click();
  await expect(page.getByRole('heading', { name: 'Want a heads-up?' })).toBeFocused();
  await skip.click();
  await expect(page).toHaveURL(/\/$/);

  // What a link inside the app does: a new history entry and no page load.
  await page.evaluate(() => {
    history.pushState({ usr: null, key: 'in-app', idx: 1 }, '', '/onboarding');
    dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page.getByRole('heading', { name: 'Where can people find you?' })).toBeVisible();
  await expect(switches(page)).toHaveCount(mixedGuilds().length);
  await expect(skip).toHaveCount(0);
  await expect(navigation).toBeVisible();
  await page.getByRole('button', { name: 'Done' }).click();
  await expect(page).toHaveURL(/\/$/);
});
