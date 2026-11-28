import { Flag, Pencil, Users, X } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { useCancelEvent, useEvent, type Event } from '../api/events';
import { ConfirmPanel } from '../components/ConfirmPanel';
import { ErrorState } from '../components/ErrorState';
import { Skeleton } from '../components/Skeleton';
import { AddToCalendar, useCanAddToCalendar } from '../features/events/AddToCalendar';
import { EventFacts } from '../features/events/EventFacts';
import { EventHero } from '../features/events/EventHero';
import { ReportSheet } from '../features/reports/ReportSheet';
import { AttendeeList, LockedGuestList } from '../features/rsvp/AttendeeList';
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

/** The host's tools: Add to calendar, Edit, Change who can see it, and Cancel behind a confirmation. */
function HostActions({ event }: { event: Event }) {
  const cancel = useCancelEvent(event.id);
  const calendar = useCanAddToCalendar(event);
  const [asking, setAsking] = useState(false);
  const edit = `/events/${event.id}/edit`;

  return (
    <section aria-label="Host actions">
      {asking ? (
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
            going or waiting can still open it, and a cancelled event can’t be edited or brought
            back.
          </p>
        </ConfirmPanel>
      ) : (
        <>
          <h2 className="mb-3 text-base font-extrabold max-lg:hidden">Your meetup</h2>
          {/* Phones show shorter labels; the full ones stay for screen readers. */}
          <div className="flex flex-wrap gap-2 lg:flex-col lg:gap-2.5">
            {calendar && (
              <AddToCalendar
                event={event}
                className="basis-full lg:basis-auto"
                buttonClassName="btn-secondary btn-sm lg:min-h-12"
              />
            )}
            <Link to={edit} className="btn btn-primary btn-sm flex-1 lg:min-h-12">
              <Pencil aria-hidden="true" className="size-4" />
              <span>
                Edit <span className="max-lg:sr-only">event</span>
              </span>
            </Link>
            <Link to={`${edit}#audience`} className="btn btn-secondary btn-sm flex-1 lg:min-h-12">
              <Users aria-hidden="true" className="size-4 max-lg:hidden" />
              <span className="whitespace-nowrap">
                <span className="max-lg:sr-only">Change</span>{' '}
                <span className="inline-block max-lg:first-letter:uppercase">who can see it</span>
              </span>
            </Link>
            <button
              type="button"
              className="btn btn-ghost btn-danger btn-sm lg:min-h-12"
              onClick={() => setAsking(true)}
            >
              <X aria-hidden="true" className="size-4 max-lg:hidden" />
              <span>
                Cancel <span className="max-lg:sr-only">event</span>
              </span>
            </button>
          </div>
        </>
      )}
    </section>
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
        className="btn btn-ghost btn-sm"
        aria-expanded={open}
        onClick={() => setOpen(true)}
      >
        <Flag aria-hidden="true" className="size-4" />
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

function EventPage({ event }: { event: Event }) {
  const cancelled = event.status === 'CANCELLED';
  // Only the host and confirmed attendees get the list; anyone else would get a 404.
  const seesGuests = event.isHost || event.myStatus === 'CONFIRMED';
  return (
    // `data-event-page` makes the shell's main column wider on desktop, and full height.
    <article
      data-event-page
      className="flex flex-1 flex-col gap-5 lg:grid lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start lg:gap-8"
    >
      <div className="min-w-0 space-y-5">
        <EventHero event={event} />
        {cancelled && (
          <div role="status" className="card p-4">
            <p className="font-semibold">This event was cancelled.</p>
          </div>
        )}
        {event.isHost && event.hiddenReason && (
          <div role="status" className="card space-y-1 p-4">
            <p className="font-semibold">The moderators hid this event.</p>
            <p className="text-sm">
              Only you and the moderators can see it. People who were going can’t open it, and no
              one new can join. You can still edit or cancel it.
            </p>
            <p className="text-sm break-words">Reason: {event.hiddenReason}</p>
          </div>
        )}
        <EventFacts event={event} />
        {event.isHost && <AttendeeList event={event} />}
        {event.description && (
          <section aria-labelledby="about-heading" className="space-y-2">
            <h2 id="about-heading" className="text-base font-extrabold">
              About
            </h2>
            <p className="leading-relaxed break-words whitespace-pre-wrap">{event.description}</p>
          </section>
        )}
        {!event.isHost &&
          (seesGuests ? <AttendeeList event={event} /> : <LockedGuestList event={event} />)}
        {!event.isHost && <ReportEvent event={event} />}
      </div>

      {/* The main action: a bar under the thumb on phones, a card beside the page on desktop. */}
      {!(event.isHost && cancelled) && (
        <div
          data-actionbar
          className="border-outline bg-bar lg:card sticky bottom-0 z-10 -mx-4 mt-auto -mb-6 max-h-[70dvh] overflow-y-auto border-t-2 px-4 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] backdrop-blur has-[[data-calendar-menu]]:overflow-visible lg:top-8 lg:bottom-auto lg:m-0 lg:max-h-[calc(100dvh-4rem)] lg:p-5"
        >
          {event.isHost ? <HostActions event={event} /> : <RsvpPanel event={event} />}
        </div>
      )}
    </article>
  );
}

export function EventDetail() {
  const { eventId = '' } = useParams();
  const query = useEvent(eventId);

  if (query.isPending) {
    return (
      <div
        data-event-page
        role="status"
        aria-label="Loading the event"
        className="w-full space-y-5"
      >
        <Skeleton className="h-40 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }
  if (query.error instanceof ApiError && [400, 404].includes(query.error.status)) {
    return <EventNotFound />;
  }
  if (query.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;

  // Keyed by event, so an open confirmation or tab never carries over to another one.
  return <EventPage key={query.data.id} event={query.data} />;
}
