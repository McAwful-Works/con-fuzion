import { useQueryClient } from '@tanstack/react-query';
import { Ban, CalendarX, Check, CircleCheck, Hourglass, Lock, type LucideIcon } from 'lucide-react';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { ApiError } from '../../api/client';
import type { Event } from '../../api/events';
import { useAccept, useJoin, useLeave, useSpots } from '../../api/rsvp';
import { ConfirmPanel } from '../../components/ConfirmPanel';
import { ErrorState } from '../../components/ErrorState';
import { AddToCalendar, useCanAddToCalendar } from '../events/AddToCalendar';
import { formatCountdown, formatDeadline, useNow, useSignupUnlocked } from './signup';
import { useOnline } from './useOnline';

function LeaveEvent({
  event,
  kind,
  look = kind === 'offered' ? 'btn-ghost' : 'btn-secondary',
}: {
  event: Event;
  kind: 'confirmed' | 'waitlisted' | 'offered';
  /** The button's variant. Quieter where another action comes first. */
  look?: string;
}) {
  const leave = useLeave(event.id);
  const online = useOnline();
  const [asking, setAsking] = useState(false);

  if (!asking) {
    return (
      <button
        type="button"
        className={`btn ${look}`}
        disabled={!online}
        onClick={() => setAsking(true)}
      >
        {kind === 'offered' ? 'Decline' : 'Leave'}
      </button>
    );
  }
  const declining = kind === 'offered';
  return (
    <div className="w-full">
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
            You’ll give up your spot, and the next person in line gets it. You can join again later
            if there’s room, otherwise at the back of the waitlist.
          </p>
        )}
      </ConfirmPanel>
    </div>
  );
}

/** A state with nothing to press: removed, cancelled or started. */
function Notice({ icon: Icon, children }: { icon: LucideIcon; children: ReactNode }) {
  return (
    <p role="status" className="flex items-start gap-2 text-sm font-semibold">
      <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <span>{children}</span>
    </p>
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
      {/* Side by side in the phone bar; the countdown goes on top in the desktop card. */}
      <div className="flex items-center gap-3 lg:flex-col-reverse lg:items-start">
        <div className="min-w-0 flex-1">
          <p className="font-head text-lg leading-snug font-extrabold">A spot is held for you.</p>
          {deadline !== null && expiresAt && (
            <p className="text-ink-muted text-sm">Accept before {formatDeadline(expiresAt)}.</p>
          )}
        </div>
        {deadline !== null && (
          <p
            role="timer"
            aria-live="off"
            className="text-ink-muted shrink-0 text-right text-xs font-semibold lg:text-left"
          >
            Offer ends in{' '}
            <span className="font-head text-accent-text block text-2xl font-extrabold tabular-nums">
              {formatCountdown(deadline - now)}
            </span>
          </p>
        )}
      </div>
      <div className="flex flex-wrap gap-2">
        <LeaveEvent event={event} kind="offered" />
        <button
          type="button"
          className="btn btn-primary flex-1"
          disabled={!online || accept.isPending}
          onClick={() =>
            accept.mutate(undefined, {
              onError: (error) => {
                if (error instanceof ApiError && error.code === 'OFFER_EXPIRED') onLapsed();
              },
            })
          }
        >
          <Check aria-hidden="true" className="size-4.5" />
          Accept spot
        </button>
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
        <p role="timer" className="text-center text-sm font-semibold">
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
        className="btn btn-primary w-full"
        disabled={!unlocked || !online || join.isPending}
        onClick={() => join.mutate()}
      >
        {!unlocked && <Lock aria-hidden="true" className="size-4.5" />}
        Join
      </button>
      {join.isError && <ErrorState error={join.error} />}
    </div>
  );
}

/**
 * The viewer's own RSVP: Join, Joined, Waitlisted #N, an offer to accept or decline, and Leave. Not
 * for the host, who holds a spot and cancels the event instead of leaving.
 */
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
  const calendar = useCanAddToCalendar(event);

  let action: ReactNode;
  if (status === 'CONFIRMED') {
    const joined = (
      <p role="status" className="pill pill-going lg:self-start">
        <CircleCheck aria-hidden="true" />
        Joined
      </p>
    );
    // With Add to calendar: one row in the phone bar, a stack in the desktop card.
    action = calendar ? (
      <div className="flex flex-wrap items-center gap-2 lg:flex-col lg:items-stretch lg:gap-2.5">
        {joined}
        <AddToCalendar event={event} className="flex-1" buttonClassName="btn-primary px-3" />
        <LeaveEvent event={event} kind="confirmed" look="btn-ghost max-lg:px-3" />
      </div>
    ) : (
      <div className="flex flex-wrap items-center justify-between gap-3">
        {joined}
        <LeaveEvent event={event} kind="confirmed" />
      </div>
    );
  } else if (status === 'WAITLISTED') {
    action = (
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p role="status" className="pill pill-waitlisted">
          <Hourglass aria-hidden="true" />
          {position === null ? 'Waitlisted' : `Waitlisted #${position}`}
        </p>
        <LeaveEvent event={event} kind="waitlisted" />
      </div>
    );
  } else if (status === 'OFFERED' && cancelled) {
    // D-014 rule 16: the offer stays open until its deadline, but nothing can be accepted any more.
    action = (
      <Notice icon={CalendarX}>You were offered a spot, but this event was cancelled.</Notice>
    );
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
    action = <Notice icon={Ban}>You can’t rejoin this event.</Notice>;
  } else if (cancelled) {
    action = <Notice icon={CalendarX}>Signup is closed because the event was cancelled.</Notice>;
  } else {
    action = started ? (
      <Notice icon={Lock}>Signup is closed because the event has started.</Notice>
    ) : (
      <JoinButton key={event.signupOpensAt} event={event} full={spots?.spotsLeft === 0} />
    );
  }

  return (
    <section aria-label="Spots and signup" className="flex flex-col gap-2">
      {/* Always mounted, so a screen reader announces the text when it appears. */}
      <p role="status" className="text-sm font-semibold empty:sr-only">
        {showLapsed ? 'Your offer expired. The spot went to the next person in line.' : null}
      </p>
      {action}
    </section>
  );
}
