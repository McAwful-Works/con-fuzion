// Helpers for the M2 specs: several signed-in users (one browser context each), friends, circles, events and the
// feed, all driven through the real UI. Reads that the UI can't show (a 404 body, the API's own answer) use the
// signed-in context's request.

import { expect } from '@playwright/test';
import { newUser, signIn } from './support.mjs';

/** A name as a regular expression that matches it literally. */
const literal = (text) => new RegExp(text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));

/** Local `YYYY-MM-DDTHH:mm` for a `datetime-local` field, `days` from now. Contexts run in UTC, so this is UTC. */
function localInput(days, hour) {
  const d = new Date(Date.now() + days * 24 * 60 * 60 * 1000);
  d.setUTCHours(hour, 0, 0, 0);
  return d.toISOString().slice(0, 16);
}

/**
 * Signs `name` in through the real UI in a fresh browser context and returns the player. Pass `discoverable` (guild
 * names, or `'all'`) to switch discoverability on in those servers the way a person does, on the onboarding page.
 * `mfaEnabled` is what Discord reports for 2FA. A Discord account that may have signed in before passes its `user`
 * and where it may `landing`; everyone else is new. `close()` ends the context; the `join` fixture in hooks.mjs calls
 * it when the test ends.
 */
export async function joinAs(
  browser,
  baseURL,
  name,
  guilds,
  { discoverable = [], mfaEnabled = false, user = newUser(name, { mfaEnabled }), landing } = {},
) {
  const context = await browser.newContext({ baseURL, timezoneId: 'UTC' });
  const page = await context.newPage();
  await signIn(page, user, guilds, landing);
  const player = {
    user,
    guilds,
    page,
    context,
    name: user.username, // the display name: global_name is null
    close: () => context.close(),
  };
  const names = discoverable === 'all' ? guilds.map((g) => g.name) : discoverable;
  for (const g of names) await setDiscoverable(player, g, true);
  return player;
}

/** Turns discoverability for one server on or off on the onboarding page and waits for the save. */
export async function setDiscoverable({ page }, guildName, on) {
  await page.goto('/onboarding');
  const toggle = page.getByRole('switch', {
    name: new RegExp(`^${literal(guildName).source}`),
  });
  await expect(toggle).toBeVisible();
  if ((await toggle.isChecked()) === on) return;
  const saved = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && r.url().includes('/api/me/guilds/'),
  );
  await toggle.click();
  expect((await saved).status()).toBe(200);
  if (on) await expect(toggle).toBeChecked();
  else await expect(toggle).not.toBeChecked();
}

/** On /people: makes an invite link and returns the URL the page shows. */
export async function makeInvite({ page }, baseURL) {
  await page.goto('/people');
  await page.getByRole('button', { name: /^(Make an invite link|Make a new link)$/ }).click();
  const shown = page.getByText(/\/invite\/[\w-]{22}$/);
  await expect(shown).toBeVisible();
  const url = (await shown.textContent()).trim();
  // The link must point at the server under test, not at a production origin.
  expect(new URL(url).origin).toBe(new URL(baseURL).origin);
  return url;
}

/** `to` opens the link and accepts. Lands on /people. */
export async function acceptInvite({ page }, url) {
  await page.goto(url);
  await expect(page.getByRole('heading', { name: /invited you to be friends/ })).toBeVisible();
  await page.getByRole('button', { name: 'Accept' }).click();
  await expect(page).toHaveURL(/\/people$/);
}

/** `from` invites, `to` accepts: they are friends afterwards. */
export async function befriend(from, to, baseURL) {
  await acceptInvite(to, await makeInvite(from, baseURL));
}

/** Opens the friends list (/people) and waits for it to load. */
export async function openFriends({ page }) {
  await page.goto('/people');
  await expect(page.getByRole('heading', { level: 1, name: 'People' })).toBeVisible();
  await expect(page.getByRole('status', { name: 'Loading your friends' })).toHaveCount(0);
}

/** The friends list row for `who`. */
export const friendRow = ({ page }, who) =>
  page
    .getByRole('list', { name: 'Your friends' })
    .getByRole('listitem')
    .filter({ hasText: who.name });

/** On the friends list: blocks `who` from their "…" menu and confirms. */
export async function blockFriend({ page }, who) {
  await page.getByRole('button', { name: `More actions for ${who.name}` }).click();
  await page.getByRole('button', { name: `Block ${who.name}` }).click();
  await page.getByRole('button', { name: 'Block', exact: true }).click();
}

/** Makes a circle on /people?tab=circles, opens it and adds `members` by searching for them. Returns nothing. */
export async function makeCircle({ page }, circleName, members) {
  await page.goto('/people?tab=circles');
  await page.getByLabel('Circle name').fill(circleName);
  await page.getByRole('button', { name: 'Create circle' }).click();
  await page.getByRole('link', { name: literal(circleName) }).click();
  await expect(page.getByRole('heading', { level: 1, name: circleName })).toBeVisible();
  for (const member of members) {
    await page.getByLabel('Search by name').fill(member.name);
    await page.getByRole('button', { name: `Add ${member.name}` }).click();
    await expect(
      page.getByRole('listitem').filter({ hasText: member.name }).getByText('In this circle'),
    ).toBeVisible();
  }
}

/**
 * Creates an event through the form and returns its ID (from the URL it lands on).
 * `audience`: `friends` (bool), `guilds`, `circles` (names), `people` (players). Without a `capacity` (2 or more,
 * host included) the spots stay unlimited. `days` and `hour` (UTC) set the start; with no end an event lasts
 * 3 hours, so events that start less than 3 hours apart overlap.
 */
export async function createEvent(
  { page },
  {
    title,
    location = 'Hall B, table 4',
    locationConfirmedOnly = false,
    capacity,
    days = 2,
    hour = 15,
    audience,
  },
) {
  await page.goto('/events/new');
  await page.getByLabel('Title').fill(title);
  await page.getByLabel('Location', { exact: true }).fill(location);
  if (locationConfirmedOnly) {
    await page
      .getByRole('switch', { name: 'Hide the location until people are confirmed' })
      .click();
  }
  // The date and time field, which "Other…" shows under the month grid.
  await page.getByRole('button', { name: 'Other…' }).click();
  await page.getByLabel('Starts').fill(localInput(days, hour));
  if (capacity !== undefined) {
    await page.getByRole('switch', { name: 'Limit the spots' }).click();
    await page.getByLabel('Capacity').fill(String(capacity));
  }
  // The checkbox of the card with that name.
  const pick = (name) => page.getByRole('checkbox', { name, exact: true }).check();
  if (audience.friends) await pick('All my friends');
  for (const g of audience.guilds ?? []) await pick(g);
  for (const c of audience.circles ?? []) await pick(c);
  for (const person of audience.people ?? []) {
    await page.getByLabel('Search by name').fill(person.name);
    await page.getByRole('button', { name: `Add ${person.name}` }).click();
    await expect(
      page.getByRole('listitem').filter({ hasText: person.name }).getByText('Added'),
    ).toBeVisible();
  }
  await page.getByRole('button', { name: 'Create meetup' }).click();
  await expect(page).toHaveURL(/\/events\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
  return page.url().split('/').pop();
}

/** The event cards of Discover and My plans, in their day sections. Side cards link to events too. */
const feedCards = (page) => page.locator('main section[aria-labelledby^="day-"] a');

/** Opens Discover and waits for the feed to load (an empty feed shows its message instead). */
export async function openFeed({ page }) {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Discover' })).toBeVisible();
  await expect(page.getByRole('status', { name: 'Loading events' })).toHaveCount(0);
  // A feed that failed to load shows an error, not events: "hidden" must not pass on that.
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(
    feedCards(page)
      .first()
      .or(page.getByRole('heading', { name: 'No upcoming meetups yet' })),
  ).toBeVisible();
}

/** Asserts what the viewer's feed shows: every title in `sees` is there, every title in `hidden` is not. */
export async function expectFeed(viewer, { sees = [], hidden = [] }) {
  await openFeed(viewer);
  for (const title of sees) {
    await expect(feedCards(viewer.page).filter({ hasText: title })).toBeVisible();
  }
  // Nowhere on the page, side cards included.
  for (const title of hidden) {
    await expect(viewer.page.getByRole('link', { name: literal(title) })).toHaveCount(0);
  }
}

/** Asserts what the viewer's My plans shows, once it has loaded, like `expectFeed`. */
export async function expectSchedule({ page }, { sees = [], hidden = [] }) {
  await page.goto('/plans');
  await expect(page.getByRole('heading', { level: 1, name: 'My plans' })).toBeVisible();
  await expect(page.getByRole('status', { name: 'Loading events' })).toHaveCount(0);
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(
    feedCards(page)
      .first()
      .or(page.getByRole('heading', { name: 'Nothing planned yet' })),
  ).toBeVisible();
  for (const title of sees) {
    await expect(feedCards(page).filter({ hasText: title })).toBeVisible();
  }
  // Nowhere on the page, side cards included.
  for (const title of hidden) {
    await expect(page.getByRole('link', { name: literal(title) })).toHaveCount(0);
  }
}

/** The sidebar's Inbox link: its name carries the unread count, "Inbox, 2 unread" (or "99+ unread"). */
export const bell = ({ page }) => page.getByRole('link', { name: /^Inbox/ });

/** Opens the inbox. */
export async function openInbox({ page }) {
  await page.goto('/inbox');
  await expect(page.getByRole('heading', { level: 1, name: 'Inbox' })).toBeVisible();
}

/** The status of `GET /api/events/{id}` as the viewer (a 404 for everything they can't see). */
export async function eventStatus({ page }, eventId) {
  return (await page.request.get(`/api/events/${eventId}`)).status();
}

/** Display names the viewer's people search returns for `prefix`, from the search box on /circles/<id>. */
export async function searchPeople({ page }, prefix) {
  const response = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/people' && r.request().method() === 'GET',
  );
  await page.getByLabel('Search by name').fill(prefix);
  expect((await response).status()).toBe(200);
  const results = page.getByRole('list', { name: 'Search results' });
  // Either some rows or "No one found" (the search is debounced; the response above is the real answer).
  await expect(results.or(page.getByText('No one found.'))).toBeVisible();
  return results
    .getByRole('button', { name: /^Add / })
    .evaluateAll((buttons) =>
      buttons.map((b) => b.getAttribute('aria-label').slice('Add '.length)),
    );
}
