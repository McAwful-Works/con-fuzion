import { useNavigate } from 'react-router';
import { useSignOut } from '../api/me';
import { ErrorState } from '../components/ErrorState';
import { PushSetting } from '../features/push/PushSetting';
import { BlockedList, BlockedNote } from '../features/social/BlockedList';
import { PrivacyNote, ServerList } from '../features/servers/ServerList';

export function Settings() {
  const navigate = useNavigate();
  const signOut = useSignOut(false);
  const logoutEverywhere = useSignOut(true);
  const leave = { onSuccess: () => void navigate('/welcome', { replace: true }) };

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-semibold">Settings</h1>

      <section aria-labelledby="servers-heading" className="space-y-4">
        <h2 id="servers-heading" className="text-xl font-semibold">
          Servers
        </h2>
        <PrivacyNote />
        <ServerList />
      </section>

      <section aria-labelledby="notifications-heading" className="space-y-4">
        <h2 id="notifications-heading" className="text-xl font-semibold">
          Notifications
        </h2>
        <PushSetting />
      </section>

      <section aria-labelledby="blocked-heading" className="space-y-4">
        <h2 id="blocked-heading" className="text-xl font-semibold">
          Blocked
        </h2>
        <BlockedNote />
        <BlockedList />
      </section>

      <section aria-labelledby="account-heading" className="space-y-4">
        <h2 id="account-heading" className="text-xl font-semibold">
          Account
        </h2>
        <div className="flex flex-col gap-3 sm:flex-row">
          <button
            type="button"
            className="btn btn-secondary"
            disabled={signOut.isPending || logoutEverywhere.isPending}
            onClick={() => signOut.mutate(undefined, leave)}
          >
            Sign out
          </button>
          <button
            type="button"
            className="btn btn-secondary"
            disabled={signOut.isPending || logoutEverywhere.isPending}
            onClick={() => logoutEverywhere.mutate(undefined, leave)}
          >
            Log out everywhere
          </button>
        </div>
        <p className="text-ink-muted text-sm">
          “Log out everywhere” ends your sessions on every device, including this one.
        </p>
        {signOut.isError && <ErrorState error={signOut.error} />}
        {logoutEverywhere.isError && <ErrorState error={logoutEverywhere.error} />}
      </section>
    </div>
  );
}
