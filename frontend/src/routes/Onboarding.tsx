import { useState } from 'react';
import { Link } from 'react-router';
import { PushSetting, usePushAvailability } from '../features/push/PushSetting';
import { PrivacyNote, ServerList } from '../features/servers/ServerList';

type Step = 'servers' | 'notifications';

/**
 * Servers, then notifications. The notifications step only appears where push can work here. The last
 * step ends with Continue.
 */
export function Onboarding() {
  const { support, key } = usePushAvailability();
  // The step is kept by name, so a step that drops out of the list while it's showing (the server turned
  // out to have no push keys) doesn't move the user elsewhere.
  const [step, setStep] = useState<Step>('servers');

  // iOS outside the Home Screen can't do push at all, so it gets no push step (Settings explains why).
  const pushStep = support === 'ok' && !key.isError && key.data !== null;
  const steps: Step[] = ['servers', ...(pushStep ? (['notifications'] as const) : [])];
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
      {next === undefined ? (
        <Link to="/" className="btn btn-primary w-full sm:w-auto">
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
