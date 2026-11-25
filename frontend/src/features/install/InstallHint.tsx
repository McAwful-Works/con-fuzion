import { Share } from 'lucide-react';
import { useState } from 'react';
import { installHintDismissed, useInstallHint, useInstallPrompt } from './install';

/** The Add to Home Screen steps for Safari on iPhone and iPad, where push only works from the Home Screen. */
export function AddToHomeScreenSteps() {
  return (
    <ol className="list-decimal space-y-1 pl-5 text-sm">
      <li>
        Tap the Share button{' '}
        <Share aria-hidden="true" className="inline size-4 align-text-bottom" /> in Safari.
      </li>
      <li>Choose “Add to Home Screen”.</li>
      <li>Open Con-Fuzion from your Home Screen.</li>
    </ol>
  );
}

/**
 * Onboarding's install step: the iOS steps, or an Install button where the browser offers one. It can be
 * dismissed, and a dismissal is remembered so it isn't shown again. Renders nothing when there's nothing to say.
 */
export function InstallHint({ onDismiss }: { onDismiss?: () => void }) {
  const kind = useInstallHint();
  const { install } = useInstallPrompt();
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
          <button type="button" className="btn btn-primary" onClick={() => void install()}>
            Install
          </button>
        </>
      )}
      <button type="button" className="btn btn-secondary" onClick={dismiss}>
        Not now
      </button>
    </section>
  );
}
