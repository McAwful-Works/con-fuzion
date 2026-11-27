import { useState } from 'react';
import { EVENT_CAPACITY_MAX, EVENT_CAPACITY_MIN, type Event } from '../../api/events';
import { useMe, type Me } from '../../api/me';
import { EventCard } from './EventCard';
import { dayKey, isValidTimeZone, zonedToUtcIso } from './time';
import type { EventFormValues } from './validation';

const DAY = 24 * 60 * 60 * 1000;

/** The event as its card would show it, from whatever the form holds right now. */
function previewEvent(
  values: EventFormValues,
  user: Me,
  startsAt: string,
  timeZone: string,
  original?: Event,
): Event {
  const typed = Number(values.capacity ?? '');
  const capacity =
    Number.isInteger(typed) && typed >= EVENT_CAPACITY_MIN && typed <= EVENT_CAPACITY_MAX
      ? typed
      : null;
  // An edit keeps the spots already taken. Otherwise it's only the host's.
  const taken =
    original && original.capacity !== null && original.spotsLeft !== null
      ? original.capacity - original.spotsLeft
      : 1;
  const end = zonedToUtcIso(values.endsAt, timeZone);
  return {
    id: original?.id ?? 'preview',
    title: values.title.trim() || 'Your meetup’s title',
    description: values.description.trim() || null,
    location: values.location.trim() || 'Where you’ll meet',
    // As other people see it, so the switch shows what it does.
    locationHidden: values.locationConfirmedOnly,
    locationVisibility: values.locationConfirmedOnly ? 'CONFIRMED' : 'VIEWERS',
    tag: values.tag.trim() || null,
    timeZone,
    startsAt,
    endsAt: end && end > startsAt ? end : null,
    signupOpensAt: zonedToUtcIso(values.signupOpensAt, timeZone),
    confirmWindowMinutes: Number(values.confirmWindowMinutes),
    capacity,
    spotsLeft: capacity === null ? null : Math.max(0, capacity - taken),
    host: { id: user.id, displayName: user.globalName ?? user.username, avatarUrl: user.avatarUrl },
    isHost: true,
    myStatus: 'CONFIRMED',
    status: 'PUBLISHED',
    hiddenReason: null,
    audience: values.audience,
    visibleBecause: null,
  };
}

/** The live preview of the Discover card. It can't be clicked or focused. */
export function EventPreview({ values, original }: { values: EventFormValues; original?: Event }) {
  const user = useMe().data;
  const [now] = useState(() => Date.now());
  if (!user) return null;
  const zone = isValidTimeZone(values.timeZone) ? values.timeZone : 'UTC';
  const chosen = zonedToUtcIso(values.startsAt, zone);
  // A card needs a time, so until there is one it shows noon tomorrow and says that it's made up.
  const tomorrow = dayKey(new Date(now + DAY).toISOString(), zone);
  const startsAt = chosen ?? zonedToUtcIso(`${tomorrow}T12:00`, zone)!;

  return (
    <section aria-labelledby="preview-heading" className="space-y-2.5">
      <h2
        id="preview-heading"
        className="text-ink-muted text-xs font-extrabold tracking-wider uppercase"
      >
        Preview in Discover
      </h2>
      <div inert>
        <ul>
          <EventCard event={previewEvent(values, user, startsAt, zone, original)} />
        </ul>
      </div>
      {!chosen && <p className="hint">The time is an example until you choose when it starts.</p>}
    </section>
  );
}
