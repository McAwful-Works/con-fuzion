import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearUnavailable } from './api/maintenance';
import type { components } from './api/schema';
import { describeNotification } from './features/notifications/describe';
import {
  envelope,
  errorEnvelope,
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
  capacity: null,
  signupOpensAt: null,
  startsAt: '2030-07-01T20:00:00Z',
  endsAt: '2030-07-01T22:00:00Z',
  timeZone: 'America/Chicago',
};

const noContent = () => new Response(null, { status: 204 });

function signedIn(
  extra: (call: RecordedCall) => Response | undefined = () => undefined,
  meBody: Schemas['MeResponse'] = me,
) {
  return mockApi((call) => {
    const custom = extra(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', meBody);
    return undefined;
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  clearUnavailable();
});

function pickReason(name: RegExp | string) {
  fireEvent.click(screen.getByRole('radio', { name }));
}

describe('reporting a person', () => {
  const friends = () => envelope('PERSON_LIST', { people: [ada] });

  it('sends the reason and note, then the block, and thanks the reporter', async () => {
    const calls = signedIn(({ method, path }) => {
      if (method === 'GET' && path === '/api/friends') return friends();
      if (path === '/api/reports' || path === `/api/blocks/${ada.id}`) return noContent();
      return undefined;
    });
    renderApp('/friends');
    fireEvent.click(await screen.findByRole('button', { name: 'Report Ada' }));

    const send = screen.getByRole('button', { name: 'Send report' });
    expect(send).toBeDisabled();
    pickReason('Harassment or bullying');
    fireEvent.change(screen.getByLabelText(/Anything else/), {
      target: { value: 'Keeps DMing me' },
    });
    fireEvent.click(screen.getByRole('checkbox', { name: /Also block Ada/ }));
    fireEvent.click(send);

    expect(
      await screen.findByText(/Thanks\. The moderators will look at it\./),
    ).toBeInTheDocument();
    expect(screen.getByText(/You’ve also blocked Ada/)).toBeInTheDocument();
    // Focus follows the form into the thanks, so a keyboard user isn't dropped at the top of the page.
    expect(screen.getByRole('heading', { name: 'Report Ada' })).toHaveFocus();
    const written = calls.filter((c) => c.method !== 'GET');
    expect(written.map((c) => `${c.method} ${c.path}`)).toEqual([
      'POST /api/reports',
      `PUT /api/blocks/${ada.id}`,
    ]);
    expect(written[0]!.body).toEqual({
      targetType: 'USER',
      targetId: ada.id,
      reason: 'HARASSMENT',
      note: 'Keeps DMing me',
    });
  });

  it('sends no block unless asked, and a blank note as null', async () => {
    const calls = signedIn(({ method, path }) => {
      if (method === 'GET' && path === '/api/friends') return friends();
      if (path === '/api/reports') return noContent();
      return undefined;
    });
    renderApp('/friends');
    fireEvent.click(await screen.findByRole('button', { name: 'Report Ada' }));
    pickReason('Something else');
    fireEvent.change(screen.getByLabelText(/Anything else/), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }));

    await screen.findByText(/Thanks\./);
    expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(1);
    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({
      reason: 'OTHER',
      note: null,
    });
  });

  it('still thanks the reporter when the block fails, and says so', async () => {
    signedIn(({ method, path }) => {
      if (method === 'GET' && path === '/api/friends') return friends();
      if (path === '/api/reports') return noContent();
      if (path === `/api/blocks/${ada.id}`) return jsonResponse(errorEnvelope, 500);
      return undefined;
    });
    renderApp('/friends');
    fireEvent.click(await screen.findByRole('button', { name: 'Report Ada' }));
    pickReason('Spam or a scam');
    fireEvent.click(screen.getByRole('checkbox', { name: /Also block Ada/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }));

    await screen.findByText(/Thanks\./);
    expect(screen.getByRole('alert')).toHaveTextContent('couldn’t block Ada');
  });

  it('explains the daily cap in plain words and keeps the form', async () => {
    signedIn(({ method, path }) => {
      if (method === 'GET' && path === '/api/friends') return friends();
      if (path === '/api/reports')
        return jsonResponse(
          { responseType: 'ERROR', body: { ...errorEnvelope.body, errorType: 'REPORT_LIMIT' } },
          429,
        );
      return undefined;
    });
    renderApp('/friends');
    fireEvent.click(await screen.findByRole('button', { name: 'Report Ada' }));
    pickReason('Unsafe or dangerous');
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('10 reports in the last 24 hours');
    expect(screen.getByRole('button', { name: 'Send report' })).toBeEnabled();
  });

  it('closes without sending when cancelled', async () => {
    const calls = signedIn(({ method, path }) =>
      method === 'GET' && path === '/api/friends' ? friends() : undefined,
    );
    renderApp('/friends');
    fireEvent.click(await screen.findByRole('button', { name: 'Report Ada' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('button', { name: 'Send report' })).toBeNull();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});

describe('reporting an event', () => {
  it('offers Report event to a viewer, and reports the event with its host to block', async () => {
    const calls = signedIn(({ method, path }) => {
      if (method === 'GET' && path === `/api/events/${walk.id}`) return envelope('EVENT', walk);
      if (path === '/api/reports') return noContent();
      return undefined;
    });
    renderApp(`/events/${walk.id}`);
    const opener = await screen.findByRole('button', { name: 'Report event' });
    opener.focus(); // a real click focuses the button; fireEvent doesn't
    fireEvent.click(opener);
    expect(screen.getByRole('checkbox', { name: /Also block Ada/ })).toBeInTheDocument();
    pickReason('Inappropriate');
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }));

    await screen.findByText(/Thanks\./);
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    // The opener stays mounted, so focus goes back to it.
    expect(screen.getByRole('button', { name: 'Report event' })).toHaveFocus();
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      targetType: 'EVENT',
      targetId: walk.id,
      reason: 'INAPPROPRIATE',
      note: null,
    });
  });

  it('does not offer a host to report their own event', async () => {
    signedIn(({ path }) =>
      path === `/api/events/${walk.id}`
        ? envelope('EVENT', { ...walk, isHost: true, host: { ...ada, id: me.id } })
        : undefined,
    );
    renderApp(`/events/${walk.id}`);
    await screen.findByRole('heading', { name: 'Cosplay photo walk' });
    expect(screen.queryByRole('button', { name: 'Report event' })).toBeNull();
  });
});

describe('a hidden event', () => {
  const mineHidden = {
    ...walk,
    isHost: true,
    host: { ...ada, id: me.id },
    hiddenReason: 'The location isn’t a public space.',
  };

  it('tells its host it is hidden, with the reason as plain text', async () => {
    signedIn(({ path }) =>
      path === `/api/events/${walk.id}`
        ? envelope('EVENT', { ...mineHidden, hiddenReason: '<b>Not public</b>' })
        : undefined,
    );
    renderApp(`/events/${walk.id}`);
    expect(await screen.findByText('The moderators hid this event.')).toBeInTheDocument();
    expect(screen.getByText(/Reason: <b>Not public<\/b>/)).toBeInTheDocument();
    expect(document.body.querySelector('b')).toBeNull();
  });

  it('shows no banner on a visible event', async () => {
    signedIn(({ path }) =>
      path === `/api/events/${walk.id}`
        ? envelope('EVENT', { ...mineHidden, hiddenReason: null })
        : undefined,
    );
    renderApp(`/events/${walk.id}`);
    await screen.findByRole('heading', { name: 'Cosplay photo walk' });
    expect(screen.queryByText('The moderators hid this event.')).toBeNull();
  });

  it('is the usual not-found page for everyone else', async () => {
    signedIn(({ path }) =>
      path === `/api/events/${walk.id}` ? jsonResponse(errorEnvelope, 404) : undefined,
    );
    renderApp(`/events/${walk.id}`);
    expect(await screen.findByRole('heading', { name: 'Event not found' })).toBeInTheDocument();
    expect(screen.queryByText(/hid|moderator/i)).toBeNull();
  });
});

describe('the 2FA note', () => {
  it('appears when moderator tools are switched off, and can be dismissed', async () => {
    signedIn(() => undefined, {
      ...me,
      mfaRequired: true,
    });
    renderApp('/settings');
    const note = await screen.findByRole('note');
    expect(note).toHaveTextContent('Discord two-factor authentication');
    expect(note).toHaveTextContent('sign in again');
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss this note' }));
    expect(screen.queryByRole('note')).toBeNull();
  });

  it('is absent for everyone else', async () => {
    signedIn();
    renderApp('/settings');
    await screen.findByRole('heading', { name: /Settings/ });
    expect(screen.queryByRole('note')).toBeNull();
  });
});

describe('a refused sign-in', () => {
  it('shows one neutral message that says nothing about why, and drops the parameter', async () => {
    mockApi((call) => (call.path === '/api/me' ? jsonResponse(errorEnvelope, 401) : undefined));
    const router = renderApp('/welcome?login=refused');
    const message = await screen.findByText('This account can’t sign in right now.');
    expect(message.textContent).not.toMatch(/ban|suspend|report|moderat/i);
    await waitFor(() => expect(router.state.location.search).toBe(''));
    // Still on screen after the URL was cleaned.
    expect(screen.getByText('This account can’t sign in right now.')).toBeInTheDocument();
  });

  it('shows nothing of the kind on a plain visit', async () => {
    mockApi((call) => (call.path === '/api/me' ? jsonResponse(errorEnvelope, 401) : undefined));
    renderApp('/welcome');
    await screen.findByRole('link', { name: 'Sign in with Discord' });
    expect(screen.queryByText(/can’t sign in/)).toBeNull();
  });
});

describe('maintenance mode', () => {
  it('replaces the page with "back soon" when any call answers 503', async () => {
    mockApi((call) =>
      call.path === '/api/me'
        ? jsonResponse(
            {
              responseType: 'ERROR',
              body: { ...errorEnvelope.body, errorType: 'SERVICE_UNAVAILABLE' },
            },
            503,
          )
        : undefined,
    );
    renderApp('/');
    expect(await screen.findByRole('heading', { name: 'Back soon' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.queryByText('Something went wrong. Try again.')).toBeNull();
  });
});

describe('notifications for reports and hidden events', () => {
  const now = Date.now();
  const note = (
    over: Partial<Schemas['NotificationResponse']>,
  ): Schemas['NotificationResponse'] => ({
    id: 1,
    type: 'EVENT_HIDDEN',
    eventId: walk.id,
    read: false,
    createdAt: new Date(now).toISOString(),
    payload: {},
    ...over,
  });

  it('describes each from its payload alone', () => {
    const text = (n: Schemas['NotificationResponse']) => describeNotification(n, now);
    expect(text(note({ payload: { eventTitle: 'Walk', reason: 'Not a public space.' } }))).toBe(
      '“Walk” was hidden by the moderators. Only you and the moderators can see it now. Reason: Not a public space.',
    );
    expect(text(note({ type: 'REPORT_REVIEWED', eventId: null }))).toMatch(/reviewed a report/);
    expect(text(note({ type: 'REPORT_RECEIVED', eventId: null, payload: { reportId: 1 } }))).toBe(
      'A new report is waiting for review.',
    );
    expect(
      text(note({ type: 'EVENT_CANCELLED', payload: { eventTitle: 'Walk', reason: 'Unsafe' } })),
    ).toBe('“Walk” was cancelled by the moderators. Reason: Unsafe');
    expect(text(note({ type: 'EVENT_CANCELLED', payload: { eventTitle: 'Walk' } }))).toBe(
      '“Walk” was cancelled.',
    );
  });

  it('links a received report to the admin area and a reviewed one nowhere', async () => {
    signedIn(({ path }) =>
      path.startsWith('/api/notifications')
        ? envelope('NOTIFICATION_PAGE', {
            items: [
              note({ id: 2, type: 'REPORT_RECEIVED', eventId: null, payload: { reportId: 1 } }),
              note({ id: 1, type: 'REPORT_REVIEWED', eventId: null }),
            ],
            nextCursor: null,
            unreadCount: 2,
          })
        : undefined,
    );
    renderApp('/notifications');
    const link = await screen.findByRole('link', { name: 'Open reports' });
    expect(link).toHaveAttribute('href', '/admin');
    expect(screen.getAllByRole('link', { name: /View|Open reports/ })).toHaveLength(1);
  });
});
