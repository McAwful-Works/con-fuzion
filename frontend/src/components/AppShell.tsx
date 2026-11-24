import { Outlet } from 'react-router';

export function AppShell() {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="border-line border-b px-4 py-3">
        <span className="text-lg font-semibold tracking-tight">Con-Fuzion</span>
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
