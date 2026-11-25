/** What this browser can do about push, before the server is asked anything. */
export type PushSupport =
  /** Push can be asked for here. */
  | 'ok'
  /** iPhone or iPad outside an installed app: push only exists once Con-Fuzion is on the Home Screen. */
  | 'needs-install'
  /** No service worker, Push API or Notification API. */
  | 'unsupported';

export function isIos(): boolean {
  const { userAgent, platform, maxTouchPoints } = navigator;
  // iPadOS 13+ reports itself as a Mac, but a Mac has no touch screen.
  return /iPad|iPhone|iPod/.test(userAgent) || (platform === 'MacIntel' && maxTouchPoints > 1);
}

/** True when running as an installed app (the manifest's `display: standalone`). */
export function isStandalone(): boolean {
  // `navigator.standalone` is Safari's own flag; the media query is everyone else's.
  const safari = (navigator as Navigator & { standalone?: boolean }).standalone === true;
  return safari || window.matchMedia?.('(display-mode: standalone)').matches === true;
}

export function pushSupport(): PushSupport {
  // iOS Safari outside standalone mode has no PushManager at all, so this check has to come first.
  if (isIos() && !isStandalone()) return 'needs-install';
  const capable =
    'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  return capable ? 'ok' : 'unsupported';
}

/** The VAPID public key (unpadded base64url) as the bytes `pushManager.subscribe()` wants. */
export function decodeKey(key: string): Uint8Array<ArrayBuffer> {
  const base64 = key.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '='));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

/** This browser's current push subscription, or null. */
export async function currentSubscription(): Promise<PushSubscription | null> {
  if (pushSupport() !== 'ok') return null;
  const registration = await navigator.serviceWorker.getRegistration();
  return (await registration?.pushManager.getSubscription()) ?? null;
}

/**
 * Asks for the notification permission and subscribes. Call it from the tap itself with nothing
 * awaited before it: iOS only allows both while a user gesture is still in progress, so the key has
 * to be loaded already and the service worker registration is only awaited after the prompt.
 */
export async function subscribeFromTap(
  publicKey: string,
): Promise<{ permission: NotificationPermission; subscription: PushSubscription | null }> {
  const ready = navigator.serviceWorker.ready;
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return { permission, subscription: null };
  const registration = await ready;
  const subscription = await registration.pushManager.subscribe({
    // Every push shows a notification (public/push-sw.js), which is what this promises.
    userVisibleOnly: true,
    applicationServerKey: decodeKey(publicKey),
  });
  return { permission, subscription };
}
