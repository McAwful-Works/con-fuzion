import { Share } from 'lucide-react';

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
