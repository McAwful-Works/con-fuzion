import { Link, Outlet } from 'react-router';
import { useMe } from '../api/me';

export function AppShell() {
  const me = useMe();
  const user = me.data;

  return (
    <div className="flex min-h-dvh flex-col">
      <header className="border-line flex items-center justify-between gap-3 border-b px-4 py-2">
        <Link to="/" className="text-lg font-semibold tracking-tight">
          Con-Fuzion
        </Link>
        {user && (
          <Link
            to="/settings"
            className="focus-visible:outline-accent flex min-h-11 items-center gap-2 rounded-control focus-visible:outline-2"
          >
            <img src={user.avatarUrl} alt="" className="size-8 rounded-full" />
            <span className="max-w-40 truncate text-sm font-medium">
              {user.globalName ?? user.username}
            </span>
            <span className="sr-only">Settings</span>
          </Link>
        )}
      </header>
      <main className="mx-auto w-full max-w-3xl flex-1 px-4 py-6">
        <Outlet />
      </main>
      <footer className="border-line text-ink-muted border-t px-4 py-4 text-sm">
        Free meetups for convention-goers.
      </footer>
    </div>
  );
}
