// Web Push handlers for the service worker. vite.config.ts loads this into the generated worker with
// Workbox `importScripts`, so the precache and the runtime caching rule stay as they were.
//
// Plain JavaScript on purpose: the browser runs it as it is, with no build step and no imports.
// src/features/push/pushSw.test.ts runs it against a fake worker scope.
//
// Rules (D-014):
// - Every push shows a notification. iOS revokes the permission of a site that receives a silent push.
// - The text comes from the push payload alone, and a payload never holds a location or a name (rule 8).
//   An event title is user text; showNotification renders it as plain text.
// - A click focuses an open tab or opens the event. The URL is built here from a validated event ID, never
//   taken from the payload.

/* global self, clients */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const FALLBACK_BODY = 'You have a new notification.';

/** What happened, by notification type. Mirrors src/features/notifications/describe.ts, minus times. */
const BODY = {
  SPOT_OFFERED: (title) => `You were offered a spot in ${title}. Open the app to accept.`,
  SPOT_CONFIRMED: (title) => `You’re confirmed for ${title}.`,
  EVENT_CHANGED: (title) => `${title} changed. Open the app to see what’s new.`,
  EVENT_CANCELLED: (title) => `${title} was cancelled.`,
  EVENT_REMINDER: (title) => `${title} starts in about an hour.`,
  FRIEND_ACCEPTED: () => 'A friend invite you sent was accepted.',
};

/** The push data as an object, or an empty one when it is missing or isn't JSON. */
function readPayload(event) {
  try {
    const data = event.data ? event.data.json() : null;
    return data && typeof data === 'object' ? data : {};
  } catch {
    return {};
  }
}

/** The notification's title, body and click target, from the push data. Never throws. */
function describePush(data) {
  // The fields may sit at the top level or in a `payload` object, like an inbox notification.
  const inner = data.payload && typeof data.payload === 'object' ? data.payload : {};
  const rawTitle = inner.eventTitle ?? data.eventTitle;
  const eventTitle = typeof rawTitle === 'string' && rawTitle.trim() ? rawTitle.trim() : null;
  const rawId = data.eventId ?? inner.eventId;
  const eventId = typeof rawId === 'string' && UUID.test(rawId) ? rawId : null;
  const body = BODY[data.type];
  return {
    title: eventTitle ?? 'Con-Fuzion',
    body: body ? body(eventTitle ? `“${eventTitle}”` : 'an event') : FALLBACK_BODY,
    // One notification per kind of news about an event, so a newer one of the same kind replaces it.
    tag: `${typeof data.type === 'string' ? data.type : 'push'}:${eventId ?? 'none'}`,
    url: eventId ? `/events/${eventId}` : '/notifications',
  };
}

self.addEventListener('push', (event) => {
  const { title, body, tag, url } = describePush(readPayload(event));
  // The notification is shown whatever the payload held: waitUntil keeps the worker alive until it is.
  event.waitUntil(self.registration.showNotification(title, { body, tag, data: { url } }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const path = event.notification.data && event.notification.data.url;
  // Only a same-origin path from this worker's own `push` handler is ever opened.
  const url =
    typeof path === 'string' && path.startsWith('/') && !path.startsWith('//') ? path : '/';
  event.waitUntil(openOrFocus(url));
});

async function openOrFocus(url) {
  const open = await clients.matchAll({ type: 'window', includeUncontrolled: true });
  const existing = open.find((client) => 'focus' in client);
  if (existing) {
    try {
      await existing.focus();
    } catch {
      await clients.openWindow(url);
      return;
    }
    try {
      if ('navigate' in existing) await existing.navigate(url);
    } catch {
      // Not every browser lets a worker navigate a tab; the tab stays where it was, now focused.
    }
    return;
  }
  await clients.openWindow(url);
}
