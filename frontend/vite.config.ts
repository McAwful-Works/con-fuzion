import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      // The user decides when to reload onto a new version; the prompt UI comes with M4.
      registerType: 'prompt',
      manifest: {
        name: 'Con-Fuzion',
        short_name: 'Con-Fuzion',
        description: 'Host and join convention meetups.',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        // Keep in sync with --color-surface in src/index.css.
        theme_color: '#fff6ea',
        background_color: '#fff6ea',
        // No icons until the artist delivers them (frontend/ASSETS.md); installability is
        // incomplete without 192px and 512px icons.
        icons: [],
      },
      workbox: {
        // The push and notificationclick handlers (public/push-sw.js). Keeping `generateSW` and importing
        // the handlers means the precache and the runtime rule below stay exactly as they were, and no
        // workbox-* package has to become a direct dependency, which `injectManifest` would need.
        importScripts: ['push-sw.js'],
        // The moderation chunk isn't precached, so only a moderator who opens `/admin` downloads it.
        // It's named after `AdminArea.tsx`, so renaming that file means renaming the pattern.
        // The first pattern is Workbox's default, which setting this option would otherwise drop.
        globIgnores: ['**/node_modules/**/*', 'assets/AdminArea-*.js'],
        // The SPA shell fallback must never swallow API calls.
        navigateFallbackDenylist: [/^\/api\//],
        runtimeCaching: [
          {
            // My schedule reads offline from its last good response, and so does `/api/me`, which
            // the app needs to know you're signed in. Nothing else is cached: every other API call,
            // and every write, goes to the network. The app clears this cache when a session ends.
            urlPattern: ({ url }) =>
              url.pathname === '/api/me' ||
              (url.pathname === '/api/events' && url.searchParams.get('scope') === 'attending'),
            handler: 'NetworkFirst',
            options: {
              // Keep in sync with OFFLINE_CACHE in src/api/offlineCache.ts.
              cacheName: 'my-schedule',
              networkTimeoutSeconds: 5,
              cacheableResponse: { statuses: [200] },
              expiration: { maxEntries: 10, maxAgeSeconds: 7 * 24 * 60 * 60 },
            },
          },
        ],
      },
    }),
  ],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
  },
});
