// Helpers shared by the specs: the fake Discord's control API, test users and guild lists, and sign-in.

import { expect } from '@playwright/test';
import { STUB_PORT } from '../fake-discord.mjs';

const stubUrl = `http://127.0.0.1:${STUB_PORT}`;

async function control(path, body) {
  const response = await fetch(`${stubUrl}/__e2e/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`fake Discord /__e2e/${path} answered ${response.status}`);
}

/** Forget the fake's scenario, pending denial, codes and tokens. Called before every test. */
export const resetStub = () => control('reset');

/** The next /oauth2/authorize answers error=access_denied. */
export const denyNextSignIn = () => control('deny-next');

let counter = 0;

/**
 * A Discord user nobody has signed in as yet. The ID is a snowflake unique to this run and test, so tests never
 * share a row (a run can repeat against the same local database) and "first sign-in" really is the first.
 */
export function newUser(name, { mfaEnabled = false } = {}) {
  counter += 1;
  // 18 digits: a Discord-sized snowflake (the backend accepts 17 to 19).
  const id = `41${Date.now()}${String(counter).padStart(3, '0')}`.slice(0, 18);
  return {
    id,
    username: `e2e-${name}-${id.slice(-6)}`,
    global_name: null,
    avatar: null,
    discriminator: '0',
    mfa_enabled: mfaEnabled,
    locale: 'en-US',
  };
}

/**
 * A guild as Discord returns it from /users/@me/guilds.
 */
export function guild(id, name, { icon = 'abc123' } = {}) {
  return {
    id: String(id),
    name,
    icon,
    owner: false,
    permissions: '0',
    features: [],
  };
}

/** Mixed case, an emoji name and a guild with no icon: the shapes the list must sort and render. */
export function mixedGuilds() {
  return [
    guild('200000000000000003', 'zeta lounge'),
    guild('200000000000000001', 'Alpha Squad'),
    guild('200000000000000004', '🎮 Game Night', { icon: null }),
    guild('200000000000000002', 'bravo con'),
    guild('200000000000000005', 'Charlie'),
  ];
}

/** At least 150 guilds (the issue asks for it), named so they sort in a predictable order. */
export function manyGuilds(count = 160) {
  return Array.from({ length: count }, (_, i) =>
    guild(300000000000000000n + BigInt(i), `Server ${String(i).padStart(3, '0')}`),
  );
}

/** Make the fake Discord answer as `user` with `guilds` for the next sign-in. */
export const useScenario = (user, guilds = mixedGuilds()) => control('scenario', { user, guilds });

/**
 * Sign in through the real UI: the landing page, the button, the fake Discord, the callback. Resolves once the
 * app has landed on `landing` (a path), which is where the backend sends a first or returning login.
 */
export async function signIn(page, user, guilds = mixedGuilds(), landing = '/onboarding') {
  await useScenario(user, guilds);
  await page.goto('/welcome');
  await page.getByRole('link', { name: 'Sign in with Discord' }).click();
  await expect(page).toHaveURL(new URL(landing, page.url()).toString());
}

/** The signed-in user's guild list straight from the API, as the SPA's own request would get it. */
export async function apiGuilds(request) {
  const response = await request.get('/api/me/guilds');
  expect(response.status()).toBe(200);
  return (await response.json()).body.guilds;
}

/**
 * Records every request to /api/me/guilds* made by the page, so a test can assert how many were sent
 * and with what bodies. Returns the live array.
 */
export function recordGuildPuts(page) {
  const puts = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (request.method() === 'PUT' && url.pathname.startsWith('/api/me/guilds')) {
      puts.push({ path: url.pathname, body: request.postDataJSON() });
    }
  });
  return puts;
}
