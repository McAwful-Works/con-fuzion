import { CalendarDays, List, Plus, Timer } from 'lucide-react';
import { Fragment } from 'react';
import { Link, useSearchParams } from 'react-router';
import { usePlans, type Event } from '../api/events';
import { ErrorState } from '../components/ErrorState';
import { useDesktop } from '../components/shell/useDesktop';
import { NeedsAnswer } from '../features/discover/SideCards';
import { EventCard } from '../features/events/EventCard';
import { EventDays, LoadingCards } from '../features/events/EventFeed';
import { findOverlaps } from '../features/events/schedule';
import { dayHeading, dayKey } from '../features/events/time';
import { DayPlans, PlanMonth, useCalendarState } from '../features/plans/PlanCalendar';
import { NudgeCard } from '../features/push/NudgeCard';
import { isOpenOffer, useOfferCountdown } from '../features/rsvp/offers';
import { formatCountdown } from '../features/rsvp/signup';

interface Segment {
  /** The `?tab=` value; All has none. */
  id: 'all' | 'going' | 'waiting' | 'hosting';
  label: string;
  has: (event: Event) => boolean;
  empty: { title: string; text: string };
}

const ALL: Segment = {
  id: 'all',
  label: 'All',
  has: () => true,
  empty: {
    title: 'Nothing planned yet',
    text: 'Meetups you join, wait for or host show up here, so you can see them all in one place.',
  },
};

const SEGMENTS: Segment[] = [
  ALL,
  {
    id: 'going',
    label: 'Going',
    // The ones you host have their own segment.
    has: (event) => !event.isHost && event.myStatus === 'CONFIRMED',
    empty: { title: 'You’re not going to any meetups yet', text: 'Meetups you join show up here.' },
  },
  {
    id: 'waiting',
    label: 'Waiting',
    has: (event) => event.myStatus === 'WAITLISTED' || event.myStatus === 'OFFERED',
    empty: {
      title: 'You’re not waiting for a spot',
      text: 'Join the waitlist of a full meetup and it shows up here, with any spot offered to you.',
    },
  },
  {
    id: 'hosting',
    label: 'Hosting',
    has: (event) => event.isHost,
    empty: {
      title: 'You’re not hosting anything yet',
      text: 'Meetups you create show up here, including cancelled ones.',
    },
  },
];

const VIEWS = [
  { calendar: false, label: 'List', icon: List },
  { calendar: true, label: 'Calendar', icon: CalendarDays },
];

/**
 * My plans: what you're going to, waiting for or hosting, as a list by day or on a month calendar,
 * with clashes marked.
 */
export function Plans() {
  // In the URL so Back from an event returns to the segment and view it was opened from.
  const [params, setParams] = useSearchParams();
  const segment = SEGMENTS.find((s) => s.id === params.get('tab')) ?? ALL;
  const calendarView = params.get('view') === 'calendar';
  const show = (tab: Segment, calendar: boolean) =>
    setParams(
      { ...(tab !== ALL && { tab: tab.id }), ...(calendar && { view: 'calendar' }) },
      { replace: true },
    );
  const plans = usePlans();
  const events = plans.data;
  const shown = events?.filter(segment.has);
  const calendar = useCalendarState(calendarView ? shown : undefined);
  const desktop = useDesktop();
  // Beside the calendar on desktop, below it on phones.
  const day = calendarView && calendar && events && shown && (
    <DayPlans
      plans={shown}
      all={events}
      day={calendar.selected}
      after={(event) => isOpenOffer(event) && <OfferCountdown event={event} />}
    />
  );
  return (
    // `data-plans-page` makes the shell give this page room for the side column.
    <div
      data-plans-page
      className="lg:grid lg:grid-cols-[minmax(0,1fr)_17rem] lg:items-start lg:gap-8 xl:grid-cols-[minmax(0,1fr)_20rem]"
    >
      <div className="min-w-0 space-y-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            {events && events.length > 0 && (
              <p className="text-ink-muted text-xs font-bold tracking-wider uppercase">
                {events.length} upcoming
              </p>
            )}
            <h1 className="text-[1.75rem] leading-tight font-extrabold lg:text-[2.125rem]">
              My plans
            </h1>
          </div>
          <div role="group" aria-label="View" className="seg w-auto">
            {VIEWS.map(({ calendar, label, icon: Icon }) => (
              <button
                key={label}
                type="button"
                aria-pressed={calendar === calendarView}
                className="seg-item px-3"
                onClick={() => show(segment, calendar)}
              >
                <Icon aria-hidden="true" className="size-4" />
                {label}
              </button>
            ))}
          </div>
        </div>
        <div role="group" aria-label="Which plans" className="seg max-sm:gap-0.5">
          {SEGMENTS.map((s) => (
            <button
              key={s.id}
              type="button"
              aria-pressed={s === segment}
              className="seg-item max-sm:px-1"
              onClick={() => show(s, calendarView)}
            >
              {s.label}{' '}
              {events && (
                <span className="text-ink-muted text-xs font-semibold">
                  {events.filter(s.has).length}
                </span>
              )}
            </button>
          ))}
        </div>
        {!events ? (
          plans.isError ? (
            <ErrorState error={plans.error} onRetry={() => void plans.refetch()} />
          ) : (
            <LoadingCards />
          )
        ) : !calendarView ? (
          <PlanList plans={events} segment={segment} />
        ) : (
          calendar &&
          shown && (
            <>
              <PlanMonth plans={shown} calendar={calendar} />
              {!desktop && day}
            </>
          )
        )}
      </div>
      {desktop && (
        // Each card is a region of its own, and there may be none.
        <div className="space-y-4">
          {day}
          <NeedsAnswer plans={events ?? []} />
          <NudgeCard />
        </div>
      )}
    </div>
  );
}

function PlanList({ plans, segment }: { plans: Event[]; segment: Segment }) {
  const shown = plans.filter(segment.has);
  if (shown.length === 0) return <Empty segment={segment} />;
  // Against all your plans, so a clash with something in another segment is still marked.
  const overlaps = findOverlaps(plans);
  // In Waiting, the spots held for you come first, whatever day they're on.
  const offers = segment.id === 'waiting' ? shown.filter(isOpenOffer) : [];
  return (
    <div className="space-y-6">
      {offers.length > 0 && (
        <section aria-labelledby="offered-to-you" className="space-y-3">
          <h2 id="offered-to-you" className="text-[1.0625rem] font-extrabold">
            Offered to you
          </h2>
          <ul className="space-y-3">
            {offers.map((event) => (
              <Fragment key={event.id}>
                <EventCard event={event} overlaps={overlaps.get(event.id)} />
                {/* No day heading above these, so the countdown says the day. */}
                <OfferCountdown event={event} day />
              </Fragment>
            ))}
          </ul>
        </section>
      )}
      <EventDays
        events={shown.filter((event) => !offers.includes(event))}
        overlaps={overlaps}
        after={(event) => isOpenOffer(event) && <OfferCountdown event={event} />}
      />
    </div>
  );
}

/** Under the card of a spot held for you: how long it still is. */
function OfferCountdown({ event, day = false }: { event: Event; day?: boolean }) {
  const { gone, left } = useOfferCountdown(event);
  if (gone) return null;
  return (
    <li className="bg-offer text-offer-ink rounded-control -mt-1 flex items-start gap-2 px-3.5 py-2.5 text-sm font-bold">
      <Timer aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <span className="min-w-0">
        {day && `${dayHeading(dayKey(event.startsAt, event.timeZone))} · `}A spot is held for you
        {left !== null && (
          <>
            {' for '}
            {/* Not a live region: screen readers aren't told every second. */}
            <span role="timer" aria-live="off" className="tabular-nums">
              {formatCountdown(left)}
            </span>
          </>
        )}
      </span>
    </li>
  );
}

function Empty({ segment }: { segment: Segment }) {
  const find = segment.id !== 'hosting';
  const host = segment.id === 'all' || segment.id === 'hosting';
  return (
    <div className="card space-y-3 p-5">
      <h2 className="text-xl font-extrabold">{segment.empty.title}</h2>
      <p className="text-ink-muted">{segment.empty.text}</p>
      <div className="flex flex-col gap-3 pt-1 sm:flex-row">
        {find && (
          <Link to="/" className="btn btn-primary">
            Find meetups
          </Link>
        )}
        {host && (
          <Link to="/events/new" className={`btn ${find ? 'btn-secondary' : 'btn-primary'}`}>
            <Plus aria-hidden="true" className="size-5" />
            Host a meetup
          </Link>
        )}
      </div>
    </div>
  );
}
