import { Link } from 'react-router';
import { PrivacyNote, ServerList } from '../features/servers/ServerList';

export function Onboarding() {
  return (
    <section className="space-y-4">
      <h1 className="text-2xl font-semibold">Where can people find you?</h1>
      <PrivacyNote />
      <ServerList />
      <p className="text-ink-muted text-sm">You can change this any time in Settings.</p>
      <Link to="/" className="btn btn-primary w-full sm:w-auto">
        Continue
      </Link>
    </section>
  );
}
