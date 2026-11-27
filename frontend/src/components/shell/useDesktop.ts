import { useSyncExternalStore } from 'react';

/** Tailwind's `lg`, where the shell switches to the sidebar. */
const DESKTOP = '(min-width: 64rem)';

function subscribe(onChange: () => void) {
  const media = window.matchMedia?.(DESKTOP);
  media?.addEventListener('change', onChange);
  return () => media?.removeEventListener('change', onChange);
}

/**
 * True from 1024px up. For content that only desktop has, so that phones don't load it; anything
 * that merely looks different belongs in CSS (`lg:`).
 */
export function useDesktop(): boolean {
  return useSyncExternalStore(subscribe, () => window.matchMedia?.(DESKTOP).matches ?? false);
}
