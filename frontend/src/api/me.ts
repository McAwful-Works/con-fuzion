import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { api } from './client';
import type { components, paths } from './schema';

type Schemas = components['schemas'];
export type Me = Schemas['MeResponse'];
export type Guild = Schemas['GuildResponse'];

/** Where the browser goes to start Discord sign-in. A full navigation, not an XHR. */
export const SIGN_IN_URL = '/api/oauth2/authorization/discord';

/** An API path relative to the `/api` root, checked against the generated contract paths. */
type ContractPath = keyof paths extends infer P ? (P extends `/api${infer R}` ? R : never) : never;
const SIGN_OUT_PATH = '/auth/logout' satisfies ContractPath;
const SIGN_OUT_EVERYWHERE_PATH = '/auth/logout-everywhere' satisfies ContractPath;

const meKey = ['me'] as const;
const guildsKey = ['guilds'] as const;
export const singleGuildKey = ['guild'] as const;

export function useMe() {
  return useQuery({
    queryKey: meKey,
    queryFn: async () => (await api<Schemas['ResponseRecordMeResponse']>('/me')).body,
    staleTime: 60_000,
  });
}

/** First load: store the browser's time zone when the account has none (`PATCH /api/me`). */
export function useSyncTimeZone(me: Me | undefined) {
  const queryClient = useQueryClient();
  const attempted = useRef(false);
  const update = useMutation({
    mutationFn: async (timeZone: string) =>
      (
        await api<Schemas['ResponseRecordMeResponse']>('/me', {
          method: 'PATCH',
          body: { timeZone } satisfies Schemas['UpdateMeRequest'],
        })
      ).body,
    onSuccess: (updated) => queryClient.setQueryData(meKey, updated),
  });
  const { mutate } = update;

  useEffect(() => {
    if (!me || me.timeZone !== null || attempted.current) return;
    attempted.current = true;
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (zone) mutate(zone);
  }, [me, mutate]);
}

export function useGuilds() {
  return useQuery({
    queryKey: guildsKey,
    queryFn: async () =>
      (await api<Schemas['ResponseRecordGuildListResponse']>('/me/guilds')).body.guilds,
  });
}

/** One server: `PUT /api/me/guilds/{guildId}`. */
export function useSetGuildDiscoverable(guildId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationKey: singleGuildKey,
    mutationFn: async (discoverable: boolean) =>
      (
        await api<Schemas['ResponseRecordGuildResponse']>(
          `/me/guilds/${encodeURIComponent(guildId)}`,
          {
            method: 'PUT',
            body: { discoverable } satisfies Schemas['UpdateGuildRequest'],
          },
        )
      ).body,
    onSuccess: (updated) =>
      queryClient.setQueryData<Guild[]>(guildsKey, (list) =>
        list?.map((g) => (g.id === updated.id ? updated : g)),
      ),
  });
}

/** Every server at once: ONE bulk `PUT /api/me/guilds`, never a request per server. */
export function useSetAllGuildsDiscoverable() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ guilds, discoverable }: { guilds: Guild[]; discoverable: boolean }) =>
      (
        await api<Schemas['ResponseRecordGuildListResponse']>('/me/guilds', {
          method: 'PUT',
          body: {
            guilds: guilds.map((g) => ({ guildId: g.id, discoverable })),
          } satisfies Schemas['UpdateGuildsRequest'],
        })
      ).body.guilds,
    onSuccess: (updated) => queryClient.setQueryData<Guild[]>(guildsKey, updated),
  });
}

/** Ends this session (`signOut`) or every session on every device (`everywhere`). */
export function useSignOut(everywhere: boolean) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api<void>(everywhere ? SIGN_OUT_EVERYWHERE_PATH : SIGN_OUT_PATH, { method: 'POST' }),
    // Drop everything cached for the old session so nothing leaks into the next sign-in.
    onSuccess: () => queryClient.clear(),
  });
}
