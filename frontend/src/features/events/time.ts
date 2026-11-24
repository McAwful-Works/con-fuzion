import type { Event } from '../../api/events';

/** The browser's own time zone, for a viewer whose profile has none yet. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

/**
 * The canonical spelling of an IANA zone name (`america/chicago` becomes `America/Chicago`), or
 * null when the browser doesn't know it. The server matches names exactly, so what we send must be
 * the canonical form. Offsets such as `+05:30` are accepted by some engines but aren't zone names.
 */
export function canonicalTimeZone(zone: string): string | null {
  if (/^[+-]/.test(zone.trim())) return null;
  try {
    return new Intl.DateTimeFormat('en-US', { timeZone: zone.trim() }).resolvedOptions().timeZone;
  } catch {
    return null;
  }
}

/** True when `zone` is an IANA name this browser knows. */
export function isValidTimeZone(zone: string): boolean {
  return canonicalTimeZone(zone) !== null;
}

/** The IANA names the browser knows, for the time zone suggestions. */
export function knownTimeZones(): string[] {
  const supported = (Intl as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
  return supported ? supported('timeZone') : [];
}

const PARTS = new Map<string, Intl.DateTimeFormat>();
function partsFormat(zone: string) {
  let format = PARTS.get(zone);
  if (!format) {
    format = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    PARTS.set(zone, format);
  }
  return format;
}

/** The wall-clock fields of an instant in `zone`, as milliseconds since the epoch read as UTC. */
function wallClockMs(instantMs: number, zone: string): number {
  const p: Record<string, number> = {};
  for (const part of partsFormat(zone).formatToParts(new Date(instantMs))) {
    if (part.type !== 'literal') p[part.type] = Number(part.value);
  }
  return Date.UTC(p.year!, p.month! - 1, p.day, p.hour, p.minute, p.second);
}

// Some browsers and autofill add seconds to a datetime-local value; they are ignored.
const LOCAL_FORMAT = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/;

/**
 * A `datetime-local` value read in `zone` becomes a UTC ISO string. A wall time that doesn't exist
 * (the hour skipped when clocks go forward) resolves to the same clock time after the gap, so
 * 2:30 in a zone that jumps from 2:00 to 3:00 becomes 3:30. Null when the text isn't a date and time.
 */
export function zonedToUtcIso(local: string, zone: string): string | null {
  const m = LOCAL_FORMAT.exec(local);
  if (!m || !isValidTimeZone(zone)) return null;
  const wanted = Date.UTC(+m[1]!, +m[2]! - 1, +m[3]!, +m[4]!, +m[5]!);
  // Take the zone's offset at a first guess, then again at the corrected instant, because the
  // offset can change between the two (a daylight-saving boundary).
  const first = wanted - (wallClockMs(wanted, zone) - wanted);
  const second = wanted - (wallClockMs(first, zone) - first);
  // In a gap neither guess lands on the wanted wall time; the later one is the "after the gap" answer
  // in zones on either side of UTC. Elsewhere the two agree, or the second is exact.
  const instant = wallClockMs(second, zone) === wanted ? second : Math.max(first, second);
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** A UTC instant as a `datetime-local` value (`2030-01-02T15:30`) in `zone`. */
export function utcToZonedLocal(iso: string, zone: string): string {
  const wall = new Date(wallClockMs(new Date(iso).getTime(), zone));
  return wall.toISOString().slice(0, 16);
}

/** `2030-01-02` for the day an instant falls on in `zone`. Events are grouped by this. */
export function dayKey(iso: string, zone: string): string {
  return utcToZonedLocal(iso, zone).slice(0, 10);
}

/** "Sat, Jan 2" for a `dayKey`. Noon UTC keeps the date the same in every time zone. */
export function dayHeading(key: string): string {
  return new Intl.DateTimeFormat(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(`${key}T12:00:00Z`));
}

function clock(iso: string, zone: string, withZoneName: boolean, withDate = false): string {
  return new Intl.DateTimeFormat(undefined, {
    timeZone: zone,
    hour: 'numeric',
    minute: '2-digit',
    ...(withDate && { weekday: 'short', month: 'short', day: 'numeric' }),
    ...(withZoneName && { timeZoneName: 'short' }),
  }).format(new Date(iso));
}

/** "3:00 PM – 5:00 PM CST" in `zone`. The zone name appears once, after the last time. */
function range(startsAt: string, endsAt: string | null, zone: string, withDate: boolean): string {
  if (!endsAt) return clock(startsAt, zone, true, withDate);
  const sameDay = dayKey(startsAt, zone) === dayKey(endsAt, zone);
  const start = clock(startsAt, zone, false, withDate);
  const end = clock(endsAt, zone, true, withDate || !sameDay);
  return `${start} – ${end}`;
}

export interface EventTimes {
  /** The time in the event's own zone. */
  event: string;
  /** The time in the viewer's zone, only when it reads differently from the event's. */
  viewer: string | null;
}

/**
 * The times to show for an event: always in the event's zone (so "3 PM" means 3 PM where the con
 * is), and also in the viewer's zone when that reads differently.
 */
export function eventTimes(
  event: Pick<Event, 'startsAt' | 'endsAt' | 'timeZone'>,
  viewerZone: string,
): EventTimes {
  const own = range(event.startsAt, event.endsAt, event.timeZone, false);
  if (viewerZone === event.timeZone || !isValidTimeZone(viewerZone)) {
    return { event: own, viewer: null };
  }
  // Add the date when the viewer's day for the start isn't the event's day.
  const otherDay = dayKey(event.startsAt, viewerZone) !== dayKey(event.startsAt, event.timeZone);
  const theirs = range(event.startsAt, event.endsAt, viewerZone, otherDay);
  return { event: own, viewer: theirs === own ? null : theirs };
}

export interface DayGroup {
  key: string;
  heading: string;
  events: Event[];
}

/**
 * Groups events (already sorted by start) by the day they start on in their own time zone. Events
 * in different zones can start on the same calendar day in a different order than the instants, so
 * the days are sorted by date and each day keeps the server's order.
 */
export function groupByDay(events: Event[]): DayGroup[] {
  const groups = new Map<string, DayGroup>();
  for (const event of events) {
    const key = dayKey(event.startsAt, event.timeZone);
    const group = groups.get(key);
    if (group) group.events.push(event);
    else groups.set(key, { key, heading: dayHeading(key), events: [event] });
  }
  return [...groups.values()].sort((a, b) => a.key.localeCompare(b.key));
}
