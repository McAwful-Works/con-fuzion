import { Plus } from 'lucide-react';
import { formatUnread } from '../../api/notifications';
import { DISCOVER, INBOX, PEOPLE, PlaceLink, PLANS, inboxLabel, type Place } from './places';

const TAB =
  'group text-ink-muted rounded-control relative flex min-h-12 flex-col items-center justify-end gap-0.5 text-[0.6875rem] font-semibold';

function Tab({ place, unread = 0 }: { place: Place; unread?: number }) {
  const Icon = place.icon;
  return (
    <PlaceLink
      to={place.to}
      // Heavier as well as darker, so the current tab doesn't depend on color alone.
      className={`${TAB} aria-[current]:text-ink aria-[current]:font-extrabold`}
      aria-label={place === INBOX ? inboxLabel(unread) : undefined}
    >
      <Icon aria-hidden="true" className="group-aria-[current]:text-accent-text size-5.5" />
      {place.label}
      {unread > 0 && (
        <span
          aria-hidden="true"
          className="bg-accent text-accent-ink border-surface absolute -top-0.5 left-1/2 ml-1 min-w-5 rounded-full border-2 px-1 text-center text-[0.6875rem] leading-4 font-bold"
        >
          {formatUnread(unread)}
        </span>
      )}
    </PlaceLink>
  );
}

/** The navigation below 1024px, with Host raised in the middle. */
export function TabBar({ unread }: { unread: number }) {
  return (
    <nav
      aria-label="Main"
      data-tabbar
      className="bg-bar border-outline fixed inset-x-0 bottom-0 z-10 grid grid-cols-5 border-t-2 px-1.5 pt-1.5 pb-[max(0.375rem,env(safe-area-inset-bottom))] backdrop-blur lg:hidden"
    >
      <Tab place={DISCOVER} />
      <Tab place={PLANS} />
      <PlaceLink to="/events/new" className={TAB}>
        <span className="bg-accent text-accent-ink border-outline rounded-fab shadow-control -mt-6 flex size-12 items-center justify-center border-2">
          <Plus aria-hidden="true" className="size-6" />
        </span>
        Host
      </PlaceLink>
      <Tab place={PEOPLE} />
      <Tab place={INBOX} unread={unread} />
    </nav>
  );
}
