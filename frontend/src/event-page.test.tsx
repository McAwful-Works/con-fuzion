import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
import { envelope, me, mockApi, renderApp } from './test-utils';

type Schemas = components['schemas'];
type Event = Schemas['EventResponse'];
type Spots = Schemas['SpotsResponse'];

const ada: Schemas['PersonResponse'] = {
  id: '11111111-1111-4111-8111-111111111111',
  displayName: 'Ada',
  avatarUrl: 'https://cdn.discordapp.com/embed/avatars/1.png',
};
const meAsPerson: Schemas['PersonResponse'] = {
  id: me.id,
  displayName: 'Con Goer',
  avatarUrl: me.avatarUrl,
};

const HOUR = 3_600_000;
const walk: Event = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  title: 'Cosplay photo walk',
  description: 'Bring props.',
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
  spotsLeft: 7,
  visibleBecause: [],
  capacity: 10,
  signupOpensAt: null,
  startsAt: '2030-07-01T20:00:00Z',
  endsAt: '2030-07-01T22:00:00Z',
  timeZone: 'America/Chicago',
};
const mine: Event = {
  ...walk,
  host: meAsPerson,
  isHost: true,
  myStatus: 'CONFIRMED',
  locationVisibility: 'CONFIRMED',
  visibleBecause: null,
  audience: [{ type: 'FRIENDS', label: null, guildId: null, circleId: null, userId: null }],
};

/** Renders an event page whose event and `/spots` answer as given. */
function open(event: Event, spots: Partial<Spots> = {}) {
  const calls = mockApi(({ path }) => {
    if (path === '/api/me') return envelope('ME', me);
    if (path.startsWith('/api/notifications'))
      return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: 0 });
    if (path === `/api/events/${event.id}`) return envelope('EVENT', event);
    if (path === `/api/events/${event.id}/spots`)
      return envelope('SPOTS', {
        capacity: 10,
        confirmed: 3,
        waitlisted: 0,
        spotsLeft: 7,
        myStatus: event.myStatus,
        myPosition: null,
        offerExpiresAt: null,
        ...spots,
      } satisfies Spots);
    if (path === `/api/events/${event.id}/attendees`)
      return envelope('ATTENDEES', { confirmed: [meAsPerson], offered: [], waitlist: [] });
    return undefined;
  });
  renderApp(`/events/${event.id}`);
  return calls;
}

// jsdom applies no CSS, so where the bar sits at a phone width is checked end to end.
const dock = () => within(document.querySelector<HTMLElement>('[data-actionbar]')!);
const facts = () => within(screen.getByRole('region', { name: 'Event details' }));
const findTitle = () => screen.findByRole('heading', { level: 1, name: 'Cosplay photo walk' });

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('the hero', () => {
  const guild = (guildName: string) => ({ type: 'GUILD' as const, guildName });

  it('tells a viewer their own reasons for seeing the event', async () => {
    open({
      ...walk,
      visibleBecause: [
        guild('Test Con'),
        guild('Retro Gamers'),
        { type: 'FRIEND', guildName: null },
        { type: 'INVITED', guildName: null },
      ],
    });
    await findTitle();
    expect(screen.getByText('Photoshoot')).toBeInTheDocument();
    expect(screen.getByText('Hosted by Ada').parentElement).toHaveTextContent(
      'Hosted by Ada · for Test Con and Retro Gamers · Friend of the host · Invited',
    );
    expect(screen.queryByRole('region', { name: 'Who can see this' })).toBeNull();
  });

  it('says nothing more when there is no reason to give', async () => {
    open(walk);
    await findTitle();
    expect(screen.getByText('Hosted by Ada').parentElement).toHaveTextContent(/^Hosted by Ada$/);
  });

  it('puts the tag’s cover behind the title', async () => {
    open(walk);
    const header = (await findTitle()).closest('header')!;
    expect(header.firstElementChild).toHaveClass('placeholder-cover-photoshoot');
  });
});

describe('the facts card', () => {
  it('shows the day and time in the event’s zone, yours when it differs, and the spots', async () => {
    mockApi(({ path }) => {
      if (path === '/api/me') return envelope('ME', { ...me, timeZone: 'Asia/Tokyo' });
      if (path.startsWith('/api/notifications'))
        return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: 0 });
      if (path === `/api/events/${walk.id}`) return envelope('EVENT', walk);
      return envelope('SPOTS', {
        capacity: 10,
        confirmed: 10,
        waitlisted: 2,
        spotsLeft: 0,
        myStatus: 'NONE',
        myPosition: null,
        offerExpiresAt: null,
      });
    });
    renderApp(`/events/${walk.id}`);
    await findTitle();
    expect(facts().getByText(/Monday, Jul 1 · 3:00 PM – 5:00 PM CDT/)).toBeInTheDocument();
    expect(await facts().findByText(/^Your time: .*GMT\+9/)).toBeInTheDocument();
    expect(facts().getByText('Hall B fountain')).toBeInTheDocument();
    expect(await facts().findByText('Full')).toBeInTheDocument();
    expect(facts().getByText('10 of 10 spots taken, 2 on the waitlist')).toBeInTheDocument();
  });
});

describe('the action bar', () => {
  const past = new Date(Date.now() - HOUR).toISOString();
  const soon = new Date(Date.now() + 2 * HOUR).toISOString();
  const offer = new Date(Date.now() + 20 * 60_000).toISOString();
  const full = { confirmed: 10, spotsLeft: 0 };

  it.each<[string, Partial<Event>, Partial<Spots>, (string | RegExp)[], string[]]>([
    ['open', {}, {}, [], ['Join']],
    ['full', {}, full, [/Joining puts you on the waitlist/], ['Join']],
    [
      'waitlisted',
      { myStatus: 'WAITLISTED' },
      { ...full, myPosition: 2 },
      ['Waitlisted #2'],
      ['Leave'],
    ],
    [
      'offered',
      { myStatus: 'OFFERED' },
      { ...full, offerExpiresAt: offer },
      ['A spot is held for you.', /Offer ends in/],
      ['Decline', 'Accept spot'],
    ],
    ['joined', { myStatus: 'CONFIRMED' }, {}, ['Joined'], ['Add to calendar', 'Leave']],
    ['removed', { myStatus: 'REMOVED' }, {}, ['You can’t rejoin this event.'], []],
    ['cancelled', { status: 'CANCELLED' }, {}, [/Signup is closed because the event was/], []],
    // D-013 rule 12: someone still signed up can leave a cancelled event, which takes it off My plans.
    [
      'cancelled, joined',
      { status: 'CANCELLED', myStatus: 'CONFIRMED' },
      {},
      ['Joined'],
      ['Leave'],
    ],
    [
      'cancelled, waitlisted',
      { status: 'CANCELLED', myStatus: 'WAITLISTED' },
      {},
      ['Waitlisted'],
      ['Leave'],
    ],
    [
      'cancelled, offered',
      { status: 'CANCELLED', myStatus: 'OFFERED' },
      {},
      [/You were offered a spot, but this event was cancelled/],
      [],
    ],
    ['started', { startsAt: past }, {}, [/Signup is closed because the event has started/], []],
    ['not open yet', { signupOpensAt: soon }, {}, [/Signup opens in/], ['Join']],
  ])('holds the action when %s', async (name, event, spots, texts, buttons) => {
    open({ ...walk, ...event }, spots);
    await findTitle();
    for (const text of texts) expect(await dock().findByText(text)).toBeInTheDocument();
    for (const button of buttons) {
      expect(await dock().findByRole('button', { name: button })).toBeInTheDocument();
    }
    expect(dock().queryAllByRole('button')).toHaveLength(buttons.length);
    if (name === 'not open yet') {
      expect(dock().getByRole('button', { name: 'Join' })).toBeDisabled();
    }
  });
});

describe('add to calendar', () => {
  const ago = (hours: number) => new Date(Date.now() - hours * HOUR).toISOString();
  const joined: Event = { ...walk, myStatus: 'CONFIRMED' };
  const button = () => screen.queryByRole('button', { name: 'Add to calendar' });

  it.each<[string, Event]>([
    ['the host', mine],
    ['a confirmed guest', joined],
    ['a guest while it is on', { ...joined, startsAt: ago(2), endsAt: null }],
  ])('is there for %s, in the action bar', async (_, event) => {
    open(event);
    await findTitle();
    expect(await dock().findByRole('button', { name: 'Add to calendar' })).toBeInTheDocument();
  });

  it.each<[string, Event]>([
    ['a viewer', walk],
    ['someone waitlisted', { ...walk, myStatus: 'WAITLISTED' }],
    ['someone offered a spot', { ...walk, myStatus: 'OFFERED' }],
    ['someone removed', { ...walk, myStatus: 'REMOVED' }],
    ['a guest of a cancelled event', { ...joined, status: 'CANCELLED' }],
    ['the host of a cancelled event', { ...mine, status: 'CANCELLED' }],
    ['a guest after the end', { ...joined, startsAt: ago(2), endsAt: ago(1) }],
    ['a guest 3 hours after a start with no end', { ...joined, startsAt: ago(4), endsAt: null }],
    ['the host after the end', { ...mine, startsAt: ago(2), endsAt: ago(1) }],
  ])('is not there for %s', async (_, event) => {
    open(event);
    await findTitle();
    // Once the spots have answered, the bar shows where the viewer really stands.
    if (event.status === 'PUBLISHED') await screen.findByText('Spots left: 7');
    expect(button()).toBeNull();
  });

  it('follows the polled status, which is the fresher one', async () => {
    open(walk, { myStatus: 'CONFIRMED' });
    expect(await screen.findByRole('button', { name: 'Add to calendar' })).toBeInTheDocument();
  });

  const openMenu = async (event: Event = joined) => {
    const calls = open(event);
    const toggle = await screen.findByRole('button', { name: 'Add to calendar' });
    await screen.findByText('Spots left: 7');
    expect(screen.queryByRole('link', { name: /Open in Google Calendar/ })).toBeNull();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    return { calls, toggle };
  };
  const google = () => screen.queryByRole('link', { name: /Open in Google Calendar/ });

  it('offers the file, the Google link in a new tab, and the note', async () => {
    await openMenu();
    expect(dock().getByRole('button', { name: /Download \.ics/ })).toBeInTheDocument();
    const link = google()!;
    expect(link).toHaveAttribute(
      'href',
      expect.stringContaining('https://calendar.google.com/calendar/render?action=TEMPLATE&'),
    );
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(
      dock().getByText(/Not kept in sync: if the host changes it, add it again\./),
    ).toBeInTheDocument();
  });

  it('saves the file in the browser, asking the API for nothing', async () => {
    const createObjectURL = vi.fn<(file: Blob) => string>(() => 'blob:file');
    // jsdom has neither.
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    const saved: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      saved.push(`${this.download} ${this.href}`);
    });
    const { calls, toggle } = await openMenu();
    expect(createObjectURL).not.toHaveBeenCalled();
    const before = calls.length;

    fireEvent.click(screen.getByRole('button', { name: /Download \.ics/ }));

    expect(saved).toEqual(['Cosplay photo walk.ics blob:file']);
    const file = createObjectURL.mock.calls[0]![0];
    expect(file.type).toBe('text/calendar;charset=utf-8');
    const text = await file.text();
    expect(text).toContain('LOCATION:Hall B fountain\r\n');
    expect(text.replace(/\r\n /g, '')).toContain(
      `URL:${window.location.origin}/events/${walk.id}\r\n`,
    );
    expect(calls).toHaveLength(before);
    expect(google()).toBeNull();
    expect(toggle).toHaveFocus();
  });

  it('closes on Escape, handing focus back, and on a press elsewhere', async () => {
    const { toggle } = await openMenu(mine);
    const choice = screen.getByRole('button', { name: /Download \.ics/ });
    choice.focus();
    fireEvent.keyDown(choice, { key: 'Escape' });
    expect(google()).toBeNull();
    expect(toggle).toHaveFocus();
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(toggle);
    fireEvent.pointerDown(google()!);
    expect(google()).not.toBeNull();
    fireEvent.pointerDown(screen.getByRole('heading', { level: 1 }));
    expect(google()).toBeNull();
  });

  it('closes when focus moves on past it', async () => {
    const { toggle } = await openMenu();
    toggle.focus();
    google()!.focus();
    expect(google()).not.toBeNull();
    screen.getByRole('button', { name: 'Leave' }).focus();
    await waitFor(() => expect(google()).toBeNull());
  });
});

describe('who’s going', () => {
  it.each(['NONE', 'WAITLISTED', 'OFFERED', 'REMOVED'] as const)(
    'is a locked card with the count for a viewer who is %s',
    async (myStatus) => {
      const calls = open({ ...walk, myStatus });
      await findTitle();
      const card = screen.getByRole('region', { name: 'Who’s going' });
      expect(await within(card).findByText('3 going')).toBeInTheDocument();
      expect(card).toHaveTextContent('Only the host and confirmed attendees see who’s going.');
      expect(screen.queryByRole('heading', { name: /^Going/ })).toBeNull();
      expect(calls.some((c) => c.path.endsWith('/attendees'))).toBe(false);
    },
  );

  it('counts the spots taken from the event itself once it is cancelled', async () => {
    open({ ...walk, status: 'CANCELLED', spotsLeft: 6 });
    await findTitle();
    expect(screen.getByText('4 going')).toBeInTheDocument();
  });

  it('is the list itself for a confirmed attendee, with no tabs', async () => {
    open({ ...walk, myStatus: 'CONFIRMED' });
    expect(await screen.findByRole('list', { name: 'Going (1)' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Who’s going' })).toBeNull();
    expect(screen.queryByRole('group', { name: 'Guest lists' })).toBeNull();
  });
});

describe('the host view', () => {
  it('shows the location, the host’s tools in the bar, and the guest tabs', async () => {
    open(mine);
    await findTitle();
    expect(screen.getByText('You’re hosting')).toBeInTheDocument();
    expect(facts().getByText('Hall B fountain')).toBeInTheDocument();
    const edit = `/events/${mine.id}/edit`;
    expect(dock().getByRole('link', { name: 'Edit event' })).toHaveAttribute('href', edit);
    expect(dock().getByRole('link', { name: 'Change who can see it' })).toHaveAttribute(
      'href',
      `${edit}#audience`,
    );
    expect(dock().getByRole('button', { name: 'Cancel event' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Spots and signup' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Who’s going' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Report event' })).toBeNull();

    const tabs = within(await screen.findByRole('group', { name: 'Guest lists' }));
    expect(tabs.getByRole('button', { name: 'Going 1' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(tabs.getByRole('button', { name: 'Offered 0' }));
    expect(screen.getByText('Nobody has an open offer.')).toBeInTheDocument();
    fireEvent.click(tabs.getByRole('button', { name: 'Waitlist 0' }));
    expect(screen.getByText('Nobody is waiting.')).toBeInTheDocument();
  });

  it('has no bar once the event is cancelled', async () => {
    open({ ...mine, status: 'CANCELLED' });
    await findTitle();
    expect(document.querySelector('[data-actionbar]')).toBeNull();
  });
});
