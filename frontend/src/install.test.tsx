import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { chooseInstallHint } from './features/install/install';
import { envelope, guilds, me, mockApi, renderApp } from './test-utils';

const IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';

/** A browser that can do push, so onboarding has a notifications step before the install step. */
function pushBrowser() {
  const registration = { pushManager: { getSubscription: async () => null } };
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: { ready: Promise.resolve(registration), getRegistration: async () => registration },
  });
  vi.stubGlobal('PushManager', class {});
  vi.stubGlobal(
    'Notification',
    Object.assign(function () {}, { permission: 'default' }),
  );
}

function signedIn(servers: typeof guilds = []) {
  mockApi((call) => {
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds: servers });
    if (call.path.startsWith('/api/notifications')) {
      return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: 0 });
    }
    if (call.path === '/api/push/key') return envelope('PUSH_KEY', { publicKey: 'AAAA' });
    if (call.path.startsWith('/api/events')) {
      return envelope('EVENT_PAGE', { items: [], nextCursor: null });
    }
    return undefined;
  });
}

/** The browser offers to install the app; `prompt` is what the Install button calls. */
function offerInstall(prompt = vi.fn(async () => undefined)) {
  act(() => {
    window.dispatchEvent(
      Object.assign(new Event('beforeinstallprompt', { cancelable: true }), { prompt }),
    );
  });
  return prompt;
}

function useIphone() {
  Object.defineProperty(navigator, 'userAgent', { configurable: true, value: IPHONE });
}

beforeEach(() => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: false })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  delete (navigator as { serviceWorker?: unknown }).serviceWorker;
  delete (navigator as { userAgent?: unknown }).userAgent;
  localStorage.clear();
  // The install prompt is remembered at module level; an installed app forgets it.
  window.dispatchEvent(new Event('appinstalled'));
});

describe('choosing the install hint', () => {
  const base = { ios: false, standalone: false, canPrompt: false, dismissed: false };

  it('shows the Add to Home Screen steps on iOS Safari', () => {
    expect(chooseInstallHint({ ...base, ios: true })).toBe('ios');
  });

  it('shows an Install button where the browser offers to install', () => {
    expect(chooseInstallHint({ ...base, canPrompt: true })).toBe('button');
  });

  it('shows nothing when there is nothing to offer', () => {
    expect(chooseInstallHint(base)).toBeNull();
  });

  it('shows nothing once installed or dismissed', () => {
    expect(chooseInstallHint({ ...base, ios: true, standalone: true })).toBeNull();
    expect(chooseInstallHint({ ...base, canPrompt: true, standalone: true })).toBeNull();
    expect(chooseInstallHint({ ...base, ios: true, dismissed: true })).toBeNull();
    expect(chooseInstallHint({ ...base, canPrompt: true, dismissed: true })).toBeNull();
  });
});

const heading = (name: string) => screen.findByRole('heading', { level: 1, name });
const SERVERS = 'Where can people find you?';
const PUSH = 'Want a heads-up?';
const INSTALL = 'One last thing';
const forward = () => fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
const skip = () => fireEvent.click(screen.getByRole('button', { name: 'Skip for now' }));
/** The progress bar's segments as text, with `*` on the current one. */
const progress = () =>
  within(screen.getByRole('list', { name: 'Setup steps' }))
    .getAllByRole('listitem')
    .map((li) => `${li.getAttribute('aria-current') === 'step' ? '*' : ''}${li.textContent}`);

describe('onboarding: servers, notifications, install', () => {
  it('goes through all three steps on a browser that can do each', async () => {
    pushBrowser();
    signedIn();
    renderApp('/onboarding');
    await heading(SERVERS);
    offerInstall();
    expect(progress()).toEqual(['*1 · Servers', '2 · Notifications', '3 · Install']);

    forward();
    // The new step's heading takes the focus, so it's announced.
    expect(await heading(PUSH)).toHaveFocus();
    expect(progress()).toEqual(['1 · Servers', '*2 · Notifications', '3 · Install']);

    forward();
    expect(await heading(INSTALL)).toHaveFocus();
    expect(progress()).toEqual(['1 · Servers', '2 · Notifications', '*3 · Install']);
    expect(screen.getByRole('button', { name: 'Install' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Continue' })).toHaveAttribute('href', '/');
  });

  it('has a header of its own and none of the app’s navigation', async () => {
    signedIn();
    renderApp('/onboarding');
    await heading(SERVERS);

    expect(screen.getByRole('link', { name: 'Con-Fuzion' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.queryByRole('contentinfo')).toBeNull();
    expect(screen.queryByRole('link', { name: 'You' })).toBeNull();
  });

  it('can skip every step, ending in the app', async () => {
    pushBrowser();
    signedIn();
    const router = renderApp('/onboarding');
    await heading(SERVERS);
    offerInstall();

    skip();
    await heading(PUSH);
    skip();
    await heading(INSTALL);
    skip();

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    // Back from the app doesn't start the setup again.
    expect(router.state.historyAction).toBe('REPLACE');
    expect(localStorage.getItem('cf-install-hint-dismissed')).toBe('1');
  });

  it('shows no progress bar or install step in a browser that has no way to install', async () => {
    signedIn();
    renderApp('/onboarding');
    await heading(SERVERS);
    expect(screen.getByRole('link', { name: 'Continue' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Continue' })).toBeNull();
    expect(screen.queryByRole('list', { name: 'Setup steps' })).toBeNull();
  });

  it('asks the browser to install when Install is tapped, and stays on the step', async () => {
    signedIn();
    renderApp('/onboarding');
    await heading(SERVERS);
    const prompt = offerInstall();

    forward();
    fireEvent.click(await screen.findByRole('button', { name: 'Install' }));
    await waitFor(() => expect(prompt).toHaveBeenCalledTimes(1));
    // The prompt used up, the step stays put with a note, instead of falling back or going empty.
    expect(await heading(INSTALL)).toBeInTheDocument();
    expect(screen.getByText(/you’ll find Con-Fuzion with your other apps/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Install' })).not.toBeInTheDocument();
    expect(progress()).toEqual(['1 · Servers', '*2 · Install']);
    expect(screen.getByRole('link', { name: 'Continue' })).toHaveAttribute('href', '/');
  });

  it('shows the iOS steps on the install step, not a second push step', async () => {
    useIphone();
    signedIn();
    renderApp('/onboarding');
    await heading(SERVERS);
    expect(progress()).toEqual(['*1 · Servers', '2 · Install']);

    forward();
    await heading(INSTALL);
    expect(screen.getByText(/Choose “Add to Home Screen”/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: PUSH })).not.toBeInTheDocument();
  });

  it('remembers a skipped install step and never shows it again', async () => {
    useIphone();
    signedIn();
    const router = renderApp('/onboarding');
    await heading(SERVERS);
    forward();
    await heading(INSTALL);
    skip();

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(localStorage.getItem('cf-install-hint-dismissed')).toBe('1');

    // A later setup goes straight to the end after the servers step.
    cleanup();
    renderApp('/onboarding');
    await heading(SERVERS);
    expect(screen.getByRole('link', { name: 'Continue' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Continue' })).toBeNull();
  });

  it('counts Continue on the install step as dismissing it', async () => {
    useIphone();
    signedIn();
    renderApp('/onboarding');
    await heading(SERVERS);
    forward();
    await heading(INSTALL);
    fireEvent.click(screen.getByRole('link', { name: 'Continue' }));

    expect(localStorage.getItem('cf-install-hint-dismissed')).toBe('1');
  });

  it('catches an install offer the browser made before the page rendered', async () => {
    signedIn();
    offerInstall();
    renderApp('/onboarding');
    await heading(SERVERS);
    forward();
    expect(await screen.findByRole('button', { name: 'Install' })).toBeInTheDocument();
  });
});

describe('a return to /onboarding from inside the app', () => {
  it('shows the servers alone, with the app around them, and Done goes back', async () => {
    pushBrowser();
    signedIn(guilds);
    const router = renderApp('/plans');
    await screen.findAllByRole('navigation', { name: 'Main' });
    offerInstall();

    await act(() => router.navigate('/onboarding'));

    await heading(SERVERS);
    expect(await screen.findByRole('switch', { name: /Retro Gamers/ })).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Setup steps' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Skip for now' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Continue' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Continue' })).toBeNull();
    expect(screen.getAllByRole('navigation', { name: 'Main' })).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    // This router doesn't count history entries, so Done falls back to You.
    await waitFor(() => expect(router.state.location.pathname).toBe('/you'));
  });
});
