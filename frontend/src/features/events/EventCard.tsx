import {
  Ban,
  Check,
  Clock,
  Hourglass,
  Info,
  Lock,
  MapPin,
  Sparkles,
  Timer,
  Users,
  type LucideIcon,
} from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import type { Event } from '../../api/events';
import { startClock } from './cardTime';
import { eventTimes } from './time';
import { useViewerZone } from './useViewerZone';

export const HIDDEN_LOCATION_TEXT = 'Location shared with confirmed attendees';

/** Why the viewer can see this event (D-017). The host gets none: it's their own. */
function reasons(event: Event): string[] {
  return (event.visibleBecause ?? []).flatMap((reason) => {
    if (reason.type === 'GUILD') return reason.guildName ? [reason.guildName] : [];
    return [reason.type === 'FRIEND' ? 'Friend of the host' : 'Invited'];
  });
}

interface Status {
  text: string;
  /** The `pill-*` utility. */
  variant: string;
  icon?: LucideIcon;
}

/** Sign-up runs from its opening time, if it has one, to the start. A cancelled event takes no one. */
function takesSignups(event: Event, now: number): boolean {
  return (
    event.status === 'PUBLISHED' &&
    Date.parse(event.startsAt) > now &&
    (!event.signupOpensAt || Date.parse(event.signupOpensAt) <= now)
  );
}

/** Where the viewer stands, or that joining a full event means waiting. */
function status(event: Event, now: number): Status | undefined {
  if (event.isHost) return { text: 'Hosting', variant: 'pill-hosting', icon: Sparkles };
  switch (event.myStatus) {
    case 'CONFIRMED':
      return { text: 'Going', variant: 'pill-going', icon: Check };
    case 'WAITLISTED':
      return { text: 'Waitlisted', variant: 'pill-waitlisted', icon: Hourglass };
    case 'OFFERED':
      return { text: 'Spot offered', variant: 'pill-offered', icon: Timer };
    case 'REMOVED':
      return { text: 'Removed', variant: 'pill-full', icon: Ban };
    case 'NONE':
      return event.spotsLeft === 0 && takesSignups(event, now)
        ? { text: 'Full · join waitlist', variant: 'pill-full' }
        : undefined;
  }
}

function Pill({ text, variant, icon: Icon }: Status) {
  return (
    <span className={`pill ${variant}`}>
      {Icon && <Icon aria-hidden="true" />}
      {text}
    </span>
  );
}

function SpotsMeter({ capacity, spotsLeft }: Pick<Event, 'capacity' | 'spotsLeft'>) {
  if (capacity === null || spotsLeft === null) {
    return (
      <span className="meter">
        <Users aria-hidden="true" className="size-4 shrink-0" />
        No limit
      </span>
    );
  }
  const full = spotsLeft === 0;
  return (
    <span className="meter">
      <span aria-hidden="true" className="meter-bar" data-full={full ? '' : undefined}>
        <span style={{ width: `${((capacity - spotsLeft) / capacity) * 100}%` }} />
      </span>
      {full ? 'Full' : `${spotsLeft} of ${capacity} left`}
    </span>
  );
}

/** One line under the title, behind an icon. */
function Line({ icon: Icon, children }: { icon: LucideIcon; children: ReactNode }) {
  return (
    <p className="text-ink-muted mt-1 flex items-start gap-1.5 text-sm">
      <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <span className="min-w-0 break-words">{children}</span>
    </p>
  );
}

/** One event in a list. Every piece of text here comes from users and is rendered as plain text. */
export function EventCard({ event, overlaps = [] }: { event: Event; overlaps?: Event[] }) {
  const viewerZone = useViewerZone();
  const times = eventTimes(event, viewerZone);
  const clock = startClock(event.startsAt, event.timeZone);
  const [now] = useState(() => Date.now());
  const cancelled = event.status === 'CANCELLED';
  const mine = status(event, now);
  const host = event.isHost ? 'You’re hosting' : `Hosted by ${event.host.displayName}`;

  return (
    <li>
      <Link to={`/events/${event.id}`} className="card focus-visible:outline-offset-3 block">
        {/* The cover photo goes here, above the body (#124). */}
        <div className="flex gap-3 p-3.5 lg:gap-4.5 lg:p-4.5">
          {/* For the eye only: the time line says the same, with the zone. */}
          <div
            aria-hidden="true"
            className="when-block w-15 shrink-0 self-start py-2 text-center lg:w-19"
          >
            <span className="font-head block text-[1.0625rem] leading-tight font-extrabold">
              {clock.time}
            </span>
            {clock.period && (
              <span className="block text-[0.6875rem] font-bold tracking-wider">
                {clock.period}
              </span>
            )}
          </div>
          <div className="flex min-w-0 flex-1 flex-col">
            {/* The title comes first for screen readers; the tag only looks like it does. */}
            <h3 className="mb-0.5 text-[1.0625rem] leading-snug font-extrabold break-words lg:text-[1.1875rem]">
              {event.title}
            </h3>
            {event.tag && (
              <span className="tag-chip order-first mb-1 max-w-full self-start break-all">
                {event.tag}
              </span>
            )}
            <Line icon={Clock}>
              {times.event}
              {times.viewer && <span className="block">Your time: {times.viewer}</span>}
            </Line>
            <Line icon={event.locationHidden ? Lock : MapPin}>
              {event.locationHidden ? HIDDEN_LOCATION_TEXT : event.location}
            </Line>
            <p className="text-ink-muted mt-1.5 flex items-start gap-1.5 text-sm">
              <img
                src={event.host.avatarUrl}
                alt=""
                loading="lazy"
                className="size-6 shrink-0 rounded-full"
              />
              <span className="min-w-0 pt-0.5 break-words">
                {[host, ...reasons(event)].join(' · ')}
              </span>
            </p>
            {overlaps.length > 0 && (
              <p className="bg-waiting text-waiting-ink rounded-tag mt-2 flex max-w-full items-start self-start gap-1.5 px-2 py-1 text-[0.78125rem] font-semibold">
                <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                <span className="min-w-0 break-words">
                  Overlaps with {overlaps.map((o) => `“${o.title}”`).join(', ')}
                </span>
              </p>
            )}
            <div className="mt-3 flex flex-wrap items-center justify-between gap-x-2 gap-y-2.5">
              {cancelled ? (
                <Pill text="Cancelled" variant="pill-full" icon={Ban} />
              ) : (
                <SpotsMeter capacity={event.capacity} spotsLeft={event.spotsLeft} />
              )}
              {mine && <Pill {...mine} />}
            </div>
          </div>
        </div>
      </Link>
    </li>
  );
}
