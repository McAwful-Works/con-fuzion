import { useQuery } from '@tanstack/react-query';
import { currentSubscription } from '../features/push/browserPush';
import { api, ApiError, type ContractPath } from './client';
import type { components } from './schema';

type Schemas = components['schemas'];

const KEY_PATH = '/push/key' satisfies ContractPath;
const SUBSCRIPTIONS_PATH = '/push/subscriptions' satisfies ContractPath;

const keyKey = ['push', 'key'] as const;
export const subscriptionKey = ['push', 'subscription'] as const;

/**
 * The server's VAPID public key. A 404 means the server has no keys (D-014 rule 14) and a 501 is the
 * endpoint before the sender exists; both mean "push isn't offered here", which is `null`, not an error.
 */
export function usePushKey(enabled: boolean) {
  return useQuery({
    queryKey: keyKey,
    enabled,
    staleTime: Infinity,
    retry: false,
    queryFn: async (): Promise<string | null> => {
      try {
        return (await api<Schemas['ResponseRecordPushKeyResponse']>(KEY_PATH)).body.publicKey;
      } catch (error) {
        if (error instanceof ApiError && (error.status === 404 || error.status === 501))
          return null;
        throw error;
      }
    },
  });
}

/** Whether this browser holds a push subscription right now. */
export function useHasSubscription(enabled: boolean) {
  return useQuery({
    queryKey: subscriptionKey,
    enabled,
    queryFn: async () => (await currentSubscription()) !== null,
  });
}

export function saveSubscription(subscription: PushSubscription) {
  const { endpoint, keys } = subscription.toJSON();
  if (!endpoint || !keys?.p256dh || !keys.auth) throw new Error('The browser gave no push keys.');
  return api<void>(SUBSCRIPTIONS_PATH, {
    method: 'POST',
    body: {
      endpoint,
      keys: { p256dh: keys.p256dh, auth: keys.auth },
    } satisfies Schemas['PushSubscriptionRequest'],
  });
}

export function setPushEnabled(pushEnabled: boolean) {
  return api<Schemas['ResponseRecordMeResponse']>('/me', {
    method: 'PATCH',
    body: { pushEnabled } satisfies Schemas['UpdateMeRequest'],
  });
}

/**
 * Removes this browser's subscription from the server, then from the browser. The server call comes
 * first because it needs the session; `strict` says whether its failure stops the rest.
 */
export async function dropSubscription(strict: boolean): Promise<void> {
  const subscription = await currentSubscription();
  if (!subscription) return;
  try {
    await api<void>(SUBSCRIPTIONS_PATH, {
      method: 'DELETE',
      body: { endpoint: subscription.endpoint } satisfies Schemas['PushEndpointRequest'],
    });
  } catch (error) {
    if (strict) throw error;
  }
  try {
    await subscription.unsubscribe();
  } catch (error) {
    if (strict) throw error;
  }
}

/**
 * Sign-out (D-014 rule 18): this browser's subscription goes before the session does, so the next person
 * to sign in here never gets this user's pushes. A failure must not keep anyone signed in.
 */
export function dropSubscriptionBeforeSignOut(): Promise<void> {
  return dropSubscription(false);
}
