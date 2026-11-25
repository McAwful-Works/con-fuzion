import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { InstallHint } from '../features/install/InstallHint';
import { installHintDismissed, useInstallHint } from '../features/install/install';
import { PushSetting, usePushAvailability } from '../features/push/PushSetting';
import { PrivacyNote, ServerList } from '../features/servers/ServerList';

type Step = 'servers' | 'notifications' | 'install';

/**
 * Servers, then notifications, then an install hint. A step only appears when it has something to offer
 * here: no push step where push can't work, and no install step once the app is installed, the hint was
 * dismissed, or the browser has no way to install. The last step ends with Continue.
 */
export function Onboarding() {
  const navigate = useNavigate();
  const installHint = useInstallHint();
  const { support, key } = usePushAvailability();
  // The step is kept by name, so a step that drops out of the list while it's showing (the install prompt
  // was used, or the server turned out to have no push keys) doesn't move the user elsewhere.
  const [step, setStep] = useState<Step>('servers');

  // On iOS outside the Home Screen, the install step explains push, so there is no separate push step.
  const pushStep = support === 'ok' && !key.isError && key.data !== null;
  const steps: Step[] = [
    'servers',
    ...(pushStep ? (['notifications'] as const) : []),
    ...(installHint ? (['install'] as const) : []),
  ];
  const at = steps.indexOf(step);
  const next = at === -1 ? undefined : steps[at + 1];

  return (
    <section className="space-y-4">
      {step === 'servers' && (
        <>
          <h1 className="text-2xl font-semibold">Where can people find you?</h1>
          <PrivacyNote />
          <ServerList />
          <p className="text-ink-muted text-sm">You can change this any time in Settings.</p>
        </>
      )}
      {step === 'notifications' && (
        <>
          <h1 className="text-2xl font-semibold">Want a heads-up?</h1>
          <PushSetting />
          <p className="text-ink-muted text-sm">You can change this any time in Settings.</p>
        </>
      )}
      {step === 'install' && (
        <>
          <h1 className="text-2xl font-semibold">One last thing</h1>
          <InstallHint onDismiss={() => void navigate('/')} />
        </>
      )}
      {next === undefined ? (
        <Link
          to="/"
          className="btn btn-primary w-full sm:w-auto"
          // Leaving the install step by any way counts as dismissing it: it isn't shown again.
          onClick={step === 'install' ? () => installHintDismissed.set() : undefined}
        >
          Continue
        </Link>
      ) : (
        <button
          type="button"
          className="btn btn-primary w-full sm:w-auto"
          onClick={() => setStep(next)}
        >
          Next
        </button>
      )}
    </section>
  );
}
