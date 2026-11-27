// Screenshots the mockups into shots/ (gitignored), then the review sheets into ../sheets/.
// Usage, from the repo root after `npm ci` in e2e/:  node docs/design/mockups/render.mjs [pages] [themes]
//   pages:  comma-separated page names (default: every *.html page except the sheets)
//   themes: a (light), ad (dark); default a,ad
import { readdirSync } from 'node:fs';
import { createRequire } from 'node:module';

const { chromium } = createRequire(new URL('../../../e2e/package.json', import.meta.url))('@playwright/test');
const here = new URL('.', import.meta.url);
const all = readdirSync(here).filter((f) => f.endsWith('.html') && !f.startsWith('sheet')).map((f) => f.slice(0, -5));
const pages = process.argv[2] ? process.argv[2].split(',') : all;
const themes = (process.argv[3] ?? 'a,ad').split(',');
const VP = {
  mobile: { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 },
  desktop: { viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 },
};
const file = (u) => decodeURIComponent(new URL(u, here).pathname).replace(/^\/([A-Za-z]:)/, '$1');

const browser = await chromium.launch();
for (const [vp, opts] of Object.entries(VP)) {
  const page = await (await browser.newContext(opts)).newPage();
  for (const p of pages) for (const t of themes) {
    await page.goto(new URL(`${p}.html?t=${t}`, here).href);
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: file(`shots/${p}-${t}-${vp}.png`) });
  }
}
const sheetPage = await browser.newPage({ viewport: { width: 1600, height: 900 } });
for (const [src, out] of [1, 2, 3, 4, 5].map((s) => [`sheet2.html?s=${s}`, `round2-${s}.png`]).concat([['sheet3.html', 'round3.png']])) {
  await sheetPage.goto(new URL(src, here).href);
  await sheetPage.waitForLoadState('networkidle');
  await sheetPage.screenshot({ path: file(`../sheets/${out}`), fullPage: true });
}
await browser.close();
console.log(`rendered ${pages.length} pages × ${themes.length} themes × 2 sizes, and the sheets`);
