import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, fillPath, isNotThere, type ContractPath } from './client';
import type { components } from './schema';

type Schemas = components['schemas'];
export type Report = Schemas['ReportResponse'];
export type ReportStatus = Report['status'];
export type Resolution = Schemas['ResolveReportRequest']['resolution'];
export type AdminUser = Schemas['AdminUserResponse'];
export type EventAction = 'hide' | 'unhide' | 'cancel';
export type GrantedRole = Schemas['RolesRequest']['roles'][number];
export type AuditEntry = Schemas['AuditEntryResponse'];

const REPORTS = '/admin/reports' satisfies ContractPath;
const REPORT = '/admin/reports/{reportId}' satisfies ContractPath;
const RESOLVE = '/admin/reports/{reportId}/resolve' satisfies ContractPath;
const USERS = '/admin/users' satisfies ContractPath;
const SUSPEND = '/admin/users/{userId}/suspend' satisfies ContractPath;
const ROLES = '/admin/users/{userId}/roles' satisfies ContractPath;
const BANS = '/admin/bans' satisfies ContractPath;
const BAN = '/admin/bans/{discordId}' satisfies ContractPath;
const AUDIT = '/admin/audit' satisfies ContractPath;
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
export const AUDIT_PAGE_SIZE = 20;

const reportsKey = ['admin', 'reports'] as const;
const usersKey = ['admin', 'users'] as const;
const rolesKey = ['admin', 'roles'] as const;
const auditKey = ['admin', 'audit'] as const;

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

/** The lengths `GET /api/admin/users` accepts (D-015 rule 13). */
export function isSearchable(q: string): boolean {
  return q.length >= USER_QUERY_MIN && q.length <= USER_QUERY_MAX;
}

/** `GET /api/admin/users?q`: at most 20 people. Nothing is sent for a query it wouldn't accept. */
export function useUserSearch(q: string) {
  return useQuery({
    queryKey: [...usersKey, q],
    enabled: isSearchable(q),
    queryFn: async (): Promise<AdminUser[]> =>
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

/** Admin only. Bans a Discord ID with a reason, or lifts its ban when the reason is null. */
export function useSetBan(discordId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reason: string | null) =>
      reason === null
        ? api<void>(fillPath(BAN, { discordId }), { method: 'DELETE' })
        : api<void>(BANS, {
            method: 'POST',
            body: { discordId, reason } satisfies Schemas['BanRequest'],
          }),
    // Not returned, so the form closes before the search shows the new state.
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: usersKey }),
    throwOnError: isNotThere,
  });
}

/** Admin only. Not kept once the form closes, so it's read afresh each time. */
export function useUserRoles(userId: string) {
  return useQuery({
    queryKey: [...rolesKey, userId],
    gcTime: 0,
    queryFn: async () =>
      (await api<Schemas['ResponseRecordRolesResponse']>(fillPath(ROLES, { userId }))).body,
    throwOnError: isNotThere,
  });
}

/** Admin only. Sets exactly these roles; an empty list removes them all (D-015 rule 3). */
export function useSetRoles(userId: string) {
  return useMutation({
    mutationFn: (roles: GrantedRole[]) =>
      api<void>(fillPath(ROLES, { userId }), {
        method: 'PUT',
        body: { roles } satisfies Schemas['RolesRequest'],
      }),
    throwOnError: isNotThere,
  });
}

/** Admin only. The audit log, newest first, a page at a time. */
export function useAuditLog() {
  return useInfiniteQuery({
    queryKey: auditKey,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => {
      const query = new URLSearchParams({ limit: String(AUDIT_PAGE_SIZE) });
      if (pageParam) query.set('cursor', pageParam);
      return (await api<Schemas['ResponseRecordAuditPageResponse']>(`${AUDIT}?${query}`)).body;
    },
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    throwOnError: isNotThere,
  });
}
