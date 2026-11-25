import { useState } from 'react';
import { AddToHomeScreenSteps } from '../push/AddToHomeScreenSteps';
import { installHintDismissed, useInstallHint, useInstallPrompt } from './install';

/**
 * Onboarding's install step: the iOS steps, or an Install button where the browser offers one. It can be
 * dismissed, and a dismissal is remembered so it isn't shown again. Renders nothing when there's nothing to say.
 */
export function InstallHint({ onDismiss }: { onDismiss?: () => void }) {
  const live = useInstallHint();
  // Fixed on first render: using the browser's prompt makes it unavailable, and the step shouldn't empty.
  const [kind] = useState(live);
  const { install } = useInstallPrompt();
  const [asked, setAsked] = useState(false);
  // `useInstallHint` reads localStorage once per render; this makes a dismissal take effect at once.
  const [dismissedNow, setDismissedNow] = useState(false);
  if (!kind || dismissedNow) return null;

  const dismiss = () => {
    installHintDismissed.set();
    setDismissedNow(true);
    onDismiss?.();
  };

  return (
    <section
      aria-labelledby="install-heading"
      className="border-line bg-surface-raised rounded-card space-y-3 border p-4"
    >
      <h2 id="install-heading" className="text-lg font-semibold">
        Add Con-Fuzion to your device
      </h2>
      {kind === 'ios' ? (
        <>
          <p className="text-ink-muted text-sm">
            Installing it gives you an app icon, and on iPhone and iPad it’s the only way to get
            push notifications.
          </p>
          <AddToHomeScreenSteps />
        </>
      ) : (
        <>
          <p className="text-ink-muted text-sm">
            Install it for an app icon and a window of its own.
          </p>
          {asked ? (
            <p role="status" className="text-sm">
              If you installed it, you’ll find Con-Fuzion with your other apps.
            </p>
          ) : (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                setAsked(true);
                void install();
              }}
            >
              Install
            </button>
          )}
        </>
      )}
      <button type="button" className="btn btn-secondary" onClick={dismiss}>
        Not now
      </button>
    </section>
  );
}
