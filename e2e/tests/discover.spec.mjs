import { expect, test } from './hooks.mjs';
import { crowd, joinEvent } from './rsvp.mjs';
import { befriend, createEvent, openFeed } from './social.mjs';

test('44. Discover’s search and chips narrow the feed through the API and stay in the URL', async ({
  join,
  baseURL,
}) => {
  const { con, host, guests } = await crowd(join, 2);
  const [viewer, other] = guests;
  await befriend(host, viewer, baseURL);
  const tag = host.user.id.slice(-6);
  const forFriends = `Karaoke ${tag}`;
  const full = `Tabletop ${tag}`;

  await createEvent(host, { title: forFriends, audience: { friends: true } });
  const id = await createEvent(host, {
    title: full,
    capacity: 2,
    audience: { guilds: [con.name] },
  });
  await joinEvent(other, id, 'Joined');

  await openFeed(viewer);
  const { page } = viewer;
  const card = (title) => page.getByRole('link', { name: new RegExp(`^${title}`) });
  const chip = (name) =>
    page.getByRole('group', { name: 'Filters' }).getByRole('button', { name, exact: true });
  const shows = async (...titles) => {
    for (const title of [forFriends, full]) {
      await expect(card(title)).toHaveCount(titles.includes(title) ? 1 : 0);
    }
  };
  await shows(forFriends, full);

  await chip(con.name).click();
  await expect(page).toHaveURL(new RegExp(`\\?guild=${con.id}$`));
  await shows(full);

  await chip(con.name).click();
  await chip('Friends').click();
  await expect(page).toHaveURL(/\?friends=true$/);
  await shows(forFriends);

  await chip('All').click();
  await chip('Has spots').click();
  await expect(page).toHaveURL(/\?hasSpots=true$/);
  await shows(forFriends);

  await chip('All').click();
  await page.getByRole('searchbox', { name: 'Search meetups' }).fill(`tabletop ${tag}`);
  await expect(page).toHaveURL(/\?q=tabletop/);
  await shows(full);

  // Back from an event returns to the same list.
  await card(full).click();
  await expect(page.getByRole('heading', { level: 1, name: full })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('searchbox', { name: 'Search meetups' })).toHaveValue(
    `tabletop ${tag}`,
  );
  await shows(full);

  await chip('Friends').click();
  await expect(page.getByRole('heading', { name: 'No meetups match' })).toBeVisible();
  await page.getByRole('button', { name: 'Clear filters' }).click();
  await expect(page).toHaveURL(/\/$/);
  await shows(forFriends, full);
});
