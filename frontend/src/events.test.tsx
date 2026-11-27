import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import type { components } from './api/schema';
import { browserTimeZone, timeZoneLabel, utcToZonedLocal } from './features/events/time';
import {
  envelope,
  errorEnvelope,
  guilds,
  jsonResponse,
  me,
  mockApi,
  onDesktop,
  renderApp,
  type RecordedCall,
} from './test-utils';

type Schemas = components['schemas'];

const ada: Schemas['PersonResponse'] = {
  id: '11111111-1111-4111-8111-111111111111',
  displayName: 'Ada',
  avatarUrl: 'https://cdn.discordapp.com/embed/avatars/1.png',
};
const bo: Schemas['PersonResponse'] = {
  id: '22222222-2222-4222-8222-222222222222',
  displayName: 'Bo',
  avatarUrl: 'https://cdn.discordapp.com/embed/avatars/2.png',
};
const crew: Schemas['CircleResponse'] = {
  id: '44444444-4444-4444-8444-444444444444',
  name: 'Cosplay crew',
  members: [ada],
};
const host: Schemas['PersonResponse'] = {
  id: me.id,
  displayName: 'Con Goer',
  avatarUrl: me.avatarUrl,
};

const walk: Schemas['EventResponse'] = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  title: 'Cosplay photo walk',
  description: 'Bring <b>props</b>.\nSecond line.',
  location: 'Hall B fountain',
  confirmWindowMinutes: 30,
  locationHidden: false,
  locationVisibility: 'VIEWERS',
  tag: 'Photoshoot',
  host: ada,
  hiddenReason: null,
  isHost: false,
  myStatus: 'NONE',
  status: 'PUBLISHED',
  audience: null,
  spotsLeft: null,
  visibleBecause: [],
  capacity: null,
  signupOpensAt: null,
  startsAt: '2030-07-01T20:00:00Z',
  endsAt: '2030-07-01T22:00:00Z',
  timeZone: 'America/Chicago',
};
// The form only accepts starts within a year of now, so the host's own event is 30 days out.
const MINE_START = new Date(Date.now() + 30 * 86_400_000);
MINE_START.setUTCSeconds(30, 123);
const mine: Schemas['EventResponse'] = {
  ...walk,
  startsAt: MINE_START.toISOString(),
  endsAt: new Date(MINE_START.getTime() + 2 * 3_600_000).toISOString(),
  id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  title: 'My panel',
  host,
  isHost: true,
  myStatus: 'CONFIRMED',
  locationVisibility: 'CONFIRMED',
  capacity: 20,
  audience: [
    { type: 'FRIENDS', label: null, guildId: null, circleId: null, userId: null },
    {
      type: 'GUILD',
      label: 'Cosplay Corner',
      guildId: '81384788765712386',
      circleId: null,
      userId: null,
    },
    { type: 'GUILD', label: null, guildId: '81384788765712399', circleId: null, userId: null },
    { type: 'USER', label: 'Bo', guildId: null, circleId: null, userId: bo.id },
  ],
};

const noContent = () => new Response(null, { status: 204 });
const page = (events: Schemas['EventResponse'][], nextCursor: string | null = null) =>
  envelope('EVENT_PAGE', { events, nextCursor });

/** Signed in, with `extra` answering the calls a test cares about. */
function signedIn(extra: (call: RecordedCall) => Response | undefined) {
  return mockApi((call) => {
    const custom = extra(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
    if (call.path === '/api/circles') return envelope('CIRCLE_LIST', { circles: [crew] });
    return undefined;
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('the event feed', () => {
  it('groups by day in the event’s zone and adds the viewer’s zone when it differs', async () => {
    const lateNight = {
      ...walk,
      id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      title: 'Late karaoke',
      startsAt: '2030-07-02T03:00:00Z',
      endsAt: null,
    };
    signedIn(({ method, path }) => {
      if (method === 'GET' && path.startsWith('/api/events?scope=feed'))
        return page([walk, lateNight]);
      // The viewer is in Tokyo: the event's own zone (Chicago) still leads.
      if (path === '/api/me') return envelope('ME', { ...me, timeZone: 'Asia/Tokyo' });
      return undefined;
    });

    renderApp('/');

    // Both start on July 1st in Chicago, though the second is July 2nd in UTC and Tokyo.
    const days = await screen.findAllByRole('heading', { level: 2 });
    expect(days.map((d) => d.textContent)).toEqual([expect.stringMatching(/Jul 1/)]);
    const cards = screen.getAllByRole('link', { name: /Cosplay photo walk|Late karaoke/ });
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent('CDT');
    expect(cards[0]).toHaveTextContent('Your time:');
    expect(cards[0]).toHaveTextContent('GMT+9');
    expect(cards[0]).toHaveAttribute('href', `/events/${walk.id}`);
  });

  it('shows no second line when the viewer is in the event’s zone', async () => {
    signedIn(({ path }) => (path.startsWith('/api/events?') ? page([walk]) : undefined));
    renderApp('/');
    await screen.findByText('Cosplay photo walk');
    expect(screen.queryByText(/Your time:/)).toBeNull();
  });

  it('says so when there is nothing yet, and offers hosting and servers', async () => {
    signedIn(({ path }) => (path.startsWith('/api/events?') ? page([]) : undefined));
    renderApp('/');
    expect(
      await screen.findByRole('heading', { name: 'No upcoming meetups yet' }),
    ).toBeInTheDocument();
    // The navigation has a Host link of its own.
    const main = within(screen.getByRole('main'));
    expect(main.getByRole('link', { name: 'Host a meetup' })).toHaveAttribute(
      'href',
      '/events/new',
    );
    expect(main.getByText(/Turn on the servers you’re in/)).toBeInTheDocument();
    expect(main.getByRole('link', { name: 'Turn on servers' })).toHaveAttribute('href', '/you');
  });

  it('loads more with the cursor the server gave', async () => {
    const second = { ...walk, id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', title: 'Second page' };
    const calls = signedIn(({ path }) => {
      if (path.startsWith('/api/events?') && !path.includes('cursor'))
        return page([walk], 'next-1');
      if (path.includes('cursor=next-1')) return page([second]);
      return undefined;
    });

    renderApp('/');
    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));

    await screen.findByText('Second page');
    expect(screen.getByText('Cosplay photo walk')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
    expect(calls.some((c) => c.path.includes('cursor=next-1'))).toBe(true);
  });

  it('has a Hosting tab that asks for scope=hosting and shows cancelled events', async () => {
    const cancelled = { ...mine, status: 'CANCELLED' as const };
    const calls = signedIn(({ path }) => {
      if (path.startsWith('/api/events?scope=feed')) return page([walk]);
      if (path.startsWith('/api/events?scope=hosting')) return page([cancelled]);
      return undefined;
    });

    renderApp('/');
    await screen.findByText('Cosplay photo walk');
    fireEvent.click(screen.getByRole('button', { name: 'Hosting' }));

    const card = await screen.findByRole('link', { name: /My panel/ });
    expect(within(card).getByText('Cancelled')).toBeInTheDocument();
    expect(calls.some((c) => c.path.startsWith('/api/events?scope=hosting'))).toBe(true);
  });

  it('shows plain text for a hidden location', async () => {
    signedIn(({ path }) =>
      path.startsWith('/api/events?')
        ? page([{ ...walk, location: null, locationHidden: true, locationVisibility: 'CONFIRMED' }])
        : undefined,
    );
    renderApp('/');
    expect(await screen.findByText('Location shared with confirmed attendees')).toBeInTheDocument();
  });
});

describe('the event page', () => {
  it('renders user text as plain text and offers no host actions to a viewer', async () => {
    signedIn(({ path }) =>
      path === `/api/events/${walk.id}` ? envelope('EVENT', walk) : undefined,
    );

    renderApp(`/events/${walk.id}`);

    expect(await screen.findByRole('heading', { name: 'Cosplay photo walk' })).toBeInTheDocument();
    // The description is text: the tag is shown as characters, not turned into an element.
    expect(screen.getByText(/Bring <b>props<\/b>\./)).toBeInTheDocument();
    expect(document.body.querySelector('b')).toBeNull();
    expect(screen.getByText('Hall B fountain')).toBeInTheDocument();
    expect(screen.getByText('Hosted by Ada')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Edit event' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Cancel event' })).toBeNull();
    // Only the host sees the audience.
    expect(screen.queryByText('Who can see this')).toBeNull();
  });

  it('says the location is for confirmed attendees when it is hidden', async () => {
    const hidden = {
      ...walk,
      location: null,
      locationHidden: true,
      locationVisibility: 'CONFIRMED' as const,
    };
    signedIn(({ path }) =>
      path === `/api/events/${walk.id}` ? envelope('EVENT', hidden) : undefined,
    );
    renderApp(`/events/${walk.id}`);
    expect(await screen.findByText('Location shared with confirmed attendees')).toBeInTheDocument();
    expect(screen.queryByText('Hall B fountain')).toBeNull();
  });

  it('shows the host their audience, Edit, and Cancel behind a confirmation', async () => {
    let cancelled = false;
    const calls = signedIn(({ method, path }) => {
      if (method === 'GET' && path === `/api/events/${mine.id}`)
        return envelope('EVENT', cancelled ? { ...mine, status: 'CANCELLED' } : mine);
      if (method === 'DELETE' && path === `/api/events/${mine.id}`) {
        cancelled = true;
        return noContent();
      }
      return undefined;
    });

    renderApp(`/events/${mine.id}`);

    await screen.findByRole('heading', { name: 'My panel' });
    expect(screen.getByRole('link', { name: 'Edit event' })).toHaveAttribute(
      'href',
      `/events/${mine.id}/edit`,
    );
    expect(screen.getByRole('link', { name: 'Change who can see it' })).toHaveAttribute(
      'href',
      `/events/${mine.id}/edit#audience`,
    );
    const audience = screen.getByRole('region', { name: 'Who can see this' });
    expect(within(audience).getByText('Your friends')).toBeInTheDocument();
    expect(within(audience).getByText('Cosplay Corner')).toBeInTheDocument();
    expect(within(audience).getByText('A server you’re not discoverable in')).toBeInTheDocument();
    expect(
      screen.getByText(/Only you and confirmed attendees see the location/),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Cancel event' }));
    // Nothing is cancelled until it is confirmed.
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
    const dialog = screen.getByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel event' }));

    expect(await screen.findByText('This event was cancelled.')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Edit event' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Cancel event' })).toBeNull();
  });

  it('backs out of cancelling without a request', async () => {
    const calls = signedIn(({ path }) =>
      path === `/api/events/${mine.id}` ? envelope('EVENT', mine) : undefined,
    );
    renderApp(`/events/${mine.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel event' }));
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Keep event' }),
    );
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
  });

  it('is not found for an event the viewer can’t see', async () => {
    signedIn(({ path }) =>
      path === `/api/events/${walk.id}` ? jsonResponse(errorEnvelope, 404) : undefined,
    );
    renderApp(`/events/${walk.id}`);
    expect(await screen.findByRole('heading', { name: 'Event not found' })).toBeInTheDocument();
  });

  it('shows a cancelled event with a banner and no way to edit it', async () => {
    signedIn(({ path }) =>
      path === `/api/events/${mine.id}`
        ? envelope('EVENT', { ...mine, status: 'CANCELLED' })
        : undefined,
    );
    renderApp(`/events/${mine.id}`);
    expect(await screen.findByText('This event was cancelled.')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Edit event' })).toBeNull();
  });

  it('does not open the editor for a cancelled event or for someone else’s', async () => {
    signedIn(({ path }) => {
      if (path === `/api/events/${mine.id}`)
        return envelope('EVENT', { ...mine, status: 'CANCELLED' });
      if (path === `/api/events/${walk.id}`) return envelope('EVENT', walk);
      return undefined;
    });
    renderApp(`/events/${mine.id}/edit`);
    expect(await screen.findByText('A cancelled event can’t be edited.')).toBeInTheDocument();
  });

  it('shows not found when a viewer opens the editor of someone else’s event', async () => {
    signedIn(({ path }) =>
      path === `/api/events/${walk.id}` ? envelope('EVENT', walk) : undefined,
    );
    renderApp(`/events/${walk.id}/edit`);
    expect(await screen.findByRole('heading', { name: 'Event not found' })).toBeInTheDocument();
  });
});

/** A start 10 days from now in Chicago, as a datetime-local value. */
function soon(days = 10) {
  return utcToZonedLocal(new Date(Date.now() + days * 86_400_000).toISOString(), 'America/Chicago');
}

function fill(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

describe('creating an event', () => {
  beforeEach(onDesktop);

  it('defaults to the profile’s time zone and says what is wrong before sending anything', async () => {
    const calls = signedIn(() => undefined);
    renderApp('/events/new');

    expect(await screen.findByLabelText('Time zone')).toHaveValue(timeZoneLabel('America/Chicago'));
    fireEvent.click(screen.getByRole('button', { name: 'Create meetup' }));

    expect(await screen.findByText('Give your event a title.')).toBeInTheDocument();
    expect(screen.getByText('Say where it is.')).toBeInTheDocument();
    expect(screen.getByText('Choose when it starts.')).toBeInTheDocument();
    expect(screen.getAllByText('Choose at least one audience.').length).toBeGreaterThan(0);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('builds the audience from friends, a server, a circle and a searched person', async () => {
    const created = { ...mine, title: 'Panel' };
    const calls = signedIn(({ method, path }) => {
      if (path.startsWith('/api/people?')) return envelope('PERSON_LIST', { people: [bo] });
      if (method === 'POST' && path === '/api/events') return envelope('EVENT', created);
      if (method === 'GET' && path === `/api/events/${created.id}`)
        return envelope('EVENT', created);
      return undefined;
    });
    const router = renderApp('/events/new');

    await screen.findByLabelText('Title');
    fill('Title', 'Panel');
    fill('Location', 'Room 4');
    fill('Starts', soon());
    fireEvent.click(screen.getByLabelText('Hide the location until people are confirmed'));
    fireEvent.click(screen.getByLabelText('Limit the spots'));
    fill('Capacity', '25');
    expect(screen.getByLabelText('Time to accept an offer')).toHaveValue('30');
    fill('Time to accept an offer', '120');

    // Only the server where the host is discoverable is offered, with the opt-in note.
    expect(await screen.findByLabelText('Cosplay Corner')).toBeInTheDocument();
    expect(screen.queryByLabelText('Retro Gamers')).toBeNull();
    expect(screen.getByText(/Only members who opted in/)).toBeInTheDocument();
    expect(screen.getByText(/shown to them as a member there/)).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('All my friends'));
    fireEvent.click(screen.getByLabelText('Cosplay Corner'));
    fireEvent.click(await screen.findByLabelText('Cosplay crew'));
    fireEvent.change(screen.getByLabelText('Search by name'), { target: { value: 'bo' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Add Bo' }));
    expect(screen.getByText('Added')).toBeInTheDocument();

    expect(
      screen.getByText(
        /visible to your friends, members of Cosplay Corner who’ve chosen to be discoverable there, people in your circle Cosplay crew and Bo\./,
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(/4 of 50 entries/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Create meetup' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/events/${created.id}`));
    const post = calls.find((c) => c.method === 'POST');
    expect(post?.body).toMatchObject({
      title: 'Panel',
      location: 'Room 4',
      locationVisibility: 'CONFIRMED',
      timeZone: 'America/Chicago',
      capacity: 25,
      confirmWindowMinutes: 120,
      endsAt: null,
      signupOpensAt: null,
      audience: [
        { type: 'FRIENDS' },
        { type: 'GUILD', guildId: '81384788765712386' },
        { type: 'CIRCLE', circleId: crew.id },
        { type: 'USER', userId: bo.id },
      ],
    });
    // Labels are display only: they never go back to the server.
    expect(JSON.stringify(post?.body)).not.toContain('label');
  });

  // #119, as it happens in Chromium: a system zone of "GMT" reads as "+00:00". The server won't
  // store that, so the profile stays without a zone and the form starts from the browser's.
  it('creates an event in UTC when the browser reports its zone as +00:00', async () => {
    const system = Intl.DateTimeFormat().resolvedOptions().timeZone;
    vi.stubEnv('TZ', 'GMT');
    // Node rereads the zone only when TZ is assigned, so assign the old one before removing it.
    onTestFinished(() => {
      vi.stubEnv('TZ', system);
      vi.unstubAllEnvs();
    });
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('+00:00');
    const created = { ...mine, title: 'Panel' };
    const calls = signedIn(({ method, path }) => {
      if (path === '/api/me')
        return method === 'PATCH'
          ? jsonResponse(errorEnvelope, 400)
          : envelope('ME', { ...me, timeZone: null });
      if (method === 'POST' && path === '/api/events') return envelope('EVENT', created);
      if (path === `/api/events/${created.id}`) return envelope('EVENT', created);
      return undefined;
    });
    const router = renderApp('/events/new');

    expect(await screen.findByLabelText('Time zone')).toHaveValue(timeZoneLabel('UTC'));
    fill('Title', 'Panel');
    fill('Location', 'Room 4');
    fill('Starts', soon());
    fireEvent.click(await screen.findByLabelText('All my friends'));
    fireEvent.click(screen.getByRole('button', { name: 'Create meetup' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/events/${created.id}`));
    expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({ timeZone: 'UTC' });
  });

  it('sends the zone picked with the keyboard, and starts the next event in it', async () => {
    const created = { ...mine, title: 'Panel' };
    const calls = signedIn(({ method, path }) => {
      if (method === 'POST' && path === '/api/events') return envelope('EVENT', created);
      if (path === `/api/events/${created.id}`) return envelope('EVENT', created);
      return undefined;
    });
    const router = renderApp('/events/new');
    const zone = await screen.findByLabelText('Time zone');
    fill('Title', 'Panel');
    fill('Location', 'Room 4');
    fill('Starts', soon());
    fireEvent.click(await screen.findByLabelText('All my friends'));

    fireEvent.change(zone, { target: { value: 'tokyo' } });
    // Enter chooses the zone; it doesn't submit the form.
    expect(fireEvent.keyDown(zone, { key: 'Enter' })).toBe(false);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Create meetup' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/events/${created.id}`));
    expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({
      timeZone: 'Asia/Tokyo',
    });

    await router.navigate('/events/new');
    await waitFor(() =>
      expect(screen.getByLabelText('Time zone')).toHaveValue(timeZoneLabel('Asia/Tokyo')),
    );
  });

  it.each([
    ['the zone used last, before the profile’s', 'Asia/Tokyo', 'Europe/Paris', 'Asia/Tokyo'],
    ['the profile’s when none was used', null, 'Europe/Paris', 'Europe/Paris'],
    ['the profile’s when the stored one can’t be read', 'Mars/Olympus', 'UTC', 'UTC'],
    ['the browser’s when there is nothing else', null, null, browserTimeZone()],
  ])('starts a new event in %s', async (_, lastUsed, profile, expected) => {
    if (lastUsed) localStorage.setItem('cf-last-time-zone', lastUsed);
    signedIn(({ path }) =>
      path === '/api/me' ? envelope('ME', { ...me, timeZone: profile }) : undefined,
    );

    renderApp('/events/new');

    expect(await screen.findByLabelText('Time zone')).toHaveValue(timeZoneLabel(expected));
  });

  it('keeps an edited event in its own zone, and doesn’t count it as used', async () => {
    localStorage.setItem('cf-last-time-zone', 'Asia/Tokyo');
    const calls = signedIn(({ path }) =>
      path === `/api/events/${mine.id}` ? envelope('EVENT', mine) : undefined,
    );
    renderApp(`/events/${mine.id}/edit`);

    expect(await screen.findByLabelText('Time zone')).toHaveValue(timeZoneLabel('America/Chicago'));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(calls.some((c) => c.method === 'PATCH')).toBe(true));
    expect(localStorage.getItem('cf-last-time-zone')).toBe('Asia/Tokyo');
  });

  it('does not let Enter in the people search submit the event', async () => {
    const calls = signedIn(() => undefined);
    renderApp('/events/new');
    const search = await screen.findByLabelText('Search by name');
    const notPrevented = fireEvent.keyDown(search, { key: 'Enter' });
    expect(notPrevented).toBe(false);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('points to onboarding when the host is discoverable nowhere', async () => {
    signedIn(({ path }) =>
      path === '/api/me/guilds'
        ? envelope('GUILD_LIST', { guilds: guilds.map((g) => ({ ...g, discoverable: false })) })
        : undefined,
    );
    renderApp('/events/new');
    expect(await screen.findByText(/not discoverable in any server yet/)).toBeInTheDocument();
  });

  it('explains EVENT_LIMIT in plain words', async () => {
    signedIn(({ method, path }) =>
      method === 'POST' && path === '/api/events'
        ? jsonResponse(
            { ...errorEnvelope, body: { ...errorEnvelope.body, errorType: 'EVENT_LIMIT' } },
            429,
          )
        : undefined,
    );
    renderApp('/events/new');
    await screen.findByLabelText('Title');
    fill('Title', 'Panel');
    fill('Location', 'Room 4');
    fill('Starts', soon());
    fireEvent.click(await screen.findByLabelText('All my friends'));
    fireEvent.click(screen.getByRole('button', { name: 'Create meetup' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/20 events in the last 24 hours/);
  });

  it('refuses a start in the past and an event longer than 7 days without a request', async () => {
    const calls = signedIn(() => undefined);
    renderApp('/events/new');
    await screen.findByLabelText('Title');
    fill('Title', 'Panel');
    fill('Location', 'Room 4');
    fill('Starts', soon(-2));
    fireEvent.click(await screen.findByLabelText('All my friends'));
    fireEvent.click(screen.getByRole('button', { name: 'Create meetup' }));
    expect(await screen.findByText('The start can’t be in the past.')).toBeInTheDocument();

    fill('Starts', soon(10));
    fill('Ends (optional)', soon(18));
    fireEvent.click(screen.getByRole('button', { name: 'Create meetup' }));
    expect(await screen.findByText('An event can last at most 7 days.')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});

describe('editing an event', () => {
  beforeEach(onDesktop);

  it('sends the whole event back, keeping an entry the host can no longer use until removed', async () => {
    const calls = signedIn(({ method, path }) => {
      if (method === 'GET' && path === `/api/events/${mine.id}`) return envelope('EVENT', mine);
      if (method === 'PATCH' && path === `/api/events/${mine.id}`) return envelope('EVENT', mine);
      return undefined;
    });
    const router = renderApp(`/events/${mine.id}/edit`);

    expect(await screen.findByLabelText('Title')).toHaveValue('My panel');
    expect(screen.getByLabelText('Hide the location until people are confirmed')).toBeChecked();
    expect(screen.getByLabelText('Limit the spots')).toBeChecked();
    expect(screen.getByLabelText('Capacity')).toHaveValue('20');
    // The server the host is no longer discoverable in is listed, by the text we chose.
    const extras = await screen.findByRole('region', { name: 'Also included' });
    expect(within(extras).getByText('A server you’re not discoverable in')).toBeInTheDocument();
    const people = screen.getByRole('list', { name: 'Added people' });
    expect(within(people).getByRole('button', { name: 'Remove Bo' })).toBeInTheDocument();

    fill('Title', 'My renamed panel');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/events/${mine.id}`));
    const patch = calls.find((c) => c.method === 'PATCH');
    expect(patch?.body).toMatchObject({
      title: 'My renamed panel',
      location: 'Hall B fountain',
      locationVisibility: 'CONFIRMED',
      capacity: 20,
      // The current window goes back, explicitly.
      confirmWindowMinutes: 30,
      // An untouched time is sent back exactly as it was received.
      startsAt: mine.startsAt,
      endsAt: mine.endsAt,
      audience: [
        { type: 'FRIENDS' },
        { type: 'GUILD', guildId: '81384788765712386' },
        { type: 'GUILD', guildId: '81384788765712399' },
        { type: 'USER', userId: bo.id },
      ],
    });
  });

  it('drops an entry the host removes', async () => {
    const calls = signedIn(({ method, path }) => {
      if (method === 'GET' && path === `/api/events/${mine.id}`) return envelope('EVENT', mine);
      if (method === 'PATCH') return envelope('EVENT', mine);
      return undefined;
    });
    renderApp(`/events/${mine.id}/edit`);
    await screen.findByLabelText('Title');
    fireEvent.click(
      await screen.findByRole('button', { name: 'Remove A server you’re not discoverable in' }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(calls.some((c) => c.method === 'PATCH')).toBe(true));
    const body = calls.find((c) => c.method === 'PATCH')?.body as { audience: unknown[] };
    expect(body.audience).not.toContainEqual({ type: 'GUILD', guildId: '81384788765712399' });
    expect(body.audience).toHaveLength(3);
  });

  it('explains a 404 on save and a 409 on a cancelled event', async () => {
    let status = 404;
    signedIn(({ method, path }) => {
      if (method === 'GET' && path === `/api/events/${mine.id}`) return envelope('EVENT', mine);
      if (method === 'PATCH') return jsonResponse(errorEnvelope, status);
      return undefined;
    });
    renderApp(`/events/${mine.id}/edit`);
    await screen.findByLabelText('Title');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText(/Part of the audience can’t be used/)).toBeInTheDocument();

    status = 409;
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(
      await screen.findByText(/has been cancelled, so it can’t be edited/),
    ).toBeInTheDocument();
  });
});
