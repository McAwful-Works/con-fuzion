# Design reference (M5.5)

The approved mockups for the UI redesign. Decisions are in [D-017](../decisions.md); issues #112–#124 build against
these screens. Where a mockup and the app's behavior or API disagree, **the behavior and the API win**: the mockups
use sample data and show the look, not the rules.

## Review sheets (`sheets/`)
| Sheet | What it shows |
|---|---|
| `round1-option-a.png` | The chosen direction, option A "Con Badge": Discover (first version) and an event with a spot offered |
| `round2-1.png` | Welcome (signed out) and onboarding step 1 |
| `round2-2.png` | My plans, and the host's view of their own meetup |
| `round2-3.png` | Host a meetup: the desktop page and two of the five phone steps |
| `round2-4.png` | People and Inbox |
| `round2-5.png` | You (settings) and Moderation |
| `round3.png` | Dark mode, cover photos and search; the final Discover |

## The mockups (`mockups/`)
Plain HTML, CSS and a little JavaScript. No build step and no dependencies of their own.

- **Pages:** `discover`, `event` (a guest with an offer), `event-cover` (the same with a cover photo), `event-host`,
  `plans`, `create` (desktop page, phone step 4), `create-when` (phone step 2), `people`, `inbox`, `you`,
  `moderation`, `welcome`, `onboarding`. Each `<name>.html` is generated from `pages/<name>.js` by `gen.sh`, except
  `event.html`.
- **Themes:** add `?t=a` for light or `?t=ad` for dark. The layout switches to desktop at 1024px wide. (`themes.css`
  also keeps options B and C from the first round, as `?t=b` and `?t=c`, for the record.)
- **Shared pieces:** `base.css` (layout and components), `themes.css` (the tokens), `shell.js` (the sidebar, top bar
  and tab bar), `cards.js` (event cards, pills, switches, fields).
- **Icons:** `icons.js` holds the Lucide icon paths (ISC), extracted from `lucide-react` by `icons.mjs`.
- **Fonts** load from Google Fonts in the mockups. The app uses the same OFL fonts from `@fontsource` packages.
- **Covers** are striped placeholder boxes, and the avatars are coloured initials standing in for Discord avatars.
  No art was drawn.

### Open or re-render
Open any `mockups/*.html` file in a browser, then resize the window or use the device toolbar.

To re-render the screenshots (into `mockups/shots/`, which git ignores) and the round 2 and 3 sheets, from the repo
root after `npm ci` and `npx playwright install chromium` in `e2e/`. (`round1-option-a.png` is a record of the first
round and isn't re-rendered.)

```
node docs/design/mockups/render.mjs                    # every page, light and dark
node docs/design/mockups/render.mjs discover,plans a   # some pages, light only
```

After editing a page script, run `sh docs/design/mockups/gen.sh` to regenerate its HTML. To add an icon, run
`node docs/design/mockups/icons.mjs <every icon name you need>` after `npm ci` in `frontend/`.

The welcome mockup's event cards are static sample text. The real welcome page makes no event request while
signed out (D-017).
