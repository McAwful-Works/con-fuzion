import { useEffect } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { useAcceptInvite, useInvitePreview } from '../api/social';
import { ErrorState } from '../components/ErrorState';
import { Skeleton } from '../components/Skeleton';
import { pendingInvite } from '../features/social/pendingInvite';

/** Every unusable link looks the same, so nothing here says why (D-011). */
function InvalidInvite() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">This invite link isn’t valid anymore</h1>
      <p className="text-ink-muted">
        It may have expired or been used already. Ask your friend for a new one.
      </p>
      <Link to="/" className="btn btn-secondary">
        Go to Con-Fuzion
      </Link>
    </div>
  );
}

const isStatus = (error: unknown, status: number) =>
  error instanceof ApiError && error.status === status;

/**
 * `/invite/:token`, the page a QR code or invite link opens. It is not behind `RequireAuth`: a
 * signed-out visitor's preview is a 401, so the token waits in `sessionStorage` while they sign in.
 */
export function Invite() {
  const { token = '' } = useParams();
  const navigate = useNavigate();
  const preview = useInvitePreview(token);
  const accept = useAcceptInvite(token);

  const signedOut = isStatus(preview.error, 401);
  useEffect(() => {
    if (signedOut) pendingInvite.set(token);
    // The invite has been seen, so a later sign-in must not bring it back.
    else if (!preview.isPending) pendingInvite.clear();
  }, [signedOut, preview.isPending, token]);

  if (preview.isPending) {
    return (
      <div role="status" aria-label="Loading the invite" className="space-y-3">
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }
  if (signedOut) return <Navigate to="/welcome" replace />;
  if (isStatus(preview.error, 404) || isStatus(preview.error, 400) || isStatus(accept.error, 404)) {
    return <InvalidInvite />;
  }
  if (preview.isError) {
    return <ErrorState error={preview.error} onRetry={() => void preview.refetch()} />;
  }

  const { inviter } = preview.data;
  return (
    <section className="space-y-6">
      <div className="flex items-center gap-3">
        <img src={inviter.avatarUrl} alt="" className="size-14 shrink-0 rounded-full" />
        <h1 className="text-2xl font-semibold break-words">
          {inviter.displayName} invited you to be friends
        </h1>
      </div>
      <p className="text-ink-muted">
        Friends can invite each other to meetups. You can remove a friend or block them any time.
      </p>
      <div className="flex flex-col gap-3 sm:flex-row">
        <button
          type="button"
          className="btn btn-primary"
          disabled={accept.isPending}
          onClick={() => accept.mutate(undefined, { onSuccess: () => void navigate('/people') })}
        >
          Accept
        </button>
        <Link to="/" className="btn btn-secondary">
          Not now
        </Link>
      </div>
      {accept.isError && <ErrorState error={accept.error} />}
    </section>
  );
}
