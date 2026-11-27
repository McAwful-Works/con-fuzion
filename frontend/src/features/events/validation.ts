import {
  AUDIENCE_MAX,
  CONFIRM_WINDOW_DEFAULT,
  CONFIRM_WINDOW_MAX,
  CONFIRM_WINDOW_MIN,
  EVENT_CAPACITY_MAX,
  EVENT_CAPACITY_MIN,
  EVENT_DESCRIPTION_MAX,
  EVENT_LOCATION_MAX,
  EVENT_TAG_MAX,
  EVENT_TITLE_MAX,
  type AudienceEntry,
  type Event,
  type EventRequest,
} from '../../api/events';
import { toRequestEntry } from './audience';
import { canonicalTimeZone, utcToZonedLocal, zonedToUtcIso } from './time';

/** What the form holds. Times are `datetime-local` text read in `timeZone`. */
export interface EventFormValues {
  title: string;
  description: string;
  location: string;
  /** "Show location only to confirmed attendees". */
  locationConfirmedOnly: boolean;
  tag: string;
  timeZone: string;
  startsAt: string;
  endsAt: string;
  signupOpensAt: string;
  capacity: string;
  /** The offer window in minutes (D-014). */
  confirmWindowMinutes: string;
  audience: AudienceEntry[];
}

export type FieldErrors = Partial<Record<keyof EventFormValues, string>>;

const DAY = 24 * 60 * 60 * 1000;
/** D-012: an event lasts at most 7 days, starts within a year either way of now, and opens signup at most a year before. */
export const MAX_DURATION_MS = 7 * DAY;
export const MAX_RANGE_MS = 365 * DAY;

export function emptyValues(timeZone: string): EventFormValues {
  return {
    title: '',
    description: '',
    location: '',
    locationConfirmedOnly: false,
    tag: '',
    timeZone,
    startsAt: '',
    endsAt: '',
    signupOpensAt: '',
    capacity: '',
    confirmWindowMinutes: String(CONFIRM_WINDOW_DEFAULT),
    audience: [],
  };
}

/** The form's starting values for an existing event: times shown in the event's own zone. */
export function valuesFromEvent(event: Event): EventFormValues {
  const zone = event.timeZone;
  return {
    title: event.title,
    description: event.description ?? '',
    // The host always gets the location; `?? ''` only guards a malformed response.
    location: event.location ?? '',
    locationConfirmedOnly: event.locationVisibility === 'CONFIRMED',
    tag: event.tag ?? '',
    timeZone: zone,
    startsAt: utcToZonedLocal(event.startsAt, zone),
    endsAt: event.endsAt ? utcToZonedLocal(event.endsAt, zone) : '',
    signupOpensAt: event.signupOpensAt ? utcToZonedLocal(event.signupOpensAt, zone) : '',
    capacity: event.capacity === null ? '' : String(event.capacity),
    confirmWindowMinutes: String(event.confirmWindowMinutes),
    // The whole audience goes back (D-012: entries already on the event are kept).
    audience: event.audience ?? [],
  };
}

export interface ValidationResult {
  errors: FieldErrors;
  /** Set only when there are no errors. */
  request: EventRequest | null;
}

/**
 * Checks the form against the contract's limits and D-012's time bounds. The server enforces all
 * of them again; this only saves a round trip and says what's wrong in plain words.
 *
 * `original` is the event being edited. A time left as it was keeps its exact original instant (the
 * form only shows minutes), and an edit may keep a start that has already passed.
 */
export function validateEvent(
  values: EventFormValues,
  now: Date,
  original?: Pick<Event, 'startsAt' | 'endsAt' | 'signupOpensAt' | 'timeZone'>,
): ValidationResult {
  const errors: FieldErrors = {};
  const nowMs = now.getTime();

  const title = values.title.trim();
  if (!title) errors.title = 'Give your event a title.';
  else if (title.length > EVENT_TITLE_MAX)
    errors.title = `Keep the title to ${EVENT_TITLE_MAX} characters or fewer.`;

  const description = values.description.trim();
  if (description.length > EVENT_DESCRIPTION_MAX)
    errors.description = `Keep the description to ${EVENT_DESCRIPTION_MAX} characters or fewer.`;

  const location = values.location.trim();
  if (!location) errors.location = 'Say where it is.';
  else if (location.length > EVENT_LOCATION_MAX)
    errors.location = `Keep the location to ${EVENT_LOCATION_MAX} characters or fewer.`;

  const tag = values.tag.trim();
  if (tag.length > EVENT_TAG_MAX)
    errors.tag = `Keep the tag to ${EVENT_TAG_MAX} characters or fewer.`;

  // The server matches zone names exactly, so `america/chicago` is sent as `America/Chicago`.
  const zone = canonicalTimeZone(values.timeZone) ?? '';
  const zoneOk = zone.length > 0 && zone.length <= 64;
  if (!zoneOk) errors.timeZone = 'Choose a time zone from the list, such as America/Chicago.';

  // Times are read in the event's zone; without a valid zone they can't be read at all.
  const sameZone = original?.timeZone === zone;
  const read = (local: string, kept: string | null | undefined): number | null => {
    if (!zoneOk) return null;
    // Unchanged since the form opened: send the original instant, not the minute-rounded one.
    if (sameZone && kept && utcToZonedLocal(kept, zone) === local) return new Date(kept).getTime();
    const iso = zonedToUtcIso(local, zone);
    return iso ? new Date(iso).getTime() : null;
  };

  let startMs: number | null = null;
  if (!values.startsAt) errors.startsAt = 'Choose when it starts.';
  else {
    startMs = read(values.startsAt, original?.startsAt);
    const originalStart = original ? new Date(original.startsAt).getTime() : null;
    if (startMs === null) {
      if (zoneOk) errors.startsAt = 'Enter a valid start date and time.';
    } else if (Math.abs(startMs - nowMs) > MAX_RANGE_MS) {
      errors.startsAt = 'The start must be within a year of today.';
    } else if (startMs < nowMs && startMs !== originalStart) {
      // Create: always in the future. Edit: a start that has passed may stay, but not be moved there.
      errors.startsAt = 'The start can’t be in the past.';
    }
  }

  let endMs: number | null = null;
  if (values.endsAt) {
    endMs = read(values.endsAt, original?.endsAt);
    if (endMs === null) {
      if (zoneOk) errors.endsAt = 'Enter a valid end date and time.';
    } else if (startMs !== null) {
      if (endMs <= startMs) errors.endsAt = 'The end must be after the start.';
      else if (endMs - startMs > MAX_DURATION_MS)
        errors.endsAt = 'An event can last at most 7 days.';
    }
  }

  let signupMs: number | null = null;
  if (values.signupOpensAt) {
    signupMs = read(values.signupOpensAt, original?.signupOpensAt);
    if (signupMs === null) {
      if (zoneOk) errors.signupOpensAt = 'Enter a valid date and time.';
    } else if (startMs !== null) {
      if (signupMs >= startMs) errors.signupOpensAt = 'Signup must open before the event starts.';
      else if (startMs - signupMs > MAX_RANGE_MS)
        errors.signupOpensAt = 'Signup can open at most a year before the start.';
    }
  }

  let capacity: number | null = null;
  const capacityText = values.capacity.trim();
  if (capacityText) {
    capacity = Number(capacityText);
    if (
      !/^\d+$/.test(capacityText) ||
      capacity < EVENT_CAPACITY_MIN ||
      capacity > EVENT_CAPACITY_MAX
    ) {
      errors.capacity = `Capacity must be a whole number from ${EVENT_CAPACITY_MIN} to ${EVENT_CAPACITY_MAX}.`;
    }
  }

  // Never left empty: on an edit that would quietly reset the host's window.
  let confirmWindowMinutes = CONFIRM_WINDOW_DEFAULT;
  const windowText = values.confirmWindowMinutes.trim();
  if (!windowText) {
    errors.confirmWindowMinutes = 'Enter the offer window in minutes, such as 30.';
  } else {
    confirmWindowMinutes = Number(windowText);
    if (
      !/^\d+$/.test(windowText) ||
      confirmWindowMinutes < CONFIRM_WINDOW_MIN ||
      confirmWindowMinutes > CONFIRM_WINDOW_MAX
    ) {
      errors.confirmWindowMinutes = `The offer window must be a whole number of minutes from ${CONFIRM_WINDOW_MIN} to ${CONFIRM_WINDOW_MAX}.`;
    }
  }

  if (values.audience.length === 0) errors.audience = 'Choose at least one audience.';
  else if (values.audience.length > AUDIENCE_MAX)
    errors.audience = `Choose at most ${AUDIENCE_MAX} audience entries.`;

  if (Object.keys(errors).length > 0 || startMs === null) return { errors, request: null };

  const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());
  return {
    errors,
    request: {
      title,
      description: description || null,
      location,
      locationVisibility: values.locationConfirmedOnly ? 'CONFIRMED' : 'VIEWERS',
      tag: tag || null,
      timeZone: zone,
      startsAt: new Date(startMs).toISOString(),
      endsAt: iso(endMs),
      signupOpensAt: iso(signupMs),
      capacity,
      confirmWindowMinutes,
      audience: values.audience.map(toRequestEntry),
    },
  };
}
