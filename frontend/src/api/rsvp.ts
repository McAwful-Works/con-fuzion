import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiConditional, fillPath, type ContractPath } from './client';
import type { components } from './schema';
import type { Event } from './events';

type Schemas = components['schemas'];
export type Spots = Schemas['SpotsResponse'];
export type Attendees = Schemas['AttendeesResponse'];
export type RsvpResponse = Schemas['RsvpResponse'];
export type MyStatus = Event['myStatus'];

const RSVP = '/events/{eventId}/rsvp' satisfies ContractPath;
const RSVP_OF = '/events/{eventId}/rsvps/{userId}' satisfies ContractPath;
const ACCEPT = '/events/{eventId}/rsvp/accept' satisfies ContractPath;
const SPOTS = '/events/{eventId}/spots' satisfies ContractPath;
const ATTENDEES = '/events/{eventId}/attendees' satisfies ContractPath;

// Everything below is under the events key, so one invalidation refreshes lists, the event and RSVPs.
const events = ['events'] as const;

/** Plan B2: poll fast around a signup drop, slowly otherwise. */
export const POLL_FAST_MS = 3_000;
export const POLL_SLOW_MS = 30_000;
/** "The 2 minutes around signup opening": one minute before and one after. */
export const SIGNUP_WINDOW_MS = 60_000;

/**
 * How long to wait before the next `/spots` poll. Fast inside the window around `signupOpensAt`,
 * slow otherwise, except that a slow wait never overshoots the start of the window, so polling
 * speeds up on time. Hidden tabs are paused by TanStack Query (`refetchIntervalInBackground` is off).
 */
export function pollInterval(signupOpensAt: string | null, nowMs: number): number {
  if (!signupOpensAt) return POLL_SLOW_MS;
  const untilOpens = Date.parse(signupOpensAt) - nowMs;
  if (Number.isNaN(untilOpens)) return POLL_SLOW_MS;
  if (Math.abs(untilOpens) <= SIGNUP_WINDOW_MS) return POLL_FAST_MS;
  if (untilOpens > 0) {
    return Math.max(POLL_FAST_MS, Math.min(POLL_SLOW_MS, untilOpens - SIGNUP_WINDOW_MS));
  }
  return POLL_SLOW_MS;
}

interface SpotsSnapshot {
  spots: Spots;
  etag: string | null;
}

/**
 * Live spot counts and the caller's own place in line. Sends the last `ETag` as `If-None-Match`; a
 * 304 keeps the data already cached (the same object, so nothing re-renders).
 */
export function useSpots(event: Pick<Event, 'id' | 'signupOpensAt' | 'status'>) {
  const eventId = event.id;
  const key = [...events, 'spots', eventId];
  return useQuery({
    queryKey: key,
    // A cancelled event has no spots to watch.
    enabled: event.status === 'PUBLISHED',
    refetchInterval: () => pollInterval(event.signupOpensAt, Date.now()),
    queryFn: async ({ client, signal }): Promise<SpotsSnapshot> => {
      const path = fillPath(SPOTS, { eventId });
      const previous = client.getQueryData<SpotsSnapshot>(key);
      const result = await apiConditional<Schemas['ResponseRecordSpotsResponse']>(path, {
        ifNoneMatch: previous?.etag ?? undefined,
        signal,
      });
      if (result.notModified) {
        if (previous) return previous;
        // A 304 we have nothing to show for (the cache was cleared meanwhile): ask again, unconditionally.
        const again = await apiConditional<Schemas['ResponseRecordSpotsResponse']>(path, {
          signal,
        });
        if (again.notModified) throw new Error('Unexpected 304');
        return { spots: again.body.body, etag: again.etag };
      }
      return { spots: result.body.body, etag: result.etag };
    },
    select: (snapshot) => snapshot.spots,
  });
}

/** The confirmed list, plus the waitlist for the host. Only the host and confirmed attendees may ask. */
export function useAttendees(eventId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...events, 'attendees', eventId],
    enabled,
    // An offer being accepted doesn't change the counts `/spots` reports, so while any are open, look again.
    refetchInterval: (query) =>
      (query.state.data?.offered?.length ?? 0) > 0 ? POLL_SLOW_MS : false,
    queryFn: async () =>
      (await api<Schemas['ResponseRecordAttendeesResponse']>(fillPath(ATTENDEES, { eventId })))
        .body,
  });
}

/** After any change to who holds a spot: refresh every event list, the event, its spots and attendees. */
function useRefreshEvents() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: events });
}

export function useJoin(eventId: string) {
  const refresh = useRefreshEvents();
  return useMutation({
    mutationFn: async () =>
      (
        await api<Schemas['ResponseRecordRsvpResponse']>(fillPath(RSVP, { eventId }), {
          method: 'POST',
        })
      ).body,
    // Stay pending until the refetch lands, so the button never flashes its old state.
    onSuccess: refresh,
    // A 409 means what we showed was out of date (not open yet, removed meanwhile): refetch it.
    onError: () => void refresh(),
  });
}

/** Takes up an open offer (D-014 rule 4). A 409 means it ran out or is gone: refetch to show what's true now. */
export function useAccept(eventId: string) {
  const refresh = useRefreshEvents();
  return useMutation({
    mutationFn: async () =>
      (
        await api<Schemas['ResponseRecordRsvpResponse']>(fillPath(ACCEPT, { eventId }), {
          method: 'POST',
        })
      ).body,
    onSuccess: refresh,
    onError: () => void refresh(),
  });
}

/** Leaving, which while offered is declining (D-014 rule 5). */
export function useLeave(eventId: string) {
  const refresh = useRefreshEvents();
  return useMutation({
    mutationFn: () => api<void>(fillPath(RSVP, { eventId }), { method: 'DELETE' }),
    onSuccess: refresh,
    onError: () => void refresh(),
  });
}

/** The host removes someone. In M3 that is permanent: they can't rejoin (D-013 rule 1). */
export function useRemoveAttendee(eventId: string) {
  const refresh = useRefreshEvents();
  return useMutation({
    mutationFn: (userId: string) =>
      api<void>(fillPath(RSVP_OF, { eventId, userId }), { method: 'DELETE' }),
    onSuccess: refresh,
    onError: () => void refresh(),
  });
}
