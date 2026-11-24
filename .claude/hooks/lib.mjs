// Shared helpers for Con-Fuzion Claude Code hooks. Node only, no dependencies, so they behave the same on Windows and CI.
import { readFileSync } from 'node:fs';
import path from 'node:path';

/** Reads and parses the hook's JSON input from stdin. */
export function readInput() {
	try {
		return JSON.parse(readFileSync(0, 'utf8') || '{}');
	} catch {
		return {};
	}
}

/** Returns the project root (worktree root when running in a worktree). */
export function projectRoot(input) {
	const dir = process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd();
	// Git Bash on Windows may hand us /c/Users/... paths; convert them to C:/Users/...
	return process.platform === 'win32' ? dir.replace(/^\/([a-zA-Z])\//, '$1:/') : dir;
}

/** Returns `file` relative to `root`, with forward slashes. */
export function relPath(root, file) {
	return path.relative(root, path.resolve(root, file)).split(path.sep).join('/');
}

/** Emits a PreToolUse permission decision ('deny' | 'ask') and exits. */
export function decide(decision, reason) {
	process.stdout.write(
		JSON.stringify({
			hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: decision, permissionDecisionReason: reason },
		}),
	);
	process.exit(0);
}

/** True for real dotenv files; example/sample/template files are allowed. */
export function isSecretEnvFile(base) {
	const b = base.toLowerCase();
	const isEnv = /^\.env($|\.)/.test(b) || b.endsWith('.env');
	return isEnv && !/\.(example|sample|template)$/.test(b);
}
