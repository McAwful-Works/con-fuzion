import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
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

/** `POST /api/notifications/read`: everything up to and including `upTo`, the newest ID on screen. */
export function useMarkRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (upTo: number) =>
      api<void>(READ, {
        method: 'POST',
        body: { upTo } satisfies Schemas['NotificationReadRequest'],
      }),
    // Only the badge: the list keeps showing which entries were new when it was opened.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: unreadKey }),
  });
}
