import { describe, expect, it, vi } from 'vitest';
// The worker script is plain JavaScript that the browser runs as it is; this runs the same text.
import script from '../../../public/push-sw.js?raw';

const EVENT_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

type Listener = (event: unknown) => void;

/** Runs the script against a fake worker scope and returns what it registered and what it called. */
function loadWorker(windows: Array<Record<string, unknown>> = []) {
  const listeners = new Map<string, Listener>();
  const showNotification = vi.fn(async () => undefined);
  const openWindow = vi.fn(async () => null);
  const scope = {
    addEventListener: (type: string, listener: Listener) => listeners.set(type, listener),
    registration: { showNotification },
  };
  const clients = { matchAll: vi.fn(async () => windows), openWindow };
  new Function('self', 'clients', script)(scope, clients);

  /** Dispatches a push whose data is `data` (a string is sent as is, to test bad JSON). */
  async function push(data: unknown) {
    const waits: Promise<unknown>[] = [];
    listeners.get('push')!({
      data:
        data === undefined
          ? null
          : { json: () => (typeof data === 'string' ? JSON.parse(data) : data) },
      waitUntil: (p: Promise<unknown>) => waits.push(p),
    });
    await Promise.all(waits);
    return showNotification.mock.lastCall as unknown as [
      string,
      { body: string; tag: string; data: { url: string } },
    ];
  }

  async function click(data: unknown) {
    const waits: Promise<unknown>[] = [];
    const close = vi.fn();
    listeners.get('notificationclick')!({
      notification: { close, data },
      waitUntil: (p: Promise<unknown>) => waits.push(p),
    });
    await Promise.all(waits);
    return close;
  }

  return { push, click, showNotification, openWindow, listeners };
}

describe('the service worker push handler', () => {
  it('shows what happened and the event title from the payload alone', async () => {
    const worker = loadWorker();
    const [title, options] = await worker.push({
      type: 'SPOT_OFFERED',
      eventId: EVENT_ID,
      eventTitle: 'Cosplay photo walk',
    });
    expect(title).toBe('Cosplay photo walk');
    expect(options.body).toBe(
      'You were offered a spot in “Cosplay photo walk”. Open the app to accept.',
    );
    expect(options.data.url).toBe(`/events/${EVENT_ID}`);
  });

  it('reads the same fields from a nested payload, like an inbox entry', async () => {
    const worker = loadWorker();
    const [title, options] = await worker.push({
      type: 'EVENT_CANCELLED',
      eventId: EVENT_ID,
      payload: { eventTitle: 'Walk' },
    });
    expect(title).toBe('Walk');
    expect(options.body).toBe('“Walk” was cancelled.');
  });

  it('writes every notification type', async () => {
    const worker = loadWorker();
    for (const type of [
      'SPOT_OFFERED',
      'SPOT_CONFIRMED',
      'EVENT_CHANGED',
      'EVENT_CANCELLED',
      'EVENT_REMINDER',
      'FRIEND_ACCEPTED',
    ]) {
      const [, options] = await worker.push({ type, eventId: EVENT_ID, eventTitle: 'Walk' });
      expect(options.body.length).toBeGreaterThan(0);
    }
    expect(worker.showNotification).toHaveBeenCalledTimes(6);
  });

  it('never shows a location or a name, even if the payload carries one', async () => {
    const worker = loadWorker();
    const [title, options] = await worker.push({
      type: 'EVENT_CHANGED',
      eventId: EVENT_ID,
      eventTitle: 'Walk',
      location: 'Hall B fountain',
      friendName: 'Ada',
    });
    expect(JSON.stringify([title, options])).not.toMatch(/Hall B|Ada/);
  });

  it.each([
    ['no data at all', undefined],
    ['data that is not JSON', 'not json {'],
    ['JSON that is not an object', '42'],
    ['an unknown type', { type: 'SOMETHING_NEW', eventTitle: 'Walk' }],
    ['an empty object', {}],
  ])('still shows a notification for %s (iOS revokes silent pushes)', async (_name, data) => {
    const worker = loadWorker();
    const [title, options] = await worker.push(data);
    expect(worker.showNotification).toHaveBeenCalledTimes(1);
    expect(title.length).toBeGreaterThan(0);
    expect(options.body.length).toBeGreaterThan(0);
    expect(options.data.url).toBe('/notifications');
  });

  it('keeps the push alive until the notification is shown', async () => {
    const worker = loadWorker();
    let shown = false;
    worker.showNotification.mockImplementation(async () => {
      await Promise.resolve();
      shown = true;
      return undefined;
    });
    await worker.push({ type: 'SPOT_CONFIRMED', eventId: EVENT_ID, eventTitle: 'Walk' });
    expect(shown).toBe(true);
  });

  it('opens /notifications rather than trusting an event ID that is not a UUID', async () => {
    const worker = loadWorker();
    const [, options] = await worker.push({
      type: 'EVENT_CANCELLED',
      eventId: '../../admin',
      eventTitle: 'Walk',
    });
    expect(options.data.url).toBe('/notifications');
  });

  it('opens a window on click when none is open', async () => {
    const worker = loadWorker([]);
    const close = await worker.click({ url: `/events/${EVENT_ID}` });
    expect(close).toHaveBeenCalled();
    expect(worker.openWindow).toHaveBeenCalledWith(`/events/${EVENT_ID}`);
  });

  it('focuses and points an open tab at the event on click', async () => {
    const tab = { focus: vi.fn(async () => tab), navigate: vi.fn(async () => tab) };
    const worker = loadWorker([tab]);
    await worker.click({ url: `/events/${EVENT_ID}` });
    expect(tab.focus).toHaveBeenCalled();
    expect(tab.navigate).toHaveBeenCalledWith(`/events/${EVENT_ID}`);
    expect(worker.openWindow).not.toHaveBeenCalled();
  });

  it('opens a window when focusing a tab fails', async () => {
    const tab = {
      focus: vi.fn(async () => {
        throw new Error('no');
      }),
    };
    const worker = loadWorker([tab]);
    await worker.click({ url: '/notifications' });
    expect(worker.openWindow).toHaveBeenCalledWith('/notifications');
  });

  it('only ever opens a path on its own origin', async () => {
    const worker = loadWorker([]);
    await worker.click({ url: 'https://evil.example/phish' });
    await worker.click({ url: '//evil.example' });
    await worker.click(undefined);
    expect(worker.openWindow.mock.calls).toEqual([['/'], ['/'], ['/']]);
  });
});
