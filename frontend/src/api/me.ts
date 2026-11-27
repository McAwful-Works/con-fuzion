import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { api, type ContractPath } from './client';
import { endOfflineSession, forgetOtherUsersCache } from './offlineCache';
import { dropSubscriptionBeforeSignOut } from './push';
import type { components } from './schema';

type Schemas = components['schemas'];
export type Me = Schemas['MeResponse'];
export type Guild = Schemas['GuildResponse'];

/** Where the browser goes to start Discord sign-in. A full navigation, not an XHR. */
export const SIGN_IN_URL = '/api/oauth2/authorization/discord';

const SIGN_OUT_PATH = '/auth/logout' satisfies ContractPath;
const SIGN_OUT_EVERYWHERE_PATH = '/auth/logout-everywhere' satisfies ContractPath;

export const meKey = ['me'] as const;
const guildsKey = ['guilds'] as const;
export const singleGuildKey = ['guild'] as const;

/**
 * Whether to offer the moderation area. `roles` lists only the roles in effect, already gated on MFA
 * (D-015 rule 2). This only decides what's shown: the server enforces access.
 */
export function isModerator(me: Me | undefined): boolean {
  return me?.roles.some((role) => role === 'MODERATOR' || role === 'ADMIN') ?? false;
}

/** Whether to show the admin-only views: bans, roles and the audit log. The server enforces them. */
export function isAdmin(me: Me | undefined): boolean {
  return me?.roles.includes('ADMIN') ?? false;
}

export function useMe() {
  return useQuery({
    queryKey: meKey,
    // Like My plans, it can come from the service worker's cache, so a cold start offline still
    // gets past the sign-in gate. Without this the query would wait for a connection that isn't coming.
    networkMode: 'offlineFirst',
    queryFn: async () => {
      const { body } = await api<Schemas['ResponseRecordMeResponse']>('/me');
      forgetOtherUsersCache(body.id);
      return body;
    },
    staleTime: 60_000,
  });
}

/** `PATCH /api/me` with a time zone. */
export function useSetTimeZone() {
  const queryClient = useQueryClient();
  return useMutation({
    // One at a time, so that the last zone chosen is the one that stays.
    scope: { id: 'time-zone' },
    mutationFn: async (timeZone: string) =>
      (
        await api<Schemas['ResponseRecordMeResponse']>('/me', {
          method: 'PATCH',
          body: { timeZone } satisfies Schemas['UpdateMeRequest'],
        })
      ).body,
    onSuccess: (updated) => queryClient.setQueryData(meKey, updated),
  });
}

/** First load: store the browser's time zone when the account has none. */
export function useSyncTimeZone(me: Me | undefined) {
  const attempted = useRef(false);
  const { mutate } = useSetTimeZone();

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
    mutationFn: async () => {
      // D-014 rule 18: this browser stops getting pushes before the session that owns them ends.
      await dropSubscriptionBeforeSignOut();
      return api<void>(everywhere ? SIGN_OUT_EVERYWHERE_PATH : SIGN_OUT_PATH, { method: 'POST' });
    },
    // Drop everything cached for the old session so nothing leaks into the next sign-in.
    onSuccess: () => {
      queryClient.clear();
      // The service worker keeps the last My plans response for offline use; it must not outlive the session.
      endOfflineSession();
    },
  });
}
