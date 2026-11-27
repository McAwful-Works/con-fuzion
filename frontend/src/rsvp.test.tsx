import { onlineManager } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './api/client';
import type { components } from './api/schema';
import { ErrorState } from './components/ErrorState';
import {
  envelope,
  guilds,
  jsonResponse,
  me,
  mockApi,
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
const cy: Schemas['PersonResponse'] = {
  id: '33333333-3333-4333-8333-333333333333',
  displayName: 'Cy',
  avatarUrl: 'https://cdn.discordapp.com/embed/avatars/3.png',
};
const meAsPerson: Schemas['PersonResponse'] = {
  id: me.id,
  displayName: 'Con Goer',
  avatarUrl: me.avatarUrl,
};

const HOUR = 3_600_000;
const walk: Schemas['EventResponse'] = {
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
  capacity: 10,
  signupOpensAt: null,
  startsAt: new Date(Date.now() + 48 * HOUR).toISOString(),
  endsAt: new Date(Date.now() + 50 * HOUR).toISOString(),
  timeZone: 'America/Chicago',
};
const mine: Schemas['EventResponse'] = {
  ...walk,
  id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  title: 'My panel',
  host: meAsPerson,
  isHost: true,
  myStatus: 'CONFIRMED',
  audience: [{ type: 'FRIENDS', label: null, guildId: null, circleId: null, userId: null }],
};

function spotsFor(overrides: Partial<Schemas['SpotsResponse']> = {}): Schemas['SpotsResponse'] {
  return {
    capacity: 10,
    confirmed: 3,
    waitlisted: 0,
    spotsLeft: 7,
    myStatus: 'NONE',
    myPosition: null,
    offerExpiresAt: null,
    ...overrides,
  };
}

const noContent = () => new Response(null, { status: 204 });
const conflict = (errorType: string) =>
  jsonResponse(
    {
      responseType: 'ERROR',
      body: { errorType, errorMessage: 'Conflict', correlationId: 'c-1' },
    },
    409,
  );

/** Signed in, with `extra` answering the calls a test cares about. */
function signedIn(extra: (call: RecordedCall) => Response | undefined) {
  return mockApi((call) => {
    const custom = extra(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
    if (call.path === '/api/circles') return envelope('CIRCLE_LIST', { circles: [] });
    return undefined;
  });
}

/** An event page whose RSVP moves with `state`, as the server's would. */
function eventServer(event: Schemas['EventResponse'], state: { spots: Schemas['SpotsResponse'] }) {
  return (call: RecordedCall) => {
    const base = `/api/events/${event.id}`;
    if (call.method === 'GET' && call.path === base)
      return envelope('EVENT', { ...event, myStatus: state.spots.myStatus });
    if (call.method === 'GET' && call.path === `${base}/spots`)
      return envelope('SPOTS', state.spots);
    return undefined;
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('Join, Joined, Waitlisted #N and Leave', () => {
  it('joins, then shows Joined with Leave, and the spots badge follows', async () => {
    const state = { spots: spotsFor() };
    const server = eventServer(walk, state);
    const calls = signedIn((call) => {
      if (call.method === 'POST' && call.path === `/api/events/${walk.id}/rsvp`) {
        state.spots = spotsFor({ myStatus: 'CONFIRMED', confirmed: 4, spotsLeft: 6 });
        return envelope('RSVP', { status: 'CONFIRMED', position: null });
      }
      return server(call);
    });

    renderApp(`/events/${walk.id}`);

    expect(await screen.findByText('Spots left: 7')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Join' }));

    expect(await screen.findByText('Joined')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Join' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Leave' })).toBeInTheDocument();
    expect(screen.getByText('Spots left: 6')).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
  });

  it('asks before leaving, and Stay changes nothing', async () => {
    const state = { spots: spotsFor({ myStatus: 'CONFIRMED' }) };
    const server = eventServer(walk, state);
    const calls = signedIn((call) => {
      if (call.method === 'DELETE' && call.path === `/api/events/${walk.id}/rsvp`) {
        state.spots = spotsFor();
        return noContent();
      }
      return server(call);
    });

    renderApp(`/events/${walk.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Leave' }));

    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByText(/give up your spot/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stay' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Leave' }));
    fireEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Leave' }),
    );

    expect(await screen.findByRole('button', { name: 'Join' })).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'DELETE')).toHaveLength(1);
  });

  it('shows the waitlisted person their own place, and Full', async () => {
    const state = {
      spots: spotsFor({
        myStatus: 'WAITLISTED',
        myPosition: 3,
        confirmed: 10,
        spotsLeft: 0,
        waitlisted: 4,
      }),
    };
    signedIn(eventServer(walk, state));

    renderApp(`/events/${walk.id}`);

    expect(await screen.findByText('Waitlisted #3')).toBeInTheDocument();
    expect(screen.getByText('Full')).toBeInTheDocument();
    expect(screen.getByText(/4 on the waitlist/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Leave' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Join' })).toBeNull();
    // A waitlisted person doesn't get the attendee list.
    expect(screen.queryByRole('heading', { name: /Going/ })).toBeNull();
  });

  it('says what joining a full event does', async () => {
    signedIn(eventServer(walk, { spots: spotsFor({ confirmed: 10, spotsLeft: 0 }) }));
    renderApp(`/events/${walk.id}`);
    expect(await screen.findByText('Full')).toBeInTheDocument();
    expect(screen.getByText(/Joining puts you on the waitlist/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Join' })).toBeEnabled();
  });

  it('shows a removed person that they can’t rejoin, with no button', async () => {
    signedIn(eventServer(walk, { spots: spotsFor({ myStatus: 'REMOVED' }) }));
    renderApp(`/events/${walk.id}`);
    expect(await screen.findByText('You can’t rejoin this event.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Join|Leave/ })).toBeNull();
  });

  it('gives the host no Join or Leave, only the spots', async () => {
    const state = { spots: spotsFor({ myStatus: 'CONFIRMED' }) };
    signedIn((call) => {
      if (call.path === `/api/events/${mine.id}/attendees`)
        return envelope('ATTENDEES', { confirmed: [meAsPerson], waitlist: [] });
      return eventServer(mine, state)(call);
    });
    renderApp(`/events/${mine.id}`);
    expect(await screen.findByText('Spots left: 7')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^(Join|Leave)$/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Cancel event' })).toBeInTheDocument();
  });

  it('shows an unlimited event with no waitlist', async () => {
    signedIn(
      eventServer(
        { ...walk, capacity: null },
        { spots: spotsFor({ capacity: null, spotsLeft: null }) },
      ),
    );
    renderApp(`/events/${walk.id}`);
    expect(await screen.findByText('Unlimited spots')).toBeInTheDocument();
    expect(screen.queryByText(/on the waitlist/)).toBeNull();
  });

  it('shows signup as closed once the event has started', async () => {
    const started = { ...walk, startsAt: new Date(Date.now() - HOUR).toISOString() };
    signedIn(eventServer(started, { spots: spotsFor() }));
    renderApp(`/events/${walk.id}`);
    expect(
      await screen.findByText(/Signup is closed because the event has started/),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Join' })).toBeNull();
  });

  it('lets a confirmed attendee open a cancelled event and leave it, but not join', async () => {
    const cancelled = { ...walk, status: 'CANCELLED' as const };
    const calls = signedIn((call) => {
      if (call.path === `/api/events/${walk.id}`)
        return envelope('EVENT', { ...cancelled, myStatus: 'CONFIRMED' });
      if (call.path === `/api/events/${walk.id}/attendees`)
        return envelope('ATTENDEES', { confirmed: [ada, meAsPerson], waitlist: null });
      return undefined;
    });
    renderApp(`/events/${walk.id}`);
    expect(await screen.findByText('This event was cancelled.')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Leave' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Join' })).toBeNull();
    // A cancelled event has no spots to watch.
    expect(calls.some((c) => c.path.endsWith('/spots'))).toBe(false);
  });

  it('keeps Join locked before signup opens, with a countdown', async () => {
    const opensIn = { ...walk, signupOpensAt: new Date(Date.now() + 2 * HOUR).toISOString() };
    signedIn(eventServer(opensIn, { spots: spotsFor() }));
    renderApp(`/events/${walk.id}`);
    expect(await screen.findByRole('timer')).toHaveTextContent(
      /Signup opens in 1h 5\d m|Signup opens in 2h 00m/,
    );
    expect(screen.getByRole('button', { name: 'Join' })).toBeDisabled();
  });

  it('needs a connection to join', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    signedIn(eventServer(walk, { spots: spotsFor() }));
    renderApp(`/events/${walk.id}`);
    expect(await screen.findByRole('button', { name: 'Join' })).toBeDisabled();
    expect(screen.getByText(/You’re offline/)).toBeInTheDocument();
  });
});

describe('the 409 messages', () => {
  it.each([
    ['SIGNUP_NOT_OPEN', /Signup hasn’t opened yet/],
    ['SIGNUP_CLOSED', /Signup is closed because the event has started/],
    ['RSVP_REMOVED', /You can’t rejoin this event/],
  ])('shows a friendly message when joining answers %s', async (code, message) => {
    const server = eventServer(walk, { spots: spotsFor() });
    signedIn((call) =>
      call.method === 'POST' && call.path.endsWith('/rsvp') ? conflict(code) : server(call),
    );
    renderApp(`/events/${walk.id}`);

    fireEvent.click(await screen.findByRole('button', { name: 'Join' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
  });

  it.each([
    ['SIGNUP_NOT_OPEN', /Signup hasn’t opened yet/],
    ['SIGNUP_CLOSED', /Signup is closed/],
    ['RSVP_REMOVED', /can’t rejoin/],
    ['HOST_CANNOT_LEAVE', /Cancel the event instead/],
    ['CAPACITY_BELOW_CONFIRMED', /fewer spots than the number of people already confirmed/],
  ])('words %s without using server text', (code, message) => {
    render(<ErrorState error={new ApiError(409, code)} />);
    expect(screen.getByRole('alert')).toHaveTextContent(message);
  });

  it('explains a capacity below the confirmed count when saving an event', async () => {
    signedIn((call) => {
      if (call.method === 'GET' && call.path === `/api/events/${mine.id}`)
        return envelope('EVENT', mine);
      if (call.method === 'PATCH') return conflict('CAPACITY_BELOW_CONFIRMED');
      return undefined;
    });
    renderApp(`/events/${mine.id}/edit`);

    fireEvent.change(await screen.findByLabelText('Capacity'), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(
      await screen.findByText(/fewer spots than the number of people already confirmed/),
    ).toBeInTheDocument();
    // It isn't mistaken for a cancelled event.
    expect(screen.queryByText(/has been cancelled/)).toBeNull();
  });
});

describe('the attendee list', () => {
  it('shows the host the confirmed list and the waitlist in order, and removes only after confirming', async () => {
    let attendees: Schemas['AttendeesResponse'] = {
      confirmed: [meAsPerson, bo],
      offered: null,
      waitlist: [cy, ada],
    };
    const calls = signedIn((call) => {
      if (call.method === 'DELETE' && call.path === `/api/events/${mine.id}/rsvps/${bo.id}`) {
        // The next person in line is promoted.
        attendees = { confirmed: [meAsPerson, cy], offered: null, waitlist: [ada] };
        return noContent();
      }
      if (call.path === `/api/events/${mine.id}/attendees`) return envelope('ATTENDEES', attendees);
      return eventServer(mine, { spots: spotsFor({ myStatus: 'CONFIRMED' }) })(call);
    });
    renderApp(`/events/${mine.id}`);

    const going = await screen.findByRole('heading', { name: 'Going (2)' });
    expect(going).toBeInTheDocument();
    // Remove is offered for Bo, not for the host themself, and not on the waitlist.
    expect(screen.getAllByRole('button', { name: /^Remove / })).toHaveLength(1);
    const waitlist = screen.getByRole('list', { name: 'Waitlist (2)' });
    expect(
      within(waitlist)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([expect.stringContaining('Cy'), expect.stringContaining('Ada')]);

    fireEvent.click(screen.getByRole('button', { name: 'Remove Bo' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent(/can’t rejoin this event/);
    expect(dialog).toHaveTextContent(/can’t be undone/);
    // Nothing happens until it is confirmed.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep' }));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Remove Bo' }));
    fireEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Remove' }),
    );

    // The list refreshes: Cy has Bo's spot.
    await waitFor(() => expect(screen.queryByText('Bo')).toBeNull());
    expect(screen.getByRole('button', { name: 'Remove Cy' })).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'DELETE')).toHaveLength(1);
  });

  it('gives a confirmed attendee the confirmed list only, with no way to remove anyone', async () => {
    const calls = signedIn((call) => {
      if (call.path === `/api/events/${walk.id}/attendees`)
        return envelope('ATTENDEES', { confirmed: [ada, meAsPerson], waitlist: null });
      return eventServer(walk, { spots: spotsFor({ myStatus: 'CONFIRMED' }) })(call);
    });
    renderApp(`/events/${walk.id}`);

    expect(await screen.findByRole('heading', { name: 'Going (2)' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /Waitlist/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Remove / })).toBeNull();
    expect(calls.some((c) => c.path.endsWith('/attendees'))).toBe(true);
  });

  it('never asks for the list of someone who isn’t going', async () => {
    const calls = signedIn(eventServer(walk, { spots: spotsFor() }));
    renderApp(`/events/${walk.id}`);
    await screen.findByText('Spots left: 7');
    expect(calls.some((c) => c.path.endsWith('/attendees'))).toBe(false);
  });
});

describe('cards and My schedule', () => {
  const page = (events: Schemas['EventResponse'][]) =>
    envelope('EVENT_PAGE', { events, nextCursor: null });

  it('shows myStatus on feed cards without asking for spots', async () => {
    const calls = signedIn((call) =>
      call.path.startsWith('/api/events?scope=feed')
        ? page([
            { ...walk, title: 'Going one', myStatus: 'CONFIRMED' },
            { ...walk, id: 'w2', title: 'Waiting one', myStatus: 'WAITLISTED' },
            { ...walk, id: 'w3', title: 'Open one' },
          ])
        : undefined,
    );
    renderApp('/');

    const going = await screen.findByRole('link', { name: /Going one/ });
    expect(within(going).getByText('Joined')).toBeInTheDocument();
    expect(
      within(screen.getByRole('link', { name: /Waiting one/ })).getByText('Waitlisted'),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole('link', { name: /Open one/ })).queryByText(/Joined|Waitlisted/),
    ).toBeNull();
    expect(calls.some((c) => c.path.includes('/spots'))).toBe(false);
  });

  it('lists My schedule by day and highlights events that overlap', async () => {
    const start = Date.now() + 48 * HOUR;
    const iso = (ms: number) => new Date(start + ms).toISOString();
    const calls = signedIn((call) =>
      call.path.startsWith('/api/events?scope=attending')
        ? page([
            {
              ...walk,
              id: 'e1',
              title: 'Panel one',
              myStatus: 'CONFIRMED',
              startsAt: iso(0),
              endsAt: iso(2 * HOUR),
            },
            {
              ...walk,
              id: 'e2',
              title: 'Panel two',
              myStatus: 'WAITLISTED',
              startsAt: iso(HOUR),
              endsAt: iso(3 * HOUR),
            },
            // Starts as the second one ends: touching isn't overlapping.
            {
              ...walk,
              id: 'e3',
              title: 'Dinner',
              myStatus: 'CONFIRMED',
              startsAt: iso(3 * HOUR),
              endsAt: iso(4 * HOUR),
            },
          ])
        : undefined,
    );
    renderApp('/schedule');

    const one = await screen.findByRole('link', { name: /^Panel one/ });
    const two = screen.getByRole('link', { name: /^Panel two/ });
    const dinner = screen.getByRole('link', { name: /^Dinner/ });
    expect(one).toHaveTextContent('Overlaps with “Panel two”');
    expect(two).toHaveTextContent('Overlaps with “Panel one”');
    expect(dinner).not.toHaveTextContent('Overlaps');
    expect(within(two).getByText('Waitlisted')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'My schedule' })).toBeInTheDocument();
    expect(calls.some((c) => c.path.startsWith('/api/events?scope=attending'))).toBe(true);
  });

  it('explains an empty schedule in words', async () => {
    signedIn((call) =>
      call.path.startsWith('/api/events?scope=attending') ? page([]) : undefined,
    );
    renderApp('/schedule');
    expect(await screen.findByText(/Nothing on your schedule yet/)).toBeInTheDocument();
  });

  it('is reachable from the home page', async () => {
    signedIn((call) => (call.path.startsWith('/api/events?') ? page([]) : undefined));
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'My schedule' })).toHaveAttribute(
      'href',
      '/schedule',
    );
  });
});

describe('the offline copy of My schedule', () => {
  const del = vi.fn(async () => true);
  const deleted = () => del.mock.calls.length;

  function stubCaches(lastUser: string | null) {
    del.mockClear();
    vi.stubGlobal('caches', { delete: del });
    localStorage.clear();
    if (lastUser) localStorage.setItem('cf-last-user', lastUser);
  }

  afterEach(() => {
    onlineManager.setOnline(true);
    localStorage.clear();
  });

  it('opens from a cold start with no connection, as the service worker answers from its cache', async () => {
    onlineManager.setOnline(false);
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    signedIn((call) =>
      call.path.startsWith('/api/events?scope=attending')
        ? envelope('EVENT_PAGE', {
            events: [{ ...walk, myStatus: 'CONFIRMED' }],
            nextCursor: null,
          })
        : undefined,
    );

    renderApp('/schedule');

    expect(await screen.findByRole('link', { name: /Cosplay photo walk/ })).toBeInTheDocument();
    expect(screen.getByText(/You’re offline/)).toBeInTheDocument();
  });

  it('is cleared at sign-out', async () => {
    stubCaches(me.id);
    signedIn((call) =>
      call.method === 'POST' && call.path === '/api/auth/logout' ? noContent() : undefined,
    );
    renderApp('/settings');
    // Signed in as the same person as last time: nothing is dropped yet.
    await screen.findByRole('button', { name: 'Sign out' });
    expect(deleted()).toBe(0);

    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(del).toHaveBeenCalledWith('my-schedule'));
  });

  it('is cleared when the session has expired', async () => {
    stubCaches(me.id);
    mockApi((call) =>
      call.path === '/api/me'
        ? jsonResponse(
            {
              responseType: 'ERROR',
              body: { errorType: 'UNAUTHORIZED', errorMessage: 'x', correlationId: 'c' },
            },
            401,
          )
        : undefined,
    );
    renderApp('/');
    await waitFor(() => expect(del).toHaveBeenCalledWith('my-schedule'));
    // And it forgets whose copy it was, so the next sign-in starts clean.
    expect(localStorage.getItem('cf-last-user')).toBeNull();
  });

  it('is cleared when a different person signs in on this device', async () => {
    stubCaches('99999999-9999-4999-8999-999999999999');
    signedIn(() => undefined);
    renderApp('/settings');
    await screen.findByRole('button', { name: 'Sign out' });
    await waitFor(() => expect(del).toHaveBeenCalledWith('my-schedule'));
    expect(localStorage.getItem('cf-last-user')).toBe(me.id);
  });
});

describe('a status change seen through /spots', () => {
  it('refreshes the event, so a promoted person gets the location and the list', async () => {
    const hidden = {
      ...walk,
      location: null,
      locationHidden: true,
      locationVisibility: 'CONFIRMED' as const,
    };
    let promoted = false;
    const calls = signedIn((call) => {
      if (call.path === `/api/events/${walk.id}`)
        return envelope(
          'EVENT',
          promoted ? { ...walk, locationVisibility: 'CONFIRMED', myStatus: 'CONFIRMED' } : hidden,
        );
      if (call.path === `/api/events/${walk.id}/spots`) {
        // The server promoted them before this poll; the event we loaded first doesn't know yet.
        promoted = true;
        return envelope('SPOTS', spotsFor({ myStatus: 'CONFIRMED' }));
      }
      if (call.path === `/api/events/${walk.id}/attendees`)
        return envelope('ATTENDEES', { confirmed: [ada, meAsPerson], waitlist: null });
      return undefined;
    });

    renderApp(`/events/${walk.id}`);

    expect(await screen.findByText('Hall B fountain')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Going (2)' })).toBeInTheDocument();
    expect(calls.filter((c) => c.path === `/api/events/${walk.id}`).length).toBeGreaterThan(1);
  });
});
