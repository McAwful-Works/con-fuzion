import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { decodeKey, pushSupport } from './features/push/browserPush';
import { chooseInstallHint } from './features/install/install';
import {
  envelope,
  errorEnvelope,
  jsonResponse,
  me,
  mockApi,
  renderApp,
  type RecordedCall,
} from './test-utils';

const IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';

/** 65 bytes, like a real VAPID public key, as unpadded base64url. */
const KEY_BYTES = Uint8Array.from({ length: 65 }, (_, i) => (i * 7 + 251) % 256);
const PUBLIC_KEY = btoa(String.fromCharCode(...KEY_BYTES))
  .replace(/\+/g, '-')
  .replace(/\//g, '_')
  .replace(/=+$/, '');

const ENDPOINT = 'https://fcm.googleapis.com/fcm/send/abc123';

function fakeSubscription() {
  return {
    endpoint: ENDPOINT,
    toJSON: () => ({ endpoint: ENDPOINT, keys: { p256dh: 'p256dh-key', auth: 'auth-key' } }),
    unsubscribe: vi.fn(async () => true),
  };
}

/**
 * A browser that can do push: a service worker registration, the Push API and the Notification API.
 * `requestPermission` and `subscribe` are the two calls that must only run from a tap.
 */
function pushBrowser(
  options: { permission?: NotificationPermission; subscribed?: boolean; answer?: string } = {},
) {
  const subscription = fakeSubscription();
  let current: ReturnType<typeof fakeSubscription> | null = options.subscribed
    ? subscription
    : null;
  const requestPermission = vi.fn(async () => {
    const answer = (options.answer ?? 'granted') as NotificationPermission;
    NotificationStub.permission = answer;
    return answer;
  });
  const NotificationStub = Object.assign(function () {}, {
    permission: options.permission ?? 'default',
    requestPermission,
  });
  const pushManager = {
    getSubscription: vi.fn(async () => current),
    subscribe: vi.fn(async () => {
      current = subscription;
      return subscription;
    }),
  };
  const registration = { pushManager };
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: {
      ready: Promise.resolve(registration),
      getRegistration: async () => registration,
    },
  });
  vi.stubGlobal('PushManager', class {});
  vi.stubGlobal('Notification', NotificationStub);
  return { requestPermission, pushManager, subscription };
}

function setUserAgent(userAgent: string) {
  Object.defineProperty(navigator, 'userAgent', { configurable: true, value: userAgent });
}

function setStandalone(standalone: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: standalone })),
  );
}

/** Taps the switch once the page has finished loading (it is disabled until it knows the browser's state). */
async function tapTurnOn() {
  const button = await screen.findByRole('button', { name: 'Turn on push' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
}

const noContent = () => new Response(null, { status: 204 });

/** A signed-in server with push keys configured, unless `key` says otherwise. */
function pushApi(
  handler: (call: RecordedCall) => Response | undefined = () => undefined,
  key: Response | (() => Response) = () => envelope('PUSH_KEY', { publicKey: PUBLIC_KEY }),
  startEnabled = false,
) {
  // The server remembers the setting, so `GET /api/me` answers with what the last PATCH set.
  let pushEnabled = startEnabled;
  return mockApi((call) => {
    const custom = handler(call);
    if (custom) return custom;
    if (call.path === '/api/me' && call.method === 'PATCH') {
      pushEnabled = (call.body as { pushEnabled: boolean }).pushEnabled;
      return envelope('ME', { ...me, pushEnabled });
    }
    if (call.path === '/api/me') return envelope('ME', { ...me, pushEnabled });
    if (call.path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds: [] });
    if (call.path.startsWith('/api/events'))
      return envelope('EVENT_PAGE', { items: [], nextCursor: null });
    if (call.path === '/api/push/key') return typeof key === 'function' ? key() : key;
    if (call.path === '/api/push/subscriptions') return noContent();
    return undefined;
  });
}

beforeEach(() => {
  setStandalone(false);
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

describe('push on a capable browser', () => {
  it('never asks for permission or subscribes until the user taps', async () => {
    const browser = pushBrowser();
    pushApi();

    renderApp('/settings');
    const button = await screen.findByRole('button', { name: 'Turn on push' });
    // Everything on the page has loaded, including the key; still nothing has been requested.
    await waitFor(() => expect(button).toBeEnabled());
    expect(browser.requestPermission).not.toHaveBeenCalled();
    expect(browser.pushManager.subscribe).not.toHaveBeenCalled();

    fireEvent.click(button);
    // Inside the tap itself, not a few microtasks later: iOS needs the user gesture to still be live.
    expect(browser.requestPermission).toHaveBeenCalledTimes(1);
    await screen.findByRole('button', { name: 'Turn off push' });
  });

  it('subscribes with the server key, saves the subscription, then sets pushEnabled', async () => {
    const browser = pushBrowser();
    const calls = pushApi();

    renderApp('/settings');
    await tapTurnOn();
    await screen.findByRole('button', { name: 'Turn off push' });

    const [options] = browser.pushManager.subscribe.mock.calls[0] as unknown as [
      { userVisibleOnly: boolean; applicationServerKey: Uint8Array },
    ];
    expect(options.userVisibleOnly).toBe(true);
    expect(Array.from(options.applicationServerKey)).toEqual(Array.from(KEY_BYTES));

    const writes = calls.filter((c) => c.method !== 'GET');
    expect(writes.map((c) => `${c.method} ${c.path}`)).toEqual([
      'POST /api/push/subscriptions',
      'PATCH /api/me',
    ]);
    expect(writes[0]!.body).toEqual({
      endpoint: ENDPOINT,
      keys: { p256dh: 'p256dh-key', auth: 'auth-key' },
    });
    expect(writes[1]!.body).toEqual({ pushEnabled: true });
  });

  it('drops the new subscription again when the server refuses it', async () => {
    const browser = pushBrowser();
    pushApi(({ method, path }) =>
      method === 'POST' && path === '/api/push/subscriptions'
        ? jsonResponse(errorEnvelope, 400)
        : undefined,
    );

    renderApp('/settings');
    await tapTurnOn();

    await screen.findByRole('alert');
    expect(browser.subscription.unsubscribe).toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Turn off push' })).not.toBeInTheDocument();
  });

  it('explains how to re-enable notifications when the permission is denied', async () => {
    const browser = pushBrowser({ answer: 'denied' });
    const calls = pushApi();

    renderApp('/settings');
    await tapTurnOn();

    await screen.findByText(/blocked for Con-Fuzion in this browser/);
    expect(screen.getByText(/browser’s site settings/)).toBeInTheDocument();
    expect(browser.pushManager.subscribe).not.toHaveBeenCalled();
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
  });

  it('shows the same help up front when the permission was denied earlier', async () => {
    const browser = pushBrowser({ permission: 'denied' });
    pushApi();

    renderApp('/settings');
    await screen.findByText(/blocked for Con-Fuzion in this browser/);
    expect(screen.getByRole('button', { name: 'Turn on push' })).toBeDisabled();
    expect(browser.requestPermission).not.toHaveBeenCalled();
  });

  it('stays off, and says so, when the permission prompt is closed', async () => {
    pushBrowser({ answer: 'default' });
    const calls = pushApi();

    renderApp('/settings');
    await tapTurnOn();

    await screen.findByText(/closed the browser’s permission prompt/);
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
  });

  it('turning it off removes the subscription from the server and the browser', async () => {
    const browser = pushBrowser({ permission: 'granted', subscribed: true });
    const calls = pushApi(undefined, undefined, true);

    renderApp('/settings');
    fireEvent.click(await screen.findByRole('button', { name: 'Turn off push' }));

    await waitFor(() => expect(browser.subscription.unsubscribe).toHaveBeenCalled());
    await waitFor(() =>
      expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([
        { pushEnabled: false },
      ]),
    );
    const writes = calls.filter((c) => c.method !== 'GET');
    expect(writes.map((c) => `${c.method} ${c.path}`)).toEqual([
      'DELETE /api/push/subscriptions',
      'PATCH /api/me',
    ]);
    expect(writes[0]!.body).toEqual({ endpoint: ENDPOINT });
  });
});

describe('when push is not available', () => {
  it.each([
    ['404, the server has no keys', 404],
    ['501, before the sender exists', 501],
  ])('hides the switch when the key is a %s', async (_name, status) => {
    const browser = pushBrowser();
    pushApi(undefined, () => jsonResponse(errorEnvelope, status));

    renderApp('/settings');
    await screen.findByText(/aren’t set up on this server/);
    expect(screen.queryByRole('button', { name: /push/i })).not.toBeInTheDocument();
    expect(browser.requestPermission).not.toHaveBeenCalled();
  });

  it('offers a retry, not a hidden setting, when the key request fails otherwise', async () => {
    pushBrowser();
    pushApi(undefined, () => jsonResponse(errorEnvelope, 500));

    renderApp('/settings');
    expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('explains it, and asks the server nothing, in a browser without the Push API', async () => {
    const calls = pushApi();

    renderApp('/settings');
    await screen.findByText(/can’t show push notifications/);
    expect(calls.some((c) => c.path === '/api/push/key')).toBe(false);
  });

  it('gives the Add to Home Screen steps on iOS outside the installed app', async () => {
    setUserAgent(IPHONE);
    // Safari there has no PushManager, so the setting can't even start.
    const calls = pushApi();

    renderApp('/settings');
    await screen.findByText(/push notifications work once Con-Fuzion is on your Home Screen/);
    expect(screen.getByText(/Choose “Add to Home Screen”/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Turn on push' })).not.toBeInTheDocument();
    expect(calls.some((c) => c.path === '/api/push/key')).toBe(false);
  });
});

describe('signing out', () => {
  it('removes this browser’s subscription before the logout request', async () => {
    const browser = pushBrowser({ permission: 'granted', subscribed: true });
    const calls = pushApi(({ method, path }) =>
      method === 'POST' && path === '/api/auth/logout' ? noContent() : undefined,
    );

    const router = renderApp('/settings');
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/welcome'));
    const writes = calls.filter((c) => c.method !== 'GET').map((c) => `${c.method} ${c.path}`);
    expect(writes).toEqual(['DELETE /api/push/subscriptions', 'POST /api/auth/logout']);
    expect(browser.subscription.unsubscribe).toHaveBeenCalled();
  });

  it('still signs out when the subscription can’t be removed', async () => {
    pushBrowser({ permission: 'granted', subscribed: true });
    const calls = pushApi(({ method, path }) => {
      if (method === 'DELETE' && path === '/api/push/subscriptions') {
        return jsonResponse(errorEnvelope, 500);
      }
      if (method === 'POST' && path === '/api/auth/logout-everywhere') return noContent();
      return undefined;
    });

    const router = renderApp('/settings');
    fireEvent.click(await screen.findByRole('button', { name: 'Log out everywhere' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/welcome'));
    expect(calls.some((c) => c.path === '/api/auth/logout-everywhere')).toBe(true);
  });

  it('still signs out when the service worker can’t be asked for the subscription', async () => {
    pushBrowser({ permission: 'granted', subscribed: true });
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      value: {
        ready: new Promise(() => undefined),
        getRegistration: async () => {
          throw new Error('worker is updating');
        },
      },
    });
    const calls = pushApi(({ method, path }) =>
      method === 'POST' && path === '/api/auth/logout' ? noContent() : undefined,
    );

    const router = renderApp('/settings');
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/welcome'));
    expect(calls.some((c) => c.path === '/api/auth/logout')).toBe(true);
  });

  it('needs no push calls when this browser never subscribed', async () => {
    pushBrowser();
    const calls = pushApi(({ method, path }) =>
      method === 'POST' && path === '/api/auth/logout' ? noContent() : undefined,
    );

    const router = renderApp('/settings');
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/welcome'));
    expect(calls.some((c) => c.path === '/api/push/subscriptions')).toBe(false);
  });
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

describe('detecting what the browser can do', () => {
  it('needs an install on iOS outside standalone mode, and works inside it', () => {
    setUserAgent(IPHONE);
    expect(pushSupport()).toBe('needs-install');
    setStandalone(true);
    pushBrowser();
    expect(pushSupport()).toBe('ok');
  });

  it('is unsupported without a service worker or Push API', () => {
    expect(pushSupport()).toBe('unsupported');
  });

  it('decodes the unpadded base64url key to its 65 bytes', () => {
    expect(Array.from(decodeKey(PUBLIC_KEY))).toEqual(Array.from(KEY_BYTES));
  });
});

describe('onboarding: servers, notifications, install', () => {
  it('goes through all three steps on a browser that can do each', async () => {
    pushBrowser();
    pushApi();
    renderApp('/onboarding');
    await screen.findByRole('heading', { name: 'Where can people find you?' });

    // The browser offers installation, once the page is listening.
    act(() => {
      const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
        prompt: vi.fn(async () => undefined),
      });
      window.dispatchEvent(event);
    });

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('heading', { name: 'Want a heads-up?' });
    expect(await screen.findByRole('button', { name: 'Turn on push' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('heading', { name: 'One last thing' });
    expect(screen.getByRole('button', { name: 'Install' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Continue' })).toHaveAttribute('href', '/');
  });

  it('asks the browser to install when Install is tapped', async () => {
    pushApi();
    const prompt = vi.fn(async () => undefined);
    renderApp('/onboarding');
    await screen.findByRole('heading', { name: 'Where can people find you?' });
    act(() => {
      window.dispatchEvent(
        Object.assign(new Event('beforeinstallprompt', { cancelable: true }), { prompt }),
      );
    });

    fireEvent.click(await screen.findByRole('button', { name: 'Next' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Install' }));
    await waitFor(() => expect(prompt).toHaveBeenCalledTimes(1));
    // The prompt used up, the step stays put instead of falling back to the one before it.
    expect(screen.getByRole('heading', { name: 'One last thing' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Continue' })).toHaveAttribute('href', '/');
  });

  it('shows the iOS steps on the install step, not a second push step', async () => {
    setUserAgent(IPHONE);
    pushApi();
    renderApp('/onboarding');
    await screen.findByRole('heading', { name: 'Where can people find you?' });

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('heading', { name: 'One last thing' });
    expect(screen.getByText(/Choose “Add to Home Screen”/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Want a heads-up?' })).not.toBeInTheDocument();
  });

  it('remembers a dismissal and never shows the install step again', async () => {
    setUserAgent(IPHONE);
    pushApi();
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

  it('skips the notifications step when the server has no push keys', async () => {
    pushBrowser();
    pushApi(undefined, () => jsonResponse(errorEnvelope, 404));
    renderApp('/onboarding');
    await screen.findByRole('heading', { name: 'Where can people find you?' });
    await waitFor(() => expect(screen.getByRole('link', { name: 'Continue' })).toBeInTheDocument());
  });
});
