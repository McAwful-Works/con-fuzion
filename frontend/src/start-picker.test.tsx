import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
import {
  envelope,
  errorEnvelope,
  guilds,
  jsonResponse,
  me,
  mockApi,
  onDesktop,
  renderApp,
  typeStart,
  type RecordedCall,
} from './test-utils';

// The start date picked on the month grid (#159). The viewer is in America/Chicago, and it's noon
// there on Tuesday, November 26, 2030.

type Schemas = components['schemas'];

const NOW = new Date('2030-11-26T18:00:00Z');

function plan(over: Partial<Schemas['EventResponse']>): Schemas['EventResponse'] {
  return {
    id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    title: 'My panel',
    description: null,
    location: 'Hall B',
    locationHidden: false,
    locationVisibility: 'VIEWERS',
    tag: null,
    host: { id: me.id, displayName: 'Con Goer', avatarUrl: me.avatarUrl },
    isHost: false,
    myStatus: 'CONFIRMED',
    status: 'PUBLISHED',
    hiddenReason: null,
    audience: [{ type: 'FRIENDS', label: null, guildId: null, circleId: null, userId: null }],
    capacity: null,
    spotsLeft: null,
    visibleBecause: null,
    signupOpensAt: null,
    confirmWindowMinutes: 30,
    startsAt: '2030-11-29T17:00:00Z',
    endsAt: null,
    timeZone: 'America/Chicago',
    ...over,
  };
}

/** The meetup being edited: 6 PM in Chicago on November 29, with seconds the form doesn't show. */
const mine = plan({ isHost: true, startsAt: '2030-11-30T00:00:42Z' });

const plans = [
  plan({ id: 'p-ramen', title: 'Ramen run', startsAt: '2030-11-29T18:30:00Z' }),
  plan({
    id: 'p-crawl',
    title: 'Artist alley crawl',
    myStatus: 'WAITLISTED',
    startsAt: '2030-11-29T19:00:00Z',
  }),
  // Early on the 30th in Tokyo, and the evening of the 29th where the viewer is.
  plan({
    id: 'p-tokyo',
    title: 'Tokyo call',
    myStatus: 'OFFERED',
    startsAt: '2030-11-30T02:00:00Z',
    timeZone: 'Asia/Tokyo',
  }),
  plan({ id: 'p-off', title: 'Called off', status: 'CANCELLED' }),
  plan({ id: 'p-later', title: 'Another day', startsAt: '2030-12-02T18:00:00Z' }),
  mine,
];

function signedIn(event = mine, attending: () => Response | undefined = () => undefined) {
  return mockApi((call) => {
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
    if (call.path === '/api/circles') return envelope('CIRCLE_LIST', { circles: [] });
    if (call.path.startsWith('/api/events?scope=attending'))
      return attending() ?? envelope('EVENT_PAGE', { events: plans, nextCursor: null });
    if (call.path.startsWith('/api/events/')) return envelope('EVENT', event);
    if (call.method === 'POST' && call.path === '/api/events') return envelope('EVENT', event);
    return undefined;
  });
}

const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
/** A day's button on the grid, by its date: "November 29". */
const day = (date: string) => screen.getByRole('button', { name: new RegExp(` ${date}, 2030,`) });
const pressed = () =>
  within(screen.getByRole('group', { name: /start time/i }))
    .queryAllByRole('button', { pressed: true })
    .map((button) => button.textContent);
const selectedDay = () =>
  screen
    .queryAllByRole('gridcell', { selected: true })
    .map((cell) => within(cell).getByRole('button').dataset.day);
const agenda = () => screen.queryByRole('region', { name: 'Already that day' });

async function newMeetup() {
  renderApp('/events/new');
  fireEvent.change(await screen.findByLabelText('Title'), { target: { value: 'Panel' } });
  fireEvent.change(screen.getByLabelText('Location'), { target: { value: 'Room 4' } });
  fireEvent.click(screen.getByLabelText('All my friends'));
}

async function edit() {
  renderApp(`/events/${mine.id}/edit`);
  await screen.findByRole('grid');
}

async function sent(calls: RecordedCall[], method = 'POST') {
  await waitFor(() => expect(calls.some((c) => c.method === method)).toBe(true));
  return calls.find((c) => c.method === method)!.body as Schemas['EventRequest'];
}

function useTimeZone(search: string) {
  const zone = screen.getByLabelText('Time zone');
  fireEvent.change(zone, { target: { value: search } });
  fireEvent.keyDown(zone, { key: 'Enter' });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  onDesktop();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('picking the start', () => {
  it.each([
    ['picked', () => (fireEvent.click(day('November 29')), click('6:00 PM'))],
    ['picked time first', () => (click('6:00 PM'), fireEvent.click(day('November 29')))],
    ['typed', () => typeStart('2030-11-29T18:00')],
  ])('sends the same startsAt, in the event’s zone, when %s', async (_, choose) => {
    const calls = signedIn();
    await newMeetup();
    useTimeZone('tokyo');

    choose();
    click('Create meetup');

    expect(await sent(calls)).toMatchObject({
      timeZone: 'Asia/Tokyo',
      startsAt: '2030-11-29T09:00:00.000Z',
    });
  });

  it('offers a time every 2 hours from 10 AM to 8 PM', async () => {
    signedIn();
    await newMeetup();

    const times = within(screen.getByRole('group', { name: 'Start time' })).getAllByRole('button');
    expect(times.map((b) => b.textContent)).toEqual([
      '10:00 AM',
      '12:00 PM',
      '2:00 PM',
      '4:00 PM',
      '6:00 PM',
      '8:00 PM',
      'Other…',
    ]);
    fireEvent.click(day('November 29'));
    expect(screen.getByRole('group', { name: 'Friday, Nov 29 · start time' })).toBeInTheDocument();
  });

  it.each([
    ['nothing', () => {}],
    ['only a day', () => fireEvent.click(day('November 29'))],
    ['only a time', () => click('2:00 PM')],
  ])('still asks for a start when %s was picked', async (_, choose) => {
    const calls = signedIn();
    await newMeetup();

    choose();
    click('Create meetup');

    expect(await screen.findByText('Choose when it starts.')).toBeInTheDocument();
    expect(screen.getByRole('group', { name: /start time/i })).toHaveAccessibleDescription(
      'Choose when it starts.',
    );
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('refuses a time that has gone by today, in today’s words', async () => {
    signedIn();
    await newMeetup();

    fireEvent.click(day('November 26'));
    click('10:00 AM');
    click('Create meetup');

    expect(await screen.findByText('The start can’t be in the past.')).toBeInTheDocument();
    click('8:00 PM');
    expect(screen.queryByText('The start can’t be in the past.')).toBeNull();
  });

  it('disables past days, which can’t be picked', async () => {
    signedIn();
    await newMeetup();

    expect(day('November 25')).toHaveAttribute('aria-disabled', 'true');
    expect(day('November 26')).not.toHaveAttribute('aria-disabled');
    fireEvent.click(day('November 25'));
    expect(selectedDay()).toEqual([]);
    fireEvent.click(day('November 26'));
    expect(selectedDay()).toEqual(['2030-11-26']);
  });

  it('counts a day as past once it’s over in the event’s zone', async () => {
    signedIn();
    await newMeetup();

    // 18:00 UTC on the 26th is already the 27th in Tokyo.
    useTimeZone('tokyo');

    expect(day('November 26')).toHaveAttribute('aria-disabled', 'true');
    expect(day('November 27')).toHaveAttribute('aria-current', 'date');
  });

  it('shows the date and time field for Other…, and the grid follows what’s typed', async () => {
    const calls = signedIn();
    await newMeetup();
    expect(screen.queryByLabelText('Starts')).toBeNull();
    fireEvent.click(day('November 29'));
    click('4:00 PM');

    click('Other…');
    const field = screen.getByLabelText('Starts');
    expect(field).toHaveAttribute('type', 'datetime-local');
    expect(field).toHaveValue('2030-11-29T16:00');
    expect(pressed()).toEqual(['Other…']);
    // A typed quick time doesn't take the field away.
    fireEvent.change(field, { target: { value: '2030-12-07T18:00' } });
    expect(screen.getByRole('heading', { name: 'December 2030' })).toBeInTheDocument();
    expect(selectedDay()).toEqual(['2030-12-07']);
    expect(pressed()).toEqual(['Other…']);
    fireEvent.change(field, { target: { value: '2030-12-07T17:45' } });
    // Another day keeps the typed time.
    fireEvent.click(day('December 8'));
    expect(field).toHaveValue('2030-12-08T17:45');
    click('Create meetup');

    expect((await sent(calls)).startsAt).toBe('2030-12-08T23:45:00.000Z');
  });

  it('puts the field away for a quick time, and clears the day with the field', async () => {
    signedIn();
    await newMeetup();
    typeStart('2030-11-29T17:45');

    click('12:00 PM');
    expect(screen.queryByLabelText('Starts')).toBeNull();
    expect(pressed()).toEqual(['12:00 PM']);
    typeStart('');
    expect(selectedDay()).toEqual([]);
  });

  it('survives a year the grid can’t draw', async () => {
    signedIn();
    await newMeetup();

    typeStart('20300-11-29T17:45');
    click('Create meetup');

    expect(await screen.findByText('Enter a valid start date and time.')).toBeInTheDocument();
    expect(selectedDay()).toEqual([]);
  });
});

describe('your own plans', () => {
  it('dots their days, and lists the ones already on the picked day', async () => {
    signedIn();
    await newMeetup();
    // The cancelled one doesn't count; the one in Tokyo falls on the viewer's 29th.
    const friday = await screen.findByRole('button', { name: /November 29, 2030, 4 plans/ });
    expect(friday.querySelectorAll('[data-dot]')).toHaveLength(3);
    expect(agenda()).toBeNull();

    fireEvent.click(friday);

    const rows = within(agenda()!).getAllByRole('listitem');
    expect(rows.map((r) => r.textContent)).toEqual([
      '12:30 PM · Ramen run ',
      '1:00 PM · Artist alley crawl (waitlisted)',
      expect.stringMatching(/^Sat, Nov 30 · 11:00 AM GMT\+9 · Tokyo call \(spot offered\)$/),
      '6:00 PM · My panel (hosting)',
    ]);
    fireEvent.click(day('November 28'));
    expect(agenda()).toBeNull();
  });

  it('leaves out the meetup being edited', async () => {
    signedIn();
    await edit();

    const list = await screen.findByRole('region', { name: 'Already that day' });
    expect(within(list).getByText(/Ramen run/)).toBeInTheDocument();
    expect(within(list).queryByText(/My panel/)).toBeNull();
    expect(day('November 29')).toHaveAccessibleName(/3 plans/);
  });

  it('says when they couldn’t load, and still takes a start', async () => {
    const calls = signedIn(mine, () => jsonResponse(errorEnvelope, 500));
    await newMeetup();

    expect(await screen.findByText(/Your plans couldn’t load/)).toBeInTheDocument();
    fireEvent.click(day('November 29'));
    click('6:00 PM');
    click('Create meetup');

    expect((await sent(calls)).startsAt).toBe('2030-11-30T00:00:00.000Z');
  });
});

describe('editing the start', () => {
  it('opens on the event’s day and time, and keeps the exact instant', async () => {
    const calls = signedIn();
    await edit();

    expect(selectedDay()).toEqual(['2030-11-29']);
    expect(pressed()).toEqual(['6:00 PM']);
    expect(screen.queryByLabelText('Starts')).toBeNull();
    click('Save changes');

    expect((await sent(calls, 'PATCH')).startsAt).toBe('2030-11-30T00:00:42.000Z');
  });

  it('opens Other… on a time that isn’t a quick one', async () => {
    signedIn(plan({ isHost: true, startsAt: '2030-12-14T23:15:00Z' }));
    await edit();

    expect(screen.getByRole('heading', { name: 'December 2030' })).toBeInTheDocument();
    expect(selectedDay()).toEqual(['2030-12-14']);
    expect(pressed()).toEqual(['Other…']);
    expect(screen.getByLabelText('Starts')).toHaveValue('2030-12-14T17:15');
  });

  it('moves to a picked day at the same time', async () => {
    const calls = signedIn();
    await edit();

    fireEvent.click(day('November 30'));
    click('Save changes');

    expect((await sent(calls, 'PATCH')).startsAt).toBe('2030-12-01T00:00:00.000Z');
  });

  it('can go back to the day of a meetup that has started', async () => {
    const started = plan({ isHost: true, startsAt: '2030-11-20T00:00:00Z' });
    const calls = signedIn(started);
    await edit();
    expect(day('November 19')).not.toHaveAttribute('aria-disabled');
    expect(day('November 18')).toHaveAttribute('aria-disabled', 'true');

    fireEvent.click(day('November 28'));
    fireEvent.click(day('November 19'));
    click('Save changes');

    expect((await sent(calls, 'PATCH')).startsAt).toBe('2030-11-20T00:00:00.000Z');
  });
});
