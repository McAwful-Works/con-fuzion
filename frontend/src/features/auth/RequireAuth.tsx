import { Navigate, Outlet, useLocation } from 'react-router';
import { ApiError } from '../../api/client';
import { useMe, useSyncTimeZone } from '../../api/me';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { invitePath, pendingInvite } from '../social/pendingInvite';

/** Layout route for every signed-in screen. A 401 from `GET /api/me` goes to the landing page. */
export function RequireAuth() {
  const me = useMe();
  useSyncTimeZone(me.data);
  const { pathname } = useLocation();

  if (me.error instanceof ApiError && me.error.status === 401) {
    return <Navigate to="/welcome" replace />;
  }
  if (me.isError) return <ErrorState error={me.error} onRetry={() => void me.refetch()} />;
  if (!me.data) {
    return (
      <div role="status" aria-label="Loading" className="space-y-3">
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }
  // Signed in from an invite link: finish onboarding first, then land on the accept page.
  const waiting = pendingInvite.get();
  if (waiting && pathname !== '/onboarding' && !pathname.startsWith('/invite/')) {
    return <Navigate to={invitePath(waiting)} replace />;
  }
  return <Outlet />;
}
