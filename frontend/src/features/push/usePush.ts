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
 * Turns push on for this browser. Start it straight from a tap, passing `subscribeFromTap(key)` made in the click handler. It subscribes, saves
 * the subscription, then sets `pushEnabled`, because saving a subscription doesn't turn push on (rule 12).
 * If saving fails the browser's new subscription is dropped again, so the two never disagree.
 */
export function useTurnPushOn() {
  const queryClient = useQueryClient();
  return useMutation({
    // `attempt` is `subscribeFromTap(...)`, already started by the click handler: TanStack Query waits
    // a few microtasks before it calls `mutationFn`, which would put the permission prompt (and, on iOS,
    // the subscribe) outside the tap.
    mutationFn: async (attempt: ReturnType<typeof subscribeFromTap>): Promise<TurnOnResult> => {
      const { permission, subscription } = await attempt;
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
