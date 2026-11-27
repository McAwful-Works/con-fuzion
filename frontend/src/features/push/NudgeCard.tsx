import { Bell } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { useMe } from '../../api/me';
import { useHasSubscription } from '../../api/push';
import { ErrorState } from '../../components/ErrorState';
import { SideCard } from '../discover/SideCards';
import { subscribeFromTap } from './browserPush';
import { usePushAvailability } from './PushSetting';
import { useTurnPushOn, type TurnOnResult } from './usePush';

const OUTCOME: Record<TurnOnResult, string> = {
  on: 'Notifications are on for this device.',
  denied:
    'Notifications are blocked for Con-Fuzion in this browser. To turn them on, allow them for this site in the browser’s site settings, then try again.',
  dismissed: 'You closed the browser’s permission prompt, so notifications are still off.',
};

/**
 * "Get a nudge": turns notifications on, from a side column. It shows only where this browser and the
 * server can do push and it's off here. The full switch, and the explanations, are in You.
 */
export function NudgeCard() {
  const { key } = usePushAvailability();
  const me = useMe();
  const subscribed = useHasSubscription(Boolean(key.data));
  const turnOn = useTurnPushOn();
  const answer = useRef<HTMLParagraphElement>(null);
  // The button pressed is gone or disabled by now; don't drop the keyboard to the top of the page.
  useEffect(() => {
    if (turnOn.data) answer.current?.focus();
  }, [turnOn.data]);

  const publicKey = key.data;
  if (!publicKey || !me.data || subscribed.data === undefined) return null;
  const on = turnOn.data === 'on';
  // Having just turned it on here, say so rather than vanish.
  if (!on && me.data.pushEnabled && subscribed.data) return null;
  // Read each render: it changes when the user re-enables notifications in the browser's settings.
  const blocked = Notification.permission === 'denied';
  // A browser that already blocks them can't be asked, so there's nothing to offer.
  if (blocked && !turnOn.data) return null;

  return (
    <SideCard title="Get a nudge">
      <div className="space-y-3">
        {!on && (
          <>
            <p className="text-ink-muted text-sm">
              Turn on notifications for offers, changes and a reminder before an event starts.
            </p>
            <button
              type="button"
              className="btn btn-secondary btn-sm w-full"
              disabled={turnOn.isPending || blocked}
              // The permission prompt and the subscription start from this tap, never from loading the page.
              onClick={() => {
                const attempt = subscribeFromTap(publicKey);
                attempt.catch(() => undefined); // mutate() reports the failure
                turnOn.mutate(attempt);
              }}
            >
              <Bell aria-hidden="true" className="size-4" />
              Turn on notifications
            </button>
          </>
        )}
        {/* Always here, so that a screen reader hears the answer when it arrives. */}
        <p ref={answer} role="status" tabIndex={-1} className="text-sm empty:hidden">
          {turnOn.data && OUTCOME[turnOn.data]}
        </p>
        {turnOn.isError && <ErrorState error={turnOn.error} />}
      </div>
    </SideCard>
  );
}
