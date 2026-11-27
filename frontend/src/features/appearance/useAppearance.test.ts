import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import html from '../../../index.html?raw';
// The pre-paint script is plain JavaScript that the browser runs as it is; this runs the same text.
import script from '../../../public/appearance.js?raw';
import { useAppearance } from './useAppearance';

const LIGHT = '#fff6ea';
const DARK = '#16111f';

let listeners: AbortController;

/** Loads the page: a device in light or dark, then the script, as `<head>` does. */
function load({ deviceDark = false } = {}) {
  const changes: Array<() => void> = [];
  const device = {
    matches: deviceDark,
    addEventListener: (_type: string, listener: () => void) => changes.push(listener),
  };
  const page = {
    matchMedia: () => device,
    // Removed after each test, so one test's script can't answer the next one's events.
    addEventListener: (type: string, listener: EventListener) =>
      window.addEventListener(type, listener, { signal: listeners.signal }),
  };
  new Function('window', script)(page);

  return {
    setDeviceDark(dark: boolean) {
      device.matches = dark;
      changes.forEach((change) => change());
    },
  };
}

const isDark = () => document.documentElement.classList.contains('dark');
const themeColor = () =>
  document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')!.content;

beforeEach(() => {
  listeners = new AbortController();
  localStorage.clear();
  document.documentElement.classList.remove('dark');
  document.head.innerHTML = `<meta name="theme-color" content="${LIGHT}">`;
});

afterEach(() => {
  listeners.abort();
  vi.restoreAllMocks();
});

describe('appearance', () => {
  it('loads before the first paint: a blocking script in the head, after the theme color', () => {
    const page = new DOMParser().parseFromString(html, 'text/html');
    const tag = page.head.querySelector('script[src="/appearance.js"]')!;

    expect(tag).not.toBeNull();
    expect(tag.getAttributeNames()).toEqual(['src']);
    expect(page.head.querySelector('meta[name="theme-color"] ~ script')).toBe(tag);
  });

  it.each([
    { deviceDark: false, color: LIGHT },
    { deviceDark: true, color: DARK },
  ])('defaults to Automatic, as the device is (dark: $deviceDark)', ({ deviceDark, color }) => {
    load({ deviceDark });
    const { result } = renderHook(() => useAppearance());

    expect(result.current.appearance).toBe('automatic');
    expect(isDark()).toBe(deviceDark);
    expect(themeColor()).toBe(color);
  });

  it('follows a change of the device setting while Automatic', () => {
    const device = load();

    device.setDeviceDark(true);
    expect(isDark()).toBe(true);
    expect(themeColor()).toBe(DARK);

    device.setDeviceDark(false);
    expect(isDark()).toBe(false);
    expect(themeColor()).toBe(LIGHT);
  });

  it.each([
    { choice: 'dark' as const, deviceDark: false, color: DARK },
    { choice: 'light' as const, deviceDark: true, color: LIGHT },
  ])('applies $choice over the device, stores it and keeps it', ({ choice, deviceDark, color }) => {
    const device = load({ deviceDark });
    const { result } = renderHook(() => useAppearance());

    act(() => result.current.setAppearance(choice));

    expect(result.current.appearance).toBe(choice);
    expect(localStorage.getItem('cf-appearance')).toBe(choice);
    expect(isDark()).toBe(choice === 'dark');
    expect(themeColor()).toBe(color);

    device.setDeviceDark(!deviceDark);
    device.setDeviceDark(deviceDark);
    expect(isDark()).toBe(choice === 'dark');
  });

  it.each(['light', 'dark'] as const)(
    'applies a stored %s choice when the page loads',
    (choice) => {
      localStorage.setItem('cf-appearance', choice);

      load({ deviceDark: choice === 'light' });
      const { result } = renderHook(() => useAppearance());

      expect(result.current.appearance).toBe(choice);
      expect(isDark()).toBe(choice === 'dark');
    },
  );

  it('goes back to Automatic: it forgets the choice and follows the device again', () => {
    localStorage.setItem('cf-appearance', 'light');
    const device = load({ deviceDark: true });
    const { result } = renderHook(() => useAppearance());

    act(() => result.current.setAppearance('automatic'));

    expect(result.current.appearance).toBe('automatic');
    expect(localStorage.getItem('cf-appearance')).toBeNull();
    expect(isDark()).toBe(true);

    device.setDeviceDark(false);
    expect(isDark()).toBe(false);
  });

  it('treats an unknown stored value as Automatic', () => {
    localStorage.setItem('cf-appearance', 'sepia');

    load({ deviceDark: true });
    const { result } = renderHook(() => useAppearance());

    expect(result.current.appearance).toBe('automatic');
    expect(isDark()).toBe(true);
  });

  it('still applies a choice when the browser refuses storage', () => {
    const refuse = () => {
      throw new DOMException('denied', 'SecurityError');
    };
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(refuse);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(refuse);

    const device = load();
    const { result } = renderHook(() => useAppearance());
    act(() => result.current.setAppearance('dark'));

    expect(result.current.appearance).toBe('dark');
    expect(isDark()).toBe(true);

    device.setDeviceDark(false);
    expect(isDark()).toBe(true);
  });
});
