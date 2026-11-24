import { test as base } from '@playwright/test';
import { resetStub } from './support.mjs';

/** `test` with the fake Discord reset before every test, so no test sees another's scenario. */
export const test = base.extend({
  stubReset: [
    async ({}, use) => {
      await resetStub();
      await use();
    },
    { auto: true },
  ],
});
export { expect } from '@playwright/test';
