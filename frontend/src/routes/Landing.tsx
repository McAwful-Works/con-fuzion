import { useEffect, useState } from 'react';
import { Navigate, useSearchParams } from 'react-router';
import { SIGN_IN_URL, useMe } from '../api/me';
import { pendingInvite } from '../features/social/pendingInvite';

export function Landing() {
  const me = useMe();
  const [params, setParams] = useSearchParams();
  const [hasInvite] = useState(() => pendingInvite.get() !== null);
  // Remember the failure, then drop `?login=failed` so a reload doesn't show it again.
  const [failed] = useState(() => params.get('login') === 'failed');
  useEffect(() => {
    if (!params.has('login')) return;
    const rest = new URLSearchParams(params);
    rest.delete('login');
    setParams(rest, { replace: true });
  }, [params, setParams]);

  if (me.data) return <Navigate to="/" replace />;

  return (
    <section className="space-y-6 py-8">
      <h1 className="text-3xl font-semibold tracking-tight">Con-Fuzion</h1>
      {failed && (
        <p role="alert" className="border-line bg-surface-raised rounded-card border p-4">
          Sign-in didn’t complete. Please try again.
        </p>
      )}
      {hasInvite && (
        <p className="border-line bg-surface-raised rounded-card border p-4">
          Sign in to answer your friend’s invite.
        </p>
      )}
      <p className="text-lg">Host convention meetups and open them to the people you choose.</p>
      {/* A normal link: sign-in is a full-page redirect through Discord, not an API call. */}
      <a href={SIGN_IN_URL} className="btn bg-discord text-discord-ink w-full sm:w-auto">
        Sign in with Discord
      </a>
      <p className="text-ink-muted text-sm">
        Discord is the only way to sign in. We ask only for your name, avatar and the list of
        servers you’re in.
      </p>
    </section>
  );
}
