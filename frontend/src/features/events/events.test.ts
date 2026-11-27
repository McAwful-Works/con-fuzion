import { describe, expect, it } from 'vitest';
import type { Event } from '../../api/events';
import { audienceSummary, circleEntry, friendsEntry, guildEntry, userEntry } from './audience';
import { dayKey, eventTimes, groupByDay, utcToZonedLocal, zonedToUtcIso } from './time';
import { emptyValues, validateEvent, valuesFromEvent, type EventFormValues } from './validation';

const NOW = new Date('2030-06-01T12:00:00Z');

describe('time zones', () => {
  it('reads a wall time in the event zone, summer and winter', () => {
    expect(zonedToUtcIso('2030-07-01T15:00', 'America/Chicago')).toBe('2030-07-01T20:00:00.000Z');
    expect(zonedToUtcIso('2030-01-15T15:00', 'America/Chicago')).toBe('2030-01-15T21:00:00.000Z');
    expect(zonedToUtcIso('2030-07-01T15:00', 'Asia/Tokyo')).toBe('2030-07-01T06:00:00.000Z');
  });

  it('round-trips an instant through a zone, including across a daylight-saving change', () => {
    for (const iso of [
      '2030-03-10T07:30:00.000Z',
      '2030-03-10T08:30:00.000Z',
      '2030-11-03T06:30:00.000Z',
    ]) {
      const local = utcToZonedLocal(iso, 'America/Chicago');
      expect(zonedToUtcIso(local, 'America/Chicago')).toBe(iso);
    }
  });

  it('moves a wall time that doesn’t exist to after the gap, on either side of UTC', () => {
    // Chicago skips 2:00 to 3:00 on 2030-03-10; Berlin skips 2:00 to 3:00 on 2030-03-31.
    expect(zonedToUtcIso('2030-03-10T02:30', 'America/Chicago')).toBe('2030-03-10T08:30:00.000Z');
    expect(zonedToUtcIso('2030-03-31T02:30', 'Europe/Berlin')).toBe('2030-03-31T01:30:00.000Z');
  });

  it('ignores seconds in a datetime-local value', () => {
    expect(zonedToUtcIso('2030-07-01T15:00:30', 'America/Chicago')).toBe(
      '2030-07-01T20:00:00.000Z',
    );
  });

  it('rejects text that is not a date and an unknown zone', () => {
    expect(zonedToUtcIso('tomorrow', 'America/Chicago')).toBeNull();
    expect(zonedToUtcIso('2030-07-01T15:00', 'Not/AZone')).toBeNull();
  });

  it('finds the day an event falls on in its own zone, not the viewer’s', () => {
    // 03:00 UTC on the 2nd is still the evening of the 1st in Chicago.
    expect(dayKey('2030-07-02T03:00:00Z', 'America/Chicago')).toBe('2030-07-01');
    expect(dayKey('2030-07-02T03:00:00Z', 'Asia/Tokyo')).toBe('2030-07-02');
  });
});

const base: Event = {
  id: 'e1',
  title: 'Photo walk',
  description: null,
  location: 'Hall B',
  confirmWindowMinutes: 30,
  locationHidden: false,
  locationVisibility: 'VIEWERS',
  tag: null,
  host: {
    id: 'h',
    displayName: 'Ada',
    avatarUrl: 'https://cdn.discordapp.com/embed/avatars/1.png',
  },
  hiddenReason: null,
  isHost: false,
  myStatus: 'NONE',
  status: 'PUBLISHED',
  audience: null,
  spotsLeft: null,
  visibleBecause: [],
  capacity: null,
  signupOpensAt: null,
  startsAt: '2030-07-01T20:00:00Z',
  endsAt: '2030-07-01T22:00:00Z',
  timeZone: 'America/Chicago',
};

describe('event times', () => {
  it('shows only the event’s zone when the viewer is in it', () => {
    const times = eventTimes(base, 'America/Chicago');
    expect(times.event).toMatch(/3:00.*3:00|3:00 PM/);
    expect(times.event).toContain('CDT');
    expect(times.viewer).toBeNull();
  });

  it('adds the viewer’s zone when it reads differently', () => {
    const times = eventTimes(base, 'Asia/Tokyo');
    expect(times.event).toContain('CDT');
    expect(times.viewer).toContain('GMT+9');
    // 05:00 the next morning in Tokyo, so the date is spelled out.
    expect(times.viewer).toMatch(/Tue|Jul/);
  });

  it('shows one line for two zones that read the same', () => {
    const winter = { ...base, startsAt: '2030-01-15T21:00:00Z', endsAt: null };
    // Mexico City and Chicago are both UTC-6 in January: same clock, both "CST".
    expect(eventTimes(winter, 'America/Mexico_City').viewer).toBeNull();
  });
});

describe('grouping the feed by day', () => {
  it('groups by the day in each event’s own zone and keeps days in order', () => {
    const evening = { ...base, id: 'a', startsAt: '2030-07-02T03:00:00Z', endsAt: null };
    const afternoon = { ...base, id: 'b', startsAt: '2030-07-01T20:00:00Z', endsAt: null };
    const tokyo = { ...base, id: 'c', timeZone: 'Asia/Tokyo', startsAt: '2030-07-02T03:00:00Z' };
    const groups = groupByDay([afternoon, evening, tokyo]);
    expect(groups.map((g) => [g.key, g.events.map((e) => e.id)])).toEqual([
      ['2030-07-01', ['b', 'a']],
      ['2030-07-02', ['c']],
    ]);
  });
});

describe('the audience summary', () => {
  it('doesn’t count a server the host is no longer discoverable in', () => {
    const idle = { ...guildEntry('81384788765712399', ''), label: null };
    expect(audienceSummary([idle])).toBe(
      'Only you can see this event as it stands. A server you’re not discoverable in won’t reach anyone until you’re discoverable there again.',
    );
    expect(audienceSummary([friendsEntry(), idle])).toBe(
      'This event will be visible to your friends. A server you’re not discoverable in won’t reach anyone until you’re discoverable there again.',
    );
  });

  it('asks for an audience when there is none', () => {
    expect(audienceSummary([])).toMatch(/at least one/);
  });

  it('says in words who will see it', () => {
    const text = audienceSummary([
      friendsEntry(),
      guildEntry('81384788765712384', 'Example Con'),
      circleEntry('c1', 'Cosplay crew'),
      userEntry('u1', 'Bo'),
    ]);
    expect(text).toBe(
      'This event will be visible to your friends, members of Example Con who’ve chosen to be discoverable there, people in your circle Cosplay crew and Bo.',
    );
  });
});

function valid(over: Partial<EventFormValues> = {}): EventFormValues {
  return {
    ...emptyValues('America/Chicago'),
    title: '  Photo walk ',
    location: 'Hall B',
    startsAt: '2030-06-10T15:00',
    audience: [friendsEntry()],
    ...over,
  };
}

describe('form validation', () => {
  it('builds the request, trimming text and sending UTC', () => {
    const { errors, request } = validateEvent(valid({ capacity: '12', tag: ' Photoshoot ' }), NOW);
    expect(errors).toEqual({});
    expect(request).toEqual({
      title: 'Photo walk',
      description: null,
      location: 'Hall B',
      locationVisibility: 'VIEWERS',
      tag: 'Photoshoot',
      timeZone: 'America/Chicago',
      startsAt: '2030-06-10T20:00:00.000Z',
      endsAt: null,
      signupOpensAt: null,
      capacity: 12,
      confirmWindowMinutes: 30,
      audience: [{ type: 'FRIENDS' }],
    });
  });

  it('sends the offer window, from 10 minutes to 24 hours', () => {
    expect(
      validateEvent(valid({ confirmWindowMinutes: '10' }), NOW).request?.confirmWindowMinutes,
    ).toBe(10);
    expect(
      validateEvent(valid({ confirmWindowMinutes: '1440' }), NOW).request?.confirmWindowMinutes,
    ).toBe(1440);
    for (const bad of ['', '  ', '9', '1441', '2.5', 'soon', '-30']) {
      const { errors, request } = validateEvent(valid({ confirmWindowMinutes: bad }), NOW);
      expect(errors.confirmWindowMinutes, bad).toBeTruthy();
      expect(request).toBeNull();
    }
  });

  it('starts an edit from the event’s own offer window and sends it back', () => {
    const values = valuesFromEvent({ ...base, confirmWindowMinutes: 90 });
    expect(values.confirmWindowMinutes).toBe('90');
    expect(emptyValues('America/Chicago').confirmWindowMinutes).toBe('30');
  });

  it('sends the confirmed-only setting', () => {
    const { request } = validateEvent(valid({ locationConfirmedOnly: true }), NOW);
    expect(request?.locationVisibility).toBe('CONFIRMED');
  });

  it('requires a title, a location, a start and at least one audience entry', () => {
    const { errors, request } = validateEvent(
      valid({ title: ' ', location: '', startsAt: '', audience: [] }),
      NOW,
    );
    expect(Object.keys(errors).sort()).toEqual(['audience', 'location', 'startsAt', 'title']);
    expect(request).toBeNull();
  });

  it('allows at most 50 audience entries', () => {
    const many = Array.from({ length: 51 }, (_, i) => userEntry(`u${i}`, `P${i}`));
    expect(validateEvent(valid({ audience: many }), NOW).errors.audience).toMatch(/at most 50/);
    expect(
      validateEvent(valid({ audience: many.slice(0, 50) }), NOW).errors.audience,
    ).toBeUndefined();
  });

  it('applies the field limits', () => {
    const { errors } = validateEvent(
      valid({
        title: 'x'.repeat(101),
        location: 'x'.repeat(201),
        tag: 'x'.repeat(31),
        description: 'x'.repeat(2001),
        capacity: '0',
      }),
      NOW,
    );
    expect(Object.keys(errors).sort()).toEqual([
      'capacity',
      'description',
      'location',
      'tag',
      'title',
    ]);
    expect(validateEvent(valid({ capacity: '10001' }), NOW).errors.capacity).toBeDefined();
    expect(validateEvent(valid({ capacity: '1.5' }), NOW).errors.capacity).toBeDefined();
  });

  it('needs a capacity of at least 2, because the host takes a spot', () => {
    const one = validateEvent(valid({ capacity: '1' }), NOW);
    expect(one.errors.capacity).toMatch(/from 2 to 10000/);
    expect(one.request).toBeNull();
    expect(validateEvent(valid({ capacity: '2' }), NOW).request?.capacity).toBe(2);
    expect(validateEvent(valid({ capacity: null }), NOW).request?.capacity).toBeNull();
  });

  it('refuses limited spots with no number, instead of dropping the limit', () => {
    const { errors, request } = validateEvent(valid({ capacity: ' ' }), NOW);
    expect(errors.capacity).toMatch(/from 2 to 10000/);
    expect(request).toBeNull();
  });

  it('keeps the start in the future and within a year either way', () => {
    expect(validateEvent(valid({ startsAt: '2030-05-31T10:00' }), NOW).errors.startsAt).toMatch(
      /past/,
    );
    expect(validateEvent(valid({ startsAt: '2031-06-02T10:00' }), NOW).errors.startsAt).toMatch(
      /within a year/,
    );
    expect(
      validateEvent(valid({ startsAt: '2031-05-30T10:00' }), NOW).errors.startsAt,
    ).toBeUndefined();
  });

  it('ends after the start and within 7 days', () => {
    expect(validateEvent(valid({ endsAt: '2030-06-10T15:00' }), NOW).errors.endsAt).toMatch(
      /after/,
    );
    expect(validateEvent(valid({ endsAt: '2030-06-17T15:01' }), NOW).errors.endsAt).toMatch(
      /7 days/,
    );
    expect(validateEvent(valid({ endsAt: '2030-06-17T15:00' }), NOW).errors.endsAt).toBeUndefined();
  });

  it('opens signup before the start and at most a year earlier', () => {
    expect(
      validateEvent(valid({ signupOpensAt: '2030-06-10T15:00' }), NOW).errors.signupOpensAt,
    ).toMatch(/before/);
    expect(
      validateEvent(valid({ signupOpensAt: '2029-06-09T15:00' }), NOW).errors.signupOpensAt,
    ).toMatch(/a year/);
    expect(
      validateEvent(valid({ signupOpensAt: '2029-06-10T15:00' }), NOW).errors.signupOpensAt,
    ).toBeUndefined();
  });

  it('sends the canonical zone name and refuses offsets', () => {
    const { request } = validateEvent(valid({ timeZone: 'america/chicago' }), NOW);
    expect(request?.timeZone).toBe('America/Chicago');
    expect(validateEvent(valid({ timeZone: '+05:30' }), NOW).errors.timeZone).toBeDefined();
  });

  it('rejects a time zone the browser does not know', () => {
    expect(validateEvent(valid({ timeZone: 'Mars/Olympus' }), NOW).errors.timeZone).toBeDefined();
  });

  it('lets an edit keep a start that has passed, but not move it there', () => {
    const original = {
      startsAt: '2030-05-31T20:00:00.000Z',
      endsAt: null,
      signupOpensAt: null,
      timeZone: 'America/Chicago',
    };
    const kept = valid({ startsAt: '2030-05-31T15:00' });
    expect(validateEvent(kept, NOW, original).errors.startsAt).toBeUndefined();
    const moved = valid({ startsAt: '2030-05-31T16:00' });
    expect(validateEvent(moved, NOW, original).errors.startsAt).toMatch(/past/);
  });

  it('sends an untouched time back exactly as the server gave it', () => {
    const original = {
      startsAt: '2030-06-10T20:00:30.123Z',
      endsAt: null,
      signupOpensAt: null,
      timeZone: 'America/Chicago',
    };
    const { request } = validateEvent(valid({ startsAt: '2030-06-10T15:00' }), NOW, original);
    expect(request?.startsAt).toBe('2030-06-10T20:00:30.123Z');
  });
});
