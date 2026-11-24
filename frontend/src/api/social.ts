import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, fillPath, type ContractPath } from './client';
import type { components } from './schema';

type Schemas = components['schemas'];
export type Person = Schemas['PersonResponse'];
export type Circle = Schemas['CircleResponse'];
export type Invite = Schemas['InviteResponse'];
export type InvitePreview = Schemas['InvitePreviewResponse'];

const FRIENDS = '/friends' satisfies ContractPath;
const FRIEND = '/friends/{userId}' satisfies ContractPath;
const INVITES = '/friends/invites' satisfies ContractPath;
const INVITE = '/friends/invites/{token}' satisfies ContractPath;
const INVITE_ACCEPT = '/friends/invites/{token}/accept' satisfies ContractPath;
const BLOCKS = '/blocks' satisfies ContractPath;
const BLOCK = '/blocks/{userId}' satisfies ContractPath;
const CIRCLES = '/circles' satisfies ContractPath;
const CIRCLE = '/circles/{circleId}' satisfies ContractPath;
const CIRCLE_MEMBER = '/circles/{circleId}/members/{userId}' satisfies ContractPath;
const PEOPLE = '/people' satisfies ContractPath;

/** Every social query lives under this prefix, so one invalidation refreshes them all. */
const social = ['social'] as const;
const friendsKey = [...social, 'friends'] as const;
const blocksKey = [...social, 'blocks'] as const;
const circlesKey = [...social, 'circles'] as const;

/** The API's limits on a people search (`GET /api/people`): 2 to 50 characters. */
export const PEOPLE_QUERY_MIN = 2;
export const PEOPLE_QUERY_MAX = 50;
/** The API's limit on a circle name: 1 to 50 characters. */
export const CIRCLE_NAME_MAX = 50;

/** Refresh everything a friendship or block change can touch. */
function useInvalidateSocial() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: social });
}

export function useFriends() {
  return useQuery({
    queryKey: friendsKey,
    queryFn: async () =>
      (await api<Schemas['ResponseRecordPersonListResponse']>(FRIENDS)).body.people,
  });
}

export function useRemoveFriend() {
  const invalidate = useInvalidateSocial();
  return useMutation({
    mutationFn: (userId: string) => api<void>(fillPath(FRIEND, { userId }), { method: 'DELETE' }),
    onSuccess: invalidate,
  });
}

/** Makes a new invite link. Called only when the user asks for one (D-011), never on screen open. */
export function useCreateInvite() {
  return useMutation({
    mutationFn: async () =>
      (await api<Schemas['ResponseRecordInviteResponse']>(INVITES, { method: 'POST' })).body,
  });
}

export function useInvitePreview(token: string) {
  return useQuery({
    queryKey: [...social, 'invite', token],
    queryFn: async () =>
      (await api<Schemas['ResponseRecordInvitePreviewResponse']>(fillPath(INVITE, { token }))).body,
    // Never refetch an invite on focus: a 404 later would only confuse the person reading it.
    refetchOnWindowFocus: false,
  });
}

export function useAcceptInvite(token: string) {
  const invalidate = useInvalidateSocial();
  return useMutation({
    mutationFn: async () =>
      (
        await api<Schemas['ResponseRecordPersonResponse']>(fillPath(INVITE_ACCEPT, { token }), {
          method: 'POST',
        })
      ).body,
    onSuccess: invalidate,
  });
}

export function useBlocks() {
  return useQuery({
    queryKey: blocksKey,
    queryFn: async () =>
      (await api<Schemas['ResponseRecordPersonListResponse']>(BLOCKS)).body.people,
  });
}

export function useBlock() {
  const invalidate = useInvalidateSocial();
  return useMutation({
    mutationFn: (userId: string) => api<void>(fillPath(BLOCK, { userId }), { method: 'PUT' }),
    onSuccess: invalidate,
  });
}

export function useUnblock() {
  const invalidate = useInvalidateSocial();
  return useMutation({
    mutationFn: (userId: string) => api<void>(fillPath(BLOCK, { userId }), { method: 'DELETE' }),
    onSuccess: invalidate,
  });
}

export function useCircles() {
  return useQuery({
    queryKey: circlesKey,
    queryFn: async () =>
      (await api<Schemas['ResponseRecordCircleListResponse']>(CIRCLES)).body.circles,
  });
}

export function useCircle(circleId: string) {
  return useQuery({
    queryKey: [...circlesKey, circleId],
    queryFn: async () =>
      (await api<Schemas['ResponseRecordCircleResponse']>(fillPath(CIRCLE, { circleId }))).body,
  });
}

export function useCreateCircle() {
  const invalidate = useInvalidateSocial();
  return useMutation({
    mutationFn: async (name: string) =>
      (
        await api<Schemas['ResponseRecordCircleResponse']>(CIRCLES, {
          method: 'POST',
          body: { name } satisfies Schemas['CircleRequest'],
        })
      ).body,
    onSuccess: invalidate,
  });
}

export function useRenameCircle(circleId: string) {
  const invalidate = useInvalidateSocial();
  return useMutation({
    mutationFn: async (name: string) =>
      (
        await api<Schemas['ResponseRecordCircleResponse']>(fillPath(CIRCLE, { circleId }), {
          method: 'PATCH',
          body: { name } satisfies Schemas['CircleRequest'],
        })
      ).body,
    onSuccess: invalidate,
  });
}

export function useDeleteCircle(circleId: string) {
  const invalidate = useInvalidateSocial();
  return useMutation({
    mutationFn: () => api<void>(fillPath(CIRCLE, { circleId }), { method: 'DELETE' }),
    onSuccess: invalidate,
  });
}

export function useAddCircleMember(circleId: string) {
  const invalidate = useInvalidateSocial();
  return useMutation({
    mutationFn: (userId: string) =>
      api<void>(fillPath(CIRCLE_MEMBER, { circleId, userId }), { method: 'PUT' }),
    onSuccess: invalidate,
  });
}

export function useRemoveCircleMember(circleId: string) {
  const invalidate = useInvalidateSocial();
  return useMutation({
    mutationFn: (userId: string) =>
      api<void>(fillPath(CIRCLE_MEMBER, { circleId, userId }), { method: 'DELETE' }),
    onSuccess: invalidate,
  });
}

/** `GET /api/people?q=`: runs only for a trimmed query of 2 to 50 characters. */
export function usePeopleSearch(query: string) {
  const q = query.trim();
  return useQuery({
    queryKey: [...social, 'people', q],
    queryFn: async () =>
      (
        await api<Schemas['ResponseRecordPersonListResponse']>(
          `${PEOPLE}?q=${encodeURIComponent(q)}`,
        )
      ).body.people,
    enabled: q.length >= PEOPLE_QUERY_MIN && q.length <= PEOPLE_QUERY_MAX,
  });
}
