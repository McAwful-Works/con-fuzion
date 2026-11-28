import { describe, expect, it } from 'vitest';
import type { Event } from '../../api/events';
import { endTime, googleCalendarUrl, icsFile, icsFileName } from './ics';

const walk: Event = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  title: 'Cosplay photo walk',
  description: 'Bring props.',
  location: 'Hall B fountain',
  confirmWindowMinutes: 30,
  locationHidden: false,
  locationVisibility: 'VIEWERS',
  tag: 'Photoshoot',
  host: {
    id: '11111111-1111-4111-8111-111111111111',
    displayName: 'Ada',
    avatarUrl: 'https://cdn.discordapp.com/embed/avatars/1.png',
  },
  hiddenReason: null,
  isHost: false,
  myStatus: 'CONFIRMED',
  status: 'PUBLISHED',
  audience: null,
  spotsLeft: 7,
  visibleBecause: [],
  capacity: 10,
  signupOpensAt: null,
  // 3 PM to 5 PM in Chicago, which is UTC-5 in July.
  startsAt: '2030-07-01T20:00:00Z',
  endsAt: '2030-07-01T22:00:00Z',
  timeZone: 'America/Chicago',
};

const ORIGIN = 'https://confuzion.example';
const NOW = Date.parse('2030-06-01T12:30:45.123Z');
const file = (event: Partial<Event> = {}) => icsFile({ ...walk, ...event }, ORIGIN, NOW);
/** The content lines as a calendar app reads them: continuation lines joined back (RFC 5545 3.1). */
const lines = (text: string) => text.replace(/\r\n /g, '').split('\r\n');
const octets = (text: string) => new TextEncoder().encode(text).length;

describe('the .ics file', () => {
  it('is one calendar with one meetup, times in UTC, and CRLF line endings', () => {
    expect(file()).toBe(
      [
        'BEGIN:VCALENDAR',
        'VERSION:2.0',
        'PRODID:-//Con-Fuzion//Con-Fuzion//EN',
        'BEGIN:VEVENT',
        'UID:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa@con-fuzion',
        'DTSTAMP:20300601T123045Z',
        'DTSTART:20300701T200000Z',
        'DTEND:20300701T220000Z',
        'SUMMARY:Cosplay photo walk',
        'DESCRIPTION:Bring props.\\n\\nEvent time zone: America/Chicago',
        'LOCATION:Hall B fountain',
        'URL:https://confuzion.example/events/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        'END:VEVENT',
        'END:VCALENDAR',
        '',
      ].join('\r\n'),
    );
  });

  it('ends 3 hours after the start when there is no end time', () => {
    expect(lines(file({ endsAt: null }))).toContain('DTEND:20300701T230000Z');
    expect(endTime({ ...walk, endsAt: null })).toBe(Date.parse('2030-07-01T23:00:00Z'));
  });

  it('keeps the same UID when the host changes the meetup', () => {
    const uid = (text: string) => lines(text).find((line) => line.startsWith('UID:'));
    expect(uid(file({ title: 'New title', startsAt: '2030-08-01T20:00:00Z' }))).toBe(uid(file()));
  });

  it.each<[string, Partial<Event>]>([
    ['hidden from the viewer', { location: null, locationHidden: true }],
    ['marked hidden, even if one came with it', { locationHidden: true }],
  ])('has no LOCATION when it is %s', (_, event) => {
    expect(file(event)).not.toContain('LOCATION');
    expect(file(event)).not.toContain('Hall B');
  });

  it('escapes backslashes, semicolons, commas and new lines', () => {
    const text = lines(
      file({
        title: 'Cards; dice, and\\or tiles',
        description: 'One\r\nTwo\rThree\nFour',
        location: 'Hall B, table 4; \\north',
      }),
    );
    expect(text).toContain('SUMMARY:Cards\\; dice\\, and\\\\or tiles');
    expect(text).toContain(
      'DESCRIPTION:One\\nTwo\\nThree\\nFour\\n\\nEvent time zone: America/Chicago',
    );
    expect(text).toContain('LOCATION:Hall B\\, table 4\\; \\\\north');
  });

  it('lets no text start a line of its own', () => {
    const text = lines(file({ title: 'Walk\r\nATTACH:https://evil.example\u0000\u0007\u2028END' }));
    expect(text).toContain('SUMMARY:Walk\\nATTACH:https://evil.example\\nEND');
    expect(text.some((line) => line.startsWith('ATTACH'))).toBe(false);
  });

  it.each([
    ['plain letters', 'a'.repeat(300)],
    ['two-byte letters', 'é'.repeat(200)],
    ['three-byte characters', '東京'.repeat(60)],
    ['four-byte characters', '🎲'.repeat(60)],
    ['a mix', 'Ramen 🍜 run, 東京; é '.repeat(12)],
  ])('folds %s at 75 octets without splitting a character', (_, description) => {
    const text = file({ description });
    const physical = text.split('\r\n');
    for (const line of physical) expect(octets(line)).toBeLessThanOrEqual(75);
    // A split character would leave half a surrogate pair on a line.
    for (const line of physical) expect(line).not.toMatch(/\p{Cs}/u);
    const escaped = description.replace(/[;,]/g, '\\$&');
    expect(lines(text)).toContain(`DESCRIPTION:${escaped}\\n\\nEvent time zone: America/Chicago`);
  });

  it('fills each line before folding', () => {
    // "SUMMARY:" is 8 octets, so 67 letters fill the first line, and 74 each line after its space.
    const text = file({ title: 'a'.repeat(67 + 74 + 1) });
    expect(text).toContain(`SUMMARY:${'a'.repeat(67)}\r\n ${'a'.repeat(74)}\r\n a\r\n`);
  });

  it('says only the time zone when there is no description', () => {
    expect(lines(file({ description: null }))).toContain(
      'DESCRIPTION:Event time zone: America/Chicago',
    );
  });

  it('leaves no tag for a calendar that reads HTML', () => {
    const description = '<a href="https://evil.example">map</a> <!-- <3 1 < 2';
    expect(lines(file({ description }))).toContain(
      'DESCRIPTION:< a href="https://evil.example">map< /a> < !-- <3 1 < 2' +
        '\\n\\nEvent time zone: America/Chicago',
    );
    const google = new URL(googleCalendarUrl({ ...walk, description }));
    expect(google.searchParams.get('details')).toContain('< a href=');
    expect(google.searchParams.get('details')).not.toContain('<a');
  });

  it.each([1, 7, 127, 133])('can’t be given a tag by dropping control character %i', (code) => {
    const hidden = String.fromCharCode(code);
    const description = `<${hidden}a href=x>map<${hidden}/a>`;
    const safe = '< a href=x>map< /a>\n\nEvent time zone: America/Chicago';
    expect(lines(file({ description }))).toContain(`DESCRIPTION:${safe.replace(/\n/g, '\\n')}`);
    const google = new URL(googleCalendarUrl({ ...walk, description }));
    expect(google.searchParams.get('details')).toBe(safe);
  });
});

describe('the file name', () => {
  it.each([
    ['Cosplay photo walk', 'Cosplay photo walk.ics'],
    ['Q&A: "Dice" <night> / games?', 'Q&A Dice night games.ics'],
    ['..\\..\\secret|*', 'secret.ics'],
    ['  tabs\tand\nlines  ', 'tabs and lines.ics'],
    ['gnp.\u202eexe', 'gnp. exe.ics'],
    ['東京 meetup 🎲', '東京 meetup 🎲.ics'],
    ['///', 'meetup.ics'],
    ['🎲'.repeat(100), `${'🎲'.repeat(60)}.ics`],
  ])('is made safe from %j', (title, name) => {
    expect(icsFileName(title)).toBe(name);
  });
});

describe('the Google Calendar link', () => {
  const params = (event: Partial<Event> = {}) => {
    const url = new URL(googleCalendarUrl({ ...walk, ...event }));
    expect(url.origin + url.pathname).toBe('https://calendar.google.com/calendar/render');
    return Object.fromEntries(url.searchParams);
  };

  it('carries the title, the UTC times, the details and the location, and nothing else', () => {
    expect(params()).toEqual({
      action: 'TEMPLATE',
      text: 'Cosplay photo walk',
      dates: '20300701T200000Z/20300701T220000Z',
      details: 'Bring props.\n\nEvent time zone: America/Chicago',
      location: 'Hall B fountain',
    });
  });

  it('ends 3 hours after the start when there is no end time', () => {
    expect(params({ endsAt: null }).dates).toBe('20300701T200000Z/20300701T230000Z');
  });

  it('has no location when the viewer can’t see it', () => {
    expect(params({ locationHidden: true })).not.toHaveProperty('location');
    expect(googleCalendarUrl({ ...walk, locationHidden: true })).not.toContain('Hall');
  });

  it('encodes text that would otherwise add parameters', () => {
    expect(params({ title: 'Tea & cake&add=someone@example.com #1' })).toMatchObject({
      text: 'Tea & cake&add=someone@example.com #1',
    });
    expect(params({ title: 'a&add=b' })).not.toHaveProperty('add');
  });
});
