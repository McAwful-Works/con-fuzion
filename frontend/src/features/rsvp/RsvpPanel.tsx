import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { ApiError } from '../../api/client';
import type { Event } from '../../api/events';
import { useAccept, useJoin, useLeave, useSpots, type Spots } from '../../api/rsvp';
import { ConfirmPanel } from '../../components/ConfirmPanel';
import { ErrorState } from '../../components/ErrorState';
import { formatCountdown, formatDeadline, useNow, useSignupUnlocked } from './signup';
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

function LeaveEvent({
  event,
  kind,
}: {
  event: Event;
  kind: 'confirmed' | 'waitlisted' | 'offered';
}) {
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
        {kind === 'offered' ? 'Decline' : 'Leave'}
      </button>
    );
  }
  const declining = kind === 'offered';
  return (
    <ConfirmPanel
      title={declining ? `Decline the spot in “${event.title}”?` : `Leave “${event.title}”?`}
      confirmLabel={declining ? 'Decline' : 'Leave'}
      cancelLabel={declining ? 'Keep the offer' : 'Stay'}
      pending={leave.isPending}
      error={leave.error}
      onCancel={() => setAsking(false)}
      onConfirm={() => leave.mutate(undefined, { onSuccess: () => setAsking(false) })}
    >
      {declining ? (
        <p>
          You’ll give up the spot, and the next person in line is offered it. You can join again
          later, at the back of the line.
        </p>
      ) : kind === 'waitlisted' ? (
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

/**
 * A spot is being held for the viewer until `expiresAt` (D-014). The countdown runs on the device
 * clock toward the server's deadline: it says "expired" the moment the deadline passes, without
 * waiting for a poll (a badly wrong clock shows it early or late; the server decides either way).
 * Screen readers get the fixed deadline and the expiry, not an announcement every second.
 * `onLapsed` tells the panel the offer ran out, so it can say so after the server stops reporting it.
 */
function OfferedSpot({
  event,
  expiresAt,
  full,
  onLapsed,
}: {
  event: Event;
  expiresAt: string | null;
  full: boolean;
  onLapsed: () => void;
}) {
  const accept = useAccept(event.id);
  const online = useOnline();
  const queryClient = useQueryClient();
  const deadline = expiresAt ? Date.parse(expiresAt) : null;
  const now = useNow(1000, deadline !== null);
  const expired = deadline !== null && !Number.isNaN(deadline) && deadline <= now;

  // Ask the server what became of the offer (a read treats it as gone: D-014 rule 15).
  useEffect(() => {
    if (!expired) return;
    onLapsed();
    void queryClient.invalidateQueries({ queryKey: ['events'] });
  }, [expired, onLapsed, queryClient]);

  // The "expired" wording is the panel's, so it survives this component going away.
  if (expired) return <JoinButton key="rejoin" event={event} full={full} />;
  return (
    <div className="space-y-3">
      <p className="font-semibold">A spot is held for you.</p>
      {deadline !== null && expiresAt && (
        <>
          <p className="text-ink-muted text-sm">Accept before {formatDeadline(expiresAt)}.</p>
          <p role="timer" aria-live="off" className="text-sm font-medium">
            Offer ends in {formatCountdown(deadline - now)}
          </p>
        </>
      )}
      <div className="flex flex-col gap-2 sm:flex-row">
        <button
          type="button"
          className="btn btn-primary"
          disabled={!online || accept.isPending}
          onClick={() =>
            accept.mutate(undefined, {
              onError: (error) => {
                if (error instanceof ApiError && error.code === 'OFFER_EXPIRED') onLapsed();
              },
            })
          }
        >
          Accept spot
        </button>
        <LeaveEvent event={event} kind="offered" />
      </div>
      {accept.isError && <ErrorState error={accept.error} />}
    </div>
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

/** Spots and the viewer's own RSVP: Join, Joined, Waitlisted #N, an offer to accept or decline, and Leave. */
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
  // Set when an offer ran out. The server soon reports no RSVP at all, so the panel keeps saying so.
  const [lapsed, setLapsed] = useState(false);
  const onLapsed = useCallback(() => setLapsed(true), []);
  const joinedSince = status === 'CONFIRMED' || status === 'WAITLISTED';
  const showLapsed = lapsed && !joinedSince && !cancelled;
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
        <LeaveEvent event={event} kind="confirmed" />
      </>
    );
  } else if (status === 'WAITLISTED') {
    action = (
      <>
        <p role="status" className="font-semibold">
          {position === null ? 'Waitlisted' : `Waitlisted #${position}`}
        </p>
        <LeaveEvent event={event} kind="waitlisted" />
      </>
    );
  } else if (status === 'OFFERED' && cancelled) {
    // D-014 rule 16: the offer stays open until its deadline, but nothing can be accepted any more.
    action = <p role="status">You were offered a spot, but this event was cancelled.</p>;
  } else if (status === 'OFFERED') {
    action = (
      <OfferedSpot
        event={event}
        expiresAt={spots?.offerExpiresAt ?? null}
        full={spots?.spotsLeft === 0}
        onLapsed={onLapsed}
      />
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
      {/* Always mounted, so a screen reader announces the text when it appears. */}
      <p role="status" className={showLapsed ? 'font-semibold' : undefined}>
        {showLapsed ? 'Your offer expired. The spot went to the next person in line.' : null}
      </p>
      {action}
    </section>
  );
}
