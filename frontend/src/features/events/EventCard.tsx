import { MapPin } from 'lucide-react';
import { Link } from 'react-router';
import type { Event } from '../../api/events';
import { EventTimes } from './EventTimes';

export const HIDDEN_LOCATION_TEXT = 'Location shared with confirmed attendees';

/** One event in the feed. Every piece of text here comes from users and is rendered as plain text. */
export function EventCard({ event }: { event: Event }) {
  const cancelled = event.status === 'CANCELLED';
  return (
    <li>
      <Link
        to={`/events/${event.id}`}
        className="border-line bg-surface-raised rounded-card focus-visible:outline-accent block space-y-2 border p-4 focus-visible:outline-2 focus-visible:outline-offset-2"
      >
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="min-w-0 text-lg font-semibold break-words">{event.title}</h3>
          {cancelled && (
            <span className="border-line rounded-control border px-2 text-sm font-medium">
              Cancelled
            </span>
          )}
          {event.tag && (
            <span className="bg-surface text-ink-muted rounded-control px-2 text-sm break-all">
              {event.tag}
            </span>
          )}
        </div>
        <EventTimes event={event} />
        <p className="flex items-start gap-2">
          <MapPin aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="min-w-0 break-words">
            {event.locationHidden ? HIDDEN_LOCATION_TEXT : event.location}
          </span>
        </p>
        <p className="text-ink-muted text-sm">
          {event.isHost ? 'You’re hosting' : `Hosted by ${event.host.displayName}`}
        </p>
      </Link>
    </li>
  );
}
