import { Bell, Smartphone } from 'lucide-react';
import { useId, useState } from 'react';
import { useAppearance, type Appearance } from '../appearance/useAppearance';
import { useInstallPrompt } from '../install/install';
import { isStandalone } from '../push/browserPush';
import { PushSetting } from '../push/PushSetting';
import { GroupHeading, RowLabel } from './rows';

/** Push for this device: the same setting, and the same explanations, as the first-run setup. */
export function PushRow() {
  return (
    <li className="space-y-3 px-3.5 py-3">
      <div className="flex items-center gap-3">
        <RowLabel icon={Bell} title="Notifications on this device" />
      </div>
      <PushSetting />
    </li>
  );
}

/**
 * Where the browser offers to install the app. Unlike the setup's hint, dismissing it doesn't hide
 * this: nobody is nudged by their own settings. On iPhone and iPad the push row has the steps.
 */
export function InstallRow() {
  const { available, install } = useInstallPrompt();
  // Stays once pressed, though the browser's offer is then used up.
  const [asked, setAsked] = useState(false);
  if (!asked && (!available || isStandalone())) return null;

  return (
    <li className="flex flex-wrap items-center gap-3 px-3.5 py-3">
      <RowLabel
        icon={Smartphone}
        title="Install Con-Fuzion"
        sub="An app icon and a window of its own."
      />
      {asked ? (
        <p role="status" className="basis-full text-sm">
          If you installed it, you’ll find Con-Fuzion with your other apps.
        </p>
      ) : (
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={() => {
            setAsked(true);
            void install();
          }}
        >
          Install
        </button>
      )}
    </li>
  );
}

const APPEARANCES: { value: Appearance; label: string }[] = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'automatic', label: 'Automatic' },
];

/** Light, Dark or Automatic, kept on this device only (D-017). */
export function AppearanceChoice() {
  const { appearance, setAppearance } = useAppearance();
  const id = useId();
  return (
    <section aria-labelledby={id} className="space-y-2">
      <GroupHeading id={id}>Appearance</GroupHeading>
      <div role="radiogroup" aria-labelledby={id} aria-describedby={`${id}-hint`} className="seg">
        {APPEARANCES.map(({ value, label }) => (
          <label key={value} className="seg-item relative cursor-pointer">
            <input
              type="radio"
              name={id}
              value={value}
              checked={appearance === value}
              onChange={() => setAppearance(value)}
              className="sr-only"
            />
            {label}
          </label>
        ))}
      </div>
      <p id={`${id}-hint`} className="hint px-0.5">
        Automatic follows your device. Saved on this device only.
      </p>
    </section>
  );
}
