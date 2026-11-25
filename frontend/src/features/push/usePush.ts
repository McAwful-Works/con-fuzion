import { useMutation, useQueryClient } from '@tanstack/react-query';
import { meKey } from '../../api/me';
import {
  dropSubscription,
  saveSubscription,
  setPushEnabled,
  subscriptionKey,
} from '../../api/push';
import { subscribeFromTap } from './browserPush';

export type TurnOnResult = 'on' | 'denied' | 'dismissed';

/**
 * Turns push on for this browser. Run it straight from a tap (`subscribeFromTap`). It subscribes, saves
 * the subscription, then sets `pushEnabled`, because saving a subscription doesn't turn push on (rule 12).
 * If saving fails the browser's new subscription is dropped again, so the two never disagree.
 */
export function useTurnPushOn(publicKey: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<TurnOnResult> => {
      const { permission, subscription } = await subscribeFromTap(publicKey);
      if (!subscription) return permission === 'denied' ? 'denied' : 'dismissed';
      try {
        await saveSubscription(subscription);
        await setPushEnabled(true);
      } catch (error) {
        await subscription.unsubscribe().catch(() => undefined);
        throw error;
      }
      return 'on';
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: subscriptionKey }),
    onSuccess: (result) => {
      if (result === 'on') void queryClient.invalidateQueries({ queryKey: meKey });
    },
  });
}

/** Turning push off: the server forgets this browser, the browser unsubscribes, `pushEnabled` goes off. */
export function useTurnPushOff() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      await dropSubscription(true);
      await setPushEnabled(false);
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: subscriptionKey }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: meKey }),
  });
}
