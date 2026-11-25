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
  it('shows the sender’s text under the app name and opens the event on click', async () => {
    const worker = loadWorker();
    const [title, options] = await worker.push({
      type: 'SPOT_OFFERED',
      eventId: EVENT_ID,
      text: 'You were offered a spot in Cosplay photo walk.',
    });
    expect(title).toBe('Con-Fuzion');
    expect(options.body).toBe('You were offered a spot in Cosplay photo walk.');
    expect(options.tag).toBe(`SPOT_OFFERED:${EVENT_ID}`);
    expect(options.data.url).toBe(`/events/${EVENT_ID}`);
  });

  it('opens the inbox for a push without an event (a friend invite accepted)', async () => {
    const worker = loadWorker();
    const [, options] = await worker.push({
      type: 'FRIEND_ACCEPTED',
      text: 'A friend invite you sent was accepted.',
    });
    expect(options.data.url).toBe('/notifications');
  });

  it('shows only the text field, so nothing else in a payload reaches the lock screen', async () => {
    const worker = loadWorker();
    const [title, options] = await worker.push({
      type: 'EVENT_CHANGED',
      eventId: EVENT_ID,
      text: '“Walk” changed.',
      location: 'Hall B fountain',
      friendName: 'Ada',
    });
    expect(JSON.stringify([title, options])).not.toMatch(/Hall B|Ada/);
  });

  it('bounds very long text', async () => {
    const worker = loadWorker();
    const [, options] = await worker.push({ type: 'EVENT_CANCELLED', text: 'x'.repeat(5000) });
    expect(options.body).toHaveLength(300);
  });

  it.each([
    ['no data at all', undefined],
    ['data that is not JSON', 'not json {'],
    ['JSON that is not an object', '42'],
    ['text that is not a string', { type: 'EVENT_CANCELLED', text: { nested: true } }],
    ['an empty object', {}],
  ])('still shows a notification for %s (iOS revokes silent pushes)', async (_name, data) => {
    const worker = loadWorker();
    const [title, options] = await worker.push(data);
    expect(worker.showNotification).toHaveBeenCalledTimes(1);
    expect(title).toBe('Con-Fuzion');
    expect(options.body).toBe('You have a new notification.');
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
    await worker.push({ type: 'SPOT_CONFIRMED', eventId: EVENT_ID, text: 'Confirmed.' });
    expect(shown).toBe(true);
  });

  it('opens /notifications rather than trusting an event ID that is not a UUID', async () => {
    const worker = loadWorker();
    const [, options] = await worker.push({
      type: 'EVENT_CANCELLED',
      eventId: '../../admin',
      text: 'Cancelled.',
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
    await worker.click({ url: String.raw`/\evil.example` });
    await worker.click(undefined);
    expect(worker.openWindow.mock.calls).toEqual([['/'], ['/'], ['/'], ['/']]);
  });
});
