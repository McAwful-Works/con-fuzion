import { onlineManager } from '@tanstack/react-query';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
import {
  envelope,
  errorEnvelope,
  guilds,
  jsonResponse,
  me,
  mockApi,
  renderApp,
  type RecordedCall,
} from './test-utils';

type Schemas = components['schemas'];
type EventBody = Schemas['EventResponse'];

const HOUR = 3_600_000;
// Noon in Chicago a month out, so that "an hour later" is the same day there.
const START = Math.floor(Date.now() / (24 * HOUR) + 30) * 24 * HOUR + 18 * HOUR;
const iso = (hours: number) => new Date(START + hours * HOUR).toISOString();

const base: EventBody = {
  id: 'base',
  title: 'Base',
  description: null,
  location: 'Hall B fountain',
  confirmWindowMinutes: 30,
  locationHidden: false,
  locationVisibility: 'VIEWERS',
  tag: null,
  host: {
    id: '11111111-1111-4111-8111-111111111111',
    displayName: 'Ada',
    avatarUrl: 'https://cdn.discordapp.com/embed/avatars/1.png',
  },
  hiddenReason: null,
  isHost: false,
  myStatus: 'CONFIRMED',
  status: 'PUBLISHED',
  audience: null,
  capacity: 8,
  spotsLeft: 3,
  visibleBecause: [],
  signupOpensAt: null,
  startsAt: iso(0),
  endsAt: iso(1),
  timeZone: 'America/Chicago',
};

/** A plan of its own, told apart by its title, starting `hours` after the first. */
const plan = (title: string, hours: number, extra: Partial<EventBody> = {}): EventBody => ({
  ...base,
  id: `event-${title.replaceAll(' ', '-')}`,
  title,
  startsAt: iso(hours),
  endsAt: iso(hours + 1),
  ...extra,
});

const mine = plan('My table', 0, { isHost: true, visibleBecause: null });
const going = plan('Ramen run', 2);
// Half an hour into the ramen run.
const queued = plan('Crawl', 2.5, { myStatus: 'WAITLISTED', spotsLeft: 0 });
// A day later than the rest, so that putting it first is visible.
const offered = plan('Board games', 24, { myStatus: 'OFFERED', spotsLeft: 0 });
const everything = [mine, going, queued, offered];

const page = (events: EventBody[], nextCursor: string | null = null) =>
  envelope('EVENT_PAGE', { events, nextCursor });

const spots = (extra: Partial<Schemas['SpotsResponse']> = {}) =>
  envelope('SPOTS', {
    capacity: 8,
    confirmed: 8,
    waitlisted: 1,
    spotsLeft: 0,
    myStatus: 'OFFERED',
    myPosition: null,
    offerExpiresAt: new Date(Date.now() + 24 * 60_000).toISOString(),
    ...extra,
  });

function signedIn(
  plans: EventBody[] = everything,
  extra: (call: RecordedCall) => Response | undefined = () => undefined,
) {
  return mockApi((call) => {
    const custom = extra(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
    if (call.path.startsWith('/api/notifications'))
      return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: 0 });
    if (call.path.startsWith('/api/events?scope=attending')) return page(plans);
    if (call.path.endsWith('/spots')) return spots();
    if (call.path === '/api/push/key') return jsonResponse(errorEnvelope, 404);
    return undefined;
  });
}

/** From 1024px up. jsdom has no `matchMedia`, so without this the window is a phone's. */
function onDesktop() {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('min-width'),
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}

const main = () => within(screen.getByRole('main'));
/** The titles of the cards on the page, in order. */
const titles = () =>
  main()
    .queryAllByRole('heading', { level: 3 })
    .map((heading) => heading.textContent);
const segment = (name: RegExp) =>
  within(screen.getByRole('group', { name: 'Which plans' })).getByRole('button', { name });
const card = (title: string) => screen.getByRole('link', { name: new RegExp(`^${title}`) });
const loaded = () => screen.findByRole('link', { name: /^My table/ });

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  onlineManager.setOnline(true);
  delete (navigator as { serviceWorker?: unknown }).serviceWorker;
  localStorage.clear();
});

describe('My plans', () => {
  it('lists everything by day, with a count on each segment', async () => {
    signedIn();
    renderApp('/plans');
    await loaded();

    expect(titles()).toEqual(['My table', 'Ramen run', 'Crawl', 'Board games']);
    expect(screen.getByText('4 upcoming')).toBeInTheDocument();
    // One section for each of the two days.
    expect(main().getAllByRole('heading', { level: 2 })).toHaveLength(2);
    expect(segment(/^All 4$/)).toHaveAttribute('aria-pressed', 'true');
    for (const name of [/^Going 1$/, /^Waiting 2$/, /^Hosting 1$/]) {
      expect(segment(name)).toHaveAttribute('aria-pressed', 'false');
    }
  });

  it.each<[string, RegExp, string, string[]]>([
    ['Going', /^Going/, '?tab=going', ['Ramen run']],
    ['Waiting', /^Waiting/, '?tab=waiting', ['Board games', 'Crawl']],
    ['Hosting', /^Hosting/, '?tab=hosting', ['My table']],
  ])('narrows to %s, keeping the choice in the URL', async (_label, name, search, shown) => {
    const calls = signedIn();
    const router = renderApp('/plans');
    await loaded();

    fireEvent.click(segment(name));

    expect(titles()).toEqual(shown);
    expect(segment(name)).toHaveAttribute('aria-pressed', 'true');
    expect(router.state.location.search).toBe(search);
    expect(router.state.historyAction).toBe('REPLACE');
    // The segments are views of one list.
    expect(calls.filter((c) => c.path.startsWith('/api/events?'))).toHaveLength(1);

    fireEvent.click(segment(/^All/));
    expect(router.state.location.search).toBe('');
    expect(titles()).toHaveLength(4);
  });

  it('opens the segment in the URL, and All for a value it doesn’t know', async () => {
    signedIn();
    renderApp('/plans?tab=hosting');
    await loaded();
    expect(titles()).toEqual(['My table']);
  });

  it('shows a cancelled event you host under Hosting', async () => {
    signedIn([{ ...mine, status: 'CANCELLED' }]);
    renderApp('/plans?tab=hosting');
    await loaded();
    expect(within(card('My table')).getByText('Cancelled')).toBeInTheDocument();
  });

  it('puts offers first in Waiting, each counting down', async () => {
    signedIn();
    renderApp('/plans?tab=waiting');

    const first = await screen.findByRole('region', { name: 'Offered to you' });
    expect(within(first).getByRole('link', { name: /^Board games/ })).toBeInTheDocument();
    const timer = await within(first).findByRole('timer');
    expect(timer).toHaveTextContent(/^(23m|24m) \d\ds$/);
    expect(timer).toHaveAttribute('aria-live', 'off');
    // No day heading above it, so the line says the day.
    expect(timer.closest('li')).toHaveTextContent(
      /^\w{3}, \w{3} \d+ · A spot is held for you for /,
    );
    expect(main().getAllByRole('timer')).toHaveLength(1);
    expect(titles()).toEqual(['Board games', 'Crawl']);
  });

  it('counts down under the offered card in All, and under no other', async () => {
    signedIn();
    renderApp('/plans');
    await loaded();

    const timer = await main().findByRole('timer');
    expect(timer.closest('li')!.previousElementSibling).toContainElement(card('Board games'));
    expect(screen.queryByRole('region', { name: 'Offered to you' })).toBeNull();
    expect(main().getAllByRole('timer')).toHaveLength(1);
  });

  it('gives a cancelled event’s offer no countdown and no first place', async () => {
    const calls = signedIn([queued, { ...offered, status: 'CANCELLED' }]);
    renderApp('/plans?tab=waiting');

    await screen.findByRole('link', { name: /^Crawl/ });
    expect(titles()).toEqual(['Crawl', 'Board games']);
    expect(screen.queryByRole('region', { name: 'Offered to you' })).toBeNull();
    expect(screen.queryByRole('timer')).toBeNull();
    expect(calls.some((c) => c.path.endsWith('/spots'))).toBe(false);
  });

  it('drops the countdown of an offer that is gone, and asks for the list again', async () => {
    let gone = false;
    const calls = signedIn(everything, ({ path }) => {
      if (path.startsWith('/api/events?')) return page(gone ? [mine] : everything);
      if (!path.endsWith('/spots')) return undefined;
      gone = true;
      return spots({ myStatus: 'NONE', offerExpiresAt: null });
    });
    renderApp('/plans');

    await waitFor(() =>
      expect(calls.filter((c) => c.path.startsWith('/api/events?')).length).toBeGreaterThan(1),
    );
    await loaded();
    await waitFor(() => expect(screen.queryByRole('link', { name: /^Board games/ })).toBeNull());
    expect(screen.queryByText(/A spot is held for you/)).toBeNull();
  });

  it('marks a clash on a card even when the other event is in another segment', async () => {
    signedIn();
    renderApp('/plans?tab=going');

    const run = await screen.findByRole('link', { name: /^Ramen run/ });
    expect(run).toHaveTextContent('Overlaps with “Crawl”');
  });

  it('loads every page, so that the counts are right', async () => {
    const calls = signedIn([], ({ path }) => {
      if (!path.startsWith('/api/events?')) return undefined;
      return path.includes('cursor=next-1') ? page([going]) : page([mine], 'next-1');
    });
    renderApp('/plans');
    await loaded();

    expect(titles()).toEqual(['My table', 'Ramen run']);
    expect(segment(/^All 2$/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
    expect(calls.map((c) => c.path).filter((p) => p.startsWith('/api/events?'))).toEqual([
      '/api/events?scope=attending&limit=50',
      '/api/events?scope=attending&limit=50&cursor=next-1',
    ]);
  });

  it('keeps the segments working offline, from the saved copy', async () => {
    onlineManager.setOnline(false);
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    signedIn();
    renderApp('/plans');
    await loaded();

    fireEvent.click(segment(/^Waiting 2$/));

    expect(titles()).toEqual(['Board games', 'Crawl']);
    expect(screen.getByText(/You’re offline\. My plans shows what was saved/)).toBeInTheDocument();
    // The deadline needs a connection; the offer is still listed.
    expect(screen.getByText(/A spot is held for you$/)).toBeInTheDocument();
  });

  it('offers to try again when the list can’t be loaded', async () => {
    let fail = true;
    signedIn(everything, ({ path }) =>
      fail && path.startsWith('/api/events?') ? jsonResponse(errorEnvelope, 500) : undefined,
    );
    renderApp('/plans');

    const retry = await screen.findByRole('button', { name: 'Try again' });
    expect(screen.queryByText('Nothing planned yet')).toBeNull();
    // No counts for a list that isn't there.
    expect(segment(/^All$/)).toBeInTheDocument();
    fail = false;
    fireEvent.click(retry);
    await loaded();
  });

  it.each<[string, string, string[]]>([
    ['', 'Nothing planned yet', ['Find meetups', 'Host a meetup']],
    ['?tab=going', 'You’re not going to any meetups yet', ['Find meetups']],
    ['?tab=waiting', 'You’re not waiting for a spot', ['Find meetups']],
    ['?tab=hosting', 'You’re not hosting anything yet', ['Host a meetup']],
  ])('says what an empty list at /plans%s is for', async (search, title, links) => {
    signedIn([]);
    renderApp(`/plans${search}`);

    const heading = await screen.findByRole('heading', { name: title });
    const empty = within(heading.parentElement!);
    expect(empty.getAllByRole('link').map((link) => link.textContent)).toEqual(links);
    for (const link of empty.getAllByRole('link')) {
      expect(link).toHaveAttribute(
        'href',
        link.textContent === 'Find meetups' ? '/' : '/events/new',
      );
    }
    expect(screen.queryByText(/upcoming$/)).toBeNull();
  });

  it('has no side column on a phone, and asks nothing about push', async () => {
    const calls = signedIn();
    renderApp('/plans');
    await loaded();

    expect(screen.queryByRole('region', { name: 'Needs your answer' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Get a nudge' })).toBeNull();
    expect(calls.some((c) => c.path.startsWith('/api/push'))).toBe(false);
  });
});

describe('the My plans side column', () => {
  /** A browser that can do push, answering the permission prompt with `answer`. */
  function pushBrowser({
    permission = 'default' as NotificationPermission,
    answer = 'granted' as NotificationPermission,
    subscribed = false,
  } = {}) {
    const subscription = {
      endpoint: 'https://fcm.googleapis.com/fcm/send/abc',
      toJSON: () => ({
        endpoint: 'https://fcm.googleapis.com/fcm/send/abc',
        keys: { p256dh: 'p', auth: 'a' },
      }),
      unsubscribe: async () => true,
    };
    let current = subscribed ? subscription : null;
    const requestPermission = vi.fn(async () => {
      Notice.permission = answer;
      return answer;
    });
    const Notice = Object.assign(function () {}, { permission, requestPermission });
    const registration = {
      pushManager: {
        getSubscription: async () => current,
        subscribe: async () => (current = subscription),
      },
    };
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      value: { ready: Promise.resolve(registration), getRegistration: async () => registration },
    });
    vi.stubGlobal('PushManager', class {});
    vi.stubGlobal('Notification', Notice);
    return { requestPermission };
  }

  /** Signed in on a server with push keys. It remembers the setting, as the real one does. */
  function pushServer(enabled = false) {
    let pushEnabled = enabled;
    return signedIn(everything, ({ method, path, body }) => {
      if (path === '/api/me') {
        if (method === 'PATCH') pushEnabled = (body as { pushEnabled: boolean }).pushEnabled;
        return envelope('ME', { ...me, pushEnabled });
      }
      if (path === '/api/push/key') return envelope('PUSH_KEY', { publicKey: 'AQAB' });
      if (path === '/api/push/subscriptions') return new Response(null, { status: 204 });
      return undefined;
    });
  }

  const nudge = () => screen.queryByRole('region', { name: 'Get a nudge' });

  it('shows the offers that need an answer', async () => {
    onDesktop();
    signedIn();
    renderApp('/plans');

    const needs = within(await screen.findByRole('region', { name: 'Needs your answer' }));
    expect(needs.getByRole('link', { name: 'Review offer: Board games' })).toHaveAttribute(
      'href',
      `/events/${offered.id}`,
    );
  });

  it('turns notifications on from a tap, and says so', async () => {
    onDesktop();
    const browser = pushBrowser();
    const calls = pushServer();
    renderApp('/plans');

    const button = await screen.findByRole('button', { name: 'Turn on notifications' });
    expect(browser.requestPermission).not.toHaveBeenCalled();
    fireEvent.click(button);

    expect(await screen.findByText('Notifications are on for this device.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Turn on notifications' })).toBeNull();
    expect(calls.filter((c) => c.method !== 'GET').map((c) => `${c.method} ${c.path}`)).toEqual([
      'POST /api/push/subscriptions',
      'PATCH /api/me',
    ]);
  });

  it('says what happened when the permission is refused', async () => {
    onDesktop();
    pushBrowser({ answer: 'denied' });
    pushServer();
    renderApp('/plans');

    fireEvent.click(await screen.findByRole('button', { name: 'Turn on notifications' }));

    expect(await screen.findByText(/Notifications are blocked/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Turn on notifications' })).toBeDisabled();
  });

  it.each<[string, () => unknown, boolean]>([
    ['push is already on', () => pushBrowser({ permission: 'granted', subscribed: true }), true],
    ['the browser blocks notifications', () => pushBrowser({ permission: 'denied' }), false],
    ['the browser can’t do push', () => undefined, false],
  ])('offers no nudge when %s', async (_when, browser, enabled) => {
    onDesktop();
    browser();
    pushServer(enabled);
    renderApp('/plans');

    await screen.findByRole('region', { name: 'Needs your answer' });
    // Give the key and the subscription time to answer.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(nudge()).toBeNull();
  });

  it('offers no nudge on a server without push', async () => {
    onDesktop();
    pushBrowser();
    const calls = signedIn();
    renderApp('/plans');

    await waitFor(() => expect(calls.some((c) => c.path === '/api/push/key')).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(nudge()).toBeNull();
  });
});
