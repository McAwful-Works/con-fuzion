# Assets

Every non-code file under `public/` or `src/assets/` must have a row here with its source, author, and license.
`npm run check` (and CI) runs `scripts/check-assets.mjs`, which fails on any file that isn't listed.
Claude never creates art. The owner or the hired artist adds the files and the rows.

Put the file's path relative to `frontend/` in backticks in the first column.

| File                   | Source          | Author                  | License | Notes                                                                                             |
| ---------------------- | --------------- | ----------------------- | ------- | ------------------------------------------------------------------------------------------------- |
| `public/push-sw.js`    | This repository | Con-Fuzion contributors | MIT     | Code, not art: the service worker's push handlers. Listed because `check-assets` walks `public/`. |
| `public/appearance.js` | This repository | Con-Fuzion contributors | MIT     | Code, not art: applies light or dark before the first paint.                                      |

## Cover photos (to come)

Event cards and event pages show a cover chosen by the event's tag (D-017). Until the owner adds the licensed
photos, each cover is a striped CSS placeholder, and a photo that fails to load falls back to it.

Put the photos in `src/assets/covers/` and give each file a row in the table above, with its source, author and
license. Each is landscape, at least 1600×900, as JPEG, WebP, AVIF or PNG. The app finds them by the file names in
`src/features/events/covers.ts`, so for another name or format, change the name there and nothing else.

- Tabletop: `cover-tabletop.jpg`
- Photoshoot: `cover-photoshoot.jpg`
- Food: `cover-food.jpg`
- Music: `cover-music.jpg`
- Panel: `cover-panel.jpg`
- Shopping: `cover-shopping.jpg`
- Hobby: `cover-hobby.jpg`
- Default, for no tag and a host's own tag: `cover-default.jpg`

For each, also add a copy 800 pixels wide, with `-800` before the extension (`cover-tabletop-800.jpg`), and its row.
Small screens download that one instead. Without it, every screen downloads the full photo.

## Not files, so not listed above

- Bricolage Grotesque (variable, headings): the `@fontsource-variable/bricolage-grotesque` npm package. SIL OFL 1.1.
- Inter (variable): the `@fontsource-variable/inter` npm package. SIL OFL 1.1.
- Icons: the `lucide-react` npm package. ISC.
- The text wordmark "Con-Fuzion" is plain text styled with the theme tokens in `src/index.css`.
- The PWA manifest has no icons until the artist delivers them.
