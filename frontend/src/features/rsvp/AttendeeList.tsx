import { useQueryClient } from '@tanstack/react-query';
import { Lock } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { Event } from '../../api/events';
import { useAttendees, useRemoveAttendee, useSpots, type Attendees } from '../../api/rsvp';
import type { Person } from '../../api/social';
import { ConfirmPanel } from '../../components/ConfirmPanel';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { useOnline } from './useOnline';

function GuestRow({
  person,
  note,
  action,
  below,
}: {
  person: Person;
  note?: string;
  action?: ReactNode;
  /** Shown under the row, such as an open confirmation. */
  below?: ReactNode;
}) {
  return (
    <li className="space-y-3 px-3.5 py-2.5">
      <div className="flex min-h-11 items-center gap-3">
        <img src={person.avatarUrl} alt="" className="size-9 shrink-0 rounded-full" />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[0.9375rem] font-bold">{person.displayName}</p>
          {note && <p className="text-ink-muted text-sm">{note}</p>}
        </div>
        {action}
      </div>
      {below}
    </li>
  );
}

/** One confirmed person. The host sees Remove on everyone but themself, and it asks first. */
function ConfirmedRow({ event, person }: { event: Event; person: Person }) {
  const remove = useRemoveAttendee(event.id);
  const online = useOnline();
  const [asking, setAsking] = useState(false);
  const isEventHost = person.id === event.host.id;
  // The host cancels the event instead of leaving, and a cancelled event has no spots to free.
  const canRemove = event.isHost && !isEventHost && event.status === 'PUBLISHED';

  return (
    <GuestRow
      person={person}
      note={isEventHost ? (event.isHost ? 'Host · that’s you' : 'Host') : undefined}
      action={
        canRemove && (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            aria-label={`Remove ${person.displayName}`}
            disabled={!online || asking}
            onClick={() => setAsking(true)}
          >
            Remove
          </button>
        )
      }
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
    />
  );
}

const LIST = 'card divide-line divide-y-2 divide-dashed';

function Going({ event, people, hidden }: { event: Event; people: Person[]; hidden?: boolean }) {
  return (
    <div className="space-y-3">
      {/* The host's tab already shows this, so there the heading is for screen readers. */}
      <h2 id="going-heading" className={hidden ? 'sr-only' : 'text-base font-extrabold'}>
        Going ({people.length})
      </h2>
      <ul aria-labelledby="going-heading" className={LIST}>
        {people.map((person) => (
          <ConfirmedRow key={person.id} event={event} person={person} />
        ))}
      </ul>
    </div>
  );
}

/** The host's list of people in line: those holding an offer, or those still waiting. */
function InLine({
  id,
  heading,
  people,
  intro,
  empty,
}: {
  id: string;
  heading: string;
  people: Person[];
  intro: string;
  empty: string;
}) {
  return (
    <div className="space-y-3">
      <h2 id={id} className="sr-only">
        {heading} ({people.length})
      </h2>
      {people.length === 0 ? (
        <p className="text-ink-muted">{empty}</p>
      ) : (
        <>
          <p className="text-ink-muted text-sm">{intro}</p>
          <ol aria-labelledby={id} className={LIST}>
            {people.map((person) => (
              <GuestRow key={person.id} person={person} />
            ))}
          </ol>
        </>
      )}
    </div>
  );
}

type Tab = 'going' | 'offered' | 'waitlist';

/** The host's Going, Offered and Waitlist tabs. */
function ManageGuests({ event, attendees }: { event: Event; attendees: Attendees }) {
  const [tab, setTab] = useState<Tab>('going');
  const { confirmed } = attendees;
  const offered = attendees.offered ?? [];
  const waitlist = attendees.waitlist ?? [];
  const tabs: [Tab, string, number][] = [
    ['going', 'Going', confirmed.length],
    ['offered', 'Offered', offered.length],
    ['waitlist', 'Waitlist', waitlist.length],
  ];
  return (
    <section aria-labelledby="guests-heading" className="space-y-3">
      <h2 id="guests-heading" className="text-base font-extrabold">
        Manage guests
      </h2>
      <div role="group" aria-label="Guest lists" className="seg">
        {tabs.map(([value, label, count]) => (
          <button
            key={value}
            type="button"
            className="seg-item"
            aria-pressed={tab === value}
            onClick={() => setTab(value)}
          >
            {label} <span className="text-xs">{count}</span>
          </button>
        ))}
      </div>
      {tab === 'going' && <Going event={event} people={confirmed} hidden />}
      {tab === 'offered' && (
        <InLine
          id="offered-heading"
          heading="Offered a spot"
          people={offered}
          intro="Each has a spot held until their deadline. If they don’t accept in time, the next person in line is offered it."
          empty="Nobody has an open offer."
        />
      )}
      {tab === 'waitlist' && (
        <InLine
          id="waitlist-heading"
          heading="Waitlist"
          people={waitlist}
          intro="In order: the first person is next in line."
          empty="Nobody is waiting."
        />
      )}
    </section>
  );
}

/**
 * Who is going: the confirmed list (the host first) for confirmed attendees, and for the host the
 * offers and the waitlist in queue order too. Everyone else gets `LockedGuestList`.
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
  return event.isHost ? (
    <ManageGuests event={event} attendees={attendees.data} />
  ) : (
    <Going event={event} people={attendees.data.confirmed} />
  );
}

/** For anyone who may not see the list (D-013): only how many are going. It never asks for the list. */
export function LockedGuestList({ event }: { event: Event }) {
  const spots = useSpots(event).data;
  // A cancelled event isn't polled, but a capped one still says how many spots are taken.
  const fromEvent =
    event.capacity !== null && event.spotsLeft !== null ? event.capacity - event.spotsLeft : null;
  const going = spots?.confirmed ?? fromEvent;
  return (
    <section aria-labelledby="who-heading" className="space-y-3">
      <h2 id="who-heading" className="text-base font-extrabold">
        Who’s going
      </h2>
      <div className="card flex items-center gap-3 p-3.5">
        <span className="bg-surface-sunken text-ink-muted rounded-control flex size-9 shrink-0 items-center justify-center">
          <Lock aria-hidden="true" className="size-4.5" />
        </span>
        <div>
          {going !== null && <p className="text-[0.9375rem] font-bold">{going} going</p>}
          <p className="text-ink-muted text-sm">
            Only the host and confirmed attendees see who’s going.
          </p>
        </div>
      </div>
    </section>
  );
}
