---
name: pr-ready
description: Use when a work session's implementation is done, before opening the PR. Runs the full checks, gets a fresh-eyes review, fixes real findings, and opens a PR that follows the template. Never merges.
---

# Get a PR ready

1. **Full checks**, for each module the branch changed:
   - Backend: `cd backend && ./gradlew check` (Windows: `gradlew.bat check`). This includes integration tests.
   - Frontend: `cd frontend && npm run check`.
   - If either fails, fix the problem and rerun. Don't open a PR with red checks.
2. **Scope self-check.** Run `git diff --stat origin/main...HEAD`.
   - Every file must trace to an acceptance criterion.
   - Revert anything that doesn't trace, and log it in `docs/backlog.md` if it's worth keeping.
   - If the change is more than about 400 lines (excluding tests and generated files), propose splitting it.
3. **Review.** Run the `reviewer` agent. Give it the base branch, the acceptance criteria, and the out-of-scope
   list, but **not** your own assessment. Fix every blocker and should-fix finding that holds up, and note any you
   reject with a one-line reason.
4. **Security review.** If the change touches auth, sessions, authorization or visibility, admin, reports, secrets,
   CSP/headers, or infra/IAM, also run `/security-review`, fix its findings, and plan to add the `security` label.
5. **Commit hygiene.** Use Conventional Commit messages. The PR title must be a valid Conventional Commit, because
   it becomes the squash commit.
6. **Open the PR.** Push with `git push -u origin HEAD`, then run `gh pr create`. Fill in
   `.github/pull_request_template.md` completely, including `Closes #<n>`, the acceptance-criteria checklist, a
   one-line justification for each new dependency, and how you verified the change. Add the `security` label if
   step 4 applied.
7. Bind the PR with the ccd_pr tools and report the PR link. **Never merge.** The owner merges.
