# Frontend — React + Vite + TypeScript PWA

Read the root `CLAUDE.md` first. Design reference: `docs/plan.md` section B7. The scaffold itself is created in M0.

## Stack
React Router · TanStack Query · Tailwind · `vite-plugin-pwa` · `openapi-typescript` · `date-fns-tz` · Vitest +
Testing Library. End-to-end tests live in `e2e/` (Playwright against the jar and a fake Discord, see
`e2e/README.md`). Don't add other libraries without asking.

## Rules
- **TypeScript strict.** No `any` without a comment explaining why.
- **API types are generated** from `docs/api/openapi.json` and never edited by hand. If the contract looks wrong,
  stop and raise it with the coordinator instead of working around it.
- **Mobile-first.** Design for 375px wide first, then scale up. Touch targets are at least 44px.
- **Plain text only** for user content. Never use `dangerouslySetInnerHTML`, and never render Markdown or HTML from users.
- **Same origin.** Call `/api/...` with relative URLs. In dev, Vite proxies `/api` to `http://localhost:8080`.
  Mutations send the CSRF token from the `XSRF-TOKEN` cookie as the `X-XSRF-TOKEN` header.
- **Time**: store and send UTC ISO strings. Show times in the event's time zone, plus the viewer's zone when they differ.
- **Polling, not sockets.** Spot counts use adaptive `refetchInterval` (plan B2 hot-spot table).
- **Offline**: My plans is cached for reading. Mutations need a connection and show an offline banner when there isn't one.

## Art and assets (strict)
- **Never create art**, including images, logos, icons, illustrations, or decorative SVG. A hook blocks image writes
  under `public/` and `src/assets/`.
- Allowed:
  - Icons from the `lucide-react` package.
  - `@fontsource` OFL fonts.
  - The official Discord logo for the sign-in button, per Discord's brand guidelines.
  - Discord CDN avatars and server icons, loaded at runtime.
  - CSS-only UI such as skeletons and spinners.
- Placeholders are named `placeholder-*`: a neutral box plus a text wordmark.
- Every non-code file in `public/` or `src/assets/` is listed in `frontend/ASSETS.md` with its source, author, and
  license. CI fails otherwise.
- Brand colors, type, and radii live only in Tailwind theme tokens, so the artist's work drops in without code changes.

## Donations
The Support links come from `GET /api/meta/support`. Show them only on Settings → About → Support and as a muted
footer link. No modals, banners, toasts, or notifications.

## Commands (after M0)
```
npm run dev       # Vite dev server on :5173 (proxies /api)
npm run check     # typecheck + lint + test
npm run gen:api   # regenerate types from ../docs/api/openapi.json
```
