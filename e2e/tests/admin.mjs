// Helpers for the M5 specs: the bootstrap admin, moderators, signing in again (or being refused) and the admin area.
// Roles are only ever granted through the app (D-015), never written to the database.

import { expect } from '@playwright/test';
import { apiWrite, userId } from './rsvp.mjs';
import { newUser, signIn, useScenario } from './support.mjs';

/** The Discord ID in `con-fuzion.admin.bootstrap-discord-ids` (application-e2e.properties). */
const BOOTSTRAP_ADMIN_ID = '400000000000000001';

/** Waits until the app knows who is signed in (the header's Settings link), so the CSRF cookie is set too. */
const expectSignedIn = ({ page }) =>
  expect(page.getByRole('link', { name: 'Settings' })).toBeVisible();

/**
 * The bootstrap admin, signed in with 2FA on. It is one account for every test and run, so its first sign-in may or
 * may not go through onboarding, and nothing may change its roles.
 */
export async function joinAdmin(join) {
  const user = {
    ...newUser('admin', { mfaEnabled: true }),
    id: BOOTSTRAP_ADMIN_ID,
    username: 'e2e-bootstrap-admin',
  };
  const admin = await join('admin', [], { user, landing: /\/(onboarding)?$/ });
  await expectSignedIn(admin);
  return admin;
}

/**
 * Signs a signed-out, already registered player in again through the UI. `mfaEnabled` changes what Discord reports
 * for 2FA from this sign-in on.
 */
export async function signInAgain(player, { mfaEnabled } = {}) {
  if (mfaEnabled !== undefined) player.user = { ...player.user, mfa_enabled: mfaEnabled };
  await signIn(player.page, player.user, player.guilds, '/');
  await expectSignedIn(player);
}

/** The admin sets exactly these roles for `player` through the API. This signs the player out. */
export async function grantRoles(admin, player, roles) {
  const id = await userId(player);
  const response = await apiWrite(admin, 'PUT', `/admin/users/${id}/roles`, { roles });
  expect(response.status()).toBe(200);
}

/** A new user in no servers whom the admin makes a moderator, signed in again so the role is in effect. */
export async function joinModerator(join, admin) {
  const moderator = await join('moderator', [], { mfaEnabled: true });
  await grantRoles(admin, moderator, ['MODERATOR']);
  await signInAgain(moderator);
  return moderator;
}

/** The player's session has ended: the API answers 401 and the app sends them to sign in. */
export async function expectSignedOut({ page }) {
  expect((await page.request.get('/api/me')).status()).toBe(401);
  await page.goto('/');
  await expect(page).toHaveURL(/\/welcome$/);
}

/**
 * The player's sign-in is refused (D-015 rule 5): the callback redirects to `/welcome?login=refused`, the page shows
 * the one neutral message, and there is no session.
 */
export async function expectSignInRefused(player) {
  const { page } = player;
  await useScenario(player.user, player.guilds);
  await page.goto('/welcome');
  const callback = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/login/oauth2/code/discord',
  );
  await page.getByRole('link', { name: 'Sign in with Discord' }).click();
  expect((await callback).headers().location).toMatch(/\/welcome\?login=refused$/);
  await expect(page.getByRole('alert')).toHaveText('This account can’t sign in right now.');
  await expect(page).toHaveURL(/\/welcome$/);
  expect((await page.request.get('/api/me')).status()).toBe(401);
}

/** On the admin area's Users page: searches for the player's Discord ID and returns their card. */
export async function findUser({ page }, player) {
  await page.goto('/admin/users');
  await page.getByLabel('Name, user ID or Discord ID').fill(player.user.id);
  await page.getByRole('button', { name: 'Search' }).click();
  const card = page.getByRole('list', { name: 'Search results' }).getByRole('listitem');
  await expect(card).toHaveCount(1);
  await expect(card.getByRole('heading', { name: player.name })).toBeVisible();
  return card;
}

/** From the open queue (oldest first, a page at a time), opens the report whose entry shows `text`. */
export async function openReport({ page }, text) {
  await expect(page.getByRole('heading', { level: 2, name: 'Reports' })).toBeVisible();
  const entry = page
    .getByRole('list', { name: 'Open reports' })
    .getByRole('link')
    .filter({ hasText: text });
  await expect(async () => {
    // A local database keeps earlier runs' open reports, so the new one may be some pages down.
    if (!(await entry.isVisible())) {
      await page.getByRole('button', { name: 'Load more' }).click({ timeout: 1_000 });
    }
    await expect(entry).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
  await entry.click();
}
