import { readFile } from 'node:fs/promises';
import { expect, test } from './hooks.mjs';
import { createEvent } from './social.mjs';
import { crowd, joinEvent, openEvent, panel } from './rsvp.mjs';

// The event page's layout (D-017), which unit tests can't see: jsdom applies no CSS.
test.describe.configure({ timeout: 90_000 });

test('43. on a phone the action is a bar at the bottom of the screen; on desktop it is a card beside the page', async ({
  join,
}) => {
  const { con, host, guests } = await crowd(join, 1);
  const [guest] = guests;
  const id = await createEvent(host, {
    title: `Bar at the bottom ${host.user.id.slice(-6)}`,
    capacity: 3,
    audience: { guilds: [con.name] },
  });
  const { page } = guest;
  const height = 640;
  await page.setViewportSize({ width: 375, height });
  await openEvent(guest, id);

  // A detail page has no tab bar, so the action bar has the whole bottom edge, without scrolling.
  await expect(page.locator('[data-tabbar]')).toHaveCount(0);
  const bar = page.locator('[data-actionbar]');
  const button = panel(guest).getByRole('button', { name: 'Join', exact: true });
  await expect(button).toBeEnabled();
  await expect(button).toBeInViewport({ ratio: 1 });
  const atTop = await bar.boundingBox();
  expect(Math.round(atTop.y + atTop.height)).toBe(height);
  expect(Math.round(atTop.width)).toBe(375);

  // Scrolled to the end, the bar is still there and covers nothing: Report is above it.
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  const atEnd = await bar.boundingBox();
  const report = await page.getByRole('button', { name: 'Report event' }).boundingBox();
  expect(Math.round(atEnd.y + atEnd.height)).toBe(height);
  expect(report.y + report.height).toBeLessThanOrEqual(atEnd.y);

  // Nothing makes the page scroll sideways.
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(375);

  await button.click();
  await expect(panel(guest).getByText('Joined', { exact: true })).toBeVisible();

  // On desktop the same action is a card to the right of the page, near the top.
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.evaluate(() => window.scrollTo(0, 0));
  const card = await bar.boundingBox();
  const title = await page.getByRole('heading', { level: 1 }).boundingBox();
  expect(card.x).toBeGreaterThan(title.x + title.width);
  expect(card.y).toBeLessThan(title.y + title.height);
});

test('52. a confirmed guest downloads the .ics file under the real CSP; Google is asked for nothing first', async ({
  join,
}) => {
  const { con, host, guests } = await crowd(join, 1);
  const [guest] = guests;
  const title = `Dice; cards, and snacks ${host.user.id.slice(-6)}`;
  const id = await createEvent(host, {
    title,
    locationConfirmedOnly: true,
    audience: { guilds: [con.name] },
  });
  const { page } = guest;
  const toGoogle = [];
  guest.context.on('request', (request) => {
    if (new URL(request.url()).hostname.endsWith('google.com')) toGoogle.push(request.url());
  });
  await page.addInitScript(() => {
    window.cspViolations = [];
    document.addEventListener('securitypolicyviolation', (e) =>
      window.cspViolations.push(`${e.violatedDirective} ${e.blockedURI}`),
    );
  });
  await page.setViewportSize({ width: 375, height: 640 });
  await joinEvent(guest, id, 'Joined');

  const add = panel(guest).getByRole('button', { name: 'Add to calendar' });
  await add.click();
  const menu = page.locator('[data-calendar-menu]');
  const google = menu.getByRole('link', { name: 'Open in Google Calendar' });
  // On a phone the choices float above the bar, whole and on screen.
  await expect(menu).toBeInViewport({ ratio: 1 });
  const bar = await page.locator('[data-actionbar]').boundingBox();
  const open = await menu.boundingBox();
  expect(open.y + open.height).toBeLessThanOrEqual(bar.y);
  const href = new URL(await google.getAttribute('href'));
  expect(href.origin + href.pathname).toBe('https://calendar.google.com/calendar/render');
  expect(href.searchParams.get('location')).toBe('Hall B, table 4');

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    menu.getByRole('button', { name: 'Download .ics' }).click(),
  ]);
  expect(download.suggestedFilename()).toBe(`${title}.ics`);
  const file = (await readFile(await download.path(), 'utf8')).replace(/\r\n /g, '');
  expect(file).toContain(`\r\nUID:${id}@con-fuzion\r\n`);
  expect(file).toContain(`\r\nSUMMARY:Dice\\; cards\\, and snacks ${host.user.id.slice(-6)}\r\n`);
  expect(file).toContain('\r\nLOCATION:Hall B\\, table 4\r\n');
  expect(file).toContain(`\r\nURL:${new URL(page.url()).origin}/events/${id}\r\n`);
  await expect(menu).toHaveCount(0);

  // On desktop the choices hang under the button, and Escape closes them from the keyboard.
  await page.setViewportSize({ width: 1440, height: 900 });
  await add.focus();
  await page.keyboard.press('Enter');
  await expect(menu).toBeInViewport({ ratio: 1 });
  const button = await add.boundingBox();
  expect((await menu.boundingBox()).y).toBeGreaterThan(button.y + button.height);
  await page.keyboard.press('Tab');
  await expect(menu.getByRole('button', { name: 'Download .ics' })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(menu).toHaveCount(0);
  await expect(add).toBeFocused();

  expect(await page.evaluate(() => window.cspViolations)).toEqual([]);
  expect(toGoogle).toEqual([]);
});
