import { expect, test } from './hooks.mjs';
import { guild } from './support.mjs';

// Hosting on a phone (D-017): five steps. jsdom applies no CSS, and `createEvent` fills the desktop page.
test('51. on a phone a meetup is hosted in five steps, with Next in a bar at the bottom, and the event page opens the audience step', async ({
  join,
}) => {
  // A Discord-sized ID (18 digits) that no earlier run has used.
  const con = guild(`51${Date.now()}000`, 'Phone Con', { icon: null });
  const { page } = await join('phone', [con], { discoverable: 'all' });
  const height = 640;
  await page.setViewportSize({ width: 375, height });
  await page.goto('/events/new');

  const question = page.getByRole('heading', { level: 1 });
  const bar = page.locator('[data-actionbar]');
  /** The step's question, the bar along the bottom edge, and nothing scrolling sideways. */
  const onStep = async (text) => {
    await expect(question).toHaveText(text);
    const box = await bar.boundingBox();
    expect(Math.round(box.y + box.height)).toBe(height);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(375);
  };
  const next = async (label) => {
    const button = page.getByRole('button', { name: `Next: ${label}` });
    await expect(button).toBeInViewport({ ratio: 1 });
    await button.click();
  };

  await onStep('What’s the plan?');
  await next('When');
  await expect(page.getByText('There are 2 things to fix.')).toBeVisible();
  const title = `Steps ${Date.now()}`;
  await page.getByLabel('Title').fill(title);
  await page.getByLabel('Location', { exact: true }).fill('Hall B, table 4');
  await next('When');

  await onStep('When is it?');
  // A move starts at the new step's question.
  await expect(question).toBeFocused();
  await expect(question).toBeInViewport({ ratio: 1 });
  const start = new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString().slice(0, 16);
  await page.getByLabel('Starts').fill(start);
  await next('Spots');

  await onStep('How many spots?');
  await page.getByRole('switch', { name: 'Limit the spots' }).click();
  await next('Who can see this');

  await onStep('Who can see it?');
  await page.getByRole('checkbox', { name: con.name }).check();
  await next('Review');

  await onStep('Look good?');
  await expect(page.getByRole('region', { name: 'Preview in Discover' })).toContainText(title);
  await page.getByRole('button', { name: 'Create meetup' }).click();
  await expect(page).toHaveURL(/\/events\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();

  await page.getByRole('link', { name: 'Change who can see it' }).click();
  await onStep('Who can see it?');
  await expect(question).toBeFocused();
  await expect(page.getByRole('checkbox', { name: con.name })).toBeChecked();
});
