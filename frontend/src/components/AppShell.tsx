import { Bell, Shield, X } from 'lucide-react';
import { Link, Outlet } from 'react-router';
import { useState } from 'react';
import { isModerator, useMe } from '../api/me';
import { useUnavailable } from '../api/maintenance';
import { formatUnread, useUnreadCount } from '../api/notifications';
import { MaintenancePage } from '../features/maintenance/MaintenancePage';
import { useOnline } from '../features/rsvp/useOnline';

export function AppShell() {
  const me = useMe();
  const user = me.data;
  const online = useOnline();
  const unavailable = useUnavailable();
  const [mfaDismissed, setMfaDismissed] = useState(false);
  const unread = useUnreadCount(Boolean(user)).data ?? 0;

  return (
    <div className="flex min-h-dvh flex-col">
      <header className="border-line flex items-center justify-between gap-3 border-b px-4 py-2">
        <Link to="/" className="text-lg font-semibold tracking-tight whitespace-nowrap">
          Con-Fuzion
        </Link>
        {user && (
          <div className="flex min-w-0 items-center gap-1">
            {isModerator(user) && (
              <Link
                to="/admin"
                aria-label="Moderation"
                className="focus-visible:outline-focus rounded-control flex min-h-11 min-w-11 items-center justify-center focus-visible:outline-2"
              >
                <Shield aria-hidden="true" className="size-5" />
              </Link>
            )}
            <Link
              to="/notifications"
              aria-label={
                unread > 0 ? `Notifications, ${formatUnread(unread)} unread` : 'Notifications'
              }
              className="focus-visible:outline-focus rounded-control relative flex min-h-11 min-w-11 items-center justify-center focus-visible:outline-2"
            >
              <Bell aria-hidden="true" className="size-5" />
              {unread > 0 && (
                <span
                  aria-hidden="true"
                  className="bg-accent text-accent-ink absolute top-1 right-0 min-w-5 rounded-full px-1 text-center text-xs font-semibold"
                >
                  {formatUnread(unread)}
                </span>
              )}
            </Link>
            <Link
              to="/settings"
              className="focus-visible:outline-focus rounded-control flex min-h-11 min-w-0 items-center gap-2 focus-visible:outline-2"
            >
              <img src={user.avatarUrl} alt="" className="size-8 shrink-0 rounded-full" />
              <span className="max-w-40 truncate text-sm font-medium">
                {user.globalName ?? user.username}
              </span>
              <span className="sr-only">Settings</span>
            </Link>
          </div>
        )}
      </header>
      {!online && (
        <p role="status" className="bg-surface-inverse text-ink-inverse px-4 py-2 text-sm">
          You’re offline. My schedule shows what was saved last time. Joining, leaving and removing
          people need a connection.
        </p>
      )}
      {user?.mfaRequired && !mfaDismissed && !unavailable && (
        <div
          role="note"
          className="bg-surface-raised border-line flex items-start gap-2 border-b px-4 py-3 text-sm"
        >
          <p className="flex-1">
            Your moderator tools are switched off because Discord two-factor authentication (2FA) is
            off on your account. Turn it on in Discord, then sign out and sign in again here.
          </p>
          <button
            type="button"
            aria-label="Dismiss this note"
            className="rounded-control flex min-h-11 min-w-11 shrink-0 items-center justify-center"
            onClick={() => setMfaDismissed(true)}
          >
            <X aria-hidden="true" className="size-5" />
          </button>
        </div>
      )}
      <main className="mx-auto w-full max-w-3xl flex-1 px-4 py-6">
        {unavailable ? <MaintenancePage /> : <Outlet />}
      </main>
      <footer className="border-line text-ink-muted border-t px-4 py-4 text-sm">
        Free meetups for convention-goers.
      </footer>
    </div>
  );
}
