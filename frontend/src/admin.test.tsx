import '@testing-library/jest-dom/vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AdminUser } from './api/admin';
import type { components } from './api/schema';
import { localDay } from './features/admin/SuspendAction';
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

// The factory runs when the admin chunk is first imported, so this shows who loads it.
const chunk = vi.hoisted(() => ({ loaded: false }));
vi.mock('./features/admin/AdminArea', async (importOriginal) => {
  chunk.loaded = true;
  return importOriginal();
});

const moderator: Schemas['MeResponse'] = { ...me, mfaEnabled: true, roles: ['USER', 'MODERATOR'] };
const admin: Schemas['MeResponse'] = { ...me, mfaEnabled: true, roles: ['USER', 'ADMIN'] };

const EVENT_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const REPORTER_ID = '11111111-1111-4111-8111-111111111111';
const USER_ID = '22222222-2222-4222-8222-222222222222';

const eventReport: Schemas['ReportResponse'] = {
  id: 12,
  targetType: 'EVENT',
  targetId: EVENT_ID,
  targetLabel: 'Cosplay photo walk',
  reason: 'UNSAFE',
  note: '<img src=x onerror=alert(1)> **Meets in the car park**',
  status: 'OPEN',
  reporterId: REPORTER_ID,
  createdAt: '2026-11-20T10:00:00Z',
  handledAt: null,
  handledBy: null,
  resolutionNote: null,
};
const userReport: Schemas['ReportResponse'] = {
  ...eventReport,
  id: 13,
  targetType: 'USER',
  targetId: USER_ID,
  targetLabel: 'Rowdy',
  reason: 'HARASSMENT',
};
const rowdy: AdminUser = {
  id: USER_ID,
  username: 'rowdy_one',
  globalName: 'Rowdy',
  discordId: '80351110224678912',
  createdAt: '2026-01-05T12:00:00Z',
  lastLoginAt: '2026-11-19T12:00:00Z',
  suspendedUntil: null,
  ban: null,
  reportCount: 3,
};

const OPEN_QUEUE = '/api/admin/reports?status=OPEN&limit=20';
const noContent = () => new Response(null, { status: 204 });
const failure = (status: number, errorType = 'NOT_FOUND') =>
  jsonResponse({ responseType: 'ERROR', body: { ...errorEnvelope.body, errorType } }, status);
const page = (items: Schemas['ReportResponse'][], nextCursor: string | null = null) =>
  envelope('REPORT_PAGE', { items, nextCursor });

function signedIn(
  meBody: Schemas['MeResponse'],
  extra: (call: RecordedCall) => Response | undefined = () => undefined,
) {
  return mockApi((call) => {
    const custom = extra(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', meBody);
    if (call.path.startsWith('/api/notifications')) {
      return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: 0 });
    }
    return undefined;
  });
}

/** Renders an admin address once its chunk is ready, so a slow first load can't time a test out. */
async function openAdmin(path: string) {
  await import('./features/admin/AdminArea');
  return renderApp(path);
}

const notFound = () => screen.findByRole('heading', { name: 'Page not found' });
const writes = (calls: RecordedCall[]) => calls.filter((c) => c.method !== 'GET');
const adminCalls = (calls: RecordedCall[]) => calls.filter((c) => c.path.startsWith('/api/admin'));

/** Opens an action's form, fills in its note, and confirms. */
async function act(open: string, confirm: string, note?: { label: RegExp; text: string }) {
  fireEvent.click(await screen.findByRole('button', { name: open }));
  const form = within(screen.getByRole('form', { name: open }));
  if (note) fireEvent.change(form.getByLabelText(note.label), { target: { value: note.text } });
  fireEvent.click(form.getByRole('button', { name: confirm }));
}

afterEach(() => vi.unstubAllGlobals());

describe('who gets the moderation area', () => {
  // First in the file: nothing has imported the chunk yet.
  it.each([
    ['a plain user', me],
    ['a moderator without 2FA', { ...me, mfaRequired: true }],
  ])('gives %s no link, the not-found page, and never loads the chunk', async (_, meBody) => {
    const calls = signedIn(meBody);
    renderApp('/admin');

    expect(await notFound()).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Moderation' })).toBeNull();
    expect(adminCalls(calls)).toEqual([]);
    expect(chunk.loaded).toBe(false);
  });

  it.each([
    ['a moderator', moderator],
    ['an admin', admin],
  ])('links %s to the queue of open reports', async (_, meBody) => {
    const calls = signedIn(meBody, ({ path }) =>
      path === OPEN_QUEUE ? page([eventReport]) : undefined,
    );
    await openAdmin('/admin');

    expect(await screen.findByRole('link', { name: /Cosplay photo walk/ })).toHaveAttribute(
      'href',
      '/admin/reports/12',
    );
    expect(screen.getByRole('link', { name: 'Moderation' })).toHaveAttribute('href', '/admin');
    expect(adminCalls(calls).map((c) => c.path)).toEqual([OPEN_QUEUE]);
    expect(chunk.loaded).toBe(true);
  });

  it('shows the same not-found page for any unknown address', async () => {
    signedIn(me);
    renderApp('/nowhere');
    expect(await notFound()).toBeInTheDocument();
  });

  it.each([403, 404])('shows only the not-found page when the queue answers %i', async (status) => {
    signedIn(moderator, ({ path }) => (path === OPEN_QUEUE ? failure(status) : undefined));
    await openAdmin('/admin');

    expect(await notFound()).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Moderation' })).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows a report about the viewer (a 404) as not found, and recovers on the way out', async () => {
    signedIn(moderator, ({ path }) => {
      if (path === '/api/admin/reports/12') return failure(404);
      return path === OPEN_QUEUE ? page([]) : undefined;
    });
    const router = await openAdmin('/admin/reports/12');
    expect(await notFound()).toBeInTheDocument();

    await router.navigate('/admin');
    expect(await screen.findByText('No open reports.')).toBeInTheDocument();
  });

  it.each(['/admin/reports/abc', '/admin/reports/0', '/admin/nothing'])(
    'asks the server nothing for %s',
    async (path) => {
      const calls = signedIn(moderator);
      await openAdmin(path);
      expect(await notFound()).toBeInTheDocument();
      expect(adminCalls(calls)).toEqual([]);
    },
  );
});

describe('the report queue', () => {
  it('filters by status', async () => {
    const calls = signedIn(moderator, ({ path }) => {
      if (path === OPEN_QUEUE) return page([eventReport]);
      if (path === '/api/admin/reports?status=DISMISSED&limit=20') return page([]);
      return undefined;
    });
    await openAdmin('/admin');
    fireEvent.click(await screen.findByRole('link', { name: 'Dismissed' }));

    expect(await screen.findByText('No dismissed reports.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Dismissed' })).toHaveAttribute('aria-current', 'page');
    expect(adminCalls(calls)).toHaveLength(2);
  });

  it('loads the next page with the cursor', async () => {
    signedIn(moderator, ({ path }) => {
      if (path === OPEN_QUEUE) return page([eventReport], 'abc');
      if (path === `${OPEN_QUEUE}&cursor=abc`) return page([userReport]);
      return undefined;
    });
    await openAdmin('/admin');
    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));

    expect(await screen.findByRole('link', { name: /Person: Rowdy/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Cosplay photo walk/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('keeps the admin area and offers a retry for a server failure', async () => {
    signedIn(moderator, ({ path }) => (path === OPEN_QUEUE ? failure(500) : undefined));
    await openAdmin('/admin');

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    expect(screen.getByRole('heading', { name: 'Moderation' })).toBeInTheDocument();
  });
});

/** The report page for `report`; `onWrite` answers everything that isn't a read of it. */
async function openReport(
  report: Schemas['ReportResponse'],
  onWrite: (call: RecordedCall) => Response | undefined = noContent,
) {
  const calls = signedIn(moderator, (call) => {
    if (call.method !== 'GET') return onWrite(call);
    return call.path === `/api/admin/reports/${report.id}` ? envelope('REPORT', report) : undefined;
  });
  await openAdmin(`/admin/reports/${report.id}`);
  await screen.findByRole('heading', { name: `Report ${report.id}` });
  return calls;
}

describe('a report', () => {
  it('shows the note as plain text, and links to the event and the reporter', async () => {
    await openReport(eventReport);

    expect(screen.getByText(eventReport.note!)).toBeInTheDocument();
    expect(document.querySelector('img[src="x"]')).toBeNull();
    expect(screen.getByRole('link', { name: 'Open event' })).toHaveAttribute(
      'href',
      `/events/${EVENT_ID}`,
    );
    expect(screen.getByRole('link', { name: 'Look up the reporter' })).toHaveAttribute(
      'href',
      `/admin/users?q=${REPORTER_ID}`,
    );
    expect(screen.queryByRole('button', { name: /Suspend/ })).toBeNull();
  });

  it.each([
    ['Hide event', 'Hide', 'hide', 'Event hidden.'],
    ['Cancel event', 'Cancel it', 'cancel', 'Event cancelled.'],
  ])('%s sends the reason', async (open, confirm, path, success) => {
    const calls = await openReport(eventReport);
    fireEvent.click(screen.getByRole('button', { name: open }));
    const form = within(screen.getByRole('form', { name: open }));
    const submit = form.getByRole('button', { name: confirm });
    const reason = form.getByLabelText(/Reason/);
    expect(reason).toHaveAttribute('maxlength', '500');
    fireEvent.change(reason, { target: { value: '   ' } });
    expect(submit).toBeDisabled();
    fireEvent.change(reason, { target: { value: '  Not a public space  ' } });
    fireEvent.click(submit);

    expect(await screen.findByText(success)).toBeInTheDocument();
    expect(screen.queryByRole('form')).toBeNull();
    expect(writes(calls)).toEqual([
      {
        method: 'POST',
        path: `/api/admin/events/${EVENT_ID}/${path}`,
        body: { reason: 'Not a public space' },
      },
    ]);
  });

  it('unhides with no body', async () => {
    const calls = await openReport(eventReport);
    await act('Unhide event', 'Unhide');

    expect(await screen.findByText('Event unhidden.')).toBeInTheDocument();
    expect(writes(calls)).toEqual([
      { method: 'POST', path: `/api/admin/events/${EVENT_ID}/unhide`, body: undefined },
    ]);
  });

  it('says only how the latest event action ended', async () => {
    await openReport(eventReport);
    await act('Unhide event', 'Unhide');
    expect(await screen.findByText('Event unhidden.')).toBeInTheDocument();
    await act('Hide event', 'Hide', { label: /Reason/, text: 'Not a public space' });

    expect(await screen.findByText('Event hidden.')).toBeInTheDocument();
    expect(screen.queryByText('Event unhidden.')).toBeNull();
  });

  it('sends nothing when a form is dismissed, and returns focus to its button', async () => {
    const calls = await openReport(eventReport);
    // Not focused first: Safari doesn't focus a clicked button, and neither does fireEvent.
    const opener = screen.getByRole('button', { name: 'Cancel event' });
    fireEvent.click(opener);
    fireEvent.click(screen.getByRole('button', { name: 'Never mind' }));

    expect(screen.queryByRole('form')).toBeNull();
    expect(opener).toHaveFocus();
    expect(writes(calls)).toEqual([]);
  });

  it('forgets the choice made in a dismissed form', async () => {
    await openReport(eventReport);
    const opener = screen.getByRole('button', { name: 'Resolve report' });
    fireEvent.click(opener);
    fireEvent.click(screen.getByRole('radio', { name: 'Dismissed' }));
    fireEvent.click(screen.getByRole('button', { name: 'Never mind' }));
    fireEvent.click(opener);
    expect(screen.getByRole('radio', { name: 'Dismissed' })).not.toBeChecked();
  });

  it('forgets the date of a dismissed suspension', async () => {
    await openReport(userReport);
    const opener = screen.getByRole('button', { name: 'Suspend Rowdy' });
    fireEvent.click(opener);
    fireEvent.change(screen.getByLabelText('Suspended until'), { target: { value: localDay(7) } });
    fireEvent.click(screen.getByRole('button', { name: 'Never mind' }));
    fireEvent.click(opener);
    expect(screen.getByLabelText('Suspended until')).toHaveValue('');
  });

  it('answers a 403 from an action with the not-found page', async () => {
    await openReport(eventReport, () => failure(403, 'FORBIDDEN'));
    await act('Unhide event', 'Unhide');
    expect(await notFound()).toBeInTheDocument();
  });

  it('suspends the reported person until the start of a day', async () => {
    const calls = await openReport(userReport);
    fireEvent.click(screen.getByRole('button', { name: 'Suspend Rowdy' }));
    const form = within(screen.getByRole('form', { name: 'Suspend Rowdy' }));
    const submit = form.getByRole('button', { name: 'Suspend' });
    const until = form.getByLabelText('Suspended until');
    expect(until).toHaveAttribute('min', localDay(1));
    expect(until).toHaveAttribute('max', localDay(364));
    fireEvent.change(form.getByLabelText(/Reason/), { target: { value: 'Threats' } });
    expect(submit).toBeDisabled();
    for (const outside of [localDay(0), localDay(366)]) {
      fireEvent.change(until, { target: { value: outside } });
      expect(submit).toBeDisabled();
    }
    fireEvent.change(until, { target: { value: localDay(7) } });
    fireEvent.click(submit);

    expect(await screen.findByText('Rowdy is suspended.')).toBeInTheDocument();
    expect(writes(calls)).toEqual([
      {
        method: 'POST',
        path: `/api/admin/users/${USER_ID}/suspend`,
        body: { until: new Date(`${localDay(7)}T00:00`).toISOString(), reason: 'Threats' },
      },
    ]);
  });

  it.each([
    ['Dismissed', 'DISMISSED', '', null],
    ['Actioned', 'ACTIONED', ' Hid it ', 'Hid it'],
  ])('resolves as %s', async (choice, resolution, typed, sent) => {
    let current = eventReport;
    const calls = signedIn(moderator, ({ method, path }) => {
      if (method === 'GET' && path === '/api/admin/reports/12') return envelope('REPORT', current);
      if (path !== '/api/admin/reports/12/resolve') return undefined;
      current = {
        ...eventReport,
        status: resolution as Schemas['ReportResponse']['status'],
        handledAt: '2026-11-25T10:00:00Z',
        handledBy: me.id,
        resolutionNote: sent,
      };
      return envelope('REPORT', current);
    });
    await openAdmin('/admin/reports/12');
    fireEvent.click(await screen.findByRole('button', { name: 'Resolve report' }));
    const form = within(screen.getByRole('form', { name: 'Resolve report' }));
    expect(form.getByRole('button', { name: 'Resolve' })).toBeDisabled();
    expect(form.getByLabelText(/Note for moderators/)).toHaveAttribute('maxlength', '1000');
    fireEvent.click(form.getByRole('radio', { name: choice }));
    fireEvent.change(form.getByLabelText(/Note for moderators/), { target: { value: typed } });
    fireEvent.click(form.getByRole('button', { name: 'Resolve' }));

    await waitFor(() => expect(screen.getByText('Report resolved.')).toHaveFocus());
    expect(screen.getByText('Resolution note')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Resolve report' })).toBeNull();
    expect(writes(calls)).toEqual([
      {
        method: 'POST',
        path: '/api/admin/reports/12/resolve',
        body: { resolution, resolutionNote: sent },
      },
    ]);
  });

  async function resolveAsDismissed() {
    fireEvent.click(screen.getByRole('button', { name: 'Resolve report' }));
    fireEvent.click(screen.getByRole('radio', { name: 'Dismissed' }));
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }));
  }

  it('says so when someone else resolved it first, and shows how it ended', async () => {
    let current = eventReport;
    signedIn(moderator, ({ method, path }) => {
      if (method === 'GET' && path === '/api/admin/reports/12') return envelope('REPORT', current);
      if (path !== '/api/admin/reports/12/resolve') return undefined;
      current = { ...eventReport, status: 'ACTIONED', handledAt: '2026-11-25T10:00:00Z' };
      return failure(409, 'REPORT_RESOLVED');
    });
    await openAdmin('/admin/reports/12');
    await screen.findByRole('button', { name: 'Resolve report' });
    await resolveAsDismissed();

    expect(await screen.findByRole('alert')).toHaveTextContent('already been resolved');
    expect(screen.getByText('Actioned')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Resolve report' })).toBeNull();
  });

  it('answers a 404 from resolve with the not-found page', async () => {
    await openReport(eventReport, () => failure(404));
    await resolveAsDismissed();
    expect(await notFound()).toBeInTheDocument();
  });

  it('links a report about a person to that person', async () => {
    await openReport(userReport);
    expect(screen.getByRole('link', { name: 'Look up this person' })).toHaveAttribute(
      'href',
      `/admin/users?q=${USER_ID}`,
    );
    expect(screen.queryByRole('link', { name: 'Open event' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Hide event' })).toBeNull();
  });

  it.each([
    ['person', userReport, 'Person: no longer exists'],
    ['event', eventReport, 'Event: no longer exists'],
  ])('offers only resolving for a %s that’s gone', async (_, report, target) => {
    await openReport({ ...report, targetLabel: null });

    expect(screen.getByText(target)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Open event|Look up this person/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Suspend|event/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Resolve report' })).toBeInTheDocument();
  });

  it('says when the reporter’s account is gone', async () => {
    await openReport({ ...eventReport, reporterId: null });
    expect(screen.getByText('Account deleted')).toBeInTheDocument();
  });
});

describe('user search', () => {
  const found = (users: AdminUser[]) => envelope('ADMIN_USER_LIST', { items: users });

  it('searches on submit and shows what a moderator may see', async () => {
    const calls = signedIn(moderator, ({ path }) =>
      path === '/api/admin/users?q=row+dy'
        ? found([
            {
              ...rowdy,
              suspendedUntil: '2099-01-01T00:00:00Z',
              ban: { createdAt: '2026-11-01T00:00:00Z', reason: '<b>Spam</b>' },
            },
          ])
        : undefined,
    );
    await openAdmin('/admin/users');
    const box = await screen.findByRole('searchbox');
    fireEvent.change(box, { target: { value: 'r' } });
    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled();
    expect(box).toHaveAttribute('maxlength', '100');
    fireEvent.change(box, { target: { value: ' row dy ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const card = within(await screen.findByRole('list', { name: 'Search results' }));
    expect(card.getAllByRole('term').map((term) => term.textContent)).toEqual([
      'Username',
      'Discord ID',
      'Joined',
      'Last signed in',
      'Suspension',
      'Ban',
      'Reports against them',
    ]);
    expect(card.getByText('80351110224678912')).toBeInTheDocument();
    expect(card.getByText(/Suspended until/)).toBeInTheDocument();
    expect(card.getByText(/<b>Spam<\/b>/)).toBeInTheDocument();
    expect(screen.queryByText(USER_ID)).toBeNull();
    expect(adminCalls(calls)).toHaveLength(1);
  });

  it('suspends from a result, then shows the new end', async () => {
    let current = rowdy;
    const calls = signedIn(moderator, ({ method, path }) => {
      if (method === 'GET' && path === `/api/admin/users?q=${USER_ID}`) return found([current]);
      if (path !== `/api/admin/users/${USER_ID}/suspend`) return undefined;
      current = { ...rowdy, suspendedUntil: '2099-01-01T00:00:00Z' };
      return noContent();
    });
    await openAdmin(`/admin/users?q=${USER_ID}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Suspend Rowdy' }));
    fireEvent.change(screen.getByLabelText('Suspended until'), { target: { value: localDay(1) } });
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: 'Threats' } });
    fireEvent.click(screen.getByRole('button', { name: 'Suspend' }));

    expect(await screen.findByText(/Suspended until/, { selector: 'dd' })).toBeInTheDocument();
    expect(screen.getByText('Rowdy is suspended.')).toBeInTheDocument();
    expect(writes(calls)).toHaveLength(1);
  });

  it('explains why a protected account can’t be suspended', async () => {
    signedIn(moderator, ({ method, path }) => {
      if (method === 'GET' && path === '/api/admin/users?q=rowdy') return found([rowdy]);
      return method === 'POST' ? failure(409, 'TARGET_HAS_ROLE') : undefined;
    });
    await openAdmin('/admin/users?q=rowdy');
    fireEvent.click(await screen.findByRole('button', { name: 'Suspend Rowdy' }));
    fireEvent.change(screen.getByLabelText('Suspended until'), { target: { value: localDay(1) } });
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: 'Threats' } });
    fireEvent.click(screen.getByRole('button', { name: 'Suspend' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('a role protects it');
    expect(screen.getByRole('form', { name: 'Suspend Rowdy' })).toBeInTheDocument();
  });

  it.each([403, 404])('shows the not-found page when search answers %i', async (status) => {
    signedIn(moderator, ({ path }) =>
      path === '/api/admin/users?q=rowdy' ? failure(status) : undefined,
    );
    await openAdmin('/admin/users?q=rowdy');
    expect(await notFound()).toBeInTheDocument();
  });

  it('shows the not-found page when the person to suspend is gone', async () => {
    signedIn(moderator, ({ method, path }) => {
      if (method === 'GET' && path === '/api/admin/users?q=rowdy') return found([rowdy]);
      return method === 'POST' ? failure(404) : undefined;
    });
    await openAdmin('/admin/users?q=rowdy');
    fireEvent.click(await screen.findByRole('button', { name: 'Suspend Rowdy' }));
    fireEvent.change(screen.getByLabelText('Suspended until'), { target: { value: localDay(1) } });
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: 'Threats' } });
    fireEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    expect(await notFound()).toBeInTheDocument();
  });

  it.each(['r', 'r'.repeat(101)])('sends nothing for the address query %s', async (q) => {
    const calls = signedIn(moderator);
    await openAdmin(`/admin/users?q=${q}`);

    expect(await screen.findByText('Search for 2 to 100 characters.')).toBeInTheDocument();
    expect(adminCalls(calls)).toEqual([]);
  });

  it('says when no one matches', async () => {
    signedIn(moderator, ({ path }) =>
      path === '/api/admin/users?q=nobody' ? found([]) : undefined,
    );
    await openAdmin('/admin/users?q=nobody');
    expect(await screen.findByText('No one found.')).toBeInTheDocument();
  });
});

const SEARCH = '/api/admin/users?q=rowdy';
const ROLES = `/api/admin/users/${USER_ID}/roles`;
const BAN_ID = rowdy.discordId;
const found = (users: AdminUser[]) => envelope('ADMIN_USER_LIST', { items: users });

/** An admin looking at one search result; `extra` answers everything else. */
async function openRowdy(
  extra: (call: RecordedCall) => Response | undefined,
  user: () => AdminUser = () => rowdy,
) {
  const calls = signedIn(admin, (call) => {
    if (call.method === 'GET' && call.path === SEARCH) return found([user()]);
    return extra(call);
  });
  await openAdmin('/admin/users?q=rowdy');
  await screen.findByRole('heading', { name: 'Rowdy' });
  return calls;
}

/** Opens a ban or unban form and returns it with its confirm button and ID box. */
function openBanForm(verb: 'Ban' | 'Unban', name = 'Rowdy') {
  fireEvent.click(screen.getByRole('button', { name: `${verb} ${name}` }));
  const form = within(screen.getByRole('form', { name: `${verb} ${name}` }));
  return {
    form,
    confirm: form.getByRole('button', { name: verb }),
    idBox: form.getByLabelText(/Type the Discord ID/),
  };
}

describe('what only an admin gets', () => {
  it('shows a moderator no bans, roles or audit log', async () => {
    signedIn(moderator, ({ path }) => (path === SEARCH ? found([rowdy]) : undefined));
    await openAdmin('/admin/users?q=rowdy');

    expect(await screen.findByRole('button', { name: 'Suspend Rowdy' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Ban|Unban|Roles/ })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Audit log' })).toBeNull();
  });

  it('shows an admin all three', async () => {
    const calls = await openRowdy(() => undefined);

    expect(screen.getByRole('button', { name: 'Ban Rowdy' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Roles for Rowdy' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Audit log' })).toHaveAttribute('href', '/admin/audit');
    // Roles are only asked for once someone opens them.
    expect(adminCalls(calls).map((c) => c.path)).toEqual([SEARCH]);
  });

  it('answers a moderator at the audit log address with not found, asking nothing', async () => {
    const calls = signedIn(moderator);
    await openAdmin('/admin/audit');

    expect(await notFound()).toBeInTheDocument();
    expect(adminCalls(calls)).toEqual([]);
  });

  it.each([
    ['a moderator', moderator, 0],
    ['an admin', admin, 2],
  ])('offers %s ban buttons for a Discord ID with no account', async (_, meBody, buttons) => {
    const path = `/api/admin/users?q=${BAN_ID}`;
    signedIn(meBody, (call) => (call.path === path ? found([]) : undefined));
    await openAdmin(`/admin/users?q=${BAN_ID}`);

    expect(await screen.findByText('No one found.')).toBeInTheDocument();
    expect(screen.queryAllByRole('button', { name: /(Ban|Unban) Discord ID/ })).toHaveLength(
      buttons,
    );
  });
});

describe('banning', () => {
  async function sendBan() {
    const { form, confirm, idBox } = openBanForm('Ban');
    fireEvent.change(idBox, { target: { value: BAN_ID } });
    fireEvent.change(form.getByLabelText(/Reason/), { target: { value: 'Threats' } });
    fireEvent.click(confirm);
  }

  it('needs the exact Discord ID and a reason, then offers the unban', async () => {
    let current = rowdy;
    const calls = await openRowdy(
      ({ path }) => {
        if (path !== '/api/admin/bans') return undefined;
        current = { ...rowdy, ban: { reason: 'Threats', createdAt: '2026-11-25T10:00:00Z' } };
        return noContent();
      },
      () => current,
    );
    const { form, confirm, idBox } = openBanForm('Ban');
    const reason = form.getByLabelText(/Reason/);
    expect(reason).toHaveAttribute('maxlength', '500');
    fireEvent.change(reason, { target: { value: ' Threats ' } });
    expect(confirm).toBeDisabled();
    for (const wrong of [BAN_ID.slice(0, -1), `${BAN_ID}0`, 'Rowdy']) {
      fireEvent.change(idBox, { target: { value: wrong } });
      expect(confirm).toBeDisabled();
    }
    fireEvent.change(idBox, { target: { value: BAN_ID } });
    fireEvent.change(reason, { target: { value: '  ' } });
    expect(confirm).toBeDisabled();
    fireEvent.change(reason, { target: { value: ' Threats ' } });
    fireEvent.click(confirm);

    expect(await screen.findByRole('button', { name: 'Unban Rowdy' })).toHaveFocus();
    expect(screen.getByText('Rowdy is banned.')).toBeInTheDocument();
    expect(screen.queryByRole('form')).toBeNull();
    expect(writes(calls)).toEqual([
      { method: 'POST', path: '/api/admin/bans', body: { discordId: BAN_ID, reason: 'Threats' } },
    ]);
  });

  it('unbans behind the same typed ID, with no reason', async () => {
    const banned = { ...rowdy, ban: { reason: 'Spam', createdAt: '2026-11-01T00:00:00Z' } };
    const calls = await openRowdy(
      ({ method }) => (method === 'DELETE' ? noContent() : undefined),
      () => banned,
    );
    const { form, confirm, idBox } = openBanForm('Unban');
    expect(form.queryByLabelText(/Reason/)).toBeNull();
    expect(confirm).toBeDisabled();
    fireEvent.change(idBox, { target: { value: BAN_ID } });
    fireEvent.click(confirm);

    expect(await screen.findByText('Rowdy is unbanned.')).toBeInTheDocument();
    expect(writes(calls)).toEqual([
      { method: 'DELETE', path: `/api/admin/bans/${BAN_ID}`, body: undefined },
    ]);
  });

  it('wants the ID again if the person was banned while the form was open', async () => {
    let current = rowdy;
    await openRowdy(
      () => undefined,
      () => current,
    );
    const { form, idBox } = openBanForm('Ban');
    fireEvent.change(idBox, { target: { value: BAN_ID } });
    current = { ...rowdy, ban: { reason: 'Spam', createdAt: '2026-11-25T10:00:00Z' } };
    // Coming back to the tab reads the search again.
    fireEvent(window, new Event('visibilitychange'));

    expect(await form.findByRole('button', { name: 'Unban' })).toBeDisabled();
    expect(idBox).toHaveValue('');
  });

  it('forgets the typed ID when the form is dismissed', async () => {
    await openRowdy(() => undefined);
    fireEvent.change(openBanForm('Ban').idBox, { target: { value: BAN_ID } });
    fireEvent.click(screen.getByRole('button', { name: 'Never mind' }));
    expect(openBanForm('Ban').idBox).toHaveValue('');
  });

  it('says only how the latest change to an unlisted ID ended', async () => {
    const calls = signedIn(admin, ({ method, path }) => {
      if (method !== 'GET') return noContent();
      return path === `/api/admin/users?q=${BAN_ID}` ? found([]) : undefined;
    });
    await openAdmin(`/admin/users?q=${BAN_ID}`);
    await screen.findByText('No one found.');
    const name = `Discord ID ${BAN_ID}`;
    const ban = openBanForm('Ban', name);
    fireEvent.change(ban.idBox, { target: { value: BAN_ID } });
    fireEvent.change(ban.form.getByLabelText(/Reason/), { target: { value: 'Known spammer' } });
    fireEvent.click(ban.confirm);
    expect(await screen.findByText(`${name} is banned.`)).toBeInTheDocument();
    const unban = openBanForm('Unban', name);
    fireEvent.change(unban.idBox, { target: { value: BAN_ID } });
    fireEvent.click(unban.confirm);

    expect(await screen.findByText(`${name} is unbanned.`)).toBeInTheDocument();
    expect(screen.queryByText(`${name} is banned.`)).toBeNull();
    expect(writes(calls).map((c) => c.method)).toEqual(['POST', 'DELETE']);
  });

  it('explains why an account with a role can’t be banned', async () => {
    await openRowdy(({ method }) =>
      method === 'POST' ? failure(409, 'TARGET_HAS_ROLE') : undefined,
    );
    await sendBan();
    expect(await screen.findByRole('alert')).toHaveTextContent('can’t be suspended or banned');
  });

  it('answers a 403 with the not-found page', async () => {
    await openRowdy(({ method }) => (method === 'POST' ? failure(403, 'FORBIDDEN') : undefined));
    await sendBan();
    expect(await notFound()).toBeInTheDocument();
  });
});

describe('roles', () => {
  const roles = (held: Schemas['RolesResponse']['roles'], mfaEnabled = true) =>
    envelope('ROLES', { roles: held, mfaEnabled });

  async function openRoles(extra: (call: RecordedCall) => Response | undefined) {
    const calls = await openRowdy(extra);
    fireEvent.click(screen.getByRole('button', { name: 'Roles for Rowdy' }));
    return calls;
  }

  const save = () => screen.getByRole('button', { name: 'Save roles' });

  it.each([
    ['grants a second role', 'Admin', ['MODERATOR', 'ADMIN']],
    ['removes the last role', 'Moderator', []],
  ])('%s', async (_, box, sent) => {
    const calls = await openRoles(({ path }) =>
      path === ROLES ? roles(['MODERATOR']) : undefined,
    );
    expect(await screen.findByRole('checkbox', { name: 'Moderator' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Admin' })).not.toBeChecked();
    expect(screen.queryByText(/2FA\) was off/)).toBeNull();
    expect(save()).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox', { name: box }));
    fireEvent.click(save());

    expect(await screen.findByText('Roles for Rowdy saved.')).toBeInTheDocument();
    expect(writes(calls)).toEqual([{ method: 'PUT', path: ROLES, body: { roles: sent } }]);
  });

  it('has nothing to save once the boxes are back as they were', async () => {
    await openRoles(({ path }) => (path === ROLES ? roles(['MODERATOR']) : undefined));
    const admin = await screen.findByRole('checkbox', { name: 'Admin' });
    fireEvent.click(admin);
    expect(save()).toBeEnabled();
    fireEvent.click(admin);
    expect(save()).toBeDisabled();
  });

  it('reads them afresh each time, and warns when 2FA is off', async () => {
    const calls = await openRoles(({ path }) => (path === ROLES ? roles([], false) : undefined));
    expect(await screen.findByText(/2FA\) was off/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Admin' }));
    fireEvent.click(screen.getByRole('button', { name: 'Never mind' }));
    fireEvent.click(screen.getByRole('button', { name: 'Roles for Rowdy' }));

    expect(await screen.findByRole('checkbox', { name: 'Admin' })).not.toBeChecked();
    expect(save()).toBeDisabled();
    await waitFor(() => expect(calls.filter((c) => c.path === ROLES)).toHaveLength(2));
  });

  it('explains why the last admin can’t be removed', async () => {
    await openRoles(({ method, path }) => {
      if (path !== ROLES) return undefined;
      return method === 'GET' ? roles(['ADMIN']) : failure(409, 'LAST_ADMIN');
    });
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Admin' }));
    fireEvent.click(save());

    expect(await screen.findByRole('alert')).toHaveTextContent('would leave no admin');
  });

  it('answers a 404 to reading them with the not-found page', async () => {
    await openRoles(({ path }) => (path === ROLES ? failure(404) : undefined));
    expect(await notFound()).toBeInTheDocument();
  });

  it('answers a 403 to saving them with the not-found page', async () => {
    await openRoles(({ method, path }) => {
      if (path !== ROLES) return undefined;
      return method === 'GET' ? roles([]) : failure(403, 'FORBIDDEN');
    });
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Admin' }));
    fireEvent.click(save());
    expect(await notFound()).toBeInTheDocument();
  });
});

describe('the audit log', () => {
  const AUDIT = '/api/admin/audit?limit=20';
  const entry: Schemas['AuditEntryResponse'] = {
    id: 7,
    actorId: REPORTER_ID,
    action: 'USER_SUSPENDED',
    targetType: 'USER',
    targetId: USER_ID,
    details: { until: '2027-01-01T00:00:00Z', reason: '<img src=x> ACCOUNT_BANNED' },
    createdAt: '2026-11-25T10:00:00Z',
  };
  const system: Schemas['AuditEntryResponse'] = {
    ...entry,
    id: 6,
    actorId: null,
    action: 'ROLES_CHANGED',
    targetType: 'DISCORD_ACCOUNT',
    targetId: BAN_ID,
    details: { roles: [], cause: 'SIGN_IN_REFUSED', resolution: 'DISMISSED', sessions: 0 },
  };
  const log = (items: Schemas['AuditEntryResponse'][], nextCursor: string | null = null) =>
    envelope('AUDIT_PAGE', { items, nextCursor });

  it('shows a page at a time, with details as plain text', async () => {
    const calls = signedIn(admin, ({ path }) => {
      if (path === AUDIT) return log([entry], 'abc');
      return path === `${AUDIT}&cursor=abc` ? log([system]) : undefined;
    });
    await openAdmin('/admin/audit');

    expect(await screen.findByRole('heading', { name: 'User suspended' })).toBeInTheDocument();
    // Free text is shown as written: no markup, and a code inside it isn't reworded.
    expect(screen.getByText('<img src=x> ACCOUNT_BANNED')).toBeInTheDocument();
    expect(document.querySelector('img[src="x"]')).toBeNull();
    expect(screen.getByText(REPORTER_ID)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByRole('heading', { name: 'Roles changed' })).toBeInTheDocument();
    const second = within(screen.getAllByRole('listitem').at(-1)!);
    expect(second.getAllByRole('definition').map((d) => d.textContent)).toEqual([
      expect.any(String),
      'The system',
      BAN_ID,
      'None',
      'Sign in refused',
      'Dismissed',
      '0',
    ]);
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
    expect(adminCalls(calls).map((c) => c.path)).toEqual([AUDIT, `${AUDIT}&cursor=abc`]);
  });

  it('says when nothing is recorded', async () => {
    signedIn(admin, ({ path }) => (path === AUDIT ? log([]) : undefined));
    await openAdmin('/admin/audit');
    expect(await screen.findByText('Nothing recorded yet.')).toBeInTheDocument();
  });

  it('answers a 403 with the not-found page', async () => {
    signedIn(admin, ({ path }) => (path === AUDIT ? failure(403, 'FORBIDDEN') : undefined));
    await openAdmin('/admin/audit');
    expect(await notFound()).toBeInTheDocument();
  });
});
