import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query';
import { api, type ContractPath } from './client';
import type { components } from './schema';

type Schemas = components['schemas'];
export type Notification = Schemas['NotificationResponse'];

const NOTIFICATIONS = '/notifications' satisfies ContractPath;
const READ = '/notifications/read' satisfies ContractPath;

export const NOTIFICATIONS_PAGE_SIZE = 20;
/** The badge is checked gently: once a minute, and not while the tab is hidden. */
export const UNREAD_POLL_MS = 60_000;
/** D-014 rule 10: the server counts at most this many unread. */
export const UNREAD_CAP = 100;

const listKey = ['notifications', 'list'] as const;
const unreadKey = ['notifications', 'unread'] as const;

/** "3", or "99+" once the server's cap is reached. */
export function formatUnread(count: number): string {
  return count >= UNREAD_CAP ? '99+' : String(count);
}

/**
 * The unread count for the navigation badge. It rides on the smallest inbox page, since
 * `unreadCount` is part of every response. Hidden tabs are paused by TanStack Query
 * (`refetchIntervalInBackground` is off), and it refetches when the tab is shown again.
 */
export function useUnreadCount(enabled: boolean) {
  return useQuery({
    queryKey: unreadKey,
    enabled,
    refetchInterval: UNREAD_POLL_MS,
    queryFn: async () =>
      (
        await api<Schemas['ResponseRecordNotificationPageResponse']>(
          `${NOTIFICATIONS}?${new URLSearchParams({ limit: '1' })}`,
        )
      ).body.unreadCount,
  });
}

/** The inbox, newest first, a page at a time. */
export function useNotifications() {
  return useInfiniteQuery({
    queryKey: listKey,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => {
      const query = new URLSearchParams({ limit: String(NOTIFICATIONS_PAGE_SIZE) });
      if (pageParam) query.set('cursor', pageParam);
      return (
        await api<Schemas['ResponseRecordNotificationPageResponse']>(`${NOTIFICATIONS}?${query}`)
      ).body;
    },
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
}

/** Takes the unread dots off the entries already loaded. Opening the inbox leaves them on. */
export function useClearUnreadDots() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.setQueryData<InfiniteData<Schemas['NotificationPageResponse']>>(
      listKey,
      (data) =>
        data && {
          ...data,
          pages: data.pages.map((page) => ({
            ...page,
            unreadCount: 0,
            items: page.items.map((n) => ({ ...n, read: true })),
          })),
        },
    );
}

/** `POST /api/notifications/read`: everything up to and including `upTo`, the newest ID on screen. */
export function useMarkRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (upTo: number) =>
      api<void>(READ, {
        method: 'POST',
        body: { upTo } satisfies Schemas['NotificationReadRequest'],
      }),
    // Only the badge: the list keeps showing which entries were new when it was opened. On a fresh page
    // the badge's first fetch can still be in flight, counted before this read, and invalidating a query
    // with no data yet reuses that fetch, so the old count would stick until the next poll. Cancel it
    // first, so the refetch always starts after the read.
    onSuccess: async () => {
      await queryClient.cancelQueries({ queryKey: unreadKey });
      await queryClient.invalidateQueries({ queryKey: unreadKey });
    },
  });
}
