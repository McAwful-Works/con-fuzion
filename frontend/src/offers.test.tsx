import { fireEvent, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
import { envelope, jsonResponse, me, mockApi, renderApp, type RecordedCall } from './test-utils';

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
  locationHidden: false,
  locationVisibility: 'VIEWERS',
  confirmWindowMinutes: 30,
  tag: null,
  host: ada,
  hiddenReason: null,
  isHost: false,
  myStatus: 'OFFERED',
  status: 'PUBLISHED',
  audience: null,
  spotsLeft: null,
  visibleBecause: [],
  capacity: 10,
  signupOpensAt: null,
  startsAt: new Date(Date.now() + 48 * HOUR).toISOString(),
  endsAt: new Date(Date.now() + 50 * HOUR).toISOString(),
  timeZone: 'America/Chicago',
};

function spotsFor(overrides: Partial<Schemas['SpotsResponse']> = {}): Schemas['SpotsResponse'] {
  return {
    capacity: 10,
    confirmed: 10,
    waitlisted: 2,
    spotsLeft: 0,
    myStatus: 'OFFERED',
    myPosition: null,
    offerExpiresAt: new Date(Date.now() + 20 * 60_000).toISOString(),
    ...overrides,
  };
}

const noContent = () => new Response(null, { status: 204 });

function signedIn(extra: (call: RecordedCall) => Response | undefined) {
  return mockApi((call) => {
    const custom = extra(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.path.startsWith('/api/notifications'))
      return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: 0 });
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

describe('an offered spot', () => {
  it('shows Accept and Decline with the time left, and the card says so', async () => {
    const state = { spots: spotsFor() };
    signedIn(eventServer(walk, state));

    renderApp(`/events/${walk.id}`);

    expect(await screen.findByText('A spot is held for you.')).toBeInTheDocument();
    // The deadline arrives with `/spots`, a moment after the event itself.
    expect(await screen.findByRole('timer')).toHaveTextContent(/Offer ends in (19m|20m) \d\ds/);
    expect(screen.getByText(/Accept before/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Accept spot' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Decline' })).toBeEnabled();
    // The countdown isn't a live region: screen readers aren't told every second.
    expect(screen.getByRole('timer')).toHaveAttribute('aria-live', 'off');
    expect(screen.queryByRole('button', { name: 'Join' })).toBeNull();
  });

  it('accepts the offer and becomes Joined', async () => {
    const state = { spots: spotsFor() };
    const server = eventServer(walk, state);
    const calls = signedIn((call) => {
      if (call.method === 'POST' && call.path === `/api/events/${walk.id}/rsvp/accept`) {
        state.spots = spotsFor({ myStatus: 'CONFIRMED', offerExpiresAt: null });
        return envelope('RSVP', { status: 'CONFIRMED', position: null, offerExpiresAt: null });
      }
      return server(call);
    });

    renderApp(`/events/${walk.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Accept spot' }));

    expect(await screen.findByText('Joined')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Accept spot' })).toBeNull();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
  });

  it('asks before declining, and Keep the offer changes nothing', async () => {
    const state = { spots: spotsFor() };
    const server = eventServer(walk, state);
    const calls = signedIn((call) => {
      if (call.method === 'DELETE' && call.path === `/api/events/${walk.id}/rsvp`) {
        state.spots = spotsFor({ myStatus: 'NONE', offerExpiresAt: null });
        return noContent();
      }
      return server(call);
    });

    renderApp(`/events/${walk.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Decline' }));

    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByText(/next person in line is offered it/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep the offer' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Decline' }));
    fireEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Decline' }),
    );

    expect(await screen.findByRole('button', { name: 'Join' })).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'DELETE')).toHaveLength(1);
  });

  it('says expired when the deadline passes, without waiting for a poll, and offers Join', async () => {
    // Two seconds out; the poll is 30 seconds away, so only the clock can notice.
    const state = {
      spots: spotsFor({ offerExpiresAt: new Date(Date.now() + 2_000).toISOString() }),
    };
    signedIn(eventServer(walk, state));
    // From then on the server reads the offer as gone (D-014 rule 15), as it would after the deadline.
    setTimeout(() => (state.spots = spotsFor({ myStatus: 'NONE', offerExpiresAt: null })), 2_000);

    renderApp(`/events/${walk.id}`);

    expect(await screen.findByRole('timer')).toBeInTheDocument();
    expect(
      await screen.findByText(/Your offer expired/, {}, { timeout: 5_000 }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Accept spot' })).toBeNull();
    expect(await screen.findByRole('button', { name: 'Join' })).toBeInTheDocument();
    // The wording outlasts the refetch that makes the status NONE.
    expect(screen.getByText(/Your offer expired/)).toBeInTheDocument();
  });

  it('shows no Accept button when the event was cancelled meanwhile', async () => {
    const cancelledWalk = { ...walk, status: 'CANCELLED' as const };
    signedIn(eventServer(cancelledWalk, { spots: spotsFor() }));

    renderApp(`/events/${walk.id}`);

    expect(await screen.findByText(/this event was cancelled/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Accept spot' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Join' })).toBeNull();
  });

  it('explains a late accept (409 OFFER_EXPIRED) and shows what is true now', async () => {
    const state = { spots: spotsFor() };
    const server = eventServer(walk, state);
    signedIn((call) => {
      if (call.method === 'POST' && call.path === `/api/events/${walk.id}/rsvp/accept`) {
        state.spots = spotsFor({ myStatus: 'NONE', offerExpiresAt: null });
        return jsonResponse(
          {
            responseType: 'ERROR',
            body: { errorType: 'OFFER_EXPIRED', errorMessage: 'x', correlationId: 'c-1' },
          },
          409,
        );
      }
      return server(call);
    });

    renderApp(`/events/${walk.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Accept spot' }));

    // The refetch finds no offer any more, so the panel moves on to Join and still says why.
    expect(await screen.findByRole('button', { name: 'Join' })).toBeInTheDocument();
    expect(screen.getByText(/Your offer expired/)).toBeInTheDocument();
  });
});

describe('the host’s attendee list', () => {
  it('lists the people holding an offer, in order', async () => {
    const mine: Schemas['EventResponse'] = {
      ...walk,
      host: meAsPerson,
      isHost: true,
      myStatus: 'CONFIRMED',
      audience: [{ type: 'FRIENDS', label: null, guildId: null, circleId: null, userId: null }],
    };
    signedIn((call) => {
      if (call.path === `/api/events/${mine.id}/attendees`)
        return envelope('ATTENDEES', {
          confirmed: [meAsPerson],
          offered: [bo, ada],
          waitlist: [],
        });
      return eventServer(mine, { spots: spotsFor({ myStatus: 'CONFIRMED' }) })(call);
    });

    renderApp(`/events/${mine.id}`);

    expect(await screen.findByRole('heading', { name: 'Going (1)' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Offered 2' }));

    const list = screen.getByRole('list', { name: 'Offered a spot (2)' });
    expect(
      within(list)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['Bo', 'Ada']);
    // One list at a time.
    expect(screen.queryByRole('heading', { name: /^Going/ })).toBeNull();
  });
});
