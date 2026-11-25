import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { POLL_FAST_MS, POLL_SLOW_MS, pollInterval, useSpots, type Spots } from '../../api/rsvp';
import type { Event } from '../../api/events';
import { findOverlaps } from '../events/schedule';
import { formatCountdown, JITTER_MAX_MS, useSignupUnlocked } from './signup';

const NOW = new Date('2030-01-01T12:00:00Z');
const at = (ms: number) => new Date(NOW.getTime() + ms).toISOString();

describe('pollInterval', () => {
  it('is slow without a signup time, and fast in the minute either side of it', () => {
    const now = NOW.getTime();
    expect(pollInterval(null, now)).toBe(POLL_SLOW_MS);
    expect(pollInterval(at(60_000), now)).toBe(POLL_FAST_MS);
    expect(pollInterval(at(0), now)).toBe(POLL_FAST_MS);
    expect(pollInterval(at(-60_000), now)).toBe(POLL_FAST_MS);
  });

  it('is slow well before and well after, but never waits past the start of the fast window', () => {
    const now = NOW.getTime();
    expect(pollInterval(at(60 * 60_000), now)).toBe(POLL_SLOW_MS);
    expect(pollInterval(at(-60 * 60_000), now)).toBe(POLL_SLOW_MS);
    // 75 s to go: the window opens in 15 s, so check back then rather than in 30 s.
    expect(pollInterval(at(75_000), now)).toBe(15_000);
    // Very near the window's edge, the floor keeps it from a burst of tiny waits.
    expect(pollInterval(at(61_000), now)).toBe(POLL_FAST_MS);
  });
});

describe('useSignupUnlocked', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('is unlocked at once with no signup time, or one that has passed', () => {
    expect(renderHook(() => useSignupUnlocked(null)).result.current).toBe(true);
    expect(renderHook(() => useSignupUnlocked(at(-1000))).result.current).toBe(true);
  });

  it('unlocks at the opening time plus a random 0 to 1.5 seconds', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    const { result } = renderHook(() => useSignupUnlocked(at(5000)));
    expect(result.current).toBe(false);

    act(() => void vi.advanceTimersByTime(5000));
    // The countdown has hit zero, but this person's jitter is 750 ms.
    expect(result.current).toBe(false);
    act(() => void vi.advanceTimersByTime(749));
    expect(result.current).toBe(false);
    act(() => void vi.advanceTimersByTime(1));
    expect(result.current).toBe(true);
  });

  it('still waits when signup is more than 24.8 days away, where one setTimeout would fire at once', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    const day = 86_400_000;
    const { result } = renderHook(() => useSignupUnlocked(at(30 * day)));

    act(() => void vi.advanceTimersByTime(29 * day));
    expect(result.current).toBe(false);
    act(() => void vi.advanceTimersByTime(day - 1));
    expect(result.current).toBe(false);
    act(() => void vi.advanceTimersByTime(1));
    expect(result.current).toBe(true);
  });

  it('spreads people across the whole 1.5 seconds', () => {
    const random = vi.spyOn(Math, 'random');

    random.mockReturnValue(0);
    const early = renderHook(() => useSignupUnlocked(at(1000)));
    random.mockReturnValue(0.999);
    const late = renderHook(() => useSignupUnlocked(at(1000)));

    act(() => void vi.advanceTimersByTime(1000));
    expect(early.result.current).toBe(true);
    expect(late.result.current).toBe(false);
    act(() => void vi.advanceTimersByTime(JITTER_MAX_MS));
    expect(late.result.current).toBe(true);
  });
});

describe('formatCountdown', () => {
  it('reads as days, hours, minutes and seconds', () => {
    expect(formatCountdown(9_000)).toBe('9s');
    expect(formatCountdown(123_000)).toBe('2m 03s');
    expect(formatCountdown(3_723_000)).toBe('1h 02m 03s');
    expect(formatCountdown(90_061_000)).toBe('1d 1h 01m 01s');
    expect(formatCountdown(-5)).toBe('0s');
  });
});

const spotsA: Spots = {
  capacity: 10,
  confirmed: 4,
  waitlisted: 0,
  spotsLeft: 6,
  myStatus: 'NONE',
  myPosition: null,
  offerExpiresAt: null,
};
const spotsB: Spots = { ...spotsA, confirmed: 5, spotsLeft: 5 };

function spotsResponse(spots: Spots, etag: string) {
  return new Response(JSON.stringify({ responseType: 'SPOTS', body: spots }), {
    status: 200,
    headers: { 'Content-Type': 'application/json', ETag: etag },
  });
}

describe('useSpots', () => {
  const sent: (string | null)[] = [];

  function stubFetch(answer: (n: number, ifNoneMatch: string | null) => Response) {
    sent.length = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
        const header = new Headers(init?.headers).get('If-None-Match');
        sent.push(header);
        return answer(sent.length, header);
      }),
    );
  }

  function render(signupOpensAt: string | null) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const event = { id: 'e1', signupOpensAt, status: 'PUBLISHED' as const };
    return renderHook(() => useSpots(event), { wrapper });
  }

  async function advance(ms: number) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ms);
    });
  }

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('polls every 30 seconds when no signup is coming', async () => {
    stubFetch(() => spotsResponse(spotsA, '"v1"'));
    render(null);
    await advance(0);
    expect(sent).toHaveLength(1);

    await advance(29_999);
    expect(sent).toHaveLength(1);
    await advance(1);
    expect(sent).toHaveLength(2);
  });

  it('switches from every 30 seconds to every 3 as the signup window arrives', async () => {
    stubFetch(() => spotsResponse(spotsA, '"v1"'));
    // Signup opens in 90 s: the fast window starts in 30 s.
    render(at(90_000));
    await advance(0);
    expect(sent).toHaveLength(1);

    await advance(29_999);
    expect(sent).toHaveLength(1);
    await advance(1);
    expect(sent).toHaveLength(2);
    // Now inside the window: three seconds apart.
    await advance(3000);
    expect(sent).toHaveLength(3);
    await advance(3000);
    expect(sent).toHaveLength(4);
  });

  it('goes back to every 30 seconds once the window has passed', async () => {
    const times: number[] = [];
    stubFetch(() => {
      times.push(Date.now() - NOW.getTime());
      return spotsResponse(spotsA, '"v1"');
    });
    render(at(5000));
    await advance(0);
    await advance(150_000);

    const gaps = times.slice(1).map((t, i) => t - times[i]!);
    // Every 3 s until a minute after opening (65 s), then every 30 s.
    const fast = gaps.filter((g) => g === POLL_FAST_MS).length;
    expect(fast).toBeGreaterThan(15);
    expect(gaps.slice(0, fast).every((g) => g === POLL_FAST_MS)).toBe(true);
    expect(gaps.slice(fast).every((g) => g === POLL_SLOW_MS)).toBe(true);
    expect(gaps.length - fast).toBeGreaterThanOrEqual(2);
  });

  it('does not poll while the tab is hidden, and resumes when it is shown again', async () => {
    stubFetch(() => spotsResponse(spotsA, '"v1"'));
    render(at(10_000));
    await advance(0);
    expect(sent).toHaveLength(1);

    const visibility = vi.spyOn(document, 'visibilityState', 'get');
    visibility.mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    await advance(60_000);
    expect(sent).toHaveLength(1);

    visibility.mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    await advance(3000);
    expect(sent.length).toBeGreaterThan(1);
  });

  it('sends the last ETag, and a 304 keeps the data it already has', async () => {
    stubFetch((n, ifNoneMatch) => {
      if (n === 1) return spotsResponse(spotsA, '"v1"');
      if (n === 2) {
        expect(ifNoneMatch).toBe('"v1"');
        return new Response(null, { status: 304 });
      }
      return spotsResponse(spotsB, '"v2"');
    });
    const { result } = render(null);
    await advance(0);
    const first = result.current.data;
    expect(first).toEqual(spotsA);
    expect(sent).toEqual([null]);

    await advance(POLL_SLOW_MS);
    // The 304 changed nothing: same object, no error, no empty state.
    expect(sent).toEqual([null, '"v1"']);
    expect(result.current.data).toBe(first);
    expect(result.current.isError).toBe(false);

    await advance(POLL_SLOW_MS);
    await advance(0);
    // Still asking with the first ETag, because that is still the newest one we have.
    expect(sent).toEqual([null, '"v1"', '"v1"']);
    await vi.waitFor(() => expect(result.current.data).toEqual(spotsB));
  });
});

function event(id: string, startsAt: string, endsAt: string | null, status = 'PUBLISHED'): Event {
  return { id, startsAt, endsAt, status } as Event;
}

describe('findOverlaps', () => {
  const hour = 3_600_000;

  it('pairs events whose times overlap, in both directions', () => {
    const a = event('a', at(0), at(2 * hour));
    const b = event('b', at(hour), at(3 * hour));
    const c = event('c', at(5 * hour), at(6 * hour));
    const result = findOverlaps([a, b, c]);
    expect(result.get('a')).toEqual([b]);
    expect(result.get('b')).toEqual([a]);
    expect(result.has('c')).toBe(false);
  });

  it('does not count events that only touch', () => {
    const a = event('a', at(0), at(hour));
    const b = event('b', at(hour), at(2 * hour));
    expect(findOverlaps([a, b]).size).toBe(0);
  });

  it('treats an event with no end as lasting 3 hours, as the feed does', () => {
    const a = event('a', at(0), null);
    expect(findOverlaps([a, event('b', at(2.5 * hour), at(4 * hour))]).has('a')).toBe(true);
    expect(findOverlaps([a, event('c', at(3.5 * hour), at(4 * hour))]).has('a')).toBe(false);
  });

  it('lists every overlap for an event, and ignores cancelled events', () => {
    const wide = event('wide', at(0), at(10 * hour));
    const one = event('one', at(hour), at(2 * hour));
    const two = event('two', at(3 * hour), at(4 * hour));
    const gone = event('gone', at(hour), at(2 * hour), 'CANCELLED');
    const result = findOverlaps([wide, one, two, gone]);
    expect(result.get('wide')).toEqual([one, two]);
    expect(result.has('gone')).toBe(false);
  });
});
