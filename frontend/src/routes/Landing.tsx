import { Navigate } from 'react-router';
import { SIGN_IN_URL, useMe } from '../api/me';

export function Landing() {
  const me = useMe();
  if (me.data) return <Navigate to="/" replace />;

  return (
    <section className="space-y-6 py-8">
      <h1 className="text-3xl font-semibold tracking-tight">Con-Fuzion</h1>
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
