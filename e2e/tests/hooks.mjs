import { test as base } from "@playwright/test";
import { joinAs } from "./social.mjs";
import { resetStub } from "./support.mjs";

/**
 * `test` with the fake Discord reset before every test, so no test sees another's scenario, and a `join` fixture
 * for the specs that need several signed-in users: each is a fresh Discord user in its own browser context, and
 * every context is closed when the test ends, pass or fail.
 */
export const test = base.extend({
  stubReset: [
    async ({}, use) => {
      await resetStub();
      await use();
    },
    { auto: true },
  ],
  join: async ({ browser, baseURL }, use) => {
    const players = [];
    try {
      await use(async (name, guilds, options) => {
        const player = await joinAs(browser, baseURL, name, guilds, options);
        players.push(player);
        return player;
      });
    } finally {
      await Promise.all(players.map((p) => p.close()));
    }
  },
});
export { expect } from "@playwright/test";
