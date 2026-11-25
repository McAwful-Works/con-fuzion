import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { InstallHint } from '../features/install/InstallHint';
import { useInstallHint } from '../features/install/install';
import { PushSetting, usePushAvailability } from '../features/push/PushSetting';
import { PrivacyNote, ServerList } from '../features/servers/ServerList';

type Step = 'servers' | 'notifications' | 'install';

/**
 * Servers, then notifications, then an install hint. A step only appears when it has something to
 * offer here: no push step where push can't work, and no install step once it's installed, dismissed,
 * or the browser has no way to install. The last step ends with Continue.
 */
export function Onboarding() {
  const navigate = useNavigate();
  const { support, key } = usePushAvailability();
  const installHint = useInstallHint();
  const [index, setIndex] = useState(0);

  // On iOS outside the Home Screen, the install step explains push, so there is no separate push step.
  const pushStep = support === 'ok' && !key.isError && key.data !== null;
  const steps: Step[] = [
    'servers',
    ...(pushStep ? (['notifications'] as const) : []),
    ...(installHint ? (['install'] as const) : []),
  ];
  const step = steps[Math.min(index, steps.length - 1)]!;
  const last = step === steps[steps.length - 1];

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
      {last ? (
        <Link to="/" className="btn btn-primary w-full sm:w-auto">
          Continue
        </Link>
      ) : (
        <button
          type="button"
          className="btn btn-primary w-full sm:w-auto"
          onClick={() => setIndex(index + 1)}
        >
          Next
        </button>
      )}
    </section>
  );
}
