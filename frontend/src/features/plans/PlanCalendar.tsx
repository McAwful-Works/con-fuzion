import { Ban, Info, Lock, MapPin } from 'lucide-react';
import { Fragment, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import type { Event } from '../../api/events';
import { monthOf } from '../../components/month';
import { MonthGrid } from '../../components/MonthGrid';
import { dayParts, startClock } from '../events/cardTime';
import { HIDDEN_LOCATION_TEXT, Pill, viewerStatus } from '../events/EventCard';
import { findOverlaps } from '../events/schedule';
import { dayHeading, dayKey, eventTimes } from '../events/time';
import { useViewerZone } from '../events/useViewerZone';

/** How a plan is marked: by a dot where cells are narrow, by its title's chip where they're wide. */
export const KINDS = {
  going: { label: 'Going', dot: 'bg-dot-going', chip: 'bg-going text-going-ink' },
  waiting: { label: 'Waiting', dot: 'bg-dot-waiting', chip: 'bg-waiting text-waiting-ink' },
  offer: { label: 'Offer', dot: 'bg-dot-offer', chip: 'bg-offer text-offer-ink' },
  hosting: { label: 'Hosting', dot: 'bg-dot-hosting', chip: 'bg-hosting text-hosting-ink' },
  cancelled: {
    label: 'Cancelled',
    dot: 'bg-dot-cancelled',
    chip: 'border-control-outline text-ink-muted border line-through',
  },
};

export function kindOf(event: Event): keyof typeof KINDS {
  if (event.status === 'CANCELLED') return 'cancelled';
  if (event.isHost) return 'hosting';
  if (event.myStatus === 'OFFERED') return 'offer';
  return event.myStatus === 'WAITLISTED' ? 'waiting' : 'going';
}

/** The plans on each day, the day being the one they start on where the viewer is. */
export function byViewerDay(plans: Event[], zone: string): Map<string, Event[]> {
  const days = new Map<string, Event[]>();
  for (const event of plans) {
    const key = dayKey(event.startsAt, zone);
    days.set(key, [...(days.get(key) ?? []), event]);
  }
  return days;
}

export interface CalendarState {
  month: string;
  selected: string;
  today: string;
  showMonth: (month: string) => void;
  select: (day: string) => void;
}

/**
 * The month shown and the day picked. Both start at the next plan's day, or today when there is
 * none, as the plans are when first given: pass none while the calendar isn't showing.
 */
export function useCalendarState(plans: Event[] | undefined): CalendarState | undefined {
  const zone = useViewerZone();
  const [now] = useState(() => new Date().toISOString());
  const today = dayKey(now, zone);
  const [state, setState] = useState<{ month: string; selected: string }>();
  if (!state) {
    if (plans) {
      const next = plans.find((event) => event.status !== 'CANCELLED');
      const day = next ? dayKey(next.startsAt, zone) : today;
      setState({ month: monthOf(day), selected: day });
    }
    return undefined;
  }
  return {
    ...state,
    today,
    showMonth: (month) => setState((was) => was && { ...was, month }),
    select: (selected) => setState((was) => was && { ...was, selected }),
  };
}

/** "1 PM", or "13:00" in a 24-hour locale: what fits in a cell, in the event's own zone. */
function shortTime(event: Event): string {
  const { time, period } = startClock(event.startsAt, event.timeZone);
  return period ? `${time.replace(/:00$/, '')} ${period}` : time;
}

/**
 * Under a day's number: up to three dots, or from 1280px two titles and how many more. Between
 * 1024px and there, the day list beside the grid leaves cells too narrow for a title.
 */
function DayMarks({ events }: { events: Event[] }) {
  if (events.length === 0) return null;
  return (
    <>
      <span className="mt-1 flex gap-0.75 xl:hidden">
        {events.slice(0, 3).map((event) => (
          <span
            key={event.id}
            data-dot={kindOf(event)}
            className={`size-1.75 rounded-full ${KINDS[kindOf(event)].dot}`}
          />
        ))}
      </span>
      <span className="mt-1 flex min-w-0 flex-col gap-1 text-left text-[0.71875rem] font-bold max-xl:hidden">
        {events.slice(0, 2).map((event) => (
          <span
            key={event.id}
            data-chip={kindOf(event)}
            className={`rounded-tag truncate px-1.5 py-0.5 ${KINDS[kindOf(event)].chip}`}
          >
            {shortTime(event)} {event.title}
          </span>
        ))}
        {events.length > 2 && (
          <span className="text-ink-muted px-1.5">+{events.length - 2} more</span>
        )}
      </span>
    </>
  );
}

export const counted = (count: number, noun: string) =>
  count === 0 ? `no ${noun}s` : `${count} ${noun}${count === 1 ? '' : 's'}`;

/** The month with your plans on it, and under dots what their colors mean. */
export function PlanMonth({ plans, calendar }: { plans: Event[]; calendar: CalendarState }) {
  const days = byViewerDay(plans, useViewerZone());
  const on = (day: string) => days.get(day) ?? [];
  const cancelled = plans.some((event) => event.status === 'CANCELLED');
  return (
    <MonthGrid
      month={calendar.month}
      onMonthChange={calendar.showMonth}
      selected={calendar.selected}
      onSelect={calendar.select}
      today={calendar.today}
      dayLabel={(day) => counted(on(day).length, 'plan')}
      renderDay={(day) => <DayMarks events={on(day)} />}
      titles
    >
      <ul
        aria-label="Dot colors"
        className="text-ink-muted flex flex-wrap gap-x-3 gap-y-1 px-1.5 pt-2.5 pb-1 text-xs font-semibold xl:hidden"
      >
        {Object.entries(KINDS)
          .filter(([kind]) => kind !== 'cancelled' || cancelled)
          .map(([kind, { label, dot }]) => (
            <li key={kind} className="flex items-center gap-1.5">
              <span aria-hidden="true" className={`size-2.25 rounded-full ${dot}`} />
              {label}
            </li>
          ))}
      </ul>
    </MonthGrid>
  );
}

function DayPlan({ event, day }: { event: Event; day: string }) {
  const times = eventTimes({ ...event, endsAt: null }, useViewerZone());
  const [now] = useState(() => Date.now());
  const status =
    event.status === 'CANCELLED'
      ? { text: 'Cancelled', variant: 'pill-full', icon: Ban }
      : viewerStatus(event, now);
  // The event's own date, when it isn't the day it's listed under.
  const ownDay = dayKey(event.startsAt, event.timeZone);
  const Place = event.locationHidden ? Lock : MapPin;
  return (
    <li>
      <Link
        to={`/events/${event.id}`}
        className="card focus-visible:outline-offset-3 grid grid-cols-[4.5rem_minmax(0,1fr)] items-center gap-x-3 gap-y-1 p-3.5 lg:grid-cols-1"
      >
        <span className="font-head text-[0.9375rem] font-extrabold lg:text-sm">
          {ownDay !== day && `${dayHeading(ownDay)} · `}
          {times.event}
        </span>
        {/* The pill goes under the text when there isn't room beside it. */}
        <span className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1.5">
          <span className="min-w-36 flex-1">
            <span className="block font-bold break-words">{event.title}</span>
            <span className="text-ink-muted flex items-start gap-1.5 text-[0.8125rem]">
              <Place aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
              <span className="min-w-0 break-words">
                {event.locationHidden ? HIDDEN_LOCATION_TEXT : event.location}
              </span>
            </span>
            {times.viewer && (
              <span className="text-ink-muted block text-[0.8125rem]">
                Your time: {times.viewer}
              </span>
            )}
          </span>
          {status && <Pill {...status} />}
        </span>
      </Link>
    </li>
  );
}

/** Each pair of overlapping meetups with at least one of them on this day, once. */
function clashes(events: Event[], overlaps: Map<string, Event[]>): [string, string][] {
  const pairs = new Map<string, string>();
  for (const event of events) {
    for (const other of overlaps.get(event.id) ?? []) {
      const key = [event.id, other.id].sort().join(' ');
      if (!pairs.has(key)) pairs.set(key, `“${event.title}” and “${other.title}” overlap`);
    }
  }
  return [...pairs];
}

/** The picked day's meetups, and which of them clash. */
export function DayPlans({
  plans,
  all,
  day,
  after,
}: {
  /** The plans the segment shows. */
  plans: Event[];
  /** Every plan, so that a clash with one in another segment is still marked. */
  all: Event[];
  day: string;
  /** A list item to put right after a meetup. */
  after: (event: Event) => ReactNode;
}) {
  const events = byViewerDay(plans, useViewerZone()).get(day) ?? [];
  const { weekday, date } = dayParts(day);
  return (
    <section aria-labelledby="picked-day" className="lg:card space-y-3 lg:p-4">
      <h2 id="picked-day" className="text-[1.0625rem] font-extrabold">
        {weekday}, {date}{' '}
        <span className="text-ink-muted font-sans text-sm font-semibold">
          · {counted(events.length, 'meetup')}
        </span>
      </h2>
      {events.length === 0 ? (
        <p className="text-ink-muted text-sm">Nothing planned this day.</p>
      ) : (
        <ul className="space-y-3">
          {events.map((event) => (
            <Fragment key={event.id}>
              <DayPlan event={event} day={day} />
              {after(event)}
            </Fragment>
          ))}
        </ul>
      )}
      {clashes(events, findOverlaps(all)).map(([key, text]) => (
        <p
          key={key}
          className="bg-waiting text-waiting-ink rounded-tag flex items-start gap-1.5 px-2 py-1 text-[0.78125rem] font-semibold"
        >
          <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span className="min-w-0 break-words">{text}</span>
        </p>
      ))}
    </section>
  );
}
