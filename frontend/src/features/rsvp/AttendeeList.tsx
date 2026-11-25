import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import type { Event } from '../../api/events';
import { useAttendees, useRemoveAttendee, useSpots } from '../../api/rsvp';
import type { Person } from '../../api/social';
import { ConfirmPanel } from '../../components/ConfirmPanel';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { PersonRow } from '../social/PersonRow';
import { useOnline } from './useOnline';

/** One confirmed person. The host sees Remove on everyone but themself, and it asks first. */
function ConfirmedRow({ event, person }: { event: Event; person: Person }) {
  const remove = useRemoveAttendee(event.id);
  const online = useOnline();
  const [asking, setAsking] = useState(false);
  const isEventHost = person.id === event.host.id;
  // The host cancels the event instead of leaving, and a cancelled event has no spots to free.
  const canRemove = event.isHost && !isEventHost && event.status === 'PUBLISHED';

  return (
    <PersonRow
      person={person}
      below={
        asking && (
          <ConfirmPanel
            title={`Remove ${person.displayName}?`}
            confirmLabel="Remove"
            cancelLabel="Keep"
            pending={remove.isPending}
            error={remove.error}
            onCancel={() => setAsking(false)}
            onConfirm={() => remove.mutate(person.id, { onSuccess: () => setAsking(false) })}
          >
            <p>
              They lose their spot and can’t rejoin this event. This can’t be undone. The next
              person on the waitlist, if there is one, is offered the spot.
            </p>
          </ConfirmPanel>
        )
      }
    >
      {isEventHost && <span className="text-ink-muted text-sm">Host</span>}
      {canRemove && (
        <button
          type="button"
          className="btn btn-secondary"
          aria-label={`Remove ${person.displayName}`}
          disabled={!online || asking}
          onClick={() => setAsking(true)}
        >
          Remove
        </button>
      )}
    </PersonRow>
  );
}

/**
 * Who is going: the confirmed list (the host first) for the host and for confirmed attendees, and the
 * waitlist in queue order for the host only. Everyone else gets nothing, so the caller doesn't render it.
 */
export function AttendeeList({ event }: { event: Event }) {
  const attendees = useAttendees(event.id, true);
  // The counts are polled; when they change, so has the list.
  const spots = useSpots(event).data;
  const version = spots ? `${spots.confirmed}/${spots.waitlisted}` : null;
  const queryClient = useQueryClient();
  const seen = useRef<string | null>(null);
  useEffect(() => {
    // The first counts match the list we just asked for; only a later change means it's out of date.
    if (version === null || version === seen.current) return;
    const first = seen.current === null;
    seen.current = version;
    if (first) return;
    void queryClient.invalidateQueries({ queryKey: ['events', 'attendees', event.id] });
  }, [version, event.id, queryClient]);

  if (attendees.isPending) {
    return <Skeleton className="h-24 w-full" />;
  }
  if (attendees.isError) {
    return <ErrorState error={attendees.error} onRetry={() => void attendees.refetch()} />;
  }

  const { confirmed, waitlist, offered } = attendees.data;
  return (
    <section aria-labelledby="attendees-heading" className="space-y-6">
      <div className="space-y-3">
        <h2 id="attendees-heading" className="text-xl font-semibold">
          Going ({confirmed.length})
        </h2>
        <ul className="space-y-3">
          {confirmed.map((person) => (
            <ConfirmedRow key={person.id} event={event} person={person} />
          ))}
        </ul>
      </div>
      {offered && offered.length > 0 && (
        <div className="space-y-3">
          <h2 id="offered-heading" className="text-xl font-semibold">
            Offered a spot ({offered.length})
          </h2>
          <p className="text-ink-muted text-sm">
            Each has a spot held until their deadline. If they don’t accept in time, the next person
            in line is offered it.
          </p>
          <ol aria-labelledby="offered-heading" className="space-y-3">
            {offered.map((person) => (
              <PersonRow key={person.id} person={person} />
            ))}
          </ol>
        </div>
      )}
      {waitlist && (
        <div className="space-y-3">
          <h2 id="waitlist-heading" className="text-xl font-semibold">
            Waitlist ({waitlist.length})
          </h2>
          {waitlist.length === 0 ? (
            <p className="text-ink-muted">Nobody is waiting.</p>
          ) : (
            <>
              <p className="text-ink-muted text-sm">In order: the first person is next in line.</p>
              <ol aria-labelledby="waitlist-heading" className="space-y-3">
                {waitlist.map((person) => (
                  <PersonRow key={person.id} person={person} />
                ))}
              </ol>
            </>
          )}
        </div>
      )}
    </section>
  );
}
