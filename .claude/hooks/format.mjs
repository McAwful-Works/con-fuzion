// PostToolUse (Edit|Write|MultiEdit): runs Prettier on edited frontend files once the frontend is installed.
// Java is formatted by Spotless in the Stop hook (Gradle is too slow to run per edit).
import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { readInput, projectRoot, relPath } from './lib.mjs';

const input = readInput();
const file = input.tool_input && input.tool_input.file_path;
if (!file) process.exit(0);

const root = projectRoot(input);
const rel = relPath(root, file);
const FORMATTED = ['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.css', '.json', '.html', '.md'];
if (!rel.startsWith('frontend/') || rel.includes('node_modules/') || !FORMATTED.includes(path.extname(rel))) process.exit(0);

const prettier = path.join(root, 'frontend', 'node_modules', 'prettier', 'bin', 'prettier.cjs');
if (!existsSync(prettier)) process.exit(0);

spawnSync(process.execPath, [prettier, '--write', '--ignore-unknown', path.resolve(root, file)], {
	cwd: path.join(root, 'frontend'),
	stdio: 'ignore',
	timeout: 30_000,
});
process.exit(0);
