import { ChevronLeft, Shield } from 'lucide-react';
import { Link } from 'react-router';
import { isModerator, type Me } from '../../api/me';
import { Wordmark } from './places';
import { useGoBack } from './useGoBack';

const ICON_BUTTON = 'flex size-11 shrink-0 items-center justify-center rounded-full';

function BackButton({ parent }: { parent: string }) {
  const goBack = useGoBack(parent);
  return (
    <button type="button" aria-label="Back" className={`${ICON_BUTTON} -ml-2.5`} onClick={goBack}>
      <ChevronLeft aria-hidden="true" className="size-6" />
    </button>
  );
}

/**
 * The slim top bar. Phones have it on every screen. A visitor who isn't signed in has no sidebar, so
 * they get it at every width.
 */
export function TopBar({ user, back }: { user?: Me; back?: { title: string; parent: string } }) {
  return (
    <header
      data-topbar
      className={`bg-bar border-outline sticky top-0 z-10 flex items-center gap-2 border-b-2 px-4 pt-[max(0.5rem,env(safe-area-inset-top))] pb-2 backdrop-blur ${user ? 'lg:hidden' : ''}`}
    >
      {back ? (
        <>
          <BackButton parent={back.parent} />
          {/* Not a heading: the page below has its own. */}
          <p className="font-head min-w-0 flex-1 truncate text-lg font-extrabold">{back.title}</p>
        </>
      ) : (
        <>
          <Wordmark accent="text-accent-text" className="mr-auto" />
          {user && (
            <>
              {isModerator(user) && (
                <Link to="/admin" aria-label="Moderation" className={ICON_BUTTON}>
                  <Shield aria-hidden="true" className="size-5" />
                </Link>
              )}
              <Link to="/you" aria-label="You" className={ICON_BUTTON}>
                <img src={user.avatarUrl} alt="" className="size-8 rounded-full" />
              </Link>
            </>
          )}
        </>
      )}
    </header>
  );
}
