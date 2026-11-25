import { Bell } from 'lucide-react';
import { Link, Outlet } from 'react-router';
import { useMe } from '../api/me';
import { formatUnread, useUnreadCount } from '../api/notifications';
import { useOnline } from '../features/rsvp/useOnline';

export function AppShell() {
  const me = useMe();
  const user = me.data;
  const online = useOnline();
  const unread = useUnreadCount(Boolean(user)).data ?? 0;

  return (
    <div className="flex min-h-dvh flex-col">
      <header className="border-line flex items-center justify-between gap-3 border-b px-4 py-2">
        <Link to="/" className="text-lg font-semibold tracking-tight">
          Con-Fuzion
        </Link>
        {user && (
          <div className="flex items-center gap-1">
            <Link
              to="/notifications"
              aria-label={
                unread > 0 ? `Notifications, ${formatUnread(unread)} unread` : 'Notifications'
              }
              className="focus-visible:outline-accent rounded-control relative flex min-h-11 min-w-11 items-center justify-center focus-visible:outline-2"
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
              className="focus-visible:outline-accent rounded-control flex min-h-11 items-center gap-2 focus-visible:outline-2"
            >
              <img src={user.avatarUrl} alt="" className="size-8 rounded-full" />
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
      <main className="mx-auto w-full max-w-3xl flex-1 px-4 py-6">
        <Outlet />
      </main>
      <footer className="border-line text-ink-muted border-t px-4 py-4 text-sm">
        Free meetups for convention-goers.
      </footer>
    </div>
  );
}
