import { randomUUID } from 'node:crypto';
import {
  expectSignedOut,
  expectSignInRefused,
  findUser,
  grantRoles,
  joinAdmin,
  joinModerator,
  openReport,
  queueEntry,
  signInAgain,
} from './admin.mjs';
import { expect, test } from './hooks.mjs';
import { apiWrite, crowd, errorType, joinEvent, openEvent, userId } from './rsvp.mjs';
import {
  bell,
  createEvent,
  eventStatus,
  expectFeed,
  expectSchedule,
  openInbox,
} from './social.mjs';

// M5 (D-015): the MFA gate, role grants, reports and hiding, suspensions and bans. Each test signs in several users,
// so the default 30 seconds is tight on a slow runner.
test.describe.configure({ timeout: 120_000 });

/** What `GET /api/me` says about the player's roles. */
async function myRoles({ page }) {
  const response = await page.request.get('/api/me');
  expect(response.status()).toBe(200);
  const { roles, mfaRequired } = (await response.json()).body;
  return { roles: [...roles].sort(), mfaRequired };
}

/** The status of an admin API read as the player. */
const adminStatus = async ({ page }, path) =>
  (await page.request.get(`/api/admin${path}`)).status();

/** The sidebar's link to the admin area. */
const moderationLink = ({ page }) => page.getByRole('link', { name: 'Moderation' });

/** `days` from today as a date input takes it. Contexts run in UTC. */
const dayFromNow = (days) =>
  new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

test.describe('roles', () => {
  test('37. a moderator without Discord 2FA acts as a plain user: no admin link or page, a 403 from the API', async ({
    join,
  }) => {
    const admin = await joinAdmin(join);
    const user = await join('no-mfa', []); // Discord reports 2FA off
    await grantRoles(admin, user, ['MODERATOR']);
    await signInAgain(user);

    expect(await myRoles(user)).toEqual({ roles: ['USER'], mfaRequired: true });
    await expect(user.page.getByRole('note')).toContainText(
      'Your moderator tools are switched off',
    );
    await expect(moderationLink(user)).toHaveCount(0);
    await user.page.goto('/admin');
    await expect(user.page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
    await expect(user.page.getByRole('heading', { name: 'Moderation' })).toHaveCount(0);

    for (const path of ['/reports', '/reports/1', '/users?q=e2e', '/audit']) {
      const response = await user.page.request.get(`/api/admin${path}`);
      expect(response.status(), path).toBe(403);
      expect(await errorType(response)).toBe('FORBIDDEN');
    }
    // A write is refused too, with a valid CSRF token, so it's the role that is missing.
    const hide = await apiWrite(user, 'POST', `/admin/events/${randomUUID()}/hide`, {
      reason: 'No',
    });
    expect(hide.status()).toBe(403);

    // The same account with 2FA on: the role takes effect at the next sign-in.
    await user.page.goto('/you');
    await user.page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(user.page).toHaveURL(/\/welcome$/);
    await signInAgain(user, { mfaEnabled: true });

    expect(await myRoles(user)).toEqual({ roles: ['MODERATOR', 'USER'], mfaRequired: false });
    await expect(moderationLink(user)).toBeVisible();
    await expect(user.page.getByRole('note')).toHaveCount(0);
    expect(await adminStatus(user, '/reports')).toBe(200);
  });

  test('38. an admin grants MODERATOR in the admin area: the user is signed out, and signs in again with the role', async ({
    join,
  }) => {
    const admin = await joinAdmin(join);
    const user = await join('promoted', [], { mfaEnabled: true });
    expect(await myRoles(user)).toEqual({ roles: ['USER'], mfaRequired: false });
    expect(await adminStatus(user, '/reports')).toBe(403);

    const card = await findUser(admin, user);
    await card.getByRole('button', { name: `Roles for ${user.name}` }).click();
    await card.getByRole('checkbox', { name: 'Moderator' }).check();
    await card.getByRole('button', { name: 'Save roles' }).click();
    await expect(card.getByText(`Roles for ${user.name} saved.`)).toBeVisible();

    // The change ends their sessions (D-015), so the old one can't carry the new role.
    await expectSignedOut(user);
    await signInAgain(user);

    expect(await myRoles(user)).toEqual({ roles: ['MODERATOR', 'USER'], mfaRequired: false });
    expect(await adminStatus(user, '/reports')).toBe(200);
    // A moderator, not an admin.
    expect(await adminStatus(user, '/audit')).toBe(403);
    await moderationLink(user).click();
    const sections = user.page.getByRole('navigation', { name: 'Moderation sections' });
    await expect(sections.getByRole('link')).toHaveText(['Reports', 'Users']);
  });
});

test.describe('reports and hidden events', () => {
  test('39. report, queue, hide: attendees lose the event silently, the host sees why, resolving tells the reporter', async ({
    join,
  }) => {
    const admin = await joinAdmin(join);
    const moderator = await joinModerator(join, admin);
    const { con, host, guests } = await crowd(join, 3);
    const [reporter, attendee, onlooker] = guests;
    const title = `Reported meetup ${host.user.id}`;
    const note = 'This looks like an advert.';
    const reason = 'Advertising is not a meetup.';
    const id = await createEvent(host, { title, audience: { guilds: [con.name] } });
    await joinEvent(reporter, id, 'Joined');
    await joinEvent(attendee, id, 'Joined');

    // Before the hide they all see it, so "gone" below means something.
    for (const player of [reporter, attendee]) {
      await expectFeed(player, { sees: [title] });
      await expectSchedule(player, { sees: [title] });
    }
    await expectFeed(onlooker, { sees: [title] });

    await openEvent(reporter, id);
    await reporter.page.getByRole('button', { name: 'Report event' }).click();
    const sheet = reporter.page.getByRole('form', { name: `Report “${title}”` });
    await sheet.getByRole('radio', { name: 'Spam or a scam' }).check();
    await sheet.getByLabel('Anything else we should know? (optional)').fill(note);
    await sheet.getByRole('button', { name: 'Send report' }).click();
    await expect(reporter.page.getByText('Thanks. The moderators will look at it.')).toBeVisible();

    // The moderator is told, and works the report from the queue.
    await openInbox(moderator);
    const received = moderator.page
      .getByRole('listitem')
      .filter({ hasText: 'A new report is waiting for review.' });
    await received.getByRole('link', { name: 'Open reports' }).click();
    await openReport(moderator, `Event: ${title}`);
    const detail = moderator.page.getByRole('article');
    await expect(detail).toContainText('Spam or a scam');
    await expect(detail).toContainText(note);

    await detail.getByRole('button', { name: 'Hide event', exact: true }).click();
    await detail.getByLabel('Reason (the host reads it)').fill(reason);
    await detail.getByRole('button', { name: 'Hide', exact: true }).click();
    await expect(detail.getByText('Event hidden.')).toBeVisible();

    // Gone for everyone who could see it, joined or not: the API, the page, the feed and My schedule.
    for (const player of [reporter, attendee, onlooker]) {
      expect(await eventStatus(player, id), player.name).toBe(404);
      expect((await player.page.request.get(`/api/events/${id}/spots`)).status()).toBe(404);
      await player.page.goto(`/events/${id}`);
      await expect(player.page.getByRole('heading', { name: 'Event not found' })).toBeVisible();
      await expectFeed(player, { hidden: [title] });
      await expectSchedule(player, { hidden: [title] });
    }
    // Silently: nobody who was going, or could have, is told. Neither is the reporter, yet.
    for (const player of [reporter, attendee, onlooker]) {
      await openInbox(player);
      await expect(player.page.getByText('Nothing yet.')).toBeVisible();
    }

    // The host still has it, with a banner that gives the reason, and a notification.
    await openEvent(host, id);
    const banner = host.page
      .getByRole('status')
      .filter({ hasText: 'The moderators hid this event.' });
    await expect(banner).toContainText(`Reason: ${reason}`);
    await openInbox(host);
    const toHost = host.page.getByRole('listitem');
    await expect(toHost).toHaveCount(1);
    await expect(toHost).toContainText(`“${title}” was hidden by the moderators.`);
    await expect(toHost).toContainText(`Reason: ${reason}`);

    await detail.getByRole('button', { name: 'Resolve report' }).click();
    await detail.getByRole('radio', { name: 'Actioned' }).check();
    await detail.getByLabel('Note for moderators (optional)').fill('Hidden as advertising.');
    await detail.getByRole('button', { name: 'Resolve', exact: true }).click();
    await expect(detail.getByText('Report resolved.')).toBeVisible();

    // It has moved from the open queue to the actioned one (newest first, so it's on the first page).
    await detail.getByRole('link', { name: 'Back to reports' }).click();
    await moderator.page.getByRole('link', { name: 'Actioned', exact: true }).click();
    await expect(queueEntry(moderator, 'Actioned reports', title)).toBeVisible();

    // The reporter hears that it was reviewed, and nothing about what was reported or done.
    await reporter.page.goto('/');
    await expect(bell(reporter)).toHaveAccessibleName('Inbox, 1 unread');
    await openInbox(reporter);
    const reviewed = reporter.page.getByRole('listitem');
    await expect(reviewed).toHaveCount(1);
    await expect(reviewed).toContainText(
      'The moderators reviewed a report you sent. Thank you for telling us.',
    );
    await expect(reviewed).not.toContainText(title);
    await expect(reviewed).not.toContainText(reason);
    await expect(reviewed.getByRole('link')).toHaveCount(0);

    // The host never hears of the report: the hide is still their only notification.
    await openInbox(host);
    await expect(host.page.getByRole('listitem')).toHaveCount(1);
  });

  // The layout, which unit tests can't see.
  test('46. the report sits beside the queue on desktop, and replaces it on a phone', async ({
    join,
  }) => {
    const admin = await joinAdmin(join);
    const reporter = await join('layout-reporter', []);
    const reported = await join('layout-reported', []);
    const sent = await apiWrite(reporter, 'POST', '/reports', {
      targetType: 'USER',
      targetId: await userId(reported),
      reason: 'HARASSMENT',
      note: null,
    });
    expect(sent.status()).toBe(204);
    const title = `Person: ${reported.name}`;
    const { page } = admin;
    const noSidewaysScroll = () =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
      );

    await page.goto('/admin');
    await openReport(admin, title);
    const table = page.getByRole('table', { name: 'Open reports' });
    const detail = page.getByRole('article');
    await expect(detail).toContainText(title);
    const queue = await table.boundingBox();
    const beside = await detail.boundingBox();
    expect(beside.x).toBeGreaterThan(queue.x + queue.width);
    // The shell's column is wider here than on other pages (52.5rem, 840px).
    expect((await page.getByRole('main').boundingBox()).width).toBeGreaterThan(900);
    expect(await noSidewaysScroll()).toBe(true);

    await page.setViewportSize({ width: 375, height: 800 });
    await expect(table).toHaveCount(0);
    await expect(detail).toBeVisible();
    expect(await noSidewaysScroll()).toBe(true);

    await detail.getByRole('link', { name: 'Back to reports' }).click();
    const cards = page.getByRole('list', { name: 'Open reports' });
    await expect(cards.getByRole('link').filter({ hasText: title })).toBeVisible();
    await expect(detail).toHaveCount(0);
    expect(await noSidewaysScroll()).toBe(true);
  });
});

test.describe('suspensions and bans', () => {
  test('40. a suspension signs the user out and refuses sign-in, and lifting it lets them back in', async ({
    join,
  }) => {
    const admin = await joinAdmin(join);
    const moderator = await joinModerator(join, admin);
    const user = await join('suspended', []);
    const id = await userId(user);

    const card = await findUser(moderator, user);
    await card.getByRole('button', { name: `Suspend ${user.name}` }).click();
    await card.getByLabel('Suspended until').fill(dayFromNow(7));
    await card.getByLabel('Reason (the person never sees it)').fill('Cooling off.');
    await card.getByRole('button', { name: 'Suspend', exact: true }).click();
    await expect(card.getByText(`${user.name} is suspended.`)).toBeVisible();

    await expectSignedOut(user);
    await expectSignInRefused(user);

    // The admin area has no button for lifting a suspension yet, so this is the moderator's API call.
    const lifted = await apiWrite(moderator, 'POST', `/admin/users/${id}/unsuspend`);
    expect(lifted.status()).toBe(204);
    await signInAgain(user);
    expect(await userId(user)).toBe(id);
  });

  test('41. a ban cancels the user’s upcoming event, tells the people going and waiting, and refuses sign-in', async ({
    join,
  }) => {
    const admin = await joinAdmin(join);
    const { con, host, guests } = await crowd(join, 2);
    const [going, waiting] = guests;
    const title = `Banned host ${host.user.id}`;
    const reason = 'Repeated harassment.';
    const id = await createEvent(host, { title, capacity: 2, audience: { guilds: [con.name] } });
    await joinEvent(going, id, 'Joined');
    await joinEvent(waiting, id, 'Waitlisted #1');
    await expectFeed(going, { sees: [title] });

    const card = await findUser(admin, host);
    await card.getByRole('button', { name: `Ban ${host.name}` }).click();
    await card.getByLabel('Reason (the person never sees it)').fill(reason);
    await card.getByLabel(`Type the Discord ID ${host.user.id} to confirm`).fill(host.user.id);
    await card.getByRole('button', { name: 'Ban', exact: true }).click();
    await expect(card.getByText(`${host.name} is banned.`)).toBeVisible();

    await expectSignedOut(host);
    await expectSignInRefused(host);

    for (const player of [going, waiting]) {
      await openInbox(player);
      const told = player.page.getByRole('listitem');
      await expect(told).toHaveCount(1);
      // The usual cancel notice: no reason, and nothing about moderators or the ban.
      await expect(told).toContainText(`“${title}” was cancelled.`);
      await expect(told).not.toContainText(reason);
      await player.page.goto(`/events/${id}`);
      await expect(player.page.getByText('This event was cancelled.')).toBeVisible();
      await expectFeed(player, { hidden: [title] });
    }
  });
});
