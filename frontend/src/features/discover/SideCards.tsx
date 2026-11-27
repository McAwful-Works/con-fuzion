import { useQueryClient } from '@tanstack/react-query';
import { ArrowRight, CalendarCheck, Check, Timer } from 'lucide-react';
import { useEffect, useId, type ReactNode } from 'react';
import { Link } from 'react-router';
import { usePlans, type Event } from '../../api/events';
import { useGuilds } from '../../api/me';
import { useSpots } from '../../api/rsvp';
import { ErrorState } from '../../components/ErrorState';
import { dayHeading, dayKey, eventTimes } from '../events/time';
import { useViewerZone } from '../events/useViewerZone';
import { formatCountdown, useNow } from '../rsvp/signup';
import { sortedDiscoverable } from './servers';

function SideCard({ title, children }: { title: string; children: ReactNode }) {
  const id = useId();
  return (
    <section aria-labelledby={id} className="card p-4.5">
      <h2 id={id} className="mb-3 text-[0.9375rem] font-extrabold">
        {title}
      </h2>
      {children}
    </section>
  );
}

/** One spot being held for you, counting down to the deadline `/spots` gives (D-014). */
function Offer({ event }: { event: Event }) {
  const queryClient = useQueryClient();
  const query = useSpots(event);
  const spots = query.data;
  const expiresAt = spots?.myStatus === 'OFFERED' ? spots.offerExpiresAt : null;
  const deadline = expiresAt ? Date.parse(expiresAt) : null;
  const now = useNow(1000, deadline !== null);
  // Ran out, or `/spots` (asked since this appeared) says it's gone: accepted, declined or expired.
  const gone =
    (deadline !== null && deadline <= now) ||
    (query.isFetchedAfterMount && spots !== undefined && spots.myStatus !== 'OFFERED');

  useEffect(() => {
    if (gone) void queryClient.invalidateQueries({ queryKey: ['events'] });
  }, [gone, queryClient]);

  if (gone) return null;
  return (
    <li className="space-y-3">
      <div className="flex gap-3">
        <Timer aria-hidden="true" className="text-accent-text size-5.5 shrink-0" />
        <div className="min-w-0">
          <p className="font-bold break-words">{event.title}</p>
          <p className="text-ink-muted text-sm">
            A spot is held for you
            {deadline !== null && (
              <>
                {' for '}
                {/* Not a live region: screen readers aren't told every second. */}
                <span role="timer" aria-live="off" className="tabular-nums">
                  {formatCountdown(deadline - now)}
                </span>
              </>
            )}
          </p>
        </div>
      </div>
      <Link
        to={`/events/${event.id}`}
        aria-label={`Review offer: ${event.title}`}
        className="btn btn-primary btn-sm w-full"
      >
        Review offer
      </Link>
    </li>
  );
}

function NextUp({ event }: { event: Event }) {
  const viewerZone = useViewerZone();
  // Only the start: it's a glance, and the card and the event page have the rest.
  const times = eventTimes({ ...event, endsAt: null }, viewerZone);
  const day = dayHeading(dayKey(event.startsAt, event.timeZone));
  return (
    <li>
      <Link to={`/events/${event.id}`} className="rounded-control flex min-h-11 gap-3 py-1">
        <CalendarCheck aria-hidden="true" className="mt-0.5 size-4.5 shrink-0" />
        <span className="min-w-0">
          <span className="block font-bold break-words">{event.title}</span>
          <span className="text-ink-muted block text-sm">
            {day}, {times.event} · {event.isHost ? 'you’re hosting' : 'going'}
          </span>
          {times.viewer && (
            <span className="text-ink-muted block text-sm">Your time: {times.viewer}</span>
          )}
        </span>
      </Link>
    </li>
  );
}

function Discoverable() {
  const guilds = useGuilds();
  if (guilds.isError && !guilds.data) {
    return <ErrorState error={guilds.error} onRetry={() => void guilds.refetch()} />;
  }
  if (!guilds.data) return null;
  const on = sortedDiscoverable(guilds.data);
  return (
    <SideCard title="Where you’re discoverable">
      {on.length === 0 ? (
        <p className="text-ink-muted text-sm">You’re not discoverable in any server yet.</p>
      ) : (
        <ul className="flex flex-wrap gap-2">
          {on.map((guild) => (
            <li
              key={guild.id}
              className="border-control-outline flex max-w-full items-center gap-1.5 rounded-full border-2 px-3 py-1 text-sm font-semibold"
            >
              <Check aria-hidden="true" className="size-4 shrink-0" />
              <span className="min-w-0 truncate">{guild.name}</span>
            </li>
          ))}
        </ul>
      )}
      <Link
        to="/you"
        className="text-ink-muted rounded-control mt-1.5 inline-flex min-h-11 items-center gap-1 text-sm font-semibold"
      >
        Manage servers
        <ArrowRight aria-hidden="true" className="size-4" />
      </Link>
    </SideCard>
  );
}

/** Discover's desktop column: open offers, what's next for you, and your discoverable servers. */
export function SideCards() {
  const plans = usePlans();
  // A cancelled event keeps its offers until they run out, but nothing can be accepted (D-014 rule 16).
  const live = (plans.data ?? []).filter((event) => event.status === 'PUBLISHED');
  const offers = live.filter((event) => event.myStatus === 'OFFERED');
  const next = live.filter((event) => event.isHost || event.myStatus === 'CONFIRMED').slice(0, 2);
  return (
    <>
      {plans.isError && !plans.data && (
        <ErrorState error={plans.error} onRetry={() => void plans.refetch()} />
      )}
      {offers.length > 0 && (
        <SideCard title="Needs your answer">
          <ul className="space-y-4">
            {offers.map((event) => (
              <Offer key={event.id} event={event} />
            ))}
          </ul>
        </SideCard>
      )}
      {next.length > 0 && (
        <SideCard title="Next up for you">
          <ul className="space-y-2">
            {next.map((event) => (
              <NextUp key={event.id} event={event} />
            ))}
          </ul>
        </SideCard>
      )}
      <Discoverable />
    </>
  );
}
