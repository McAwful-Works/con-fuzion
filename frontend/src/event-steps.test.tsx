import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
import { utcToZonedLocal } from './features/events/time';
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

// jsdom has no `matchMedia`, so every test here sees the phone's five steps.

type Schemas = components['schemas'];

const DAY = 86_400_000;
/** A `datetime-local` value this many days from now, in the profile's zone. */
const inDays = (days: number) =>
  utcToZonedLocal(new Date(Date.now() + days * DAY).toISOString(), 'America/Chicago');

const START = new Date(Date.now() + 30 * DAY);
START.setUTCSeconds(0, 0);

const mine: Schemas['EventResponse'] = {
  id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
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
  capacity: 20,
  spotsLeft: 14,
  visibleBecause: null,
  signupOpensAt: null,
  confirmWindowMinutes: 30,
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
    if (call.path.endsWith('/spots')) return jsonResponse(errorEnvelope, 404);
    if (call.path.startsWith('/api/events/')) return envelope('EVENT', mine);
    if (call.method === 'POST' && call.path === '/api/events') return envelope('EVENT', mine);
    return undefined;
  });
}

const field = (label: string) => screen.getByLabelText(label);
const fill = (label: string, value: string) =>
  fireEvent.change(field(label), { target: { value } });
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const heading = () => screen.getByRole('heading', { level: 1 });
const onStep = (question: string) => expect(heading()).toHaveTextContent(question);
const saved = (calls: RecordedCall[]) => calls.filter((c) => c.method !== 'GET');

async function newMeetup() {
  const router = renderApp('/events/new');
  await screen.findByRole('heading', { level: 1, name: 'What’s the plan?' });
  return router;
}

/** Fills each step well enough to pass it, up to the one asked for. */
async function reach(step: 'When' | 'Spots' | 'Who can see this' | 'Review') {
  fill('Title', 'Panel');
  fill('Location', 'Room 4');
  click('Next: When');
  if (step === 'When') return;
  fill('Starts', inDays(10));
  click('Next: Spots');
  if (step === 'Spots') return;
  click('Next: Who can see this');
  if (step === 'Who can see this') return;
  fireEvent.click(await screen.findByRole('checkbox', { name: 'All my friends' }));
  click('Next: Review');
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('the phone’s steps', () => {
  it('shows one section at a time, with where you are', async () => {
    signedIn();
    await newMeetup();

    expect(screen.getByText('Step 1 of 5 · Details')).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAttribute(
      'aria-valuetext',
      'Step 1 of 5: Details',
    );
    expect(screen.getByRole('region', { name: 'Details' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'When' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Preview in Discover' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Back to/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Create meetup' })).toBeNull();
  });

  it('checks Details before moving on, and only Details', async () => {
    const calls = signedIn();
    await newMeetup();

    click('Next: When');

    onStep('What’s the plan?');
    expect(screen.getByText('Give your event a title.')).toBeInTheDocument();
    expect(screen.getByText('Say where it is.')).toBeInTheDocument();
    expect(screen.getByText('There are 2 things to fix.')).toBeInTheDocument();
    expect(screen.queryByText('Choose when it starts.')).toBeNull();

    fill('Title', 'Panel');
    fill('Location', 'Room 4');
    click('Next: When');

    onStep('When is it?');
    expect(heading()).toHaveFocus();
    expect(saved(calls)).toEqual([]);
  });

  it('checks the times before moving on', async () => {
    signedIn();
    await newMeetup();
    await reach('When');

    click('Next: Spots');
    expect(screen.getByText('Choose when it starts.')).toBeInTheDocument();
    fill('Starts', inDays(-2));
    click('Next: Spots');
    expect(screen.getByText('The start can’t be in the past.')).toBeInTheDocument();
    fill('Starts', inDays(10));
    fill('Ends (optional)', inDays(9));
    click('Next: Spots');
    expect(screen.getByText('The end must be after the start.')).toBeInTheDocument();
    onStep('When is it?');

    fill('Ends (optional)', inDays(11));
    click('Next: Spots');
    onStep('How many spots?');
  });

  it('checks the spots before moving on', async () => {
    signedIn();
    await newMeetup();
    await reach('Spots');

    fireEvent.click(field('Limit the spots'));
    fill('Capacity', '1');
    // The start, from the step before, is what signup is checked against.
    fill('Signup opens (optional)', inDays(12));
    click('Next: Who can see this');

    onStep('How many spots?');
    expect(
      screen.getByText('Capacity must be a whole number from 2 to 10000.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Signup must open before the event starts.')).toBeInTheDocument();

    fill('Capacity', '8');
    fill('Signup opens (optional)', '');
    click('Next: Who can see this');
    onStep('Who can see it?');
  });

  it('needs an audience before Review, which shows the preview and Create', async () => {
    const calls = signedIn(({ method, path }) =>
      method === 'POST' && path === '/api/events' ? envelope('EVENT', mine) : undefined,
    );
    const router = await newMeetup();
    await reach('Who can see this');

    click('Next: Review');
    onStep('Who can see it?');
    expect(screen.getByText('Choose at least one audience.')).toBeInTheDocument();

    fireEvent.click(await screen.findByRole('checkbox', { name: 'All my friends' }));
    click('Next: Review');

    onStep('Look good?');
    const preview = within(screen.getByRole('region', { name: 'Preview in Discover' }));
    expect(preview.getByText('Panel')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Visible to' })).toHaveTextContent('your friends');
    expect(saved(calls)).toEqual([]);

    click('Create meetup');

    await waitFor(() => expect(router.state.location.pathname).toBe(`/events/${mine.id}`));
    expect(saved(calls).map((c) => c.body)).toMatchObject([
      { title: 'Panel', location: 'Room 4', capacity: null, audience: [{ type: 'FRIENDS' }] },
    ]);
  });

  it('moves on with Enter, without creating anything', async () => {
    const calls = signedIn();
    await newMeetup();
    fill('Title', 'Panel');
    fill('Location', 'Room 4');

    // What Enter in a text box does: it submits the form.
    fireEvent.submit(field('Title').closest('form')!);

    onStep('When is it?');
    expect(saved(calls)).toEqual([]);
  });

  it('doesn’t create the meetup on the second tap of a double tap', async () => {
    const calls = signedIn();
    await newMeetup();
    await reach('Review');

    fireEvent.click(screen.getByRole('button', { name: 'Create meetup' }), { detail: 2 });

    expect(saved(calls)).toEqual([]);
  });

  it('keeps what was entered when going back', async () => {
    signedIn();
    await newMeetup();
    click('Other');
    fill('Your tag', 'Karaoke');
    await reach('Spots');
    fireEvent.click(field('Limit the spots'));
    fill('Capacity', '12');
    fireEvent.click(field('Limit the spots'));

    click('Back to When');
    expect(field('Starts')).not.toHaveValue('');
    click('Back to Details');
    expect(field('Your tag')).toHaveValue('Karaoke');
    click('Next: When');
    click('Next: Spots');
    fireEvent.click(field('Limit the spots'));

    expect(field('Capacity')).toHaveValue('12');
  });

  it('goes to the step that stopped being valid by the time of Create', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const calls = signedIn();
    await newMeetup();
    await reach('Review');

    vi.setSystemTime(Date.now() + 11 * DAY);
    click('Create meetup');

    onStep('When is it?');
    expect(screen.getByText('The start can’t be in the past.')).toBeInTheDocument();
    expect(saved(calls)).toEqual([]);
  });

  it('saves an edit from Review, and says there why it was refused', async () => {
    const calls = signedIn(({ method }) =>
      method === 'PATCH'
        ? jsonResponse(
            {
              ...errorEnvelope,
              body: { ...errorEnvelope.body, errorType: 'CAPACITY_BELOW_CONFIRMED' },
            },
            409,
          )
        : undefined,
    );
    renderApp(`/events/${mine.id}/edit`);
    await screen.findByRole('heading', { level: 1, name: 'What’s the plan?' });
    expect(field('Title')).toHaveValue('My panel');
    click('Next: When');
    click('Next: Spots');
    fill('Capacity', '2');
    click('Next: Who can see this');
    click('Next: Review');

    click('Save changes');

    expect(await screen.findByText(/fewer spots than the number/)).toBeInTheDocument();
    expect(saved(calls).map((c) => c.body)).toMatchObject([{ title: 'My panel', capacity: 2 }]);
    click('Back to Who can see this');
    expect(screen.queryByText(/fewer spots than the number/)).toBeNull();
  });
});

describe('a link to who can see it', () => {
  it('opens that step, with its question focused', async () => {
    signedIn();
    renderApp(`/events/${mine.id}/edit#audience`);

    await waitFor(() => expect(heading()).toHaveFocus());

    onStep('Who can see it?');
    expect(screen.getByText('Step 4 of 5 · Who can see this')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Who can see this' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Back to Spots' })).toBeInTheDocument();
  });

  it('is the only way to start anywhere but Details', async () => {
    signedIn();
    renderApp(`/events/${mine.id}/edit`);
    await screen.findByLabelText('Title');

    onStep('What’s the plan?');
    expect(document.body).toHaveFocus();
  });
});
