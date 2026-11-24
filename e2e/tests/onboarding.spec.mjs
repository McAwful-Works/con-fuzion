import {
  apiGuilds,
  manyGuilds,
  mixedGuilds,
  newUser,
  recordGuildPuts,
  signIn,
} from './support.mjs';
import { expect, test } from './hooks.mjs';

/** The server list's switches, in the order the page shows them. */
const switches = (page) => page.getByRole('switch');

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

test('8. select all, then turn off for all: one bulk PUT /api/me/guilds each, never one per server', async ({
  page,
}) => {
  const guilds = manyGuilds(160);
  await signIn(page, newUser('bulk'), guilds);
  await expect(switches(page)).toHaveCount(guilds.length);
  const puts = recordGuildPuts(page);

  // Click, then wait for the bulk response. A request for a single server fails the test on the spot.
  const clickForBulk = async (name) => {
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
              `"${name}" must send ONE bulk PUT /api/me/guilds, but sent ${request.method()} ${request.url()}`,
            ),
          );
        }
      }),
    );
    perServer.catch(() => {}); // only the race below reports it
    await page.getByRole('button', { name }).click();
    return Promise.race([bulk, perServer]);
  };

  // Select all.
  let response = await clickForBulk('Select all servers');
  expect(response.status()).toBe(200);
  await expect(page.getByRole('button', { name: 'Turn off for all servers' })).toBeVisible();
  expect(puts).toHaveLength(1);
  expect(puts[0].path).toBe('/api/me/guilds');
  expect(puts[0].body.guilds).toHaveLength(guilds.length);
  expect(puts[0].body.guilds.every((g) => g.discoverable === true)).toBe(true);

  await page.reload();
  await expect(switches(page)).toHaveCount(guilds.length);
  await expect(page.getByRole('switch', { checked: true })).toHaveCount(guilds.length);
  expect((await apiGuilds(page.request)).every((g) => g.discoverable)).toBe(true);

  // Turn off for all.
  response = await clickForBulk('Turn off for all servers');
  expect(response.status()).toBe(200);
  await expect(page.getByRole('button', { name: 'Select all servers' })).toBeVisible();
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
