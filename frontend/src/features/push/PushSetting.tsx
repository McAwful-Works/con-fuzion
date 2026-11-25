import { useMe } from '../../api/me';
import { useHasSubscription, usePushKey } from '../../api/push';
import { ErrorState } from '../../components/ErrorState';
import { AddToHomeScreenSteps } from './AddToHomeScreenSteps';
import { pushSupport, subscribeFromTap } from './browserPush';
import { useTurnPushOff, useTurnPushOn } from './usePush';

/**
 * What the push setting can do here, from the browser and the server alone: it is `null` while the
 * server is still being asked, and `unavailable` once push can't be offered at all.
 */
export function usePushAvailability() {
  const support = pushSupport();
  // Only a browser that can do push has any use for the server's key.
  const key = usePushKey(support === 'ok');
  return { support, key };
}

/**
 * The push on/off switch for Settings and onboarding. Turning it on only ever happens from a tap, since
 * the browser's permission prompt needs one (and iOS refuses without one). It hides itself behind an
 * explanation when push isn't possible instead of failing.
 */
export function PushSetting() {
  const { support, key } = usePushAvailability();
  const me = useMe();

  if (support === 'unsupported') {
    return (
      <Note>This browser can’t show push notifications. Your inbox still has everything.</Note>
    );
  }
  if (support === 'needs-install') {
    return (
      <div className="space-y-3">
        <Note>
          On iPhone and iPad, push notifications work once Con-Fuzion is on your Home Screen. Your
          inbox still has everything until then.
        </Note>
        <AddToHomeScreenSteps />
      </div>
    );
  }
  if (key.isError) return <ErrorState error={key.error} onRetry={() => void key.refetch()} />;
  if (key.isPending || !me.data) return <p className="text-ink-muted text-sm">Checking…</p>;
  if (key.data === null) {
    return (
      <Note>Push notifications aren’t set up on this server. Your inbox still has everything.</Note>
    );
  }
  return <PushSwitch publicKey={key.data} pushEnabled={me.data.pushEnabled} />;
}

function Note({ children }: { children: string | string[] }) {
  return <p className="text-ink-muted text-sm">{children}</p>;
}

function PushSwitch({ publicKey, pushEnabled }: { publicKey: string; pushEnabled: boolean }) {
  const subscribed = useHasSubscription(true);
  const turnOn = useTurnPushOn();
  const turnOff = useTurnPushOff();
  const busy = turnOn.isPending || turnOff.isPending;
  // Read each render: it changes when the user re-enables notifications in the browser's settings.
  const blocked = Notification.permission === 'denied' || turnOn.data === 'denied';
  const on = pushEnabled && subscribed.data === true;

  return (
    <div className="space-y-3">
      <p className="text-sm">
        {on
          ? 'Push is on for this device. You get a notification when you’re offered a spot, an event changes or is cancelled, and before an event starts.'
          : 'Get a notification on this device when you’re offered a spot, an event changes or is cancelled, and before an event starts. Notifications never show locations or names.'}
      </p>
      {on ? (
        <button
          type="button"
          className="btn btn-secondary"
          disabled={busy}
          onClick={() => turnOff.mutate()}
        >
          Turn off push
        </button>
      ) : (
        <button
          type="button"
          className="btn btn-primary"
          disabled={busy || blocked || subscribed.isPending}
          // The permission prompt and the subscription start from this tap, never from loading the page.
          onClick={() => {
            const attempt = subscribeFromTap(publicKey);
            attempt.catch(() => undefined); // mutate() reports the failure
            turnOn.mutate(attempt);
          }}
        >
          Turn on push
        </button>
      )}
      {blocked && (
        <p role="status" className="text-sm">
          Notifications are blocked for Con-Fuzion in this browser. To turn them on, allow them for
          this site in the browser’s site settings (the lock or settings icon next to the address
          bar), then come back and tap “Turn on push”.
        </p>
      )}
      {turnOn.data === 'dismissed' && (
        <p role="status" className="text-ink-muted text-sm">
          You closed the browser’s permission prompt, so push is still off.
        </p>
      )}
      {turnOn.isError && <ErrorState error={turnOn.error} />}
      {turnOff.isError && <ErrorState error={turnOff.error} />}
    </div>
  );
}
