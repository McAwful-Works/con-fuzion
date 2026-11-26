import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, fillPath, isNotThere, type ContractPath } from './client';
import type { components } from './schema';

type Schemas = components['schemas'];
export type Report = Schemas['ReportResponse'];
export type ReportStatus = Report['status'];
export type Resolution = Schemas['ResolveReportRequest']['resolution'];
export type AdminUser = Schemas['AdminUserResponse'];
export type EventAction = 'hide' | 'unhide' | 'cancel';

const REPORTS = '/admin/reports' satisfies ContractPath;
const REPORT = '/admin/reports/{reportId}' satisfies ContractPath;
const RESOLVE = '/admin/reports/{reportId}/resolve' satisfies ContractPath;
const USERS = '/admin/users' satisfies ContractPath;
const SUSPEND = '/admin/users/{userId}/suspend' satisfies ContractPath;
const EVENT_ACTIONS = {
  hide: '/admin/events/{eventId}/hide',
  unhide: '/admin/events/{eventId}/unhide',
  cancel: '/admin/events/{eventId}/cancel',
} satisfies Record<EventAction, ContractPath>;

/** D-015 rules 4 and 13. The server checks them again. */
export const RESOLUTION_NOTE_MAX = 1000;
export const REASON_MAX = 500;
export const USER_QUERY_MIN = 2;
export const USER_QUERY_MAX = 100;
export const SUSPEND_MAX_DAYS = 365;
export const REPORT_PAGE_SIZE = 20;

const reportsKey = ['admin', 'reports'] as const;
const usersKey = ['admin', 'users'] as const;

// Every call here throws a 403 or 404 to the route's error element, which shows the not-found page
// (a report about the viewer is a 404, for one). Other failures show where they happened.

/** The queue, a page at a time: open reports oldest first, resolved ones newest first. */
export function useReports(status: ReportStatus) {
  return useInfiniteQuery({
    queryKey: [...reportsKey, 'list', status],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => {
      const query = new URLSearchParams({ status, limit: String(REPORT_PAGE_SIZE) });
      if (pageParam) query.set('cursor', pageParam);
      return (await api<Schemas['ResponseRecordReportPageResponse']>(`${REPORTS}?${query}`)).body;
    },
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    throwOnError: isNotThere,
  });
}

export function useReport(reportId: string) {
  return useQuery({
    queryKey: [...reportsKey, 'one', reportId],
    queryFn: async () =>
      (await api<Schemas['ResponseRecordReportResponse']>(fillPath(REPORT, { reportId }))).body,
    throwOnError: isNotThere,
  });
}

export function useResolveReport(reportId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: Schemas['ResolveReportRequest']) =>
      api<void>(fillPath(RESOLVE, { reportId }), { method: 'POST', body }),
    // Also after a failure: "already resolved" means what's on screen is out of date.
    onSettled: () => queryClient.invalidateQueries({ queryKey: reportsKey }),
    throwOnError: isNotThere,
  });
}

/** Hide or cancel an event with a reason for its host, or unhide it (which takes none). */
export function useEventAction(eventId: string, action: EventAction) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reason?: string) =>
      api<void>(fillPath(EVENT_ACTIONS[action], { eventId }), {
        method: 'POST',
        body:
          reason === undefined ? undefined : ({ reason } satisfies Schemas['ModerationRequest']),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['events'] }),
    throwOnError: isNotThere,
  });
}

/** `GET /api/admin/users?q`: at most 20 people. Nothing is sent for a query that's too short. */
export function useUserSearch(q: string) {
  return useQuery({
    queryKey: [...usersKey, q],
    enabled: q.length >= USER_QUERY_MIN,
    queryFn: async () =>
      (
        await api<Schemas['ResponseRecordAdminUserListResponse']>(
          `${USERS}?${new URLSearchParams({ q })}`,
        )
      ).body.items,
    throwOnError: isNotThere,
  });
}

export function useSuspend(userId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: Schemas['SuspendRequest']) =>
      api<void>(fillPath(SUSPEND, { userId }), { method: 'POST', body }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: usersKey }),
    throwOnError: isNotThere,
  });
}
