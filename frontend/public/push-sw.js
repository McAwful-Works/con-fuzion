// Web Push handlers for the service worker. vite.config.ts loads this into the generated worker with
// Workbox `importScripts`, so the precache and the runtime caching rule stay as they were.
//
// Plain JavaScript on purpose: the browser runs it as it is, with no build step and no imports.
// src/features/push/pushSw.test.ts runs it against a fake worker scope.
//
// The payload (sent by the backend, #67) is JSON: { type, eventId?, text }. `text` is a ready-made sentence
// with the event title and what happened.
//
// Rules (D-014):
// - Every push shows a notification, whatever the payload held. iOS revokes the permission of a site that
//   receives a silent push.
// - The text comes from the payload alone, and a payload never holds a location or a name (rule 8). It is
//   user text (an event title), and showNotification renders it as plain text.
// - A click focuses an open tab or opens the event. The URL is built here from a validated event ID, never
//   taken from the payload.

/* global self, clients */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// Operating systems cut long text anyway; this only bounds what is handed to them.
const MAX_TEXT = 300;
const FALLBACK_BODY = 'You have a new notification.';

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
  const text = typeof data.text === 'string' ? data.text.trim() : '';
  const eventId = typeof data.eventId === 'string' && UUID.test(data.eventId) ? data.eventId : null;
  return {
    title: 'Con-Fuzion',
    body: text.slice(0, MAX_TEXT) || FALLBACK_BODY,
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
  const url = typeof path === 'string' && /^\/(?![/\\])/.test(path) ? path : '/';
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
