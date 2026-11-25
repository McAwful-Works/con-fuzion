import { MapPin } from 'lucide-react';
import { Link } from 'react-router';
import type { Event } from '../../api/events';
import { EventTimes } from './EventTimes';

const STATUS_TEXT: Partial<Record<Event['myStatus'], string>> = {
  CONFIRMED: 'Joined',
  WAITLISTED: 'Waitlisted',
  REMOVED: 'Removed',
};

export const HIDDEN_LOCATION_TEXT = 'Location shared with confirmed attendees';

/** One event in the feed. Every piece of text here comes from users and is rendered as plain text. */
export function EventCard({ event, overlaps = [] }: { event: Event; overlaps?: Event[] }) {
  const cancelled = event.status === 'CANCELLED';
  // The host's own spot is "Hosting" already, so it isn't also "Joined".
  const myStatus = event.isHost ? undefined : STATUS_TEXT[event.myStatus];
  return (
    <li>
      <Link
        to={`/events/${event.id}`}
        className={`bg-surface-raised rounded-card focus-visible:outline-accent block space-y-2 border p-4 focus-visible:outline-2 focus-visible:outline-offset-2 ${
          overlaps.length > 0 ? 'border-accent border-2' : 'border-line'
        }`}
      >
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="min-w-0 text-lg font-semibold break-words">{event.title}</h3>
          {cancelled && (
            <span className="border-line rounded-control border px-2 text-sm font-medium">
              Cancelled
            </span>
          )}
          {myStatus && (
            <span
              className={`rounded-control px-2 text-sm font-medium ${
                event.myStatus === 'REMOVED' ? 'border-line border' : 'bg-accent text-accent-ink'
              }`}
            >
              {myStatus}
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
        {overlaps.length > 0 && (
          <p className="text-sm font-semibold">
            Overlaps with {overlaps.map((o) => `“${o.title}”`).join(', ')}
          </p>
        )}
        <p className="text-ink-muted text-sm">
          {event.isHost ? 'You’re hosting' : `Hosted by ${event.host.displayName}`}
        </p>
      </Link>
    </li>
  );
}
