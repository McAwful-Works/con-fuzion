import { ChevronRight, Plus, Settings, Shield } from 'lucide-react';
import { Link } from 'react-router';
import { isModerator, type Me } from '../../api/me';
import { formatUnread } from '../../api/notifications';
import {
  DISCOVER,
  INBOX,
  PEOPLE,
  PlaceLink,
  PLANS,
  Wordmark,
  inboxLabel,
  type Place,
} from './places';

const ITEM =
  'group rounded-control text-side-muted aria-[current]:bg-accent aria-[current]:text-accent-ink flex min-h-11 shrink-0 items-center gap-3 px-3 font-semibold';

const YOU: Place = { to: '/you', label: 'Settings', icon: Settings };
const MODERATION: Place = { to: '/admin', label: 'Moderation', icon: Shield };

function Item({ place, unread = 0 }: { place: Place; unread?: number }) {
  const Icon = place.icon;
  return (
    <PlaceLink
      to={place.to}
      className={ITEM}
      aria-label={place === INBOX ? inboxLabel(unread) : undefined}
    >
      <Icon aria-hidden="true" className="size-5 shrink-0" />
      {place.label}
      {unread > 0 && (
        <span
          aria-hidden="true"
          // Turned round on the current place, where coral would vanish into the coral.
          className="bg-accent text-accent-ink group-aria-[current]:bg-side group-aria-[current]:text-side-ink ml-auto min-w-6 rounded-full px-1.5 text-center text-xs leading-6 font-bold"
        >
          {formatUnread(unread)}
        </span>
      )}
    </PlaceLink>
  );
}

/** The navigation from 1024px up. The focus ring is the sidebar's own ink: coral is too faint on it. */
export function Sidebar({ user, unread }: { user: Me; unread: number }) {
  const name = user.globalName ?? user.username;
  return (
    <aside className="bg-side text-side-ink **:focus-visible:outline-side-ink sticky top-0 hidden h-dvh flex-col overflow-y-auto px-4 py-5 lg:flex">
      <Wordmark accent="text-accent" className="mb-4 shrink-0 self-start px-3" />
      <nav aria-label="Main" className="flex flex-col gap-1">
        <Item place={DISCOVER} />
        <Item place={PLANS} />
        <Item place={PEOPLE} />
        <Item place={INBOX} unread={unread} />
        <PlaceLink
          to="/events/new"
          className="btn btn-primary aria-[current]:outline-side-ink my-3.5 shrink-0 aria-[current]:outline-3 aria-[current]:outline-offset-2"
        >
          <Plus aria-hidden="true" className="size-5" />
          Host a meetup
        </PlaceLink>
        <div className="bg-side-line mx-1 mb-2 h-px shrink-0" />
        {isModerator(user) && <Item place={MODERATION} />}
        <Item place={YOU} />
      </nav>
      <div className="mt-auto shrink-0 pt-4">
        <p className="text-side-muted px-3 pb-3 text-xs">Free meetups for convention-goers.</p>
        <Link
          to={YOU.to}
          className="bg-side-raised rounded-control flex items-center gap-2.5 p-2.5"
        >
          <img src={user.avatarUrl} alt="" className="size-8 shrink-0 rounded-full" />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-bold">{name}</span>
            <span className="text-side-muted block truncate text-xs">@{user.username}</span>
          </span>
          <ChevronRight aria-hidden="true" className="text-side-muted size-4 shrink-0" />
        </Link>
      </div>
    </aside>
  );
}
