import { MapPin } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { useCancelEvent, useEvent, type Event } from '../api/events';
import { ConfirmPanel } from '../components/ConfirmPanel';
import { ErrorState } from '../components/ErrorState';
import { Skeleton } from '../components/Skeleton';
import { entryKey, entryLabel } from '../features/events/audience';
import { HIDDEN_LOCATION_TEXT } from '../features/events/EventCard';
import { EventTimes } from '../features/events/EventTimes';
import { AttendeeList } from '../features/rsvp/AttendeeList';
import { ReportSheet } from '../features/reports/ReportSheet';
import { RsvpPanel } from '../features/rsvp/RsvpPanel';

export function EventNotFound() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Event not found</h1>
      <p className="text-ink-muted">It may have been cancelled, or it isn’t shared with you.</p>
      <Link to="/" className="btn btn-secondary">
        Back to events
      </Link>
    </div>
  );
}

function CancelEvent({ event }: { event: Event }) {
  const cancel = useCancelEvent(event.id);
  const [asking, setAsking] = useState(false);

  if (!asking) {
    return (
      <button type="button" className="btn btn-secondary" onClick={() => setAsking(true)}>
        Cancel event
      </button>
    );
  }
  return (
    <ConfirmPanel
      title={`Cancel “${event.title}”?`}
      confirmLabel="Cancel event"
      cancelLabel="Keep event"
      pending={cancel.isPending}
      error={cancel.error}
      onCancel={() => setAsking(false)}
      onConfirm={() => cancel.mutate(undefined, { onSuccess: () => setAsking(false) })}
    >
      <p>
        The event is cancelled and drops out of everyone’s feed. Only you and the people who are
        going or waiting can still open it, and a cancelled event can’t be edited or brought back.
      </p>
    </ConfirmPanel>
  );
}

/** "Report event", for anyone but the host (D-015 rule 6: you can't report your own event). */
function ReportEvent({ event }: { event: Event }) {
  const [open, setOpen] = useState(false);
  // The button stays mounted, so focus has somewhere to return to when the sheet closes.
  return (
    <div className="space-y-3">
      <button
        type="button"
        className="btn btn-secondary"
        aria-expanded={open}
        onClick={() => setOpen(true)}
      >
        Report event
      </button>
      {open && (
        <ReportSheet
          targetType="EVENT"
          targetId={event.id}
          label={`“${event.title}”`}
          blockable={event.host}
          onDone={() => setOpen(false)}
        />
      )}
    </div>
  );
}

export function EventDetail() {
  const { eventId = '' } = useParams();
  const query = useEvent(eventId);

  if (query.isPending) {
    return (
      <div role="status" aria-label="Loading the event" className="space-y-3">
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }
  if (query.error instanceof ApiError && [400, 404].includes(query.error.status)) {
    return <EventNotFound />;
  }
  if (query.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;

  const event = query.data;
  const cancelled = event.status === 'CANCELLED';
  return (
    <article className="space-y-6">
      <Link to="/" className="text-sm underline">
        All events
      </Link>
      {cancelled && (
        <div role="status" className="border-line bg-surface-raised rounded-card border p-4">
          <p className="font-semibold">This event was cancelled.</p>
        </div>
      )}
      {event.isHost && event.hiddenReason && (
        <div
          role="status"
          className="border-line bg-surface-raised rounded-card space-y-1 border p-4"
        >
          <p className="font-semibold">The moderators hid this event.</p>
          <p className="text-sm">
            Only you and the moderators can see it. People who were going can’t open it, and no one
            new can join. You can still edit or cancel it.
          </p>
          <p className="text-sm break-words">Reason: {event.hiddenReason}</p>
        </div>
      )}
      <header className="space-y-2">
        <h1 className="text-2xl font-semibold break-words">{event.title}</h1>
        {event.tag && <p className="text-ink-muted break-all">{event.tag}</p>}
      </header>

      <div className="space-y-2">
        <EventTimes event={event} />
        <p className="flex items-start gap-2">
          <MapPin aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="min-w-0 break-words">
            {event.locationHidden ? HIDDEN_LOCATION_TEXT : event.location}
          </span>
        </p>
        {event.isHost && event.locationVisibility === 'CONFIRMED' && (
          <p className="text-ink-muted text-sm">
            Only you and confirmed attendees see the location.
          </p>
        )}
      </div>

      <p className="flex items-center gap-3">
        <img src={event.host.avatarUrl} alt="" className="size-8 rounded-full" />
        <span>{event.isHost ? 'You’re hosting' : `Hosted by ${event.host.displayName}`}</span>
      </p>

      {event.description && <p className="break-words whitespace-pre-wrap">{event.description}</p>}

      <RsvpPanel event={event} />

      {/* Only the host and confirmed attendees get the list; anyone else would get a 404. */}
      {(event.isHost || event.myStatus === 'CONFIRMED') && <AttendeeList event={event} />}

      {event.isHost && event.audience && (
        <section aria-labelledby="audience-heading" className="space-y-2">
          <h2 id="audience-heading" className="text-xl font-semibold">
            Who can see this
          </h2>
          <ul className="list-disc space-y-1 pl-5">
            {event.audience.map((entry) => (
              <li key={entryKey(entry)}>
                <span className="break-words">{entryLabel(entry)}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {event.isHost && !cancelled && (
        <section aria-label="Host actions" className="space-y-3">
          <Link to={`/events/${event.id}/edit`} className="btn btn-secondary">
            Edit event
          </Link>
          <CancelEvent event={event} />
        </section>
      )}

      {!event.isHost && <ReportEvent event={event} />}
    </article>
  );
}
