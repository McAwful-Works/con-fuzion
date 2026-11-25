// Helpers for the M3 RSVP specs: opening an event, joining, leaving, removing, and writes to the API with the CSRF
// header the way the SPA sends it. Everything a person can do goes through the real UI.

import { expect } from '@playwright/test';

/** The Spots and signup panel on an event page. */
export const panel = ({ page }) => page.getByRole('region', { name: 'Spots and signup' });

/** Opens an event page and waits for its title and the spots panel to load. */
export async function openEvent(player, eventId) {
  await player.page.goto(`/events/${eventId}`);
  await expect(player.page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(panel(player)).toBeVisible();
}

/** Presses Join (waiting for the button to unlock) and waits for `expected`: "Joined" or "Waitlisted #N". */
export async function joinEvent(player, eventId, expected) {
  await openEvent(player, eventId);
  const join = panel(player).getByRole('button', { name: 'Join', exact: true });
  await expect(join).toBeEnabled({ timeout: 20_000 });
  await join.click();
  await expect(panel(player).getByText(expected, { exact: true })).toBeVisible();
}

/** Reopens the event page and checks the viewer's own status line. */
export async function expectStatus(player, eventId, expected) {
  await openEvent(player, eventId);
  await expect(panel(player).getByText(expected, { exact: true })).toBeVisible();
}

/**
 * Reloads the event until the spots panel shows `text`. The counts in `/spots` can be up to a second old (the
 * backend's counts cache), and the page only refetches them on a poll, so a count read straight after a change
 * needs a fresh look.
 */
export async function expectSpots(player, eventId, text) {
  await expect(async () => {
    await openEvent(player, eventId);
    await expect(panel(player).getByText(text)).toBeVisible({ timeout: 1_500 });
  }).toPass({ timeout: 20_000 });
}

/** The signed-in player's own user ID (the one the API uses, not their Discord ID). */
export async function userId({ page }) {
  const response = await page.request.get('/api/me');
  expect(response.status()).toBe(200);
  return (await response.json()).body.id;
}

/** Leave, then confirm. */
export async function leaveEvent(player) {
  await panel(player).getByRole('button', { name: 'Leave', exact: true }).click();
  await player.page
    .getByRole('alertdialog')
    .getByRole('button', { name: 'Leave', exact: true })
    .click();
  await expect(panel(player).getByRole('button', { name: 'Join', exact: true })).toBeVisible();
}

/** On the host's event page: Remove next to `who`, then confirm. */
export async function removeAttendee(host, who) {
  await host.page.getByRole('button', { name: `Remove ${who.name}` }).click();
  await host.page
    .getByRole('alertdialog')
    .getByRole('button', { name: 'Remove', exact: true })
    .click();
  await expect(host.page.getByRole('alertdialog')).toHaveCount(0);
}

/** The "Going (N)" and "Waitlist (N)" headings of the attendee list. */
export const goingHeading = ({ page }, n) =>
  page.getByRole('heading', { name: `Going (${n})`, exact: true });
export const waitlistHeading = ({ page }, n) =>
  page.getByRole('heading', { name: `Waitlist (${n})`, exact: true });

/**
 * Waits for the attendee list to show exactly these names, in order: `confirmed` (the host first) and `waitlist`
 * (empty when the viewer gets no waitlist, or nobody is waiting).
 */
export async function expectAttendees({ page }, { confirmed, waitlist }) {
  const section = page.getByRole('region', { name: /^Going \(/ });
  await expect(section.getByRole('list').first().getByRole('listitem')).toContainText(confirmed);
  const waiting = section.getByRole('list', { name: /^Waitlist \(/ });
  if (waitlist.length === 0) await expect(waiting).toHaveCount(0);
  else await expect(waiting.getByRole('listitem')).toContainText(waitlist);
}

/**
 * A write to `/api` as `player`, with the CSRF token from their cookie in `X-XSRF-TOKEN` (the SPA's own client does
 * the same). Returns the Playwright response.
 */
export async function apiWrite(player, method, path, body) {
  const cookie = (await player.context.cookies()).find((c) => c.name === 'XSRF-TOKEN');
  expect(cookie, 'the SPA has set the XSRF-TOKEN cookie').toBeTruthy();
  return player.page.request.fetch(`/api${path}`, {
    method,
    headers: { 'X-XSRF-TOKEN': decodeURIComponent(cookie.value) },
    data: body,
  });
}

/** The D-010 error envelope's `errorType` of a failed response, after checking the envelope's shape. */
export async function errorType(response) {
  const envelope = await response.json();
  expect(envelope.responseType).toBe('ERROR');
  expect(envelope.body.errorMessage).toBeTruthy();
  expect(envelope.body.correlationId).toBeTruthy();
  return envelope.body.errorType;
}
