import { onlineManager } from '@tanstack/react-query';
import { fireEvent, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
import { envelope, me, mockApi, onDesktop, renderApp } from './test-utils';

type EventBody = components['schemas']['EventResponse'];

// Thursday noon in Chicago, the viewer's zone.
const NOW = '2026-11-26T18:00:00Z';

/** A plan told apart by its title, starting at a Chicago wall time and lasting two hours. */
function plan(title: string, local: string, extra: Partial<EventBody> = {}): EventBody {
  const start = Date.parse(`${local}:00-06:00`);
  return {
    id: `event-${title.replaceAll(' ', '-')}`,
    title,
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
    startsAt: new Date(start).toISOString(),
    endsAt: new Date(start + 2 * 3_600_000).toISOString(),
    timeZone: 'America/Chicago',
    ...extra,
  };
}

const gunpla = plan('Gunpla', '2026-11-28T10:00', { isHost: true, visibleBecause: null });
const boardGames = plan('Board games', '2026-11-28T18:00', { myStatus: 'OFFERED' });
const crawl = plan('Crawl', '2026-11-29T11:00', {
  myStatus: 'WAITLISTED',
  location: null,
  locationHidden: true,
});
// Half an hour before the crawl ends.
const ramen = plan('Ramen run', '2026-11-29T12:30');
const walk = plan('Photo walk', '2026-11-29T16:00');
const quiz = plan('Pub quiz', '2026-11-29T19:00');
const swap = plan('Swap meet', '2026-12-02T13:00', { status: 'CANCELLED' });
const everything = [gunpla, boardGames, crawl, ramen, walk, quiz, swap];

function signedIn(plans: EventBody[] = everything) {
  return mockApi(({ path }) => {
    if (path === '/api/me') return envelope('ME', me);
    if (path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds: [] });
    if (path.startsWith('/api/notifications'))
      return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: 0 });
    if (path.startsWith('/api/events?scope=attending'))
      return envelope('EVENT_PAGE', { events: plans, nextCursor: null });
    if (path.endsWith('/spots'))
      return envelope('SPOTS', {
        capacity: 8,
        confirmed: 8,
        waitlisted: 1,
        spotsLeft: 0,
        myStatus: 'OFFERED',
        myPosition: null,
        offerExpiresAt: '2026-11-26T18:24:00Z',
      });
    return undefined;
  });
}

const month = () => screen.getByRole('grid').getAttribute('aria-labelledby')!;
const monthName = () => document.getElementById(month())!.textContent;
/** A day's button, by its date: "November 29". */
const day = (date: string) => screen.getByRole('button', { name: new RegExp(` ${date}, 202\\d,`) });
const marks = (date: string, mark: 'dot' | 'chip') =>
  [...day(date).querySelectorAll(`[data-${mark}]`)].map((e) => e.getAttribute(`data-${mark}`));
const dayList = (date: string) =>
  within(screen.getByRole('region', { name: new RegExp(`day, ${date} `) }));
const titles = (list: ReturnType<typeof dayList>) =>
  list.queryAllByRole('link').map((link) => link.querySelector('.font-bold')!.textContent);
const pickedDays = () =>
  screen
    .getAllByRole('gridcell', { selected: true })
    .map((cell) => within(cell).getByRole('button').dataset.day);
const opened = () => screen.findByRole('grid');

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  onlineManager.setOnline(true);
  localStorage.clear();
});

describe('the My plans calendar', () => {
  it('opens on the next plan’s month and day, passing over a cancelled one', async () => {
    signedIn([swap, plan('Later', '2027-01-09T13:00')]);
    renderApp('/plans?view=calendar');
    await opened();

    expect(monthName()).toBe('January 2027');
    expect(pickedDays()).toEqual(['2027-01-09']);
    expect(titles(dayList('Jan 9'))).toEqual(['Later']);
  });

  it('opens on today when nothing is planned', async () => {
    signedIn([]);
    renderApp('/plans?view=calendar');
    await opened();

    expect(monthName()).toBe('November 2026');
    expect(pickedDays()).toEqual(['2026-11-26']);
    expect(day('November 26')).toHaveAttribute('aria-current', 'date');
    expect(dayList('Nov 26').getByText('Nothing planned this day.')).toBeInTheDocument();
  });

  it('marks each day with its plans, a dot and a title for each status', async () => {
    signedIn();
    renderApp('/plans?view=calendar');
    await opened();

    expect(marks('November 28', 'dot')).toEqual(['hosting', 'offer']);
    expect(marks('November 28', 'chip')).toEqual(['hosting', 'offer']);
    // Three dots and two titles at most.
    expect(marks('November 29', 'dot')).toEqual(['waiting', 'going', 'going']);
    expect(marks('November 29', 'chip')).toEqual(['waiting', 'going']);
    expect(day('November 29')).toHaveTextContent('11 AM Crawl12:30 PM Ramen run+2 more');
    // A day of the next month that the grid shows.
    expect(marks('December 2', 'dot')).toEqual(['cancelled']);

    expect(day('November 26')).toHaveAccessibleName('Thursday, November 26, 2026, no plans');
    expect(day('November 28')).toHaveAccessibleName('Saturday, November 28, 2026, 2 plans');
    expect(day('December 2')).toHaveAccessibleName('Wednesday, December 2, 2026, 1 plan');
    const legend = within(screen.getByRole('list', { name: 'Dot colors' }));
    expect(legend.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Going',
      'Waiting',
      'Offer',
      'Hosting',
      'Cancelled',
    ]);
  });

  it('lists a tapped day’s meetups, with what overlaps', async () => {
    signedIn();
    renderApp('/plans?view=calendar');
    await opened();
    // The next plan's day, with the offer counting down.
    expect(titles(dayList('Nov 28'))).toEqual(['Gunpla', 'Board games']);
    expect(await dayList('Nov 28').findByRole('timer')).toHaveTextContent('24m 00s');

    fireEvent.click(day('November 29'));

    expect(pickedDays()).toEqual(['2026-11-29']);
    const list = dayList('Nov 29');
    expect(screen.getByRole('heading', { name: /Nov 29 · 4 meetups/ })).toBeInTheDocument();
    expect(titles(list)).toEqual(['Crawl', 'Ramen run', 'Photo walk', 'Pub quiz']);
    const first = list.getAllByRole('link')[0]!;
    expect(first).toHaveAttribute('href', `/events/${crawl.id}`);
    expect(first).toHaveTextContent('11:00 AM CST');
    expect(first).toHaveTextContent('Location shared with confirmed attendees');
    expect(first).toHaveTextContent('Waitlisted');
    expect(list.getAllByText(/overlap$/).map((line) => line.textContent)).toEqual([
      '“Crawl” and “Ramen run” overlap',
    ]);

    fireEvent.click(day('November 27'));
    expect(dayList('Nov 27').getByText('Nothing planned this day.')).toBeInTheDocument();
    expect(screen.queryByText(/overlap$/)).toBeNull();
  });

  it('puts a plan on the viewer’s day, with the time in the event’s zone', async () => {
    // 10:30 PM on the 29th in Los Angeles is half past midnight on the 30th in Chicago.
    signedIn([plan('Late show', '2026-11-30T00:30', { timeZone: 'America/Los_Angeles' })]);
    renderApp('/plans?view=calendar');
    await opened();

    expect(pickedDays()).toEqual(['2026-11-30']);
    expect(marks('November 29', 'dot')).toEqual([]);
    const link = dayList('Nov 30').getByRole('link');
    expect(link).toHaveTextContent('Sun, Nov 29 · 10:30 PM PST');
    expect(link).toHaveTextContent(/Your time: Mon, Nov 30.*12:30 AM CST/);
  });

  it('keeps the picked day while the month turns, and follows Today and a neighbour', async () => {
    signedIn();
    renderApp('/plans?view=calendar');
    await opened();

    fireEvent.click(screen.getByRole('button', { name: 'Next month' }));
    expect(monthName()).toBe('December 2026');
    expect(titles(dayList('Nov 28'))).toEqual(['Gunpla', 'Board games']);

    fireEvent.click(screen.getByRole('button', { name: 'Today' }));
    expect(monthName()).toBe('November 2026');
    expect(pickedDays()).toEqual(['2026-11-26']);

    fireEvent.click(day('December 2'));
    expect(monthName()).toBe('December 2026');
    expect(dayList('Dec 2').getByRole('link')).toHaveTextContent('Cancelled');
  });

  it('keeps the view and the segment in the URL, and the segment filters both', async () => {
    const calls = signedIn();
    const router = renderApp('/plans');
    await screen.findByRole('link', { name: /^Gunpla/ });
    const press = (group: string, name: RegExp) =>
      fireEvent.click(
        within(screen.getByRole('group', { name: group })).getByRole('button', { name }),
      );
    const search = () => router.state.location.search;

    press('View', /Calendar/);
    expect(search()).toBe('?view=calendar');
    expect(router.state.historyAction).toBe('REPLACE');
    press('Which plans', /^Going/);
    expect(search()).toBe('?tab=going&view=calendar');
    expect(day('November 29')).toHaveAccessibleName(/, 3 plans$/);
    expect(day('November 28')).toHaveAccessibleName(/, no plans$/);
    fireEvent.click(day('November 29'));
    expect(titles(dayList('Nov 29'))).toEqual(['Ramen run', 'Photo walk', 'Pub quiz']);
    // A clash with a meetup of another segment is still marked.
    expect(screen.getByText('“Ramen run” and “Crawl” overlap')).toBeInTheDocument();

    press('View', /List/);
    expect(search()).toBe('?tab=going');
    expect(screen.queryByRole('grid')).toBeNull();
    expect(screen.queryByRole('link', { name: /^Crawl/ })).toBeNull();
    // Back on the calendar, the day is still the one picked.
    press('View', /Calendar/);
    expect(pickedDays()).toEqual(['2026-11-29']);
    press('Which plans', /^All/);
    expect(search()).toBe('?view=calendar');
    expect(calls.filter((c) => c.path.startsWith('/api/events?'))).toHaveLength(1);
  });

  it('opens on the view and segment in the URL', async () => {
    signedIn();
    renderApp('/plans?tab=hosting&view=calendar');
    await opened();

    for (const name of [/Calendar/, /^Hosting/]) {
      expect(screen.getByRole('button', { name })).toHaveAttribute('aria-pressed', 'true');
    }
    expect(titles(dayList('Nov 28'))).toEqual(['Gunpla']);
  });

  it('puts the picked day beside the calendar on desktop', async () => {
    onDesktop();
    signedIn();
    renderApp('/plans?view=calendar');
    await opened();

    const picked = screen.getByRole('region', { name: /Nov 28/ });
    const needs = await screen.findByRole('region', { name: 'Needs your answer' });
    expect(picked.parentElement).toBe(needs.parentElement);
  });

  // The request still goes out offline, which is how the service worker's saved copy answers it.
  it('works offline as the list does', async () => {
    onlineManager.setOnline(false);
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    signedIn();
    renderApp('/plans?view=calendar');
    await opened();

    fireEvent.click(day('November 29'));
    expect(titles(dayList('Nov 29'))).toHaveLength(4);
  });
});
