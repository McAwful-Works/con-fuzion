import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { api, fillPath, type ContractPath } from './client';
import type { components, operations } from './schema';

type Schemas = components['schemas'];
type ListQuery = NonNullable<operations['getEvents']['parameters']['query']>;
/** Discover's search and filters. They only narrow the feed (D-017); leave out the ones not in use. */
export type FeedFilters = Pick<ListQuery, 'q' | 'guild' | 'friends' | 'hasSpots'>;
export type Event = Schemas['EventResponse'];
export type EventRequest = Schemas['EventRequest'];
export type AudienceEntry = Schemas['AudienceEntryResponse'];
export type AudienceEntryRequest = Schemas['AudienceEntryRequest'];

const EVENTS = '/events' satisfies ContractPath;
const EVENT = '/events/{eventId}' satisfies ContractPath;

/** The limits the API enforces on an event (D-012 and the contract). The server checks them again. */
export const EVENT_TITLE_MAX = 100;
export const EVENT_DESCRIPTION_MAX = 2000;
export const EVENT_LOCATION_MAX = 200;
export const EVENT_TAG_MAX = 30;
/** D-013: the capacity is the whole headcount and the host holds a spot, so at least 2. */
export const EVENT_CAPACITY_MIN = 2;
export const EVENT_CAPACITY_MAX = 10000;
/** D-014: how long a freed spot is held for the next person in line, in minutes (10 minutes to 24 hours). */
export const CONFIRM_WINDOW_MIN = 10;
export const CONFIRM_WINDOW_MAX = 1440;
export const CONFIRM_WINDOW_DEFAULT = 30;
export const AUDIENCE_MAX = 50;
export const FEED_PAGE_SIZE = 20;
const PAGE_LIMIT_MAX = 50;
/** The lengths the API accepts for a search. */
export const SEARCH_MIN = 2;
export const SEARCH_MAX = 100;

const events = ['events'] as const;

/**
 * Discover's feed, a page at a time. Every page asks for the same `filters`. `enabled: false`
 * waits.
 */
export function useEventFeed(filters: FeedFilters = {}, enabled = true) {
  return useInfiniteQuery({
    queryKey: [...events, 'list', 'feed', filters],
    enabled,
    // The last list stays on screen while a new search or filter loads.
    placeholderData: keepPreviousData,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => {
      const query = new URLSearchParams({ scope: 'feed', limit: String(FEED_PAGE_SIZE) });
      for (const [name, value] of Object.entries(filters)) query.set(name, String(value));
      if (pageParam) query.set('cursor', pageParam);
      return (await api<Schemas['ResponseRecordEventPageResponse']>(`${EVENTS}?${query}`)).body;
    },
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
}

/**
 * Everything upcoming that you're hosting, going to, waiting for or offered a spot in (D-014), for My
 * plans and the side cards: every page of it, so that the counts are right. The service worker keeps
 * these responses for reading offline.
 */
export function usePlans() {
  return useQuery({
    queryKey: [...events, 'list', 'plans'],
    // Let the first request go out without a connection, so the saved copy can answer it (the default
    // would pause it until we're back online).
    networkMode: 'offlineFirst',
    queryFn: async () => {
      const plans: Event[] = [];
      let cursor: string | null = null;
      do {
        const query = new URLSearchParams({ scope: 'attending', limit: String(PAGE_LIMIT_MAX) });
        if (cursor) query.set('cursor', cursor);
        const page: Schemas['EventPageResponse'] = (
          await api<Schemas['ResponseRecordEventPageResponse']>(`${EVENTS}?${query}`)
        ).body;
        plans.push(...page.events);
        cursor = page.nextCursor;
      } while (cursor);
      return plans;
    },
  });
}

export function eventQuery(eventId: string) {
  return {
    queryKey: [...events, 'one', eventId],
    queryFn: async () =>
      (await api<Schemas['ResponseRecordEventResponse']>(fillPath(EVENT, { eventId }))).body,
  };
}

export function useEvent(eventId: string) {
  return useQuery(eventQuery(eventId));
}

export function useCreateEvent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: EventRequest) =>
      (
        await api<Schemas['ResponseRecordEventResponse']>(EVENTS, {
          method: 'POST',
          body: request,
        })
      ).body,
    onSuccess: (created) => {
      queryClient.setQueryData([...events, 'one', created.id], created);
      return queryClient.invalidateQueries({ queryKey: [...events, 'list'] });
    },
  });
}

/** `PATCH` is a full replace (D-012): send the whole event, audience included. */
export function useUpdateEvent(eventId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: EventRequest) =>
      (
        await api<Schemas['ResponseRecordEventResponse']>(fillPath(EVENT, { eventId }), {
          method: 'PATCH',
          body: request,
        })
      ).body,
    onSuccess: (updated) => {
      queryClient.setQueryData([...events, 'one', eventId], updated);
      return queryClient.invalidateQueries({ queryKey: [...events, 'list'] });
    },
  });
}

export function useCancelEvent(eventId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api<void>(fillPath(EVENT, { eventId }), { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: events }),
  });
}
