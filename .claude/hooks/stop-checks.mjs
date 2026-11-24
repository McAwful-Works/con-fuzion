// Stop: when backend/frontend code changed since the last green run, run Spotless + unit tests and/or the frontend
// check script. Blocks the stop once on failure so Claude fixes it; never loops (stop_hook_active).
import { existsSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { readInput, projectRoot } from './lib.mjs';

const input = readInput();
if (input.stop_hook_active) process.exit(0);

const root = projectRoot(input);
const git = (...args) => {
	try {
		return execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });
	} catch {
		return '';
	}
};

// -z: NUL-separated, unquoted paths; renames/copies are "XY new\0old\0".
const entries = git('status', '--porcelain=v1', '-z', '-uall').split('\0');
const all = [];
for (let i = 0; i < entries.length; i++) {
	if (!entries[i]) continue;
	all.push(entries[i].slice(3));
	if (/^[RC]/.test(entries[i])) i++; // skip the rename/copy source
}
const isBackendCode = (p) => p.startsWith('backend/') && /\.(java|gradle|kts|properties|xml|sql|ya?ml)$/.test(p);
const isFrontendCode = (p) => p.startsWith('frontend/') && !p.includes('node_modules/') && /\.(tsx?|jsx?|mjs|css|json|html)$/.test(p);
const frontendPkg = path.join(root, 'frontend', 'package.json');
const backend = all.some(isBackendCode);
const frontend = existsSync(frontendPkg) && all.some(isFrontendCode);
if (!backend && !frontend) process.exit(0);
// Only code paths feed the fingerprint, so doc edits don't re-trigger test runs.
const changed = all.filter((p) => isBackendCode(p) || (frontend && isFrontendCode(p)));

// Skip if nothing changed since the last green run.
const stamp = path.join(root, '.claude', '.stop-checks-ok');
const fingerprint = () => {
	const h = createHash('sha1').update(git('diff', 'HEAD', '--', ...changed.filter((p) => !p.endsWith('/'))));
	for (const p of changed) {
		try {
			const s = statSync(path.join(root, p));
			h.update(`${p}:${s.size}:${s.mtimeMs}`);
		} catch {
			h.update(`${p}:gone`);
		}
	}
	return h.digest('hex');
};
if (existsSync(stamp) && readFileSync(stamp, 'utf8') === fingerprint()) process.exit(0);

const run = (cmd, args, cwd) => {
	const r = spawnSync(cmd, args, { cwd, encoding: 'utf8', shell: true, timeout: 840_000, maxBuffer: 64 * 1024 * 1024 });
	return { ok: r.status === 0, out: `${r.stdout || ''}${r.stderr || ''}${r.error ? String(r.error) : ''}` };
};
const tail = (s, n = 60) => s.trim().split('\n').slice(-n).join('\n');
const failures = [];
const warnings = [];

if (backend) {
	const dir = path.join(root, 'backend');
	const gradlew = `"${path.join(dir, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')}"`;
	const build = readFileSync(path.join(dir, 'build.gradle'), 'utf8');
	const tasks = [...(build.includes('spotless') ? ['spotlessApply'] : []), 'test', '-PexcludeTags=integration', '--console=plain'];
	const r = run(gradlew, tasks, dir);
	// Environment problems (no JDK 25, no wrapper jar, no JAVA_HOME) only warn; test/compile failures block.
	if (!r.ok && /toolchain|Cannot find a Java installation|GradleWrapperMain|JAVA_HOME/i.test(r.out)) {
		warnings.push(`stop-checks: backend checks skipped (build environment not ready, which M0 fixes): ${tail(r.out, 3)}`);
	} else if (!r.ok) {
		failures.push(`Backend (${gradlew} ${tasks.join(' ')}) failed:\n${tail(r.out)}`);
	}
}

if (frontend) {
	const scripts = JSON.parse(readFileSync(frontendPkg, 'utf8')).scripts || {};
	if (scripts.check) {
		const r = run('npm', ['run', 'check'], path.join(root, 'frontend'));
		if (!r.ok) failures.push(`Frontend (npm run check) failed:\n${tail(r.out)}`);
	}
}

if (failures.length) {
	process.stdout.write(
		JSON.stringify({ decision: 'block', reason: `stop-checks: fix these before finishing (or explain why they are unrelated):\n\n${failures.join('\n\n')}` }),
	);
	process.exit(0);
}
if (!warnings.length) writeFileSync(stamp, fingerprint());
if (warnings.length) process.stdout.write(JSON.stringify({ systemMessage: warnings.join('\n') }));
process.exit(0);
