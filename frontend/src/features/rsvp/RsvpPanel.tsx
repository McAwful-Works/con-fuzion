import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import type { Event } from '../../api/events';
import { useJoin, useLeave, useSpots, type Spots } from '../../api/rsvp';
import { ConfirmPanel } from '../../components/ConfirmPanel';
import { ErrorState } from '../../components/ErrorState';
import { formatCountdown, useNow, useSignupUnlocked } from './signup';
import { useOnline } from './useOnline';

/** "Spots left: 3", "Full" or "Unlimited spots", plus how many are going and waiting. */
export function SpotsBadge({ spots }: { spots: Spots }) {
  const headline =
    spots.spotsLeft === null
      ? 'Unlimited spots'
      : spots.spotsLeft === 0
        ? 'Full'
        : `Spots left: ${spots.spotsLeft}`;
  return (
    <p className="flex flex-wrap items-center gap-2">
      <span className="border-line rounded-control border px-2 font-medium">{headline}</span>
      <span className="text-ink-muted text-sm">
        {spots.confirmed} going
        {spots.waitlisted > 0 && `, ${spots.waitlisted} on the waitlist`}
      </span>
    </p>
  );
}

function LeaveEvent({ event, waitlisted }: { event: Event; waitlisted: boolean }) {
  const leave = useLeave(event.id);
  const online = useOnline();
  const [asking, setAsking] = useState(false);

  if (!asking) {
    return (
      <button
        type="button"
        className="btn btn-secondary"
        disabled={!online}
        onClick={() => setAsking(true)}
      >
        Leave
      </button>
    );
  }
  return (
    <ConfirmPanel
      title={`Leave “${event.title}”?`}
      confirmLabel="Leave"
      cancelLabel="Stay"
      pending={leave.isPending}
      error={leave.error}
      onCancel={() => setAsking(false)}
      onConfirm={() => leave.mutate(undefined, { onSuccess: () => setAsking(false) })}
    >
      {waitlisted ? (
        <p>You’ll lose your place in line. You can join again later, at the back of the line.</p>
      ) : (
        <p>
          You’ll give up your spot, and the next person in line gets it. You can join again later if
          there’s room, otherwise at the back of the waitlist.
        </p>
      )}
    </ConfirmPanel>
  );
}

/** The Join button before, during and after the signup countdown. Keyed by the signup time. */
function JoinButton({ event, full }: { event: Event; full: boolean }) {
  const join = useJoin(event.id);
  const online = useOnline();
  const unlocked = useSignupUnlocked(event.signupOpensAt);
  const now = useNow(1000, !unlocked);
  const remaining = event.signupOpensAt ? Date.parse(event.signupOpensAt) - now : 0;

  return (
    <div className="space-y-2">
      {!unlocked && (
        <p role="timer" className="text-ink-muted text-sm">
          {remaining > 0 ? `Signup opens in ${formatCountdown(remaining)}` : 'Signup is opening…'}
        </p>
      )}
      {unlocked && full && (
        <p className="text-ink-muted text-sm">
          This event is full. Joining puts you on the waitlist, and you move up as people leave.
        </p>
      )}
      <button
        type="button"
        className="btn btn-primary w-full sm:w-auto"
        disabled={!unlocked || !online || join.isPending}
        onClick={() => join.mutate()}
      >
        Join
      </button>
      {join.isError && <ErrorState error={join.error} />}
    </div>
  );
}

/** Spots and the viewer's own RSVP: Join, Joined, Waitlisted #N and Leave. */
export function RsvpPanel({ event }: { event: Event }) {
  const cancelled = event.status === 'CANCELLED';
  const spotsQuery = useSpots(event);
  const spots = spotsQuery.data;
  // `/spots` is the fresher of the two (it is polled); the event's own status covers the first moments.
  const status = spots?.myStatus ?? event.myStatus;
  // Promoted or removed since the event was loaded: its location and attendee list changed with it.
  const queryClient = useQueryClient();
  const spotsStatus = spots?.myStatus;
  useEffect(() => {
    if (spotsStatus && spotsStatus !== event.myStatus) {
      void queryClient.invalidateQueries({ queryKey: ['events', 'one', event.id] });
    }
  }, [spotsStatus, event.myStatus, event.id, queryClient]);
  const position = spots?.myPosition ?? null;
  // Only a person who can still join needs the clock, to notice that the event has started.
  const now = useNow(1000, !cancelled && status === 'NONE');
  const started = Date.parse(event.startsAt) <= now;

  let action: ReactNode = null;
  if (event.isHost) {
    // The host holds a spot and can't leave: they cancel the event instead.
    action = null;
  } else if (status === 'CONFIRMED') {
    action = (
      <>
        <p role="status" className="font-semibold">
          Joined
        </p>
        <LeaveEvent event={event} waitlisted={false} />
      </>
    );
  } else if (status === 'WAITLISTED') {
    action = (
      <>
        <p role="status" className="font-semibold">
          {position === null ? 'Waitlisted' : `Waitlisted #${position}`}
        </p>
        <LeaveEvent event={event} waitlisted />
      </>
    );
  } else if (status === 'REMOVED') {
    action = <p role="status">You can’t rejoin this event.</p>;
  } else if (!cancelled) {
    action = started ? (
      <p role="status">Signup is closed because the event has started.</p>
    ) : (
      <JoinButton key={event.signupOpensAt} event={event} full={spots?.spotsLeft === 0} />
    );
  }

  return (
    <section aria-label="Spots and signup" className="space-y-3">
      {!cancelled && spots && <SpotsBadge spots={spots} />}
      {action}
    </section>
  );
}
