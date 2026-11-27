import { randomBytes } from 'node:crypto';
import { expect, test } from './hooks.mjs';
import { bell, createEvent, openInbox } from './social.mjs';
import {
  apiWrite,
  crowd,
  errorType,
  expectStatus,
  fullEvent,
  joinEvent,
  leaveEvent,
  openEvent,
  panel,
  server,
  userId,
} from './rsvp.mjs';
import { expireOffer, pushEndpoints } from './db.mjs';

// M4 (D-014): offers with a deadline, the inbox and its unread badge, and the push subscribe call. Each test signs in
// several users, so the default 30 seconds is tight on a slow runner.
test.describe.configure({ timeout: 90_000 });

/** The "offered a spot" wording of the inbox for an event. */
const offeredText = (title) => new RegExp(`You were offered a spot in “${title}”`);

/** The offer on the event page: the held-spot text, the countdown and the two buttons. */
async function expectOffer(player, eventId) {
  await openEvent(player, eventId);
  await expect(panel(player).getByText('A spot is held for you.')).toBeVisible();
  await expect(panel(player).getByRole('timer')).toContainText(/Offer ends in/);
  await expect(panel(player).getByRole('button', { name: 'Accept spot' })).toBeVisible();
  await expect(panel(player).getByRole('button', { name: 'Decline' })).toBeVisible();
}

/** Waits until the API says `player` holds an open offer (the expiry job runs every few seconds in this suite). */
async function expectOfferedByApi(player, eventId) {
  await expect
    .poll(
      async () => {
        const spots = await player.page.request.get(`/api/events/${eventId}/spots`);
        return (await spots.json()).body.myStatus;
      },
      { timeout: 30_000, intervals: [500] },
    )
    .toBe('OFFERED');
}

test.describe('offers', () => {
  test('32. a confirmed guest leaves: the first in line sees an offer with a countdown, accepts it, and the inboxes show it', async ({
    join,
  }) => {
    const { host, a, c, d, id, title } = await fullEvent(join);

    // Nobody has been told anything yet: joining sends no notification.
    await openInbox(c);
    await expect(c.page.getByText('Nothing yet.')).toBeVisible();
    await expect(bell(c)).toHaveAccessibleName('Inbox');

    await openEvent(a, id);
    await leaveEvent(a);

    // c was first in line: the spot is held for them, with a deadline and a countdown.
    await expectOffer(c, id);
    await expect(bell(c)).toHaveAccessibleName('Inbox, 1 unread');

    // The held spot counts as taken, so the person behind c still waits, now first in line.
    await expectStatus(d, id, 'Waitlisted #1');

    await openEvent(c, id);
    await panel(c).getByRole('button', { name: 'Accept spot' }).click();
    await expect(panel(c).getByText('Joined', { exact: true })).toBeVisible();
    await expect(panel(c).getByText('A spot is held for you.')).toHaveCount(0);
    await expectStatus(c, id, 'Joined');

    // c's inbox has the offer, and only that: accepting sends nothing. Opening it marks it read.
    await openInbox(c);
    await expect(c.page.getByRole('listitem')).toHaveCount(1);
    await expect(c.page.getByRole('listitem')).toContainText(offeredText(title));
    await expect(c.page.getByRole('listitem')).toContainText('New');
    await expect(bell(c)).toHaveAccessibleName('Inbox');

    // Leaving, joining and accepting sent nothing to anyone else.
    for (const quiet of [a, d, host]) {
      await openInbox(quiet);
      await expect(quiet.page.getByText('Nothing yet.')).toBeVisible();
    }
  });

  test('33. declining an offer gives the spot to the next person in line', async ({ join }) => {
    const { a, c, d, id, title } = await fullEvent(join);

    await openEvent(a, id);
    await leaveEvent(a);
    await expectOffer(c, id);

    await panel(c).getByRole('button', { name: 'Decline' }).click();
    await c.page
      .getByRole('alertdialog')
      .getByRole('button', { name: 'Decline', exact: true })
      .click();
    await expect(panel(c).getByRole('button', { name: 'Join', exact: true })).toBeVisible();

    // d is next: the spot is offered to them, and their inbox says so.
    await expectOffer(d, id);
    await expect(bell(d)).toHaveAccessibleName('Inbox, 1 unread');
    await openInbox(d);
    await expect(d.page.getByRole('listitem')).toHaveCount(1);
    await expect(d.page.getByRole('listitem')).toContainText(offeredText(title));

    // c declined, so their offer can't be taken any more.
    const late = await apiWrite(c, 'POST', `/events/${id}/rsvp/accept`);
    expect(late.status()).toBe(409);
    expect(await errorType(late)).toBe('NO_OFFER');
  });

  test('34. an offer that runs out ends, and the expiry job offers the spot to the next person', async ({
    join,
  }) => {
    const { a, c, d, id, title } = await fullEvent(join);

    await openEvent(a, id);
    await leaveEvent(a);
    await expectOffer(c, id);
    await expectStatus(d, id, 'Waitlisted #1');

    // The window can't be made shorter than 10 minutes, so end it by hand: its deadline is moved into the past.
    expect(expireOffer(id, await userId(c))).toBe(1);

    // The offer is gone for c at once (D-014 rule 15), whether or not the job has run.
    const late = await apiWrite(c, 'POST', `/events/${id}/rsvp/accept`);
    expect(late.status()).toBe(409);
    expect(await errorType(late)).toBe('OFFER_EXPIRED');

    // Only the job gives the spot to d. If it never ran, d would wait for ever.
    await expectOfferedByApi(d, id);
    await expectOffer(d, id);
    await openInbox(d);
    await expect(d.page.getByRole('listitem')).toContainText(offeredText(title));

    // c may join again, at the back of the line: the spot d holds counts as taken.
    await joinEvent(c, id, 'Waitlisted #1');
  });
});

test.describe('the inbox', () => {
  test('35. cancelling an event puts an entry in the attendees’ inboxes and the unread badge follows', async ({
    join,
  }) => {
    const { con, host, guests } = await crowd(join, 2);
    const [going, waiting] = guests;
    const title = `Cancel me ${host.user.id.slice(-6)}`;
    const id = await createEvent(host, {
      title,
      capacity: 2,
      audience: { guilds: [con.name] },
    });
    await joinEvent(going, id, 'Joined');
    await joinEvent(waiting, id, 'Waitlisted #1');
    await expect(bell(going)).toHaveAccessibleName('Inbox');

    await openEvent(host, id);
    await host.page.getByRole('button', { name: 'Cancel event' }).click();
    await host.page
      .getByRole('alertdialog')
      .getByRole('button', { name: 'Cancel event', exact: true })
      .click();
    await expect(host.page.getByRole('alertdialog')).toHaveCount(0);

    // Everyone with an active RSVP but the host is told. The badge counts it, then clears once the inbox is read.
    for (const player of [going, waiting]) {
      await expect(async () => {
        await player.page.goto('/');
        await expect(bell(player)).toHaveAccessibleName('Inbox, 1 unread', {
          timeout: 1_500,
        });
      }).toPass({ timeout: 20_000 });

      await openInbox(player);
      await expect(player.page.getByRole('listitem')).toHaveCount(1);
      await expect(player.page.getByRole('listitem')).toContainText(`“${title}” was cancelled.`);
      await expect(player.page.getByRole('listitem')).toContainText('New');
      await expect(bell(player)).toHaveAccessibleName('Inbox');
      await player.page.reload();
      await expect(player.page.getByRole('listitem')).not.toContainText('New');
    }

    await openInbox(host);
    await expect(host.page.getByText('Nothing yet.')).toBeVisible();
  });
});

test.describe('push', () => {
  /** A fake push subscription on an allowed push host: what a real browser gets from its push service. */
  const fakeSubscription = () => ({
    endpoint: `https://fcm.googleapis.com/fcm/send/e2e-${randomBytes(12).toString('hex')}`,
    p256dh: Buffer.concat([Buffer.from([4]), randomBytes(64)]).toString('base64url'),
    auth: randomBytes(16).toString('base64url'),
  });

  test('36. settings: turning push on sends the subscription to the backend, turning it off removes it', async ({
    join,
  }) => {
    const me = await join('pusher', [server('Push test')]);
    const fake = fakeSubscription();

    // Headless Chromium can't reach a real push service, so the browser's subscribe is replaced by one that returns
    // a fake subscription. That makes this a test of the subscribe call and of what the app does with the result.
    await me.context.grantPermissions(['notifications'], {
      origin: new URL(me.page.url()).origin,
    });
    await me.context.addInitScript((sub) => {
      // Headless Chromium answers requestPermission() with 'granted' after the grant above, but keeps reporting
      // Notification.permission as 'denied', which would leave the button disabled as "blocked". A real browser agrees.
      Object.defineProperty(Notification, 'permission', {
        get: () => 'granted',
      });
      const KEY = 'e2e-fake-push';
      const subscription = {
        endpoint: sub.endpoint,
        toJSON: () => ({
          endpoint: sub.endpoint,
          keys: { p256dh: sub.p256dh, auth: sub.auth },
        }),
        unsubscribe: async () => {
          localStorage.removeItem(KEY);
          return true;
        },
      };
      PushManager.prototype.subscribe = async function () {
        localStorage.setItem(KEY, '1');
        return subscription;
      };
      PushManager.prototype.getSubscription = async function () {
        return localStorage.getItem(KEY) ? subscription : null;
      };
    }, fake);

    // The server has VAPID keys in this suite, so it offers its public key.
    const key = await me.page.request.get('/api/push/key');
    expect(key.status()).toBe(200);
    expect((await key.json()).body.publicKey).toMatch(/^[A-Za-z0-9_-]{87}$/);

    const mine = await userId(me);
    expect(pushEndpoints(mine)).toEqual([]);

    await me.page.goto('/you');
    const subscribed = me.page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith('/api/push/subscriptions'),
    );
    await me.page.getByRole('button', { name: 'Turn on push' }).click();
    expect((await subscribed).status()).toBe(204);
    await expect(me.page.getByRole('button', { name: 'Turn off push' })).toBeVisible();

    // The backend stored the endpoint for this user, and push is on in their settings.
    expect(pushEndpoints(mine)).toEqual([fake.endpoint]);
    expect((await (await me.page.request.get('/api/me')).json()).body.pushEnabled).toBe(true);

    // It is still on after a reload.
    await me.page.reload();
    await expect(me.page.getByRole('button', { name: 'Turn off push' })).toBeVisible();

    await me.page.getByRole('button', { name: 'Turn off push' }).click();
    await expect(me.page.getByRole('button', { name: 'Turn on push' })).toBeVisible();
    expect(pushEndpoints(mine)).toEqual([]);
    expect((await (await me.page.request.get('/api/me')).json()).body.pushEnabled).toBe(false);
  });
});
