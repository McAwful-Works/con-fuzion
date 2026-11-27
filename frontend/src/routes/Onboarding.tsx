import { ChevronRight } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { Wordmark } from '../components/shell/places';
import { useGoBack } from '../components/shell/useGoBack';
import { InstallHint } from '../features/install/InstallHint';
import { installHintDismissed, useInstallHint } from '../features/install/install';
import { useSetupVisit } from '../features/onboarding/useSetupVisit';
import { PushSetting, usePushAvailability } from '../features/push/PushSetting';
import { ServerList } from '../features/servers/ServerList';

const ORDER = ['servers', 'notifications', 'install'] as const;
type Step = (typeof ORDER)[number];

const STEPS: Record<Step, { label: string; title: string }> = {
  servers: { label: 'Servers', title: 'Where can people find you?' },
  notifications: { label: 'Notifications', title: 'Want a heads-up?' },
  install: { label: 'Install', title: 'One last thing' },
};

const HEADING = 'text-[2rem] leading-[1.1] font-extrabold lg:text-[2.625rem]';
const MAIN_BUTTON = 'btn btn-primary ml-auto flex w-full max-w-80';

/** The three-step setup after the first sign-in, and the servers step alone on any later visit. */
export function Onboarding() {
  return useSetupVisit() ? <Setup /> : <ServersAgain />;
}

/**
 * Servers, then notifications, then an install hint, without the app's navigation. A step only appears
 * when it has something to offer here: no push step where push can't work, and no install step once the
 * app is installed, the hint was dismissed, or the browser has no way to install.
 */
function Setup() {
  const navigate = useNavigate();
  const installHint = useInstallHint();
  const { support, key } = usePushAvailability();
  const [step, setStep] = useState<Step>('servers');
  const heading = useRef<HTMLHeadingElement>(null);

  const offered: Record<Step, boolean> = {
    servers: true,
    // On iOS outside the Home Screen, the install step explains push, so there is no push step.
    notifications: support === 'ok' && !key.isError && key.data !== null,
    install: installHint !== null,
  };
  // The step on screen stays, even once it has nothing left to offer (the install prompt was used,
  // or the server turned out to have no push keys).
  const steps = ORDER.filter((s) => s === step || offered[s]);
  const next = steps[steps.indexOf(step) + 1];

  // A new step starts at its heading: screen readers hear it, and a long server list doesn't leave
  // the next step scrolled out of view.
  useEffect(() => {
    if (step !== 'servers') heading.current?.focus();
  }, [step]);

  // Leaving the install step by any way counts as dismissing it: it isn't shown again.
  const leave = () => {
    if (step === 'install') installHintDismissed.set();
  };
  // Every choice saves as it's made, so skipping a step and continuing from it end the same way.
  const advance = () => {
    leave();
    if (next) setStep(next);
    else void navigate('/', { replace: true });
  };

  return (
    <>
      <header className="border-outline flex items-center gap-2 border-b-2 px-4 pt-[max(0.5rem,env(safe-area-inset-top))] pb-2 lg:px-12">
        {/* Not a link: Continue and Skip are the ways on, and they record what was dismissed. */}
        <Wordmark accent="text-accent-text" className="mr-auto" asText />
        <button type="button" className="btn btn-ghost btn-sm" onClick={advance}>
          Skip for now
        </button>
      </header>
      <div className="mx-auto w-full max-w-2xl flex-1 space-y-4 px-4 pt-4 pb-6 lg:flex-none lg:pt-10">
        {steps.length > 1 && <Progress steps={steps} current={step} />}
        <h1 ref={heading} tabIndex={-1} className={HEADING}>
          {STEPS[step].title}
        </h1>
        {step === 'servers' && <Servers />}
        {step === 'notifications' && (
          <>
            <div className="card p-4">
              <PushSetting />
            </div>
            <p className="hint">You can change this any time in Settings.</p>
          </>
        )}
        {step === 'install' && <InstallHint />}
      </div>
      {/* Pinned to the bottom of the screen on phones, under the content on desktop. */}
      <div
        data-actionbar
        className="border-outline bg-bar sticky bottom-0 z-10 border-t-2 px-4 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] backdrop-blur lg:static lg:mx-auto lg:w-full lg:max-w-2xl lg:border-0 lg:bg-transparent lg:pt-0 lg:pb-10 lg:backdrop-blur-none"
      >
        {next ? (
          <button type="button" className={MAIN_BUTTON} onClick={advance}>
            Continue
            <ChevronRight aria-hidden="true" className="size-4" />
          </button>
        ) : (
          // Replaces, so Back from the app doesn't start the setup again.
          <Link to="/" replace className={MAIN_BUTTON} onClick={leave}>
            Continue
            <ChevronRight aria-hidden="true" className="size-4" />
          </Link>
        )}
      </div>
    </>
  );
}

/** One segment for each step that applies here, filled up to the current one. */
function Progress({ steps, current }: { steps: readonly Step[]; current: Step }) {
  const at = steps.indexOf(current);
  return (
    <ol aria-label="Setup steps" className="flex gap-1.5">
      {steps.map((step, i) => (
        <li
          key={step}
          aria-current={step === current ? 'step' : undefined}
          className="flex-1 text-center first:text-left last:text-right"
        >
          <span
            aria-hidden="true"
            data-done={i <= at ? '' : undefined}
            className="border-control-outline bg-surface-sunken data-done:bg-accent block h-2.5 rounded-full border-2"
          />
          <span
            className={`mt-1.5 block text-xs font-bold ${step === current ? '' : 'text-ink-muted'}`}
          >
            {i + 1} · {STEPS[step].label}
          </span>
        </li>
      ))}
    </ol>
  );
}

/** A return to `/onboarding` from inside the app: the servers, and the way back. */
function ServersAgain() {
  const done = useGoBack('/you');
  return (
    <div className="space-y-4">
      <h1 className={HEADING}>{STEPS.servers.title}</h1>
      <Servers />
      <button type="button" className={MAIN_BUTTON} onClick={done}>
        Done
      </button>
    </div>
  );
}

function Servers() {
  return (
    <>
      <p className="text-ink-muted">
        Turn on the servers where you’re happy to be found. Members who’ve turned on the same server
        can find you, invite you to their meetups and add you to their circles. Everything starts
        off.
      </p>
      <ServerList />
      <p className="hint">
        You can change this any time in Settings → Servers. Discord never shows us your messages or
        a server’s member list.
      </p>
    </>
  );
}
