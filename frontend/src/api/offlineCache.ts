/** The service worker's cache of My schedule and `/api/me` (see `vite.config.ts`), read when offline. */
export const OFFLINE_CACHE = 'my-schedule';

const LAST_USER_KEY = 'cf-last-user';

/** Drops the saved responses. They belong to one signed-in user and must not outlive their session. */
export function clearOfflineCache(): void {
  if ('caches' in globalThis) void caches.delete(OFFLINE_CACHE);
}

/**
 * Called with each `/api/me` answer. When a different person is signed in than last time on this
 * device (a session expired, someone else signed in), the saved responses are the previous person's.
 */
export function forgetOtherUsersCache(userId: string): void {
  try {
    if (localStorage.getItem(LAST_USER_KEY) !== userId) {
      clearOfflineCache();
      localStorage.setItem(LAST_USER_KEY, userId);
    }
  } catch {
    // Storage is unavailable (private mode); sign-out and expiry still clear the cache.
  }
}
