import { expect, test } from './hooks.mjs';
import {
  befriend,
  blockFriend,
  createEvent,
  eventStatus,
  expectFeed,
  friendRow,
  makeCircle,
  makeInvite,
  openFriends,
  searchPeople,
  setDiscoverable,
} from './social.mjs';
import { guild, newUser, signInLink, useScenario } from './support.mjs';

// Server IDs are shared between the users of one test and are never reused across tests, so the rows other tests
// leave in the shared database can't make two tests see each other. (Users are fresh per test anyway.)
let serverCounter = 0;
function servers(...names) {
  serverCounter += 1;
  const run = String(Date.now()).slice(-9);
  return names.map((name, i) =>
    guild(`5${run}${String(serverCounter).padStart(4, '0')}${String(i).padStart(4, '0')}`, name),
  );
}

test.describe('friends via an invite link', () => {
  test('15. two users become friends: A makes a link, B opens it, previews and accepts', async ({
    join,
    baseURL,
  }) => {
    const [con] = servers('Con Hall');
    const a = await join('host', [con]);
    const b = await join('guest', [con]);

    const url = await makeInvite(a, baseURL);

    await b.page.goto(url);
    await expect(
      b.page.getByRole('heading', {
        name: `${a.name} invited you to be friends`,
      }),
    ).toBeVisible();
    await b.page.getByRole('button', { name: 'Accept' }).click();
    await expect(b.page).toHaveURL(/\/people$/);
    await expect(friendRow(b, a)).toBeVisible();

    await openFriends(a);
    await expect(friendRow(a, b)).toBeVisible();
    await openFriends(b);
    await expect(friendRow(b, a)).toBeVisible();

    // The link works once: a third person finds it used up (the inviter's own link is invalid anyway).
    const c = await join('third', [con]);
    await c.page.goto(url);
    await expect(c.page.getByRole('heading', { name: /isn’t valid anymore/ })).toBeVisible();
    await expect(c.page.getByRole('button', { name: 'Accept' })).toHaveCount(0);
  });

  test('16. signed-out B opens the link, signs in (first time: onboarding), and lands on the accept page', async ({
    join,
    browser,
    baseURL,
  }) => {
    const [con] = servers('Con Hall');
    const a = await join('host', [con]);
    const url = await makeInvite(a, baseURL);

    // B has never signed in: a fresh context, nobody's session.
    const context = await browser.newContext({ baseURL, timezoneId: 'UTC' });
    try {
      const page = await context.newPage();
      const b = { page, name: '' };
      const user = newUser('latecomer');
      b.name = user.username;

      await page.goto(url);
      // The preview is a 401 for a signed-out visitor, so the SPA sends them to sign in.
      await expect(page).toHaveURL(/\/welcome$/);

      await useScenario(user, [con]);
      await signInLink(page).click();
      // First sign-in: onboarding comes first, and the accept page waits behind it.
      await expect(page).toHaveURL(/\/onboarding$/);
      // The suite's jar has VAPID keys (M4), so a notifications step follows the servers.
      await page.getByRole('button', { name: 'Continue' }).click();
      await expect(page.getByRole('heading', { name: 'Want a heads-up?' })).toBeVisible();
      await page.getByRole('link', { name: 'Continue' }).click();

      await expect(page).toHaveURL(url);
      await expect(
        page.getByRole('heading', {
          name: `${a.name} invited you to be friends`,
        }),
      ).toBeVisible();
      await page.getByRole('button', { name: 'Accept' }).click();
      await expect(page).toHaveURL(/\/people$/);
      await expect(friendRow(b, a)).toBeVisible();

      await openFriends(a);
      await expect(friendRow(a, b)).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

test.describe('event audiences and visibility', () => {
  test('17. FRIENDS: a friend sees the event in the feed and on its page, a stranger gets nothing and a 404', async ({
    join,
    baseURL,
  }) => {
    const [con] = servers('Con Hall');
    const host = await join('host', [con]);
    const friend = await join('friend', [con]);
    const stranger = await join('stranger', [con]);
    await befriend(host, friend, baseURL);

    const title = `Friends only ${host.user.id.slice(-6)}`;
    const id = await createEvent(host, { title, audience: { friends: true } });

    await expectFeed(friend, { sees: [title] });
    await friend.page.getByRole('link', { name: new RegExp(title) }).click();
    await expect(friend.page.getByRole('heading', { level: 1, name: title })).toBeVisible();
    expect(await eventStatus(friend, id)).toBe(200);

    await expectFeed(stranger, { hidden: [title] });
    await expect(
      stranger.page.getByRole('heading', { name: 'No upcoming meetups yet' }),
    ).toBeVisible();
    expect(await eventStatus(stranger, id)).toBe(404);
    await stranger.page.goto(`/events/${id}`);
    await expect(stranger.page.getByRole('heading', { name: 'Event not found' })).toBeVisible();
  });

  test('18. GUILD: a discoverable member sees it, a member who isn’t does not, and once the host turns discoverability off the member loses it', async ({
    join,
  }) => {
    const [con] = servers('Con Hall');
    const host = await join('host', [con], { discoverable: [con.name] });
    const visible = await join('visible', [con], { discoverable: [con.name] });
    const hiding = await join('hiding', [con]); // in the server, not discoverable
    const outsider = await join('outsider', servers('Elsewhere')); // not in the server at all

    const title = `Server meetup ${host.user.id.slice(-6)}`;
    const id = await createEvent(host, {
      title,
      audience: { guilds: [con.name] },
    });

    await expectFeed(visible, { sees: [title] });
    expect(await eventStatus(visible, id)).toBe(200);

    for (const viewer of [hiding, outsider]) {
      await expectFeed(viewer, { hidden: [title] });
      expect(await eventStatus(viewer, id)).toBe(404);
    }

    // The host turns discoverability off in that server: it stops reaching anyone (D-012).
    await setDiscoverable(host, con.name, false);
    await expectFeed(visible, { hidden: [title] });
    expect(await eventStatus(visible, id)).toBe(404);
    // Still theirs.
    expect(await eventStatus(host, id)).toBe(200);
  });

  test('19. CIRCLE: only the circle’s members see the event, a friend outside the circle does not', async ({
    join,
    baseURL,
  }) => {
    const [con] = servers('Con Hall');
    const host = await join('host', [con], { discoverable: [con.name] });
    const inCircle = await join('member', [con], { discoverable: [con.name] });
    const outOfCircle = await join('other', [con], {
      discoverable: [con.name],
    });
    // Friends can be picked wherever they are; the other person is also pickable through the shared server.
    await befriend(host, inCircle, baseURL);
    await befriend(host, outOfCircle, baseURL);

    const circle = `Crew ${host.user.id.slice(-6)}`;
    await makeCircle(host, circle, [inCircle]);

    const title = `Circle only ${host.user.id.slice(-6)}`;
    const id = await createEvent(host, {
      title,
      audience: { circles: [circle] },
    });

    await expectFeed(inCircle, { sees: [title] });
    expect(await eventStatus(inCircle, id)).toBe(200);
    await expectFeed(outOfCircle, { hidden: [title] });
    expect(await eventStatus(outOfCircle, id)).toBe(404);
  });

  test('20. USER: only the named person sees the event, even when others share a server and a friendship', async ({
    join,
    baseURL,
  }) => {
    const [con] = servers('Con Hall');
    const host = await join('host', [con], { discoverable: [con.name] });
    const named = await join('named', [con], { discoverable: [con.name] });
    const friend = await join('friend', [con], { discoverable: [con.name] });
    await befriend(host, friend, baseURL);

    const title = `Just for one ${host.user.id.slice(-6)}`;
    const id = await createEvent(host, {
      title,
      audience: { people: [named] },
    });

    await expectFeed(named, { sees: [title] });
    expect(await eventStatus(named, id)).toBe(200);
    // A friend and a fellow server member, but not named.
    await expectFeed(friend, { hidden: [title] });
    expect(await eventStatus(friend, id)).toBe(404);
  });

  test('21. hidden location: a viewer sees "Location shared with confirmed attendees" and no place, the host sees it', async ({
    join,
    baseURL,
  }) => {
    const [con] = servers('Con Hall');
    const host = await join('host', [con]);
    const friend = await join('friend', [con]);
    await befriend(host, friend, baseURL);

    const place = `Secret room ${host.user.id.slice(-6)}`;
    const title = `Hidden place ${host.user.id.slice(-6)}`;
    const id = await createEvent(host, {
      title,
      location: place,
      locationConfirmedOnly: true,
      audience: { friends: true },
    });

    // The host sees the place on the event page and in the feed.
    await expect(host.page.getByText(place)).toBeVisible();
    await expect(
      host.page.getByText('Only you and confirmed attendees see the location.'),
    ).toBeVisible();
    await expectFeed(host, { sees: [title] });
    await expect(host.page.getByText(place)).toBeVisible();

    // The friend sees the notice instead, in the feed and on the page, and the page never had the place.
    await expectFeed(friend, { sees: [title] });
    await expect(friend.page.getByText('Location shared with confirmed attendees')).toBeVisible();
    await expect(friend.page.getByText(place)).toHaveCount(0);
    await friend.page.goto(`/events/${id}`);
    await expect(friend.page.getByRole('heading', { level: 1, name: title })).toBeVisible();
    await expect(friend.page.getByText('Location shared with confirmed attendees')).toBeVisible();
    await expect(friend.page.getByText(place)).toHaveCount(0);

    // And the API never sent it.
    const body = (await (await friend.page.request.get(`/api/events/${id}`)).json()).body;
    expect(body.location).toBeNull();
    expect(body.locationHidden).toBe(true);
    expect(body.audience).toBeNull(); // only the host sees the audience
  });
});

test.describe('blocks', () => {
  test('22. A blocks B: the friendship is gone, each one’s events are hidden from the other, B’s old invite link is dead', async ({
    join,
    baseURL,
  }) => {
    // The server audience is what a block must cut: both stay discoverable there, so the shared server alone would
    // still show the events. (A friends-only event would hide itself when the friendship ends.)
    const [con] = servers('Con Hall');
    const a = await join('blocker', [con], { discoverable: [con.name] });
    const b = await join('blocked', [con], { discoverable: [con.name] });
    await befriend(a, b, baseURL);

    // B makes a link for someone else to use later, then the block happens.
    const bsLink = await makeInvite(b, baseURL);

    const aTitle = `A hosts ${a.user.id.slice(-6)}`;
    const bTitle = `B hosts ${b.user.id.slice(-6)}`;
    const aId = await createEvent(a, {
      title: aTitle,
      audience: { guilds: [con.name], friends: true },
    });
    const bId = await createEvent(b, {
      title: bTitle,
      audience: { guilds: [con.name], friends: true },
    });

    // Before the block, they see each other's events.
    await expectFeed(b, { sees: [aTitle] });
    await expectFeed(a, { sees: [bTitle] });

    // A blocks B from the friends list.
    await openFriends(a);
    await blockFriend(a, b);
    await expect(friendRow(a, b)).toHaveCount(0);

    // No longer friends, from either side.
    await openFriends(b);
    await expect(friendRow(b, a)).toHaveCount(0);

    // Events are hidden both ways, in the feed and by ID.
    await expectFeed(b, { hidden: [aTitle] });
    await expectFeed(a, { hidden: [bTitle] });
    expect(await eventStatus(b, aId)).toBe(404);
    expect(await eventStatus(a, bId)).toBe(404);
    // Each still sees their own.
    await expectFeed(a, { sees: [aTitle] });
    expect(await eventStatus(a, aId)).toBe(200);

    // B's link from before the block is the same "isn't valid" page for A.
    await a.page.goto(bsLink);
    await expect(a.page.getByRole('heading', { name: /isn’t valid anymore/ })).toBeVisible();
    await expect(a.page.getByRole('button', { name: 'Accept' })).toHaveCount(0);
  });

  test('23. a block works in the other direction too: B blocks A', async ({ join, baseURL }) => {
    const [con] = servers('Con Hall');
    const a = await join('host', [con], { discoverable: [con.name] });
    const b = await join('guest', [con], { discoverable: [con.name] });
    await befriend(a, b, baseURL);
    const aLink = await makeInvite(a, baseURL);

    const title = `A hosts ${a.user.id.slice(-6)}`;
    const id = await createEvent(a, { title, audience: { guilds: [con.name] } });
    await expectFeed(b, { sees: [title] });
    expect(await eventStatus(b, id)).toBe(200);

    await openFriends(b);
    await blockFriend(b, a);
    await expect(friendRow(b, a)).toHaveCount(0);

    await expectFeed(b, { hidden: [title] });
    expect(await eventStatus(b, id)).toBe(404);
    await b.page.goto(aLink);
    await expect(b.page.getByRole('heading', { name: /isn’t valid anymore/ })).toBeVisible();
  });
});

test.describe('people search', () => {
  test('24. only pickable people: friends and people who share a server where both are discoverable', async ({
    join,
    baseURL,
  }) => {
    const [shared, mineOnly, theirsOnly] = servers('Shared', 'Mine Only', 'Theirs Only');
    const me = await join('srch-me', [shared, mineOnly, theirsOnly], {
      discoverable: [shared.name, mineOnly.name], // not in Theirs Only
    });

    // Pickable: a friend (who shares nothing), and someone who shares a server where both are discoverable.
    const friend = await join('srch-friend', servers('Elsewhere'));
    const both = await join('srch-both', [shared], {
      discoverable: [shared.name],
    });
    // Not pickable: in a shared server but not discoverable there; discoverable in a server where I'm not;
    // in a server where only I am discoverable; nothing in common.
    const hiding = await join('srch-hiding', [shared]);
    const theirs = await join('srch-theirs', [theirsOnly], {
      discoverable: [theirsOnly.name],
    });
    const mine = await join('srch-mine', [mineOnly]);
    const stranger = await join('srch-stranger', servers('Nowhere'), {
      discoverable: 'all',
    });
    await befriend(me, friend, baseURL);

    // Search from a circle's page, the way a person finds people to add.
    await me.page.goto('/people?tab=circles');
    await me.page.getByLabel('Circle name').fill('Search circle');
    await me.page.getByRole('button', { name: 'Create circle' }).click();
    await me.page.getByRole('link', { name: /Search circle/ }).click();

    const found = await searchPeople(me, 'e2e-srch');
    expect(found.sort()).toEqual([both.name, friend.name].sort());
    for (const hidden of [hiding, theirs, mine, stranger]) {
      expect(found).not.toContain(hidden.name);
    }

    // The API answers the same.
    const api = await me.page.request.get('/api/people?q=e2e-srch');
    expect(api.status()).toBe(200);
    const names = (await api.json()).body.people.map((p) => p.displayName);
    expect(names.sort()).toEqual([both.name, friend.name].sort());
  });
});
