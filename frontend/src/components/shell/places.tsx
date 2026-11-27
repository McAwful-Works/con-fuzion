import { Bell, CalendarCheck, Compass, Users, type LucideIcon } from 'lucide-react';
import type { ComponentProps } from 'react';
import { Link, NavLink, useMatch } from 'react-router';
import { formatUnread } from '../../api/notifications';

export interface Place {
  to: string;
  label: string;
  icon: LucideIcon;
}

export const DISCOVER: Place = { to: '/', label: 'Discover', icon: Compass };
export const PLANS: Place = { to: '/plans', label: 'My plans', icon: CalendarCheck };
export const PEOPLE: Place = { to: '/people', label: 'People', icon: Users };
export const INBOX: Place = { to: '/inbox', label: 'Inbox', icon: Bell };

/** A navigation link with `aria-current` on the place you're in. A circle's page is part of People. */
export function PlaceLink({ to, ...props }: ComponentProps<typeof Link> & { to: string }) {
  const inCircle = useMatch('/circles/:circleId') !== null;
  if (inCircle && to === PEOPLE.to) return <Link to={to} aria-current="true" {...props} />;
  return <NavLink to={to} end={to === DISCOVER.to} {...props} />;
}

/** The Inbox link's accessible name, which carries the unread count that the badge shows. */
export function inboxLabel(unread: number): string | undefined {
  return unread > 0 ? `${INBOX.label}, ${formatUnread(unread)} unread` : undefined;
}

/** The wordmark, as a link to Discover. `accent` is the coral that reads on its background. */
export function Wordmark({ accent, className = '' }: { accent: string; className?: string }) {
  return (
    <Link
      to={DISCOVER.to}
      className={`font-head rounded-control inline-flex min-h-11 items-center text-xl font-extrabold tracking-tight whitespace-nowrap ${className}`}
    >
      <span>
        Con<span className={accent}>-Fuzion</span>
      </span>
    </Link>
  );
}
