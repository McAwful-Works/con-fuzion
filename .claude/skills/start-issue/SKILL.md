---
name: start-issue
description: Use at the very start of a work session that implements one GitHub issue. Loads the issue, its acceptance criteria and out-of-scope list, the docs it points to, and sets up the branch. Takes the issue number as an argument.
---

# Start an issue

Argument: the GitHub issue number (for example `12`).

1. **Load the brief.** Run `gh issue view <n> --json title,body,labels,milestone` and read it in full. Pull out:
   - the goal
   - the acceptance criteria
   - the **out-of-scope** list
   - the files and directories in scope
   - the contract commit, if one is given
   - the must-pass tests

   If the acceptance criteria or the out-of-scope list is missing, stop and ask the coordinator (or the user)
   before writing any code.
2. **Read the context.** Read `CLAUDE.md`, the module `CLAUDE.md` for each directory in scope, and the
   `docs/plan.md` sections the issue names. If the issue has the `security` label or touches auth, sessions,
   visibility, admin, reports, secrets, or headers, also read `docs/security.md`.
3. **Branch.** If you're not already on a branch for this issue, create one from the latest `origin/main`:
   `feat/<scope>-<slug>` (or `fix/`, `chore/`, `docs/`, matching the issue type).
4. **Plan.** Post the task list. Each step must map to an acceptance criterion, plus a final "pr-ready" step.
   Nothing on the list can come from the out-of-scope list.
5. **Scope guard for the rest of the session.** If you find something worth doing that isn't in the acceptance
   criteria, add one line to `docs/backlog.md` (or tell the coordinator) and keep going. Don't build it.
   New dependencies or infrastructure need the user's OK first.
