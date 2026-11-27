import { Clock, Lock, MapPin, Users, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import type { Event } from '../../api/events';
import { useSpots, type Spots } from '../../api/rsvp';
import { HIDDEN_LOCATION_TEXT } from './EventCard';
import { eventTimes } from './time';
import { useViewerZone } from './useViewerZone';

function Fact({ icon: Icon, title, note }: { icon: LucideIcon; title: string; note?: ReactNode }) {
  return (
    <div className="flex items-start gap-3 px-3.5 py-3">
      <span className="bg-surface-sunken text-accent-text rounded-control flex size-9 shrink-0 items-center justify-center">
        <Icon aria-hidden="true" className="size-4.5" />
      </span>
      <div className="min-w-0 self-center">
        <p className="text-[0.9375rem] font-bold break-words">{title}</p>
        {note && <p className="text-ink-muted text-sm">{note}</p>}
      </div>
    </div>
  );
}

/** "Spots left: 3", "Full" or "Unlimited spots", then how many spots are taken and who is waiting. */
function SpotsFact({ spots }: { spots: Spots }) {
  const { capacity, confirmed, spotsLeft, waitlisted } = spots;
  const taken =
    capacity === null ? `${confirmed} spots taken` : `${confirmed} of ${capacity} spots taken`;
  return (
    <Fact
      icon={Users}
      title={
        spotsLeft === null
          ? 'Unlimited spots'
          : spotsLeft === 0
            ? 'Full'
            : `Spots left: ${spotsLeft}`
      }
      note={waitlisted > 0 ? `${taken}, ${waitlisted} on the waitlist` : taken}
    />
  );
}

/** When (in the event's zone, plus yours when it differs), where, and the live spot counts. */
export function EventFacts({ event }: { event: Event }) {
  const times = eventTimes(event, useViewerZone());
  const spots = useSpots(event).data;
  const day = new Intl.DateTimeFormat(undefined, {
    timeZone: event.timeZone,
    weekday: 'long',
    month: 'short',
    day: 'numeric',
  }).format(new Date(event.startsAt));
  return (
    <section aria-label="Event details" className="card divide-line divide-y-2 divide-dashed py-1">
      <Fact
        icon={Clock}
        title={`${day} · ${times.event}`}
        note={times.viewer && `Your time: ${times.viewer}`}
      />
      <Fact
        icon={event.locationHidden ? Lock : MapPin}
        title={event.locationHidden ? HIDDEN_LOCATION_TEXT : (event.location ?? '')}
        note={
          event.isHost &&
          event.locationVisibility === 'CONFIRMED' &&
          'Only you and confirmed attendees see the location.'
        }
      />
      {/* A cancelled event has no spots to watch. */}
      {event.status === 'PUBLISHED' && spots && <SpotsFact spots={spots} />}
    </section>
  );
}
