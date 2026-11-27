import '@testing-library/jest-dom/vitest';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearUnavailable, markUnavailable } from './api/maintenance';
import { createQueryClient } from './api/queryClient';
import type { components } from './api/schema';
import {
  envelope,
  errorEnvelope,
  findSignIn,
  guilds,
  jsonResponse,
  me,
  mockApi,
  renderApp,
  type RecordedCall,
} from './test-utils';
import { routes } from './router';

type Schemas = components['schemas'];

const moderator: Schemas['MeResponse'] = { ...me, mfaEnabled: true, roles: ['USER', 'MODERATOR'] };
const ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

/** A signed-in user with nothing anywhere. An event or a circle by ID is a 404, unless `extra` answers. */
function signedIn({
  meBody = me,
  unread = 0,
  extra = (() => undefined) as (call: RecordedCall) => Response | undefined,
} = {}) {
  return mockApi((call) => {
    const { path } = call;
    const custom = extra(call);
    if (custom) return custom;
    if (path === '/api/me') return envelope('ME', meBody);
    if (path.startsWith('/api/notifications')) {
      return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: unread });
    }
    if (path.startsWith('/api/events?')) {
      return envelope('EVENT_PAGE', { events: [], nextCursor: null });
    }
    if (path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
    if (path === '/api/friends' || path === '/api/blocks') {
      return envelope('PERSON_LIST', { people: [] });
    }
    if (path === '/api/circles') return envelope('CIRCLE_LIST', { circles: [] });
    if (path === '/api/push/key') return jsonResponse(errorEnvelope, 404);
    if (path.startsWith('/api/admin/reports')) {
      return envelope('REPORT_PAGE', { items: [], nextCursor: null });
    }
    return jsonResponse(errorEnvelope, 404);
  });
}

const browserRouters: ReturnType<typeof createBrowserRouter>[] = [];

/** Like `renderApp`, on the browser's own history, which Back reads. */
function renderInBrowser(path: string) {
  window.history.replaceState(null, '', path);
  const router = createBrowserRouter(routes);
  browserRouters.push(router);
  const queryClient = createQueryClient();
  queryClient.setDefaultOptions({ queries: { retry: false }, mutations: { retry: false } });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const where = () => window.location.pathname + window.location.search;
const goBack = async () => fireEvent.click(await screen.findByRole('button', { name: 'Back' }));

const mine: Schemas['EventResponse'] = {
  id: ID,
  title: 'My panel',
  description: null,
  location: 'Hall B',
  locationHidden: false,
  locationVisibility: 'VIEWERS',
  tag: null,
  host: { id: me.id, displayName: 'Con Goer', avatarUrl: me.avatarUrl },
  isHost: true,
  myStatus: 'CONFIRMED',
  status: 'PUBLISHED',
  hiddenReason: null,
  audience: [{ type: 'FRIENDS', label: null, guildId: null, circleId: null, userId: null }],
  capacity: null,
  spotsLeft: null,
  visibleBecause: null,
  signupOpensAt: null,
  confirmWindowMinutes: 30,
  startsAt: new Date(Date.now() + 30 * 86_400_000).toISOString(),
  endsAt: new Date(Date.now() + 31 * 86_400_000).toISOString(),
  timeZone: 'America/Chicago',
};

/** Answers for the host's own event: reading it, saving it and creating it. */
function myEvent({ method, path }: RecordedCall) {
  if (path === `/api/events/${ID}`) return envelope('EVENT', mine);
  if (method === 'POST' && path === '/api/events') return envelope('EVENT', mine);
  return undefined;
}

afterEach(() => {
  browserRouters.splice(0).forEach((router) => router.dispose());
  window.history.replaceState(null, '', '/');
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  clearUnavailable();
});

// jsdom applies no CSS, so the desktop and the phone navigation are both there.
const desktopNav = () => document.querySelector<HTMLElement>('[data-sidebar]')!;
const findSidebar = () => waitFor(() => expect(desktopNav()).not.toBeNull());
const sidebar = () => within(desktopNav());
const tabBar = () => document.querySelector<HTMLElement>('[data-tabbar]');
const topBar = () => within(screen.getByRole('banner'));

/** The text and `aria-current` of the links marked as current. */
const current = (element: HTMLElement) =>
  within(element)
    .queryAllByRole('link')
    .filter((link) => link.hasAttribute('aria-current'))
    .map((link) => [link.textContent, link.getAttribute('aria-current')]);

describe('the old paths', () => {
  it.each([
    ['/schedule', '/plans', '', 'My schedule'],
    ['/friends', '/people', '', 'People'],
    ['/circles', '/people', '?tab=circles', 'People'],
    ['/notifications', '/inbox', '', 'Notifications'],
    ['/settings', '/you', '', 'Settings'],
  ])('replaces %s with %s%s', async (from, pathname, search, heading) => {
    signedIn();
    const router = renderApp(from);

    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument();
    expect(router.state.location).toMatchObject({ pathname, search });
    expect(router.state.historyAction).toBe('REPLACE');
  });

  it('leaves nothing behind for Back to return to', async () => {
    signedIn();
    const router = renderApp('/');
    await findSidebar();

    await router.navigate('/settings');
    await waitFor(() => expect(router.state.location.pathname).toBe('/you'));
    await router.navigate(-1);

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });

  it('shows the blocked people at /people?tab=blocked', async () => {
    signedIn();
    renderApp('/people?tab=blocked');

    expect(await screen.findByText('You haven’t blocked anyone.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /^Blocked/ })).toHaveAttribute('aria-current', 'page');
  });
});

describe('the current place', () => {
  it.each([
    ['/', 'Discover'],
    ['/plans', 'My plans'],
    ['/people?tab=circles', 'People'],
    ['/inbox', 'Inbox'],
  ])('marks %s in the sidebar and the tab bar', async (path, label) => {
    signedIn();
    renderApp(path);
    await findSidebar();

    expect(current(desktopNav())).toEqual([[label, 'page']]);
    expect(current(tabBar()!)).toEqual([[label, 'page']]);
  });

  it.each([
    ['/you', 'Settings', 'page'],
    ['/events/new', 'Host a meetup', 'page'],
    [`/circles/${ID}`, 'People', 'true'],
  ])('marks %s in the sidebar', async (path, label, value) => {
    signedIn();
    renderApp(path);
    await findSidebar();

    expect(current(desktopNav())).toEqual([[label, value]]);
  });

  it('links the five places from the tab bar, in order', async () => {
    signedIn();
    renderApp('/');
    await findSidebar();

    const links = within(tabBar()!).getAllByRole('link');
    expect(links.map((a) => [a.textContent, a.getAttribute('href')])).toEqual([
      ['Discover', '/'],
      ['My plans', '/plans'],
      ['Host', '/events/new'],
      ['People', '/people'],
      ['Inbox', '/inbox'],
    ]);
  });

  it('links the avatar and your name to You', async () => {
    signedIn();
    renderApp('/');
    await findSidebar();

    expect(topBar().getByRole('link', { name: 'You' })).toHaveAttribute('href', '/you');
    expect(sidebar().getByRole('link', { name: /Con Goer/ })).toHaveAttribute('href', '/you');
    expect(sidebar().getByRole('link', { name: 'Host a meetup' })).toHaveAttribute(
      'href',
      '/events/new',
    );
  });
});

describe('the unread badge', () => {
  it('is on Inbox in the sidebar and the tab bar', async () => {
    signedIn({ unread: 3 });
    renderApp('/');
    await findSidebar();

    for (const nav of [desktopNav(), tabBar()!]) {
      const inbox = await within(nav).findByRole('link', { name: 'Inbox, 3 unread' });
      expect(inbox).toHaveTextContent('3');
    }
  });
});

describe('the Moderation link', () => {
  it('is there for a moderator, in the sidebar and the top bar', async () => {
    signedIn({ meBody: moderator });
    renderApp('/');
    await findSidebar();

    expect(sidebar().getByRole('link', { name: 'Moderation' })).toHaveAttribute('href', '/admin');
    expect(topBar().getByRole('link', { name: 'Moderation' })).toHaveAttribute('href', '/admin');
  });

  it.each([
    ['a plain user', me],
    ['a moderator without 2FA', { ...me, mfaRequired: true }],
  ])('is not there for %s, and /admin is not named', async (_, meBody) => {
    signedIn({ meBody });
    renderApp('/admin');
    await findSidebar();

    expect(screen.queryByRole('link', { name: 'Moderation' })).toBeNull();
    expect(screen.queryByText('Moderation')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
  });

  it('is marked on the pages under /admin', async () => {
    signedIn({ meBody: moderator });
    renderApp('/admin');
    await screen.findByRole('heading', { name: 'Moderation' });

    expect(current(desktopNav())).toEqual([['Moderation', 'page']]);
    expect(topBar().getByText('Moderation')).toBeInTheDocument();
  });
});

describe('detail pages', () => {
  it.each([
    ['/events/new', 'Host a meetup'],
    [`/events/${ID}`, 'Meetup'],
    [`/events/${ID}/edit`, 'Edit meetup'],
    [`/circles/${ID}`, 'Circle'],
  ])('%s hides the tab bar and has Back and a title', async (path, title) => {
    signedIn();
    renderApp(path);
    await findSidebar();

    expect(tabBar()).toBeNull();
    expect(topBar().getByRole('button', { name: 'Back' })).toBeInTheDocument();
    expect(topBar().getByText(title)).toBeInTheDocument();
    expect(topBar().queryByRole('link', { name: 'You' })).toBeNull();
  });

  it('keeps the tab bar on You, with Back and a title', async () => {
    signedIn();
    renderApp('/you');
    await findSidebar();

    expect(tabBar()).not.toBeNull();
    expect(topBar().getByRole('button', { name: 'Back' })).toBeInTheDocument();
    expect(topBar().getByText('You')).toBeInTheDocument();
  });

  it('goes Back to the page before', async () => {
    signedIn();
    const router = renderInBrowser('/plans');
    await findSidebar();
    await router.navigate('/events/new');

    await goBack();

    await waitFor(() => expect(where()).toBe('/plans'));
  });

  it.each([
    ['/events/new', '/'],
    [`/events/${ID}`, '/'],
    [`/events/${ID}/edit`, `/events/${ID}`],
    [`/circles/${ID}`, '/people?tab=circles'],
  ])('goes Back from %s to %s when the visit started there', async (path, parent) => {
    signedIn();
    const router = renderInBrowser(path);

    await goBack();

    await waitFor(() => expect(where()).toBe(parent));
    expect(router.state.historyAction).toBe('REPLACE');
  });

  it('goes Back from You to Discover when an old path started the visit', async () => {
    signedIn();
    renderInBrowser('/settings');
    await waitFor(() => expect(where()).toBe('/you'));

    await goBack();

    await waitFor(() => expect(where()).toBe('/'));
  });

  it('goes Back from a new event past the form it was created in', async () => {
    signedIn({ extra: myEvent });
    const router = renderInBrowser('/plans');
    await findSidebar();
    await router.navigate('/events/new');

    fireEvent.change(await screen.findByLabelText('Title'), { target: { value: 'My panel' } });
    fireEvent.change(screen.getByLabelText('Location'), { target: { value: 'Hall B' } });
    const starts = new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 16);
    fireEvent.change(screen.getByLabelText('Starts'), { target: { value: starts } });
    fireEvent.click(screen.getByLabelText('All my friends'));
    fireEvent.click(screen.getByRole('button', { name: 'Create meetup' }));
    await waitFor(() => expect(where()).toBe(`/events/${ID}`));
    await goBack();

    await waitFor(() => expect(where()).toBe('/plans'));
  });

  it('goes Back from a saved event past the edit form', async () => {
    signedIn({ extra: myEvent });
    const router = renderInBrowser('/plans');
    await findSidebar();
    await router.navigate(`/events/${ID}`);
    await router.navigate(`/events/${ID}/edit`);

    fireEvent.click(await screen.findByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(where()).toBe(`/events/${ID}`));
    await goBack();

    await waitFor(() => expect(where()).toBe('/plans'));
  });
});

describe('around the page', () => {
  it('puts the footer on You only, once signed in', async () => {
    signedIn();
    const router = renderApp('/');
    await findSidebar();
    expect(screen.queryByRole('contentinfo')).toBeNull();

    await router.navigate('/you');

    expect(await screen.findByRole('contentinfo')).toHaveTextContent('Free meetups');
  });

  it('gives a signed-out visitor no navigation, and the footer', async () => {
    mockApi(() => jsonResponse(errorEnvelope, 401));
    renderApp('/welcome');

    expect(await findSignIn()).toBeInTheDocument();
    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
  });

  it('keeps the navigation when maintenance mode replaces the page', async () => {
    signedIn();
    renderApp('/plans');
    await findSidebar();

    markUnavailable();

    expect(await screen.findByRole('heading', { name: 'Back soon' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'My schedule' })).toBeNull();
    expect(tabBar()).not.toBeNull();
  });
});
