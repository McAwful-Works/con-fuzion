import { lazy, Suspense } from 'react';
import { useRouteError } from 'react-router';
import { isNotThere } from '../../api/client';
import { isModerator, useMe } from '../../api/me';
import { ErrorState } from '../../components/ErrorState';
import { NotFound } from '../../components/NotFound';
import { Skeleton } from '../../components/Skeleton';

// A separate chunk, left out of the service worker's precache too (vite.config.ts).
const AdminArea = lazy(() => import('./AdminArea'));

/** `/admin`. The chunk is fetched only for a moderator or admin; anyone else gets the not-found page. */
export function AdminRoute() {
  const me = useMe();
  if (!isModerator(me.data)) return <NotFound />;
  return (
    <Suspense
      fallback={
        <div role="status" aria-label="Loading moderation">
          <Skeleton className="h-24 w-full" />
        </div>
      }
    >
      <AdminArea />
    </Suspense>
  );
}

/**
 * A 403 or 404 from any admin call is the not-found page, with no details. Anything else, such as the
 * chunk failing to load after a new release, offers a reload.
 */
export function AdminError() {
  const error = useRouteError();
  if (isNotThere(error)) return <NotFound />;
  return <ErrorState error={error} onRetry={() => window.location.reload()} />;
}
