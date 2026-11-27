import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
import { formatUnread } from './api/notifications';
import { describeNotification } from './features/notifications/describe';
import { envelope, errorEnvelope, jsonResponse, me, mockApi, renderApp } from './test-utils';
import type { RecordedCall } from './test-utils';

type Schemas = components['schemas'];
type Notification = Schemas['NotificationResponse'];

const EVENT_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const now = Date.now();

function note(id: number, over: Partial<Notification> = {}): Notification {
  return {
    id,
    type: 'EVENT_CANCELLED',
    eventId: EVENT_ID,
    read: false,
    createdAt: new Date(now - id * 60_000).toISOString(),
    payload: { eventTitle: 'Cosplay photo walk' },
    ...over,
  };
}

function page(items: Notification[], unreadCount: number, nextCursor: string | null = null) {
  return envelope('NOTIFICATION_PAGE', { items, nextCursor, unreadCount });
}

function signedIn(handler: (call: RecordedCall) => Response | undefined) {
  return mockApi((call) => {
    const custom = handler(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', me);
    return undefined;
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** The sidebar's link: the tab bar has the same one, and jsdom shows both. */
async function findInboxLink(name: string) {
  const [sidebar] = await screen.findAllByRole('navigation', { name: 'Main' });
  return within(sidebar!).findByRole('link', { name });
}

describe('describeNotification', () => {
  it('writes each type from its payload alone', () => {
    const at = (n: Notification) => describeNotification(n, now);
    const soon = new Date(now + 600_000).toISOString();
    const past = new Date(now - 600_000).toISOString();
    expect(
      at(note(1, { type: 'SPOT_OFFERED', payload: { eventTitle: 'Walk', offerExpiresAt: soon } })),
    ).toMatch(/offered a spot in “Walk”\. Accept before/);
    expect(
      at(note(1, { type: 'SPOT_OFFERED', payload: { eventTitle: 'Walk', offerExpiresAt: past } })),
    ).toMatch(/The deadline was/);
    expect(at(note(2, { type: 'SPOT_CONFIRMED', payload: { eventTitle: 'Walk' } }))).toBe(
      'You’re confirmed for “Walk”.',
    );
    expect(
      at(
        note(3, {
          type: 'EVENT_CHANGED',
          payload: { eventTitle: 'Walk', changes: ['START', 'LOCATION'], startsAt: soon },
        }),
      ),
    ).toMatch(/“Walk” changed: the start time and the location\. It now starts /);
    expect(at(note(4, { payload: { eventTitle: 'Walk' } }))).toBe('“Walk” was cancelled.');
    expect(
      at(note(5, { type: 'EVENT_REMINDER', payload: { eventTitle: 'Walk', startsAt: soon } })),
    ).toMatch(/“Walk” starts soon, /);
    expect(
      at(note(6, { type: 'FRIEND_ACCEPTED', eventId: null, payload: { friendId: 'x' } })),
    ).toBe('A friend invite you sent was accepted.');
  });

  it('shows 100 unread as 99+', () => {
    expect(formatUnread(7)).toBe('7');
    expect(formatUnread(99)).toBe('99');
    expect(formatUnread(100)).toBe('99+');
  });
});

describe('the unread badge', () => {
  it('shows the count from unreadCount in the navigation, and 99+ at the cap', async () => {
    let unread = 3;
    const calls = signedIn((call) =>
      call.path.startsWith('/api/notifications') ? page([], unread) : undefined,
    );
    renderApp('/inbox');

    const link = await findInboxLink('Inbox, 3 unread');
    expect(link).toHaveAttribute('href', '/inbox');
    expect(calls.some((c) => c.path === '/api/notifications?limit=1')).toBe(true);

    unread = 100;
    // Coming back to the tab checks again, whatever the timer says.
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }));
    expect(await findInboxLink('Inbox, 99+ unread')).toBeVisible();
  });

  it('clears after the inbox is read, even when its first count was still loading', async () => {
    // A fresh page starts the badge's count and the inbox together. Here the count is read before
    // the inbox is marked read but arrives after it, so the badge has to fetch again, not keep it.
    let releaseFirstCount = () => {};
    let read = false;
    let counts = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        if (path === '/api/me') return envelope('ME', me);
        if (init?.method === 'POST' && path === '/api/notifications/read') {
          await waitFor(() => expect(counts).toBe(1));
          read = true;
          return new Response(null, { status: 204 });
        }
        if (path === '/api/notifications?limit=1') {
          counts += 1;
          if (counts > 1) return page([], read ? 0 : 1);
          await new Promise<void>((resolve) => {
            releaseFirstCount = resolve;
          });
          return page([], 1);
        }
        if (path.startsWith('/api/notifications')) return page([note(12)], 1);
        throw new Error(`Unmocked request: ${init?.method ?? 'GET'} ${path}`);
      }),
    );
    renderApp('/inbox');

    await waitFor(() => expect(counts).toBe(2));
    releaseFirstCount();
    expect(await findInboxLink('Inbox')).toBeVisible();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(screen.queryByRole('link', { name: 'Inbox, 1 unread' })).toBeNull();
  });

  it('has no badge when everything is read', async () => {
    signedIn((call) => (call.path.startsWith('/api/notifications') ? page([], 0) : undefined));
    renderApp('/inbox');
    expect(await findInboxLink('Inbox')).toBeVisible();
  });
});

describe('the inbox', () => {
  it('lists newest first and marks everything read up to the newest ID on screen', async () => {
    const items = [note(12), note(11, { read: true, type: 'SPOT_CONFIRMED' }), note(9)];
    const calls = signedIn((call) => {
      if (call.method === 'POST' && call.path === '/api/notifications/read')
        return new Response(null, { status: 204 });
      if (call.path.startsWith('/api/notifications')) return page(items, 2);
      return undefined;
    });

    renderApp('/inbox');

    expect(await screen.findByRole('heading', { level: 1, name: 'Inbox' })).toBeVisible();
    const entries = await screen.findAllByRole('listitem');
    expect(entries).toHaveLength(3);
    expect(entries[0]).toHaveTextContent('“Cosplay photo walk” was cancelled.');
    expect(entries[1]).toHaveTextContent('You’re confirmed for “Cosplay photo walk”.');
    // Unread entries are marked in words, not colour alone.
    expect(entries[0]).toHaveTextContent('New');
    expect(entries[1]).not.toHaveTextContent('New');

    await waitFor(() =>
      expect(calls.filter((c) => c.path === '/api/notifications/read')).toHaveLength(1),
    );
    expect(calls.find((c) => c.path === '/api/notifications/read')?.body).toEqual({ upTo: 12 });
    // Marking read doesn't reshuffle the list the person is reading.
    expect(entries[0]).toHaveTextContent('New');
  });

  it('sends nothing when everything on screen is already read', async () => {
    const calls = signedIn((call) =>
      call.path.startsWith('/api/notifications') ? page([note(5, { read: true })], 0) : undefined,
    );
    renderApp('/inbox');
    await screen.findByText(/was cancelled/);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('loads older entries with Load more, using the cursor', async () => {
    const calls = signedIn((call) => {
      if (call.method === 'POST') return new Response(null, { status: 204 });
      if (call.path.includes('cursor=abc')) return page([note(3, { read: true })], 0);
      if (call.path.startsWith('/api/notifications?limit=20')) return page([note(5)], 1, 'abc');
      if (call.path.startsWith('/api/notifications')) return page([], 1);
      return undefined;
    });
    renderApp('/inbox');

    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));

    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2));
    expect(calls.some((c) => c.path.includes('cursor=abc'))).toBe(true);
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('opens an event that still exists', async () => {
    signedIn((call) => {
      if (call.method === 'POST') return new Response(null, { status: 204 });
      if (call.path === `/api/events/${EVENT_ID}`)
        return jsonResponse({ responseType: 'ERROR', body: { errorType: 'X' } }, 500);
      if (call.path.startsWith('/api/notifications')) return page([note(5)], 1);
      return undefined;
    });
    const router = renderApp('/inbox');

    fireEvent.click(await screen.findByRole('link', { name: 'View event' }));

    // Not a 404, so it goes on: the event page says what it has to say.
    await waitFor(() => expect(router.state.location.pathname).toBe(`/events/${EVENT_ID}`));
  });

  it('says "no longer available" when the event now returns 404, and stays here', async () => {
    signedIn((call) => {
      if (call.method === 'POST') return new Response(null, { status: 204 });
      if (call.path === `/api/events/${EVENT_ID}`) return jsonResponse(errorEnvelope, 404);
      if (call.path.startsWith('/api/notifications')) return page([note(5)], 1);
      return undefined;
    });
    const router = renderApp('/inbox');

    fireEvent.click(await screen.findByRole('link', { name: 'View event' }));

    expect(await screen.findByText('This event is no longer available.')).toBeVisible();
    expect(router.state.location.pathname).toBe('/inbox');
    expect(screen.queryByRole('link', { name: 'View event' })).toBeNull();
  });

  const quiet = (items: Notification[], unread = 1) =>
    signedIn((call) => {
      if (call.method === 'POST') return new Response(null, { status: 204 });
      if (call.path === `/api/events/${EVENT_ID}`) return jsonResponse(errorEnvelope, 500);
      if (call.path.startsWith('/api/notifications')) return page(items, unread);
      return undefined;
    });

  /** A group's entries, by its heading. */
  const group = (name: string) =>
    within(screen.getByRole('region', { name })).getAllByRole('listitem');

  it('groups today’s entries apart from earlier ones', async () => {
    const lastWeek = new Date(now - 7 * 86_400_000).toISOString();
    quiet([
      note(12, { createdAt: new Date().toISOString() }),
      note(4, { type: 'SPOT_CONFIRMED', createdAt: lastWeek }),
    ]);
    renderApp('/inbox');

    await screen.findByRole('region', { name: 'Today' });
    expect(group('Today')).toHaveLength(1);
    expect(group('Today')[0]).toHaveTextContent('was cancelled');
    expect(group('Earlier')).toHaveLength(1);
    expect(group('Earlier')[0]).toHaveTextContent('You’re confirmed');
  });

  it('leaves out a group with nothing in it', async () => {
    quiet([note(4, { createdAt: new Date(now - 7 * 86_400_000).toISOString() })]);
    renderApp('/inbox');

    await screen.findByRole('region', { name: 'Earlier' });
    expect(screen.queryByRole('region', { name: 'Today' })).toBeNull();
  });

  it('offers Review offer on an open offer, which opens the event', async () => {
    const offerExpiresAt = new Date(now + 600_000).toISOString();
    quiet([note(5, { type: 'SPOT_OFFERED', payload: { eventTitle: 'Walk', offerExpiresAt } })]);
    const router = renderApp('/inbox');

    fireEvent.click(await screen.findByRole('link', { name: 'Review offer' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/events/${EVENT_ID}`));
  });

  it('offers no review once the deadline has passed', async () => {
    const offerExpiresAt = new Date(now - 600_000).toISOString();
    quiet([note(5, { type: 'SPOT_OFFERED', payload: { eventTitle: 'Walk', offerExpiresAt } })]);
    renderApp('/inbox');

    expect(await screen.findByRole('link', { name: 'View event' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Review offer' })).toBeNull();
  });

  it('takes the dots off with Mark all read, and keeps the keyboard on the button', async () => {
    const calls = quiet([note(12), note(9)], 2);
    renderApp('/inbox');

    expect(await screen.findByText('2 unread')).toBeInTheDocument();
    expect(document.querySelectorAll('[data-unread]')).toHaveLength(2);
    const button = screen.getByRole('button', { name: 'Mark all read' });
    button.focus();
    fireEvent.click(button);

    await waitFor(() => expect(document.querySelectorAll('[data-unread]')).toHaveLength(0));
    expect(screen.queryByText('2 unread')).toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent('All marked as read.');
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveFocus();
    const posts = calls.filter((c) => c.method === 'POST');
    expect(posts.at(-1)?.body).toEqual({ upTo: 12 });
    // Pressed again, there is nothing to send.
    fireEvent.click(button);
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(posts.length);
  });

  it('says so when there is nothing yet', async () => {
    signedIn((call) => (call.path.startsWith('/api/notifications') ? page([], 0) : undefined));
    renderApp('/inbox');
    expect(await screen.findByText(/Nothing yet/)).toBeVisible();
  });
});
