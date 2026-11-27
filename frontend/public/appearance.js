// Light, Dark or Automatic (D-017): puts the `dark` class on <html>, which swaps the color tokens in
// src/index.css, and matches the browser's theme color.
//
// index.html loads this in <head> as a blocking classic script, so the choice applies before the
// first paint. It's a file and not an inline script because the Content-Security-Policy allows
// same-origin script files only (SecurityConfig), so the header doesn't change.
//
// The choice is `localStorage['cf-appearance']`: "light" or "dark". Anything else, or nothing, is
// Automatic, which follows the device. src/features/appearance/useAppearance.ts stores it and then
// sends a `cf-appearance` event carrying the new choice, which also works when the browser refuses
// storage. useAppearance.test.ts runs this file.

/* global window, document, localStorage */

(() => {
  const KEY = 'cf-appearance';
  // Keep in sync with --color-surface in src/index.css.
  const THEME_COLOR = { light: '#fff6ea', dark: '#16111f' };

  const device = window.matchMedia('(prefers-color-scheme: dark)');
  let choice = null;
  try {
    choice = localStorage.getItem(KEY);
  } catch {
    // Storage is off: Automatic.
  }

  function apply() {
    const dark = choice === 'dark' || (choice !== 'light' && device.matches);
    document.documentElement.classList.toggle('dark', dark);
    document
      .querySelector('meta[name="theme-color"]')
      ?.setAttribute('content', dark ? THEME_COLOR.dark : THEME_COLOR.light);
  }

  device.addEventListener('change', apply);
  window.addEventListener(KEY, (event) => {
    choice = event.detail;
    apply();
  });
  apply();
})();
