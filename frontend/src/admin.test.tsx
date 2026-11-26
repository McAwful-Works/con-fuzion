import '@testing-library/jest-dom/vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
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
  it('shows the note as plain text, links to the event and names the reporter', async () => {
    await openReport(eventReport);

    expect(screen.getByText(eventReport.note!)).toBeInTheDocument();
    expect(document.querySelector('img[src="x"]')).toBeNull();
    expect(screen.getByRole('link', { name: 'Open event' })).toHaveAttribute(
      'href',
      `/events/${EVENT_ID}`,
    );
    expect(screen.getByText(REPORTER_ID)).toBeInTheDocument();
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

  it('answers a 403 from an action with the not-found page', async () => {
    await openReport(eventReport, () => failure(403, 'FORBIDDEN'));
    await act('Unhide event', 'Unhide');
    expect(await notFound()).toBeInTheDocument();
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

  it.each([
    ['a report about a person', userReport, 'Person: Rowdy'],
    ['an event that’s gone', { ...eventReport, targetLabel: null }, 'Event: no longer exists'],
  ])('offers only resolving for %s', async (_, report, target) => {
    await openReport(report);

    expect(screen.getByText(target)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Open event' })).toBeNull();
    expect(screen.getAllByRole('button', { name: / (event|report)$/ })).toEqual([
      screen.getByRole('button', { name: 'Resolve report' }),
    ]);
  });

  it('says when the reporter’s account is gone', async () => {
    await openReport({ ...eventReport, reporterId: null });
    expect(screen.getByText('Account deleted')).toBeInTheDocument();
  });
});
