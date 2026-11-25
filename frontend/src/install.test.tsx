import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { chooseInstallHint } from './features/install/install';
import { envelope, me, mockApi, renderApp } from './test-utils';

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

function signedIn() {
  mockApi((call) => {
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds: [] });
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

describe('onboarding: servers, notifications, install', () => {
  it('goes through all three steps on a browser that can do each', async () => {
    pushBrowser();
    signedIn();
    renderApp('/onboarding');
    await screen.findByRole('heading', { name: 'Where can people find you?' });
    offerInstall();

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('heading', { name: 'Want a heads-up?' });

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('heading', { name: 'One last thing' });
    expect(screen.getByRole('button', { name: 'Install' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Continue' })).toHaveAttribute('href', '/');
  });

  it('shows no install step in a browser that has no way to install', async () => {
    signedIn();
    renderApp('/onboarding');
    await screen.findByRole('heading', { name: 'Where can people find you?' });
    expect(screen.getByRole('link', { name: 'Continue' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument();
  });

  it('asks the browser to install when Install is tapped, and stays on the step', async () => {
    signedIn();
    renderApp('/onboarding');
    await screen.findByRole('heading', { name: 'Where can people find you?' });
    const prompt = offerInstall();

    fireEvent.click(await screen.findByRole('button', { name: 'Next' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Install' }));
    await waitFor(() => expect(prompt).toHaveBeenCalledTimes(1));
    // The prompt used up, the step stays put instead of falling back to the one before it.
    expect(screen.getByRole('heading', { name: 'One last thing' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Continue' })).toHaveAttribute('href', '/');
  });

  it('shows the iOS steps on the install step, not a second push step', async () => {
    useIphone();
    signedIn();
    renderApp('/onboarding');
    await screen.findByRole('heading', { name: 'Where can people find you?' });

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('heading', { name: 'One last thing' });
    expect(screen.getByText(/Choose “Add to Home Screen”/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Want a heads-up?' })).not.toBeInTheDocument();
  });

  it('remembers a dismissal and never shows the install step again', async () => {
    useIphone();
    signedIn();
    const router = renderApp('/onboarding');
    await screen.findByRole('heading', { name: 'Where can people find you?' });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Not now' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(localStorage.getItem('cf-install-hint-dismissed')).toBe('1');

    // A later visit goes straight to the end after the servers step.
    cleanup();
    const second = renderApp('/onboarding');
    await waitFor(() => expect(second.state.location.pathname).toBe('/onboarding'));
    expect(await screen.findAllByRole('link', { name: 'Continue' })).not.toHaveLength(0);
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument();
  });
});
