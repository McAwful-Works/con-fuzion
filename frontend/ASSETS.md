# Assets

Every non-code file under `public/` or `src/assets/` must have a row here with its source, author, and license.
`npm run check` (and CI) runs `scripts/check-assets.mjs`, which fails on any file that isn't listed.
Claude never creates art. The owner or the hired artist adds the files and the rows.

Put the file's path relative to `frontend/` in backticks in the first column.

| File                | Source          | Author                  | License | Notes                                                                                             |
| ------------------- | --------------- | ----------------------- | ------- | ------------------------------------------------------------------------------------------------- |
| `public/push-sw.js` | This repository | Con-Fuzion contributors | MIT     | Code, not art: the service worker's push handlers. Listed because `check-assets` walks `public/`. |

## Not files, so not listed above

- Inter (variable): the `@fontsource-variable/inter` npm package. SIL OFL 1.1.
- Icons: the `lucide-react` npm package. ISC.
- The text wordmark "Con-Fuzion" is plain text styled with the theme tokens in `src/index.css`.
- The PWA manifest has no icons until the artist delivers them.
