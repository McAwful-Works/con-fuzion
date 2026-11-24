# Branching & releases

Con-Fuzion uses **trunk-based development with release tags** (decision D-009). There's one long-lived branch,
`main`, and it's always releasable.

## Day-to-day development
- Every change is a short-lived branch off `main`, covering one issue, ideally merged within a few days.
  Branch names follow `CLAUDE.md`: `feat/<scope>-<slug>`, `fix/…`, `chore/…`, `docs/…`, `research/…`.
- Every change reaches `main` through a **squash-merged PR**. The PR title becomes the commit, so it must be a valid
  Conventional Commit.
- Keep branches current by merging or rebasing `main` into them. Never merge one feature branch into another.
- **Unfinished work** merges only when it's harmless. If a feature spans several PRs, the early PRs leave it
  unreachable (no route or menu entry) until the last one wires it in. There's no flag framework and no long-lived
  feature branches.

## Versions
- Versions follow SemVer tags: `vMAJOR.MINOR.PATCH`. The project stays on `0.x` until the MVP launch, which is
  **`v1.0.0`**.
- Conventional Commits suggest the bump:
  - `feat` → minor
  - `fix` or `perf` → patch
  - `!` or `BREAKING CHANGE` → major (minor while on 0.x)

## Cutting a release (owner only)
1. Check that `main` is green and that its latest commit is what you want to ship.
2. Create the release: GitHub → Releases → *Draft a new release* → new tag `vX.Y.Z` on `main` →
   *Generate release notes* → Publish.
   Or from a terminal: `gh release create vX.Y.Z --target main --generate-notes`.
3. From M6 onward: merging to `main` deploys to **staging**, and publishing a `v*` tag deploys to **prod** through a
   GitHub environment with manual approval. Until M6, a tag only marks the version.

## Hotfixes
- **Normal case:** the fix lands on `main` through a PR, then you tag a new patch release from `main`.
- **When `main` holds unreleased work that mustn't ship yet:**
  1. Cut `release/X.Y` from the last tag: `git switch -c release/X.Y vX.Y.Z`, then push it. An admin creates it,
     because the branch is protected once it exists.
  2. Fix on `main` first, then open a PR into `release/X.Y` that cherry-picks the fix.
  3. Tag `vX.Y.(Z+1)` on `release/X.Y`.

  Release branches are temporary. Delete them, or just stop using them, once `main` ships the next release.

## Protection (GitHub rulesets)
| Ruleset | Applies to | Rules |
|---|---|---|
| Protect main | default branch | PR required (0 approvals, threads resolved), squash only, required checks `conventional-commit` + `gitleaks`, CodeQL with no high-or-worse alerts, no force-push, no deletion |
| Protect release branches | `release/*` | Same rules as `main` |
| Protect release tags | `v*` tags | Only repository admins can create tags. Nobody can move or delete them |

Repository settings: squash merge only, PR title and body as the squash commit message, and head branches are
deleted after merge.

## What Claude sessions may do
- Create branches, push their own branch, and open PRs.
- **Never** merge, create tags or releases, push to `release/*`, or push tags. A `guard-bash` hook blocks these
  commands, and the rulesets block them on GitHub.
