import type { Event } from '../../api/events';

/** The server treats an event with no end as lasting 3 hours (D-012's "upcoming"); so do we. */
const DEFAULT_DURATION_MS = 3 * 60 * 60 * 1000;

function interval(event: Pick<Event, 'startsAt' | 'endsAt'>): [number, number] {
  const start = Date.parse(event.startsAt);
  return [start, event.endsAt ? Date.parse(event.endsAt) : start + DEFAULT_DURATION_MS];
}

/**
 * For each event, the other events it overlaps in time, keyed by event ID. Events that only touch
 * (one ends as the next starts) don't overlap, and cancelled events never do. Events with no
 * overlap are left out of the map.
 */
export function findOverlaps(events: Event[]): Map<string, Event[]> {
  const live = events.filter((e) => e.status !== 'CANCELLED');
  const overlaps = new Map<string, Event[]>();
  for (let i = 0; i < live.length; i++) {
    const a = live[i]!;
    const [aStart, aEnd] = interval(a);
    for (let j = i + 1; j < live.length; j++) {
      const b = live[j]!;
      const [bStart, bEnd] = interval(b);
      if (aStart < bEnd && bStart < aEnd) {
        overlaps.set(a.id, [...(overlaps.get(a.id) ?? []), b]);
        overlaps.set(b.id, [...(overlaps.get(b.id) ?? []), a]);
      }
    }
  }
  return overlaps;
}
