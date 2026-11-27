import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
import { utcToZonedLocal } from './features/events/time';
import { envelope, guilds, me, mockApi, renderApp, type RecordedCall } from './test-utils';

type Schemas = components['schemas'];

const START = new Date(Date.now() + 30 * 86_400_000);
START.setUTCSeconds(0, 0);

const mine: Schemas['EventResponse'] = {
  id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  title: 'My panel',
  description: null,
  location: 'Hall B',
  locationHidden: false,
  locationVisibility: 'VIEWERS',
  tag: 'Karaoke night',
  host: { id: me.id, displayName: 'Con Goer', avatarUrl: me.avatarUrl },
  isHost: true,
  myStatus: 'CONFIRMED',
  status: 'PUBLISHED',
  hiddenReason: null,
  audience: [{ type: 'FRIENDS', label: null, guildId: null, circleId: null, userId: null }],
  capacity: 20,
  spotsLeft: 14,
  visibleBecause: null,
  signupOpensAt: null,
  // Not one of the presets: set through the old free-text field.
  confirmWindowMinutes: 45,
  startsAt: START.toISOString(),
  endsAt: null,
  timeZone: 'America/Chicago',
};

function signedIn(extra: (call: RecordedCall) => Response | undefined = () => undefined) {
  return mockApi((call) => {
    const custom = extra(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
    if (call.path === '/api/circles') return envelope('CIRCLE_LIST', { circles: [] });
    if (call.path.startsWith('/api/events/')) return envelope('EVENT', mine);
    if (call.method === 'POST' && call.path === '/api/events') return envelope('EVENT', mine);
    return undefined;
  });
}

const field = (label: string) => screen.getByLabelText(label);
const fill = (label: string, value: string) =>
  fireEvent.change(field(label), { target: { value } });
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
const chip = (name: string) => screen.getByRole('button', { name });
const preview = () => within(screen.getByRole('region', { name: 'Preview in Discover' }));

/** The new-meetup form, filled in far enough to be accepted. */
async function newMeetup() {
  renderApp('/events/new');
  await screen.findByLabelText('Title');
  fill('Title', 'Panel');
  fill('Location', 'Room 4');
  const soon = new Date(Date.now() + 10 * 86_400_000).toISOString();
  fill('Starts', utcToZonedLocal(soon, 'America/Chicago'));
  fireEvent.click(field('All my friends'));
}

async function sent(calls: RecordedCall[], method = 'POST') {
  await waitFor(() => expect(calls.some((c) => c.method === method)).toBe(true));
  return calls.find((c) => c.method === method)!.body as Schemas['EventRequest'];
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('the tag chips', () => {
  it('sends a preset, and none once it is tapped again', async () => {
    const calls = signedIn();
    await newMeetup();

    click('Food');
    click('Music');
    expect(chip('Music')).toHaveAttribute('aria-pressed', 'true');
    expect(chip('Food')).toHaveAttribute('aria-pressed', 'false');
    click('Music');
    expect(chip('Music')).toHaveAttribute('aria-pressed', 'false');
    click('Tabletop');
    click('Create meetup');

    expect((await sent(calls)).tag).toBe('Tabletop');
  });

  it('opens a text box for Other, without carrying a preset into it', async () => {
    const calls = signedIn();
    await newMeetup();
    expect(screen.queryByLabelText('Your tag')).toBeNull();

    click('Food');
    click('Other');
    expect(field('Your tag')).toHaveValue('');
    fill('Your tag', '  Karaoke ');
    expect(preview().getByText('Karaoke')).toBeInTheDocument();
    click('Create meetup');

    expect((await sent(calls)).tag).toBe('Karaoke');
  });

  it('opens an event with its own tag on Other, and drops the tag for a preset', async () => {
    const calls = signedIn();
    renderApp(`/events/${mine.id}/edit`);

    expect(await screen.findByLabelText('Your tag')).toHaveValue('Karaoke night');
    expect(chip('Other')).toHaveAttribute('aria-pressed', 'true');
    click('Panel');
    expect(screen.queryByLabelText('Your tag')).toBeNull();
    click('Save changes');

    expect((await sent(calls, 'PATCH')).tag).toBe('Panel');
  });
});

describe('the spots', () => {
  it('has no limit until the switch is on, then steps from 8', async () => {
    const calls = signedIn();
    await newMeetup();
    expect(screen.queryByLabelText('Capacity')).toBeNull();
    expect(preview().queryByText(/of \d+ left/)).toBeNull();

    fireEvent.click(field('Limit the spots'));
    expect(field('Capacity')).toHaveValue('8');
    click('More spots');
    click('Fewer spots');
    click('Fewer spots');
    expect(field('Capacity')).toHaveValue('7');
    // Off and on again returns to what was there.
    fireEvent.click(field('Limit the spots'));
    fireEvent.click(field('Limit the spots'));
    expect(field('Capacity')).toHaveValue('7');
    click('Create meetup');

    expect((await sent(calls)).capacity).toBe(7);
  });

  it('keeps the stepper between 2 and 10,000', async () => {
    signedIn();
    await newMeetup();
    fireEvent.click(field('Limit the spots'));

    fill('Capacity', '2');
    expect(chip('Fewer spots')).toHaveAttribute('aria-disabled', 'true');
    click('Fewer spots');
    expect(field('Capacity')).toHaveValue('2');
    fill('Capacity', '10000');
    expect(chip('More spots')).toHaveAttribute('aria-disabled', 'true');
    click('More spots');
    expect(field('Capacity')).toHaveValue('10000');
    fill('Capacity', '99999');
    click('Fewer spots');
    expect(field('Capacity')).toHaveValue('10000');
    // An emptied field steps back to the smallest.
    fill('Capacity', '');
    click('More spots');
    expect(field('Capacity')).toHaveValue('2');
  });

  it('refuses a limit with no number, in today’s words', async () => {
    const calls = signedIn();
    await newMeetup();
    fireEvent.click(field('Limit the spots'));
    fill('Capacity', '');
    click('Create meetup');

    expect(
      await screen.findByText('Capacity must be a whole number from 2 to 10000.'),
    ).toBeInTheDocument();
    expect(field('Capacity')).toHaveAttribute('aria-invalid', 'true');
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('keeps an event’s own offer window as a choice', async () => {
    const calls = signedIn();
    renderApp(`/events/${mine.id}/edit`);

    expect(await screen.findByLabelText('Time to accept an offer')).toHaveValue('45');
    fill('Time to accept an offer', '60');
    expect(screen.getByRole('option', { name: '45 minutes' })).toBeInTheDocument();
    fill('Time to accept an offer', '45');
    click('Save changes');

    expect((await sent(calls, 'PATCH')).confirmWindowMinutes).toBe(45);
  });

  it('offers only windows from 10 minutes to 24 hours', async () => {
    signedIn();
    renderApp('/events/new');
    const select = await screen.findByLabelText('Time to accept an offer');

    const options = within(select).getAllByRole<HTMLOptionElement>('option');
    expect(options.map((o) => o.value)).toEqual([
      '10',
      '15',
      '30',
      '60',
      '120',
      '240',
      '720',
      '1440',
    ]);
  });
});

describe('the preview', () => {
  it('follows the form, and can’t be used to leave it', async () => {
    signedIn();
    renderApp('/events/new');
    await screen.findByLabelText('Title');
    expect(preview().getByText('Your meetup’s title')).toBeInTheDocument();
    expect(preview().getByText(/The time is an example/)).toBeInTheDocument();

    fill('Title', 'Board games');
    fill('Location', 'Table 4');
    fill('Starts', '2030-07-01T18:00');
    click('Tabletop');

    expect(preview().getByRole('heading', { name: 'Board games' })).toBeInTheDocument();
    expect(preview().getByText('Table 4')).toBeInTheDocument();
    expect(preview().getByText('Tabletop')).toBeInTheDocument();
    expect(preview().getByText(/6:00.*CDT/)).toBeInTheDocument();
    expect(preview().queryByText(/The time is an example/)).toBeNull();
    expect(preview().getByRole('link').closest('[inert]')).not.toBeNull();

    fireEvent.click(field('Hide the location until people are confirmed'));
    expect(preview().queryByText('Table 4')).toBeNull();
    expect(preview().getByText('Location shared with confirmed attendees')).toBeInTheDocument();
  });

  it('counts the host’s own spot, and on an edit the spots already taken', async () => {
    signedIn();
    const router = renderApp('/events/new');
    await screen.findByLabelText('Title');
    expect(preview().getByText('No limit')).toBeInTheDocument();
    fireEvent.click(field('Limit the spots'));
    expect(preview().getByText('7 of 8 left')).toBeInTheDocument();

    // 6 of 20 are taken, so a limit of 10 leaves 4.
    await router.navigate(`/events/${mine.id}/edit`);
    await waitFor(() => expect(preview().getByText('14 of 20 left')).toBeInTheDocument());
    fill('Capacity', '10');
    expect(preview().getByText('4 of 10 left')).toBeInTheDocument();
  });

  it('says who it’s visible to', async () => {
    signedIn();
    renderApp('/events/new');
    const visible = within(await screen.findByRole('region', { name: 'Visible to' }));
    expect(visible.getByText(/Nobody can see this event yet/)).toBeInTheDocument();

    fireEvent.click(field('All my friends'));

    expect(visible.getByText(/visible to your friends\./)).toBeInTheDocument();
  });
});

describe('your own time', () => {
  it('shows it once the event’s zone reads differently', async () => {
    signedIn();
    renderApp('/events/new');
    await screen.findByLabelText('Title');
    fill('Starts', '2030-07-01T18:00');
    // The new event starts in the viewer's own zone.
    expect(screen.queryByText(/^Your time: .*CDT/, { selector: 'span' })).toBeNull();

    const zone = field('Time zone');
    fireEvent.change(zone, { target: { value: 'new york' } });
    fireEvent.keyDown(zone, { key: 'Enter' });

    expect(
      within(screen.getByRole('region', { name: 'When' })).getByText(/^Your time: 5:00.*CDT/),
    ).toBeInTheDocument();
  });
});

describe('opening the editor at who can see it', () => {
  it('focuses that section', async () => {
    signedIn();
    renderApp(`/events/${mine.id}/edit#audience`);

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Who can see this' })).toHaveFocus(),
    );
  });

  it('leaves the page at the top otherwise', async () => {
    signedIn();
    renderApp(`/events/${mine.id}/edit`);
    await screen.findByLabelText('Title');

    expect(document.body).toHaveFocus();
  });
});
