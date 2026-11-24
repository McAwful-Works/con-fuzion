# Con-Fuzion — Artist brief (draft)

> Draft for the owner to edit and send. The owner handles hiring, pricing, and the contract.

## About the app
Con-Fuzion is a free web app (installable on phones like an app) that convention-goers use to plan meetups:
dinners, photoshoots, room parties, panel groups. Hosts open events to their friends or Discord communities, with
limited first-come-first-served spots. The tone is friendly, inclusive, and fandom-adjacent. It should read clearly
on small screens and in busy, low-light convention halls.

## Deliverables
| Item | Specs |
|---|---|
| Logo mark + "Con-Fuzion" wordmark | Vector SVG, with light and dark background versions and a one-color version |
| App icons | PNG 192×192 and 512×512, a **maskable** 512×512 (keep the key art inside the central 80% safe zone), Apple touch icon 180×180 |
| Favicon | SVG + 32×32 PNG |
| Social share image | 1200×630 PNG |
| Empty-state illustrations (3) | "No events yet", "No friends yet", "You're offline". SVG preferred, roughly 240×180 display size, readable in light and dark themes |
| Color palette & type suggestion | Primary, accent, and neutrals with hex values. Must meet WCAG AA contrast for text on its backgrounds |
| *(Optional)* Mascot and small badge set | Quoted separately |

## Constraints
- **Original work, created by a human artist.** Please don't use generative-AI tools for any part of the artwork.
- No use of third-party characters, logos, or trademarks, including Discord's.
- Source files (AI/SVG/PSD/Figma) delivered along with the exports.

## Rights
- Work-for-hire, or an exclusive, perpetual, worldwide license covering use in the app, the website, app stores,
  social media, and merchandise. Credit the artist on the About page if they'd like.
- The final terms belong in the owner's contract.

## Handoff
Files go into the repo under `frontend/src/assets/brand/` and `frontend/public/icons/`, each recorded in
`frontend/ASSETS.md` with author and license. The app's colors live in theme tokens, so the palette drops in
directly.
