import { expect, test } from './hooks.mjs';
import { createEvent } from './social.mjs';
import { crowd, openEvent, panel } from './rsvp.mjs';

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
