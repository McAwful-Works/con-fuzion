import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError, shouldRetry } from './client';
import { clearOfflineCache } from './offlineCache';

/**
 * A 401 from any call means the session is gone. Marking `/api/me` stale makes the auth gate
 * refetch it, get its own 401, and send the user to the landing page.
 */
export function createQueryClient() {
  const expire = (error: unknown) => {
    if (error instanceof ApiError && error.status === 401) {
      // The session is over, so the saved copy of its schedule must go too.
      clearOfflineCache();
      void queryClient.invalidateQueries({ queryKey: ['me'] });
    }
  };
  const queryClient: QueryClient = new QueryClient({
    // `/api/me` is the gate itself: its own 401 must not trigger another refetch of it.
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (query.queryKey[0] !== 'me') expire(error);
        else if (error instanceof ApiError && error.status === 401) clearOfflineCache();
      },
    }),
    mutationCache: new MutationCache({ onError: expire }),
    defaultOptions: { queries: { retry: shouldRetry } },
  });
  return queryClient;
}
