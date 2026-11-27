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

const ada: Schemas['PersonResponse'] = {
  id: '11111111-1111-4111-8111-111111111111',
  displayName: 'Ada',
  avatarUrl: 'https://cdn.discordapp.com/embed/avatars/1.png',
};

const HOUR = 3_600_000;
const START = Date.now() + 48 * HOUR;
const walk: EventBody = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  title: 'Cosplay photo walk',
  description: null,
  location: 'Hall B fountain',
  confirmWindowMinutes: 30,
  locationHidden: false,
  locationVisibility: 'VIEWERS',
  tag: null,
  host: ada,
  hiddenReason: null,
  isHost: false,
  myStatus: 'NONE',
  status: 'PUBLISHED',
  audience: null,
  capacity: 8,
  spotsLeft: 3,
  visibleBecause: [],
  signupOpensAt: null,
  startsAt: new Date(START).toISOString(),
  endsAt: new Date(START + 2 * HOUR).toISOString(),
  timeZone: 'America/Chicago',
};

/** An event of its own, told apart by its title. */
const event = (title: string, extra: Partial<EventBody> = {}): EventBody => ({
  ...walk,
  id: `event-${title.replaceAll(' ', '-')}`,
  title,
  ...extra,
});

const page = (events: EventBody[], nextCursor: string | null = null) =>
  envelope('EVENT_PAGE', { events, nextCursor });

interface Server {
  feed?: EventBody[];
  plans?: EventBody[];
  extra?: (call: RecordedCall) => Response | undefined;
}

/** Signed in, with a feed, the viewer's plans and their servers. */
function signedIn({ feed = [], plans = [], extra }: Server = {}) {
  return mockApi((call) => {
    const custom = extra?.(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
    if (call.path.startsWith('/api/notifications'))
      return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: 0 });
    if (call.path.startsWith('/api/events?scope=feed')) return page(feed);
    if (call.path.startsWith('/api/events?scope=attending')) return page(plans);
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

const card = async (title: string) =>
  within(await screen.findByRole('link', { name: new RegExp(`^${title}`) }));

afterEach(() => vi.unstubAllGlobals());

describe('the event card', () => {
  const PILLS = /^(Hosting|Going|Waitlisted|Spot offered|Removed|Full · join waitlist)$/;

  it.each<[string, Partial<EventBody>, string]>([
    ['the host', { isHost: true, myStatus: 'CONFIRMED', visibleBecause: null }, 'Hosting'],
    ['someone going', { myStatus: 'CONFIRMED' }, 'Going'],
    ['someone waiting', { myStatus: 'WAITLISTED', spotsLeft: 0 }, 'Waitlisted'],
    ['someone offered a spot', { myStatus: 'OFFERED', spotsLeft: 0 }, 'Spot offered'],
    ['someone removed', { myStatus: 'REMOVED' }, 'Removed'],
    ['a newcomer to a full event', { spotsLeft: 0 }, 'Full · join waitlist'],
  ])('shows %s one status pill', async (_who, extra, pill) => {
    signedIn({ feed: [event('Panel', extra)] });
    renderApp('/');

    const panel = await card('Panel');
    expect(panel.getAllByText(PILLS).map((found) => found.textContent)).toEqual([pill]);
  });

  it.each<[string, Partial<EventBody>]>([
    ['there are spots', {}],
    // Sign-up closes at the start, so there is no waitlist to join.
    [
      'a full event has started',
      { spotsLeft: 0, startsAt: new Date(Date.now() - HOUR).toISOString() },
    ],
    [
      'a full event’s sign-up hasn’t opened',
      { spotsLeft: 0, signupOpensAt: new Date(Date.now() + HOUR).toISOString() },
    ],
  ])('shows a newcomer no pill when %s', async (_when, extra) => {
    signedIn({ feed: [event('Panel', extra)] });
    renderApp('/');

    expect((await card('Panel')).queryByText(PILLS)).toBeNull();
  });

  it('says a cancelled event is cancelled, in place of its spots', async () => {
    signedIn({
      feed: [event('Panel', { status: 'CANCELLED', myStatus: 'CONFIRMED', spotsLeft: 0 })],
    });
    renderApp('/');

    const panel = await card('Panel');
    expect(panel.getByText('Cancelled')).toBeInTheDocument();
    expect(panel.getByText('Going')).toBeInTheDocument();
    expect(panel.queryByText('Full')).toBeNull();
  });

  it('counts the spots: some left, none left, or no limit', async () => {
    signedIn({
      feed: [
        event('Open'),
        event('Packed', { spotsLeft: 0 }),
        event('Endless', { capacity: null, spotsLeft: null }),
      ],
    });
    renderApp('/');

    expect((await card('Open')).getByText('3 of 8 left')).toBeInTheDocument();
    expect((await card('Packed')).getByText('Full')).toBeInTheDocument();
    expect((await card('Endless')).getByText('No limit')).toBeInTheDocument();
  });

  it('shows the time block, tag, location and the host’s avatar', async () => {
    signedIn({
      feed: [event('Panel', { tag: 'Tabletop', startsAt: '2030-07-01T20:00:00Z', endsAt: null })],
    });
    renderApp('/');

    const link = await screen.findByRole('link', { name: /^Panel/ });
    // The block is for the eye: the time line says the same, with the zone.
    const block = link.querySelector('[aria-hidden="true"].when-block');
    expect(block).toHaveTextContent('3:00PM');
    expect(link).toHaveTextContent('CDT');
    expect(within(link).getByText('Tabletop')).toBeInTheDocument();
    expect(within(link).getByText('Hall B fountain')).toBeInTheDocument();
    expect(link.querySelector('img')).toHaveAttribute('src', ada.avatarUrl);
    // Covers come with #124.
    expect(link.querySelectorAll('img')).toHaveLength(1);
  });

  it('shows a lock in place of a hidden location', async () => {
    signedIn({ feed: [event('Panel', { location: null, locationHidden: true })] });
    renderApp('/');

    const panel = await card('Panel');
    expect(panel.getByText('Location shared with confirmed attendees')).toBeInTheDocument();
    expect(panel.queryByText('Hall B fountain')).toBeNull();
  });

  it('gives the viewer’s own reasons for seeing it, beside the host', async () => {
    signedIn({
      feed: [
        event('Server one', {
          visibleBecause: [
            { type: 'GUILD', guildName: 'Test Con' },
            { type: 'GUILD', guildName: 'Anime Weekend' },
            { type: 'FRIEND', guildName: null },
            { type: 'INVITED', guildName: null },
          ],
        }),
        // Seen only through a spot they already hold: no reasons, and nothing in their place.
        event('Kept', { visibleBecause: [], myStatus: 'CONFIRMED' }),
        event('Mine', { isHost: true, myStatus: 'CONFIRMED', visibleBecause: null }),
      ],
    });
    renderApp('/');

    expect(
      (await card('Server one')).getByText(
        'Hosted by Ada · Test Con · Anime Weekend · Friend of the host · Invited',
      ),
    ).toBeInTheDocument();
    expect((await card('Kept')).getByText('Hosted by Ada')).toBeInTheDocument();
    expect((await card('Mine')).getByText('You’re hosting')).toBeInTheDocument();
  });
});

describe('Discover', () => {
  it('names the servers you’re discoverable in above the heading', async () => {
    signedIn();
    renderApp('/');

    const heading = await screen.findByRole('heading', { level: 1, name: 'Discover' });
    await waitFor(() =>
      expect(heading.previousElementSibling).toHaveTextContent(/^Cosplay Corner$/),
    );
  });

  it('heads each day with its weekday and date', async () => {
    signedIn({
      feed: [
        event('Saturday one', { startsAt: '2030-07-06T20:00:00Z', endsAt: null }),
        event('Sunday one', { startsAt: '2030-07-07T20:00:00Z', endsAt: null }),
      ],
    });
    renderApp('/');

    const days = await screen.findAllByRole('heading', { level: 2 });
    expect(days.map((day) => day.textContent)).toEqual(['Saturday Jul 6', 'Sunday Jul 7']);
  });

  it('shows placeholders while the first page loads', async () => {
    signedIn({ feed: [walk] });
    renderApp('/');

    expect(await screen.findByRole('status', { name: 'Loading events' })).toBeInTheDocument();
    await card(walk.title);
    expect(screen.queryByRole('status', { name: 'Loading events' })).toBeNull();
  });

  it('shows an error with a retry when the feed fails', async () => {
    let failing = true;
    signedIn({
      feed: [walk],
      extra: ({ path }) =>
        failing && path.startsWith('/api/events?scope=feed')
          ? jsonResponse(errorEnvelope, 500)
          : undefined,
    });
    renderApp('/');

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    failing = false;
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    await card(walk.title);
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('search and filters', () => {
  const [alpha, beta] = ['81384788765712385', '81384788765712386'];
  const servers: Schemas['GuildResponse'][] = [
    { id: '81384788765712384', name: 'Quiet Server', iconUrl: null, discoverable: false },
    { id: beta, name: 'Test Con', iconUrl: null, discoverable: true },
    { id: alpha, name: 'Anime Weekend', iconUrl: null, discoverable: true },
  ];
  const FEED = '/api/events?scope=feed&limit=20';

  /** Signed in with those servers. `answer` may take over the feed. */
  function filtering(answer: (path: string) => Response | undefined = () => undefined) {
    return signedIn({
      feed: [walk],
      extra: ({ path }) => {
        if (path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds: servers });
        return path.startsWith('/api/events?scope=feed') ? answer(path) : undefined;
      },
    });
  }

  const feedCalls = (calls: RecordedCall[]) =>
    calls.map((c) => c.path).filter((path) => path.startsWith('/api/events?scope=feed'));
  const box = () => screen.findByRole('searchbox', { name: 'Search meetups' });
  const chips = async () => within(await screen.findByRole('group', { name: 'Filters' }));
  const chip = async (name: string) => (await chips()).findByRole('button', { name });
  const pressed = async () =>
    (await chips()).getAllByRole('button', { pressed: true }).map((button) => button.textContent);

  it('offers All, each server you’re discoverable in, Friends and Has spots', async () => {
    filtering();
    renderApp('/');

    await chip('Test Con');
    const names = (await chips()).getAllByRole('button').map((button) => button.textContent);
    expect(names).toEqual(['All', 'Anime Weekend', 'Test Con', 'Friends', 'Has spots']);
    expect(await pressed()).toEqual(['All']);
  });

  it('searches after a pause in typing, trimmed, keeping what was typed', async () => {
    const calls = filtering();
    const router = renderApp('/');
    await card(walk.title);
    const search = await box();

    fireEvent.change(search, { target: { value: 'bo' } });
    fireEvent.change(search, { target: { value: ' board ' } });

    await waitFor(() => expect(feedCalls(calls)).toEqual([FEED, `${FEED}&q=board`]));
    expect(router.state.location.search).toBe('?q=board');
    expect(router.state.historyAction).toBe('REPLACE');
    expect(search).toHaveValue(' board ');
    expect(search).toHaveAttribute('maxlength', '100');
  });

  it('searches at once on Enter', async () => {
    filtering();
    const router = renderApp('/');

    fireEvent.change(await box(), { target: { value: 'ramen' } });
    fireEvent.submit(screen.getByRole('search'));

    await waitFor(() => expect(router.state.location.search).toBe('?q=ramen'));
  });

  it('keeps a single character in the URL without asking for it', async () => {
    const calls = filtering();
    const router = renderApp('/');
    await card(walk.title);

    fireEvent.change(await box(), { target: { value: 'a' } });

    await waitFor(() => expect(router.state.location.search).toBe('?q=a'));
    await card(walk.title);
    expect(feedCalls(calls).some((path) => path.includes('q='))).toBe(false);
  });

  it('puts each chip in the URL and the request, one server at a time', async () => {
    const calls = filtering();
    const router = renderApp('/?q=board');
    const click = async (name: string) => fireEvent.click(await chip(name));
    const url = (search: string) =>
      waitFor(() => expect(router.state.location.search).toBe(search));

    await click('Test Con');
    await url(`?q=board&guild=${beta}`);
    await click('Anime Weekend');
    await url(`?q=board&guild=${alpha}`);
    await click('Friends');
    await url(`?q=board&guild=${alpha}&friends=true`);
    await click('Has spots');
    await url(`?q=board&guild=${alpha}&friends=true&hasSpots=true`);
    await waitFor(() =>
      expect(feedCalls(calls).at(-1)).toBe(
        `${FEED}&q=board&guild=${alpha}&friends=true&hasSpots=true`,
      ),
    );
    expect(await pressed()).toEqual(['Anime Weekend', 'Friends', 'Has spots']);

    // Pressing a chip again turns it off.
    await click('Anime Weekend');
    await url('?q=board&friends=true&hasSpots=true');
    // All clears the chips and leaves the search.
    await click('All');
    await url('?q=board');
    expect(await pressed()).toEqual(['All']);
  });

  it('opens a filtered URL as it stands, once it knows the server is yours', async () => {
    const calls = filtering();
    renderApp(`/?q=board&guild=${beta}&hasSpots=true`);

    await card(walk.title);
    // Nothing is asked until the servers are known.
    expect(feedCalls(calls)).toEqual([`${FEED}&q=board&guild=${beta}&hasSpots=true`]);
    expect(await box()).toHaveValue('board');
    expect(await pressed()).toEqual(['Test Con', 'Has spots']);
  });

  it('leaves out a server you aren’t discoverable in, and values that aren’t true', async () => {
    const calls = filtering();
    const router = renderApp('/?guild=81384788765712384&friends=false&hasSpots=1');

    await card(walk.title);
    expect(feedCalls(calls)).toEqual([FEED]);
    expect(await pressed()).toEqual(['All']);

    // The next change takes the server out of the URL.
    fireEvent.click(await chip('Friends'));
    await waitFor(() => expect(router.state.location.search).toBe('?friends=true&hasSpots=1'));
  });

  it('leaves the server out when your servers can’t be loaded', async () => {
    const calls = signedIn({
      feed: [walk],
      extra: ({ path }) =>
        path === '/api/me/guilds' ? jsonResponse(errorEnvelope, 500) : undefined,
    });
    renderApp(`/?guild=${beta}&friends=true`);

    await card(walk.title);
    expect(feedCalls(calls)).toEqual([`${FEED}&friends=true`]);
  });

  it('keeps the last list on screen, quiet, while the next one loads', async () => {
    // A search that never answers.
    const unanswered = new Promise<Response>(() => {}) as unknown as Response;
    const calls = filtering((path) => (path.includes('q=') ? unanswered : page([walk], 'next-1')));
    renderApp('/');
    await card(walk.title);

    fireEvent.change(await box(), { target: { value: 'ramen' } });

    await waitFor(() => expect(feedCalls(calls).at(-1)).toBe(`${FEED}&q=ramen`));
    expect(screen.getByRole('link', { name: new RegExp(`^${walk.title}`) })).toBeVisible();
    expect(screen.queryByRole('status', { name: 'Loading events' })).toBeNull();
    // The old list's next page belongs to the old search, so it isn't offered.
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
    expect(within(screen.getByRole('main')).getByRole('status')).toBeEmptyDOMElement();
  });

  it('keeps the search and filters when loading more', async () => {
    const calls = filtering((path) =>
      path.includes('cursor=') ? page([event('Second page')]) : page([walk], 'next-1'),
    );
    renderApp('/?q=board&friends=true');

    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));

    await card('Second page');
    expect(feedCalls(calls).at(-1)).toBe(`${FEED}&q=board&friends=true&cursor=next-1`);
  });

  it('says when nothing matches, and clears the search and filters', async () => {
    const calls = filtering((path) => (path === FEED ? undefined : page([])));
    const router = renderApp('/?q=zzz&friends=true');

    expect(await screen.findByRole('heading', { name: 'No meetups match' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'No upcoming meetups yet' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

    await card(walk.title);
    // The button has gone; the focus stays with the filters.
    expect(await chip('All')).toHaveFocus();
    expect(router.state.location.search).toBe('');
    expect(await box()).toHaveValue('');
    expect(await pressed()).toEqual(['All']);
    expect(feedCalls(calls).at(-1)).toBe(FEED);
  });

  it.each<[string, EventBody[], string | null, string]>([
    ['nothing', [], null, 'No meetups match'],
    ['one', [walk], null, '1 meetup matches'],
    ['several', [walk, event('Other')], null, '2 meetups match'],
    ['more than a page', [walk, event('Other')], 'next-1', 'More than 2 meetups match'],
  ])('tells a screen reader when a search finds %s', async (_what, events, cursor, words) => {
    filtering((path) => (path === FEED ? undefined : page(events, cursor)));
    renderApp('/');
    await card(walk.title);
    // There before the search, and quiet: only a change to it is announced.
    const result = within(screen.getByRole('main')).getByRole('status');
    expect(result).toBeEmptyDOMElement();

    fireEvent.change(await box(), { target: { value: 'walk' } });

    await waitFor(() => expect(result).toHaveTextContent(words));
    expect(result).toBeInTheDocument();
  });

  it('empties the search box when the Discover link drops the search', async () => {
    filtering();
    const router = renderApp('/?q=board');
    expect(await box()).toHaveValue('board');

    await router.navigate('/');

    await waitFor(async () => expect(await box()).toHaveValue(''));
    expect(router.state.location.search).toBe('');
  });

  it('has none of it on the Hosting tab, which asks for no filters', async () => {
    const calls = signedIn({
      extra: ({ path }) =>
        path.startsWith('/api/events?scope=hosting') ? page([walk]) : undefined,
    });
    renderApp('/?tab=hosting&q=board&friends=true');

    await card(walk.title);
    expect(screen.queryByRole('searchbox')).toBeNull();
    expect(screen.queryByRole('group', { name: 'Filters' })).toBeNull();
    expect(calls.map((c) => c.path)).toContain('/api/events?scope=hosting&limit=20');
  });
});

describe('the side cards', () => {
  const offer = event('Board games', { myStatus: 'OFFERED', spotsLeft: 0 });
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
  const region = (name: string) => screen.findByRole('region', { name });

  it('counts down each open offer from /spots, with a way to review it', async () => {
    onDesktop();
    const calls = signedIn({
      plans: [
        offer,
        // Nothing can be accepted on a cancelled event (D-014 rule 16).
        event('Called off', { myStatus: 'OFFERED', status: 'CANCELLED' }),
      ],
      extra: ({ path }) => (path === `/api/events/${offer.id}/spots` ? spots() : undefined),
    });
    renderApp('/');

    const needs = within(await region('Needs your answer'));
    expect(await needs.findByRole('timer')).toHaveTextContent(/^(23m|24m) \d\ds$/);
    expect(needs.getByRole('timer')).toHaveAttribute('aria-live', 'off');
    expect(needs.getByRole('link', { name: 'Review offer: Board games' })).toHaveAttribute(
      'href',
      `/events/${offer.id}`,
    );
    expect(needs.queryByText('Called off')).toBeNull();
    expect(calls.filter((c) => c.path.endsWith('/spots'))).toHaveLength(1);
    expect(calls.some((c) => c.path === '/api/events?scope=attending&limit=50')).toBe(true);
  });

  it('drops an offer that has run out and asks again', async () => {
    onDesktop();
    let expired = false;
    const calls = signedIn({
      extra: ({ path }) => {
        if (path.startsWith('/api/events?scope=attending')) return page(expired ? [] : [offer]);
        if (path === `/api/events/${offer.id}/spots`) {
          expired = true;
          return spots({ myStatus: 'NONE', offerExpiresAt: null });
        }
        return undefined;
      },
    });
    renderApp('/');

    await waitFor(() =>
      expect(
        calls.filter((c) => c.path.startsWith('/api/events?scope=attending')).length,
      ).toBeGreaterThan(1),
    );
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: 'Needs your answer' })).toBeNull(),
    );
  });

  it('lists the next two events you’re hosting or going to', async () => {
    onDesktop();
    signedIn({
      plans: [
        event('Waiting', { myStatus: 'WAITLISTED' }),
        event('Called off', { myStatus: 'CONFIRMED', status: 'CANCELLED' }),
        event('Mine', { isHost: true, myStatus: 'CONFIRMED', visibleBecause: null }),
        event('Joined', {
          myStatus: 'CONFIRMED',
          startsAt: '2030-07-01T20:00:00Z',
          endsAt: null,
        }),
        event('Third', { myStatus: 'CONFIRMED' }),
      ],
    });
    renderApp('/');

    const next = within(await region('Next up for you'));
    const links = next.getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/events/event-Mine',
      '/events/event-Joined',
    ]);
    expect(links[0]).toHaveTextContent('you’re hosting');
    expect(links[1]).toHaveTextContent(/Mon, Jul 1, 3:00 PM CDT · going/);
    expect(screen.queryByRole('region', { name: 'Needs your answer' })).toBeNull();
  });

  it('names your discoverable servers and links to manage them', async () => {
    onDesktop();
    signedIn();
    renderApp('/');

    const servers = within(await region('Where you’re discoverable'));
    expect(servers.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Cosplay Corner',
    ]);
    expect(servers.getByRole('link', { name: 'Manage servers' })).toHaveAttribute('href', '/you');
    // With no plans, there is nothing to answer and nothing next.
    expect(screen.queryByRole('region', { name: 'Next up for you' })).toBeNull();
  });

  it('says so when you’re discoverable nowhere', async () => {
    onDesktop();
    signedIn({
      extra: ({ path }) =>
        path === '/api/me/guilds' ? envelope('GUILD_LIST', { guilds: [] }) : undefined,
    });
    renderApp('/');

    expect(
      await screen.findByText('You’re not discoverable in any server yet.'),
    ).toBeInTheDocument();
  });

  it('isn’t there on a phone, and nothing is asked for it', async () => {
    const calls = signedIn({ feed: [walk], plans: [offer] });
    renderApp('/');

    await card(walk.title);
    expect(screen.queryByRole('region', { name: 'Needs your answer' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Where you’re discoverable' })).toBeNull();
    expect(calls.some((c) => c.path.includes('scope=attending'))).toBe(false);
  });
});
