import type { Event } from '../../api/events';

/** With no end time, a meetup counts as 3 hours long, as the API's "upcoming" does. */
const DEFAULT_LENGTH_MS = 3 * 60 * 60 * 1000;

/** When a meetup is over, in milliseconds since the epoch. */
export function endTime(event: Pick<Event, 'startsAt' | 'endsAt'>): number {
  return event.endsAt ? Date.parse(event.endsAt) : Date.parse(event.startsAt) + DEFAULT_LENGTH_MS;
}

/** `20300701T200000Z`: the UTC form that iCalendar and Google Calendar both take. */
function utcStamp(at: string | number): string {
  return new Date(at).toISOString().replace(/[-:]|\.\d+/g, '');
}

/** The location, only when this viewer can see it (D-013). */
function visibleLocation(event: Event): string | null {
  return event.locationHidden ? null : event.location;
}

/** Control characters, apart from tabs and line breaks. */
const CONTROLS = /(?![\t\n\r])\p{Cc}/gu;

/**
 * The description and the event's own time zone. Google Calendar reads HTML in a description, and
 * the host's text is plain text, so a space goes after any `<` that could start a tag. Control
 * characters go first, so that removing one can't join a `<` to a tag name afterwards.
 */
function details(event: Event): string {
  const description = event.description?.replace(CONTROLS, '').replace(/<(?=[a-z/!?])/gi, '< ');
  return [description, `Event time zone: ${event.timeZone}`].filter(Boolean).join('\n\n');
}

/** An iCalendar TEXT value (RFC 5545 3.3.11): no control characters, so nothing starts a new line. */
function escapeText(text: string): string {
  return text
    .replace(/\r\n|[\r\n\u2028\u2029]/g, '\n')
    .replace(CONTROLS, '')
    .replace(/[\\;,]/g, '\\$&')
    .replace(/\n/g, '\\n');
}

const encoder = new TextEncoder();

/**
 * Folds a content line at 75 octets of UTF-8 (RFC 5545 3.1), never inside a character. The space
 * that starts a continuation line counts toward its 75.
 */
function fold(line: string): string {
  let folded = '';
  let octets = 0;
  for (const character of line) {
    const size = encoder.encode(character).length;
    if (octets + size > 75) {
      folded += '\r\n ';
      octets = 1;
    }
    folded += character;
    octets += size;
  }
  return folded;
}

/**
 * A one-off `.ics` file for one meetup (D-018), built from what the viewer already has. `origin` is
 * the app's, for the link back to the event page.
 */
export function icsFile(event: Event, origin: string, now = Date.now()): string {
  const location = visibleLocation(event);
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Con-Fuzion//Con-Fuzion//EN',
    'BEGIN:VEVENT',
    // The same for every download, so a calendar can tell it is the same meetup.
    `UID:${event.id}@con-fuzion`,
    `DTSTAMP:${utcStamp(now)}`,
    `DTSTART:${utcStamp(event.startsAt)}`,
    `DTEND:${utcStamp(endTime(event))}`,
    `SUMMARY:${escapeText(event.title)}`,
    `DESCRIPTION:${escapeText(details(event))}`,
    ...(location ? [`LOCATION:${escapeText(location)}`] : []),
    `URL:${origin}/events/${event.id}`,
    'END:VEVENT',
    'END:VCALENDAR',
  ];
  return lines.map((line) => `${fold(line)}\r\n`).join('');
}

/** The title as a file name: without the characters file systems refuse, and short enough for them. */
export function icsFileName(title: string): string {
  const safe = title.replace(/[\p{Cc}\p{Cf}\s<>:"/\\|?*]+/gu, ' ');
  const name = Array.from(safe)
    .slice(0, 60)
    .join('')
    .replace(/^[. ]+|[. ]+$/g, '');
  return `${name || 'meetup'}.ics`;
}

/** Google Calendar's "new event" page, filled in. Opening it sends these fields to Google (D-018). */
export function googleCalendarUrl(event: Event): string {
  const location = visibleLocation(event);
  const query = new URLSearchParams({
    action: 'TEMPLATE',
    text: event.title,
    dates: `${utcStamp(event.startsAt)}/${utcStamp(endTime(event))}`,
    details: details(event),
    ...(location && { location }),
  });
  return `https://calendar.google.com/calendar/render?${query}`;
}
