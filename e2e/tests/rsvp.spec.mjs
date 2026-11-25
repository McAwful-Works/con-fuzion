import { expect, test } from './hooks.mjs';
import { createEvent, eventStatus, expectFeed } from './social.mjs';
import {
  apiWrite,
  expectAttendees,
  errorType,
  expectSpots,
  expectStatus,
  goingHeading,
  joinEvent,
  leaveEvent,
  openEvent,
  panel,
  removeAttendee,
  userId,
  waitlistHeading,
} from './rsvp.mjs';
import { guild } from './support.mjs';

// Each test signs in several users and makes a handful of joins, so the default 30 seconds is tight on a slow runner.
test.describe.configure({ timeout: 90_000 });

// One server per test, so no test sees the events another leaves in the shared database.
let serverCounter = 0;
function server(name) {
  serverCounter += 1;
  const run = String(Date.now()).slice(-9);
  return guild(`6${run}${String(serverCounter).padStart(4, '0')}0000`, name);
}

/** A host and `count` guests who all see the server's events, because everyone is discoverable in it. */
async function crowd(join, count) {
  const con = server('Con Hall');
  const host = await join('host', [con], { discoverable: [con.name] });
  const guests = [];
  for (let i = 1; i <= count; i += 1) {
    guests.push(await join(`guest${i}`, [con], { discoverable: [con.name] }));
  }
  return { con, host, guests };
}

/**
 * A freed spot is offered to the next person in line (D-014). The UI to accept comes in #68, so this checks that
 * `player` is offered the spot (`/spots`) and accepts it through the API.
 */
async function acceptOffer(player, eventId) {
  const spots = await player.page.request.get(`/api/events/${eventId}/spots`);
  expect(spots.status()).toBe(200);
  expect((await spots.json()).body.myStatus).toBe('OFFERED');
  const accepted = await apiWrite(player, 'POST', `/events/${eventId}/rsvp/accept`);
  expect(accepted.status()).toBe(200);
  expect((await accepted.json()).body.status).toBe('CONFIRMED');
}

/** A full event: capacity 3 (the host and two guests), then two more guests on the waitlist, in that order. */
async function fullEvent(join, extra = {}) {
  const { con, host, guests } = await crowd(join, 4);
  const title = `Full house ${host.user.id.slice(-6)}`;
  const id = await createEvent(host, {
    title,
    capacity: 3,
    audience: { guilds: [con.name] },
    ...extra,
  });
  const [a, b, c, d] = guests;
  await joinEvent(a, id, 'Joined');
  await joinEvent(b, id, 'Joined');
  await joinEvent(c, id, 'Waitlisted #1');
  await joinEvent(d, id, 'Waitlisted #2');
  return { host, a, b, c, d, id, title, con };
}

test.describe('joining and the waitlist', () => {
  test('25. fill an event up: the next people land on the waitlist and see their position', async ({
    join,
  }) => {
    const { con, host, guests } = await crowd(join, 4);
    const [a, b, c, d] = guests;
    const id = await createEvent(host, {
      title: `Fill me ${host.user.id.slice(-6)}`,
      capacity: 3,
      audience: { guilds: [con.name] },
    });

    // The host holds the first spot: 1 of 3 is taken before anyone joins.
    await expectSpots(a, id, 'Spots left: 2');
    await expect(panel(a).getByText('1 going')).toBeVisible();

    await joinEvent(a, id, 'Joined');
    await expectSpots(a, id, 'Spots left: 1');
    await joinEvent(b, id, 'Joined');
    await expectSpots(b, id, /^Full$/);
    await expect(panel(b).getByText('3 going')).toBeVisible();

    // The third guest is told the event is full, joins, and sees a place in line.
    await openEvent(c, id);
    await expect(panel(c).getByText(/This event is full/)).toBeVisible();
    await joinEvent(c, id, 'Waitlisted #1');
    await joinEvent(d, id, 'Waitlisted #2');
    await expectSpots(d, id, '3 going, 2 on the waitlist');

    // A reload keeps the position, and each person sees only their own.
    await expectStatus(c, id, 'Waitlisted #1');
    await expectStatus(d, id, 'Waitlisted #2');
    // (Waitlisted guests get no attendee list, so nobody else's place is on their page.)
    await expect(c.page.getByRole('heading', { name: /^Waitlist \(/ })).toHaveCount(0);
    await expect(c.page.getByText('Waitlisted #2')).toHaveCount(0);

    // The host sees both lists, the waitlist in queue order; nobody has been turned away.
    await openEvent(host, id);
    await expect(goingHeading(host, 3)).toBeVisible();
    await expect(waitlistHeading(host, 2)).toBeVisible();
    await expectAttendees(host, {
      confirmed: [host.name, a.name, b.name],
      waitlist: [c.name, d.name],
    });
  });

  test('26. a confirmed guest leaves: the first person in line gets the spot and the others move up', async ({
    join,
  }) => {
    const { host, a, b, c, d, id } = await fullEvent(join);

    await openEvent(a, id);
    await leaveEvent(a);

    // c was first in line, is offered the spot and takes it; d moves up to first.
    await acceptOffer(c, id);
    await expectStatus(c, id, 'Joined');
    await expectStatus(d, id, 'Waitlisted #1');
    await expectSpots(d, id, '3 going, 1 on the waitlist');

    await openEvent(host, id);
    await expectAttendees(host, {
      confirmed: [host.name, b.name, c.name],
      waitlist: [d.name],
    });

    // Someone who left may join again: the event is full, so they go to the back of the line.
    await joinEvent(a, id, 'Waitlisted #2');
  });

  test('27. the host removes a guest: the next in line is promoted and the removed guest can’t come back', async ({
    join,
  }) => {
    const { host, a, b, c, d, id } = await fullEvent(join);

    // A guest sees who is going but has no Remove buttons, and the API says no (404, so it doesn't reveal more).
    await openEvent(b, id);
    await expect(goingHeading(b, 3)).toBeVisible();
    await expect(b.page.getByRole('button', { name: /^Remove / })).toHaveCount(0);
    const refused = await apiWrite(b, 'DELETE', `/events/${id}/rsvps/${await userId(c)}`);
    expect(refused.status()).toBe(404);
    expect(await errorType(refused)).toBe('NOT_FOUND');

    await openEvent(host, id);
    await expect(goingHeading(host, 3)).toBeVisible();
    // The host has no Remove button for themself.
    await expect(host.page.getByRole('button', { name: `Remove ${host.name}` })).toHaveCount(0);
    await removeAttendee(host, a);

    // c was first in line, is offered the spot and takes it.
    await acceptOffer(c, id);
    await openEvent(host, id);
    await expect(goingHeading(host, 3)).toBeVisible();
    await expect(waitlistHeading(host, 1)).toBeVisible();
    await expectAttendees(host, {
      confirmed: [host.name, b.name, c.name],
      waitlist: [d.name],
    });

    await expectStatus(c, id, 'Joined');
    await expectStatus(d, id, 'Waitlisted #1');

    // The removed guest can't rejoin: the page says so, there is no Join, and the API answers 409 RSVP_REMOVED.
    await expectStatus(a, id, 'You can’t rejoin this event.');
    await expect(panel(a).getByRole('button', { name: 'Join', exact: true })).toHaveCount(0);
    const again = await apiWrite(a, 'POST', `/events/${id}/rsvp`);
    expect(again.status()).toBe(409);
    expect(await errorType(again)).toBe('RSVP_REMOVED');
  });
});

test.describe('capacity edits', () => {
  test('28. cutting the capacity below the confirmed count shows the friendly 409, raising it promotes the waitlist', async ({
    join,
  }) => {
    const { host, c, d, id } = await fullEvent(join);

    // 3 confirmed (the host and two guests): 2 is too few, and nothing is saved.
    await host.page.goto(`/events/${id}/edit`);
    await expect(host.page.getByRole('heading', { name: 'Edit event' })).toBeVisible();
    await host.page.getByLabel('Capacity').fill('2');
    await host.page.getByRole('button', { name: 'Save changes' }).click();
    await expect(
      host.page.getByText(
        'That’s fewer spots than the number of people already confirmed. Choose a higher number, or remove people first.',
      ),
    ).toBeVisible();
    await expect(host.page).toHaveURL(/\/edit$/);
    await openEvent(host, id);
    await expect(goingHeading(host, 3)).toBeVisible();
    await expect(waitlistHeading(host, 2)).toBeVisible();

    // Raising it by one offers the new spot to the first in line, who takes it, and the next moves up.
    await host.page.goto(`/events/${id}/edit`);
    await host.page.getByLabel('Capacity').fill('4');
    await host.page.getByRole('button', { name: 'Save changes' }).click();
    await expect(host.page).toHaveURL(new RegExp(`/events/${id}$`));
    await acceptOffer(c, id);
    await openEvent(host, id);
    await expect(goingHeading(host, 4)).toBeVisible();
    await expect(waitlistHeading(host, 1)).toBeVisible();
    await expectStatus(c, id, 'Joined');
    await expectStatus(d, id, 'Waitlisted #1');
  });
});

test.describe('signup opening', () => {
  test('29. the Join button is locked until signup opens, and then the join works', async ({
    join,
  }) => {
    const { con, host, guests } = await crowd(join, 1);
    const [guest] = guests;

    // The create form only goes to the minute, so a signup time a few seconds away is made through the API,
    // the way the SPA would send it.
    const opensAt = Date.now() + 15_000;
    const created = await apiWrite(host, 'POST', '/events', {
      title: `Doors open soon ${host.user.id.slice(-6)}`,
      description: null,
      location: 'Hall B, table 4',
      locationVisibility: 'VIEWERS',
      tag: null,
      timeZone: 'UTC',
      startsAt: new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString(),
      endsAt: null,
      signupOpensAt: new Date(opensAt).toISOString(),
      capacity: 5,
      audience: [{ type: 'GUILD', guildId: con.id }],
    });
    expect(created.status()).toBe(201);
    const id = (await created.json()).body.id;

    await openEvent(guest, id);
    const button = panel(guest).getByRole('button', { name: 'Join', exact: true });
    await expect(panel(guest).getByRole('timer')).toContainText(
      /Signup opens in|Signup is opening/,
    );
    await expect(button).toBeDisabled();

    // The server refuses too, with its own reason, and no RSVP was made.
    const early = await apiWrite(guest, 'POST', `/events/${id}/rsvp`);
    expect(early.status()).toBe(409);
    expect(await errorType(early)).toBe('SIGNUP_NOT_OPEN');

    // The button unlocks on its own: the countdown, then 0 to 1.5 seconds of jitter. Never before the time.
    await expect(button).toBeEnabled({ timeout: 30_000 });
    expect(Date.now()).toBeGreaterThanOrEqual(opensAt);
    await button.click();
    await expect(panel(guest).getByText('Joined', { exact: true })).toBeVisible();
    await expect(panel(guest).getByRole('timer')).toHaveCount(0);
  });
});

test.describe('who sees what once someone is going', () => {
  test('30. a confirmed attendee sees the CONFIRMED location, the attendee list and a cancelled event; others don’t', async ({
    join,
  }) => {
    const { con, host, guests } = await crowd(join, 3);
    const [going, waiting, outsider] = guests;
    const place = `Back room ${host.user.id.slice(-6)}`;
    const title = `Secret meetup ${host.user.id.slice(-6)}`;
    // Capacity 2: the host and one guest. The second guest ends up on the waitlist.
    const id = await createEvent(host, {
      title,
      location: place,
      locationConfirmedOnly: true,
      capacity: 2,
      audience: { guilds: [con.name] },
    });

    // Before joining, nobody sees the place or who is going.
    await openEvent(going, id);
    await expect(going.page.getByText('Location shared with confirmed attendees')).toBeVisible();
    await expect(going.page.getByText(place)).toHaveCount(0);
    await expect(going.page.getByRole('heading', { name: /^Going \(/ })).toHaveCount(0);
    expect((await going.page.request.get(`/api/events/${id}/attendees`)).status()).toBe(404);

    await joinEvent(going, id, 'Joined');
    await joinEvent(waiting, id, 'Waitlisted #1');

    // Confirmed: the place and the list (the host first, no waitlist), on the page, in the feed and from the API.
    await openEvent(going, id);
    await expect(going.page.getByText(place)).toBeVisible();
    await expect(going.page.getByText('Location shared with confirmed attendees')).toHaveCount(0);
    await expect(goingHeading(going, 2)).toBeVisible();
    await expectAttendees(going, {
      confirmed: [host.name, going.name],
      waitlist: [],
    });
    await expect(going.page.getByRole('heading', { name: /^Waitlist \(/ })).toHaveCount(0);
    await expectFeed(going, { sees: [title] });
    await expect(going.page.getByText(place)).toBeVisible();

    // Waitlisted and not joined: no place, no list, and the API says 404 to the list.
    for (const viewer of [waiting, outsider]) {
      await openEvent(viewer, id);
      await expect(viewer.page.getByText('Location shared with confirmed attendees')).toBeVisible();
      await expect(viewer.page.getByText(place)).toHaveCount(0);
      await expect(viewer.page.getByRole('heading', { name: /^Going \(/ })).toHaveCount(0);
      expect((await viewer.page.request.get(`/api/events/${id}/attendees`)).status()).toBe(404);
      const event = (await (await viewer.page.request.get(`/api/events/${id}`)).json()).body;
      expect(event.location).toBeNull();
    }

    // The host cancels (the button asks first).
    await openEvent(host, id);
    await host.page.getByRole('button', { name: 'Cancel event', exact: true }).click();
    await host.page
      .getByRole('alertdialog')
      .getByRole('button', { name: 'Cancel event', exact: true })
      .click();
    await expect(host.page.getByText('This event was cancelled.')).toBeVisible();

    // The person who is going can still open it, and sees it was cancelled, and so can the person waiting
    // (D-013 rule 10). A guest who never joined gets what the API gives them: nothing.
    for (const keeper of [going, waiting]) {
      await openEvent(keeper, id);
      await expect(keeper.page.getByText('This event was cancelled.')).toBeVisible();
      expect(await eventStatus(keeper, id)).toBe(200);
    }
    await going.page.goto(`/events/${id}`);
    await expect(going.page.getByText(place)).toBeVisible();

    await outsider.page.goto(`/events/${id}`);
    await expect(outsider.page.getByRole('heading', { name: 'Event not found' })).toBeVisible();
    expect(await eventStatus(outsider, id)).toBe(404);
  });
});

test.describe('My schedule', () => {
  test('31. lists what you host and what you’re going to, and marks the events that overlap', async ({
    join,
  }) => {
    const { con, host: other, guests } = await crowd(join, 1);
    const [me] = guests;
    const tag = other.user.id.slice(-6);
    const audience = { guilds: [con.name] };

    // Two events an hour apart (3 hours each by default, so they overlap), one on another day, and one I never
    // join. `other` hosts them, `me` hosts one.
    const first = await createEvent(other, { title: `Panel ${tag}`, days: 2, hour: 15, audience });
    const second = await createEvent(other, {
      title: `Photoshoot ${tag}`,
      days: 2,
      hour: 16,
      audience,
    });
    await createEvent(other, { title: `Not for me ${tag}`, days: 2, hour: 15, audience });
    await createEvent(me, {
      title: `My own table ${tag}`,
      days: 4,
      hour: 12,
      audience,
    });
    await joinEvent(me, first, 'Joined');
    await joinEvent(me, second, 'Joined');

    await me.page.goto('/');
    await me.page.getByRole('link', { name: 'My schedule' }).click();
    await expect(me.page.getByRole('heading', { level: 1, name: 'My schedule' })).toBeVisible();

    const card = (title) => me.page.getByRole('link', { name: new RegExp(`^${title}`) });
    // What I host and what I joined are listed, what I didn't join is not.
    await expect(card(`Panel ${tag}`)).toBeVisible();
    await expect(card(`Photoshoot ${tag}`)).toBeVisible();
    await expect(card(`My own table ${tag}`)).toBeVisible();
    await expect(card(`My own table ${tag}`)).toContainText('You’re hosting');
    await expect(me.page.getByRole('link', { name: new RegExp(`Not for me ${tag}`) })).toHaveCount(
      0,
    );

    // The two that run at the same time name each other. The one on another day doesn't overlap.
    await expect(card(`Panel ${tag}`)).toContainText(`Overlaps with “Photoshoot ${tag}”`);
    await expect(card(`Photoshoot ${tag}`)).toContainText(`Overlaps with “Panel ${tag}”`);
    await expect(card(`My own table ${tag}`)).not.toContainText('Overlaps');

    // The event I never joined is still in the feed, but not on my schedule.
    await expectFeed(me, { sees: [`Not for me ${tag}`] });
  });
});
