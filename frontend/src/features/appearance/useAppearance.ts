import { useState } from 'react';

export type Appearance = 'light' | 'dark' | 'automatic';

/** The storage key and the event name. Keep in sync with public/appearance.js. */
const KEY = 'cf-appearance';

function readAppearance(): Appearance {
  try {
    const stored = localStorage.getItem(KEY);
    return stored === 'light' || stored === 'dark' ? stored : 'automatic';
  } catch {
    return 'automatic';
  }
}

/**
 * The Light, Dark or Automatic choice, stored on this device only (D-017). public/appearance.js
 * applies it, before the first paint and on each change, and follows the device while it's
 * Automatic.
 */
export function useAppearance() {
  const [appearance, setState] = useState(readAppearance);

  function setAppearance(next: Appearance) {
    try {
      if (next === 'automatic') localStorage.removeItem(KEY);
      else localStorage.setItem(KEY, next);
    } catch {
      // Storage is off: the choice lasts until the page reloads.
    }
    setState(next);
    window.dispatchEvent(new CustomEvent(KEY, { detail: next }));
  }

  return { appearance, setAppearance };
}
