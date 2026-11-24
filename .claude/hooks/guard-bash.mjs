// PreToolUse (Bash|PowerShell): protects main, blocks force-push / merges / terraform apply / printing secrets;
// asks before adding packages. Best-effort guard: GitHub branch protection is the real backstop.
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { readInput, projectRoot, decide, isSecretEnvFile } from './lib.mjs';

const input = readInput();
const command = (input.tool_input && input.tool_input.command) || '';
if (!command.trim()) process.exit(0);

const branchCache = new Map();
/** Current branch of the repo containing `dir` ('' if unknown). */
function branchAt(dir) {
	if (!branchCache.has(dir)) {
		let b = '';
		try {
			b = execFileSync('git', ['branch', '--show-current'], { cwd: dir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
		} catch {
			// not a repo / dir missing: branch checks are skipped
		}
		branchCache.set(dir, b);
	}
	return branchCache.get(dir);
}
const isMain = (b) => b === 'main' || b === 'master';
const MAIN_REF = /^\+?([^:]*:)?(refs\/heads\/)?(main|master)$/;

/** Splits a command line into simple command segments, each a token array (quotes and $( ) wrappers stripped). */
function segments(cmd) {
	const joined = cmd.replace(/\\\r?\n/g, ' ').replace(/`\r?\n/g, ' '); // bash / PowerShell line continuations
	const out = [[]];
	for (const t of joined.match(/"(?:\\.|[^"\\])*"|'[^']*'|&&|\|\||[;|\n]|[^\s;|&"']+|&/g) || []) {
		if (['&&', '||', ';', '|', '\n', '&'].includes(t)) out.push([]);
		else out[out.length - 1].push(t.replace(/^["']|["']$/g, '').replace(/^(\$\(|`|\()+/, '').replace(/\)+$/, ''));
	}
	return out.filter((t) => t.length > 0);
}

/** Program name of a token: basename, lower-cased, without .exe/.cmd. */
const prog = (t) => path.basename(t).toLowerCase().replace(/\.(exe|cmd|bat)$/, '');

/** Index of the first non-option token at or after `start`, skipping values of options that take one. */
function firstWord(tokens, start, valueOpts) {
	let i = start;
	while (i < tokens.length && tokens[i].startsWith('-')) {
		if (valueOpts.includes(tokens[i])) i++;
		i++;
	}
	return i;
}

const READERS = ['cat', 'type', 'less', 'more', 'head', 'tail', 'bat', 'get-content', 'gc', 'grep', 'rg', 'sed', 'awk', 'nl', 'strings',
	'xxd', 'od', 'base64', 'select-string', 'sls', 'findstr', 'format-hex', 'copy', 'cp', 'import-csv'];
const INSTALL = ['install', 'i', 'add', 'in', 'isntall'];
const GUARDED = ['git', 'gh', 'terraform', 'tofu', 'npm', 'pnpm', 'yarn', 'cat', 'type', 'get-content', 'grep', 'bash', 'sh', 'pwsh', 'powershell', 'cmd'];

function scan(cmd, startDir, depth = 0) {
	let dir = startDir;
	for (const tokens of segments(cmd)) {
		const first = prog(tokens[0]);

		// Nested command lines: bash -c "...", powershell -Command "...", cmd /c "...", or a quoted string that starts
		// with a guarded tool. Other quoted text (commit messages, echo) is not rescanned, to avoid false positives.
		if (depth < 3) {
			tokens.forEach((t, i) => {
				if (i === 0 || !/\s/.test(t)) return;
				const launcher = ['-c', '-command', '/c', '/k', '-encodedcommand'].includes(tokens[i - 1].toLowerCase());
				if (launcher || GUARDED.includes(prog(t.trim().replace(/^(\$\(|`|\()+/, '').split(/\s/)[0]))) scan(t, dir, depth + 1);
			});
		}

		// Track `cd X` so later git commands check the right checkout.
		if (['cd', 'set-location', 'pushd', 'sl'].includes(first) && tokens[1]) {
			dir = path.resolve(dir, tokens[1]);
			continue;
		}

		if (READERS.includes(first) && tokens.slice(1).some((t) => isSecretEnvFile(path.basename(t)))) {
			decide('deny', 'guard-bash: reading .env files is not allowed. Ask the user for the specific non-secret value you need.');
		}

		const idx = tokens.findIndex((t) => ['git', 'gh', 'terraform', 'tofu', 'npm', 'pnpm', 'yarn'].includes(prog(t)));
		if (idx < 0) continue;
		const tool = prog(tokens[idx]);

		if (tool === 'git') {
			const cIdx = tokens.indexOf('-C', idx);
			const gitDir = cIdx > idx && tokens[cIdx + 1] ? path.resolve(dir, tokens[cIdx + 1]) : dir;
			const branch = branchAt(gitDir);
			const s = firstWord(tokens, idx + 1, ['-C', '-c', '--git-dir', '--work-tree']);
			const [sub, ...args] = tokens.slice(s);
			// `git switch -c X` / `git checkout -b X` earlier in the same command line moves later segments off main.
			const nb = sub === 'switch' ? args.findIndex((a) => ['-c', '-C', '--create'].includes(a)) : -1;
			const cb = sub === 'checkout' ? args.findIndex((a) => ['-b', '-B'].includes(a)) : -1;
			const created = nb >= 0 ? args[nb + 1] : cb >= 0 ? args[cb + 1] : undefined;
			if (created) {
				branchCache.set(gitDir, created);
				continue;
			}
			if (isMain(branch) && ['commit', 'merge', 'cherry-pick', 'revert', 'am', 'rebase', 'pull'].includes(sub)) {
				decide('deny', `guard-bash: you are on "${branch}". Never change main locally; create a branch (feat/…, fix/…, chore/…) first.`);
			}
			if (sub === 'branch' && args.some((a) => ['-f', '--force', '-M', '-m', '-D'].includes(a)) && args.some((a) => /^(main|master)$/.test(a))) {
				decide('deny', 'guard-bash: moving, renaming or deleting main is blocked.');
			}
			if (sub === 'update-ref' && args.some((a) => /refs\/heads\/(main|master)$/.test(a))) {
				decide('deny', 'guard-bash: updating refs/heads/main is blocked.');
			}
			// `git tag` lists unless it is given a name to create, or a create/move/delete flag. List-mode flags win.
			const TAG_LIST = /^(-l|--list|-n\d*|--contains|--no-contains|--merged|--no-merged|--points-at|--sort|--format|--column|--no-column|-v|--verify|-i|--ignore-case)(=|$)/;
			const TAG_WRITE = /^(-a|--annotate|-s|--sign|-u|--local-user|-d|--delete|-f|--force|-m|--message|-F|--file)(=|$)/;
			const tagWrites = args.some((a) => TAG_WRITE.test(a)) || (args.some((a) => !a.startsWith('-')) && !args.some((a) => TAG_LIST.test(a)));
			if (sub === 'tag' && tagWrites && !(args.some((a) => TAG_LIST.test(a)) && !args.some((a) => /^(-d|--delete|-f|--force)$/.test(a)))) {
				decide('deny', 'guard-bash: creating, moving or deleting tags is the owner\'s job (docs/releasing.md).');
			}
			// Tags pushed by short name (`git push origin v1.0.0`) count too: any refspec side that looks like a v-tag.
			const refs = args.filter((a) => !a.startsWith('-')).slice(1);
			const tagLike = (r) => r.replace(/^\+/, '').split(':').some((side) => /^v\d/.test(side) || /^refs\/tags\//.test(side));
			if (sub === 'push' && (args.some((a) => a === '--tags' || a === '--follow-tags') || refs.some(tagLike) || refs.some((r) => /^\+?(release\/|refs\/heads\/release\/)/.test(r.split(':').pop())))) {
				decide('deny', 'guard-bash: pushing tags or to release/* is blocked. Releases are cut by the owner (docs/releasing.md).');
			}
			if (sub === 'push') {
				const force = args.some((a) => a === '--force' || a === '-f' || /^\+/.test(a) || /^-[a-z]*f[a-z]*$/.test(a));
				if (force && !args.some((a) => a.startsWith('--force-with-lease'))) {
					decide('deny', 'guard-bash: plain force-push is blocked. Use --force-with-lease on your own branch, never on main.');
				}
				const refspecs = args.filter((a) => !a.startsWith('-')).slice(1); // first non-option is the remote
				const headIsMain = isMain(branch) && refspecs.some((r) => /^\+?(HEAD|@)(:|$)/.test(r));
				if (refspecs.some((r) => MAIN_REF.test(r)) || headIsMain || (isMain(branch) && refspecs.length === 0) || args.includes('--all') || args.includes('--mirror')) {
					decide('deny', 'guard-bash: pushing to main is blocked. Push your branch and open a PR; the owner merges.');
				}
			}
		}
		if (tool === 'gh') {
			const [sub, ...args] = tokens.slice(firstWord(tokens, idx + 1, ['-R', '--repo']));
			if (sub === 'pr' && args.includes('merge')) {
				decide('deny', 'guard-bash: Claude never merges PRs. Tell the user the PR is ready; the owner merges.');
			}
			if (sub === 'release' && ['create', 'edit', 'delete', 'upload'].includes(args[0])) {
				decide('deny', 'guard-bash: releases are cut by the owner (docs/releasing.md).');
			}
			if (sub === 'api' && args.some((a) => /\/pulls\/\d+\/merge\b|\/merges\b/.test(a))) {
				decide('deny', 'guard-bash: merging via the API is blocked. The owner merges.');
			}
		}
		if (tool === 'terraform' || tool === 'tofu') {
			const sub = tokens[firstWord(tokens, idx + 1, [])];
			if (['apply', 'destroy', 'import'].includes(sub)) {
				decide('deny', `guard-bash: "terraform ${sub}" is run by the owner only. Run fmt/validate/plan and share the plan.`);
			}
		}
		if (['npm', 'pnpm', 'yarn'].includes(tool)) {
			// Find the install verb anywhere (covers --prefix x, -w x, --filter x, `yarn workspace x add`).
			const v = tokens.findIndex((t, i) => i > idx && INSTALL.includes(t));
			if (v > 0 && tokens.slice(v + 1).some((a) => !a.startsWith('-'))) {
				decide('ask', `Dependency gate: "${tokens.slice(idx).join(' ')}" adds packages. Approve only if needed for the current issue.`);
			}
		}
	}
}

scan(command, input.cwd || projectRoot(input));
process.exit(0);
