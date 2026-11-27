import { WifiOff, X } from 'lucide-react';
import { useState } from 'react';
import { Outlet, useMatch, useMatches } from 'react-router';
import { isModerator, useMe } from '../api/me';
import { useUnavailable } from '../api/maintenance';
import { useUnreadCount } from '../api/notifications';
import { MaintenancePage } from '../features/maintenance/MaintenancePage';
import { useSetupVisit } from '../features/onboarding/useSetupVisit';
import { useOnline } from '../features/rsvp/useOnline';
import type { ShellPage } from './shell/page';
import { Sidebar } from './shell/Sidebar';
import { TabBar } from './shell/TabBar';
import { TopBar } from './shell/TopBar';

/** What the current route says about itself, if this viewer may be told. */
function useShellPage(moderator: boolean) {
  const match = useMatches().at(-1);
  const page = match?.handle as ShellPage | undefined;
  if (!match || !page || (page.moderatorsOnly && !moderator)) return undefined;
  return { ...page, parent: page.parent(match.params) };
}

/**
 * The frame around every screen. Signed in: a sidebar from 1024px up, and below that a top bar and a tab
 * bar. Signed out: the top bar and a footer. The setup after the first sign-in has no navigation and
 * brings its own header.
 */
export function AppShell() {
  const me = useMe();
  const user = me.data;
  const online = useOnline();
  const unavailable = useUnavailable();
  const [mfaDismissed, setMfaDismissed] = useState(false);
  const unread = useUnreadCount(Boolean(user)).data ?? 0;
  const page = useShellPage(isModerator(user));
  const onYou = useMatch('/you') !== null;
  const onWelcome = useMatch('/welcome') !== null;
  // Only once there's a user to set up: loading, errors and "Back soon" keep the usual frame.
  const setup = useSetupVisit() && me.isSuccess && !unavailable;
  const navigation = Boolean(user) && !setup;
  const tabs = navigation && !page?.detail;

  return (
    <div className={navigation ? 'lg:grid lg:grid-cols-[16.5rem_minmax(0,1fr)]' : undefined}>
      {user && !setup && <Sidebar user={user} unread={unread} />}
      <div
        className={`flex min-h-dvh min-w-0 flex-col ${tabs ? 'pb-[calc(4.5rem+env(safe-area-inset-bottom))] lg:pb-0' : ''}`}
      >
        {!setup && (
          <TopBar user={user} back={user && page} signIn={onWelcome && !user && !unavailable} />
        )}
        {!online && (
          <p
            role="status"
            className="bg-surface-inverse text-ink-inverse flex items-start gap-2.5 px-4 py-2.5 text-sm font-semibold lg:px-10"
          >
            <WifiOff aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            You’re offline. My plans shows what was saved last time. Joining, leaving and removing
            people need a connection.
          </p>
        )}
        {user?.mfaRequired && !mfaDismissed && !unavailable && (
          <div
            role="note"
            className="card mx-4 mt-4 flex items-start gap-2 py-1.5 pr-1.5 pl-4 text-sm lg:mx-10 lg:max-w-[47.5rem]"
          >
            <p className="flex-1 py-2">
              Your moderator tools are switched off because Discord two-factor authentication (2FA)
              is off on your account. Turn it on in Discord, then sign out and sign in again here.
            </p>
            <button
              type="button"
              aria-label="Dismiss this note"
              className="rounded-control flex size-11 shrink-0 items-center justify-center"
              onClick={() => setMfaDismissed(true)}
            >
              <X aria-hidden="true" className="size-5" />
            </button>
          </div>
        )}
        <main
          className={
            setup
              ? 'flex w-full flex-1 flex-col'
              : `mx-auto w-full max-w-3xl flex-1 px-4 py-6 has-[>[data-event-page]]:flex has-[>[data-event-page]]:flex-col has-[>[data-welcome-page]]:max-w-[80rem] lg:has-[>[data-welcome-page]]:px-12 ${user ? 'lg:mx-0 lg:max-w-[52.5rem] lg:px-10 lg:py-8 lg:has-[>[data-discover-page]]:max-w-[70rem] lg:has-[>[data-plans-page]]:max-w-[70rem] lg:has-[>[data-event-page]]:max-w-[70rem]' : ''}`
          }
        >
          {unavailable ? <MaintenancePage /> : <Outlet />}
        </main>
        {/* Signed in, this is in the sidebar, and on phones it's at the bottom of You. */}
        {!setup && (!user || onYou) && (
          <footer
            // On welcome the line starts where the page's 80rem column does.
            className={`border-line text-ink-muted border-t px-4 py-4 text-sm ${user ? 'lg:hidden' : ''} ${onWelcome ? 'lg:px-[max(3rem,calc(50%-37rem))]' : ''}`}
          >
            Free meetups for convention-goers.
          </footer>
        )}
      </div>
      {tabs && <TabBar unread={unread} />}
    </div>
  );
}
