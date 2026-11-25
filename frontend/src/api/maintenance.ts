import { useSyncExternalStore } from 'react';

/**
 * Maintenance mode (D-015 rule 16): every `/api` route answers 503. The client records that once and
 * the app shell swaps the page for "back soon". It lives outside React Query because any call, a
 * query or a mutation, can be the first to find out.
 */
let down = false;
const listeners = new Set<() => void>();

export function markUnavailable() {
  if (down) return;
  down = true;
  listeners.forEach((listener) => listener());
}

/** Used by tests to reset the flag. The app itself leaves it set until the page reloads. */
export function clearUnavailable() {
  if (!down) return;
  down = false;
  listeners.forEach((listener) => listener());
}

export function useUnavailable(): boolean {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => down,
  );
}
