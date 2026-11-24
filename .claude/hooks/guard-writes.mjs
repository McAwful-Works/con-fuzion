// PreToolUse (Edit|Write|MultiEdit|NotebookEdit): blocks secret files and art assets; asks before dependency-file edits.
import path from 'node:path';
import { readInput, projectRoot, relPath, decide, isSecretEnvFile } from './lib.mjs';

const input = readInput();
const ti = input.tool_input || {};
const file = ti.file_path || ti.notebook_path;
if (!file) process.exit(0);

const relRaw = relPath(projectRoot(input), file);
// Windows filesystems are case-insensitive: compare lower-cased there.
const rel = process.platform === 'win32' ? relRaw.toLowerCase() : relRaw;
const base = path.posix.basename(rel).toLowerCase();
const ext = path.posix.extname(base);

// Secrets: never written by Claude.
if (
	isSecretEnvFile(base) ||
	['.pem', '.key', '.p12', '.pfx', '.jks'].includes(ext) ||
	/(^|\/)secrets\//i.test(rel) ||
	/(^|\/)\.claude\/settings\.local\.json$/.test(rel) ||
	/^application-(local|secret)[^/]*\.(properties|ya?ml)$/.test(base)
) {
	decide('deny', `guard-writes: "${rel}" is a secret/local-only file. Claude must not write it; ask the user to manage it.`);
}

// Art: no AI-generated art (CLAUDE.md rule 5). Assets are added by the owner or the hired artist.
const IMAGE_EXT = ['.png', '.jpg', '.jpeg', '.webp', '.gif', '.ico', '.avif', '.bmp', '.tif', '.tiff'];
const inAssetDir = /(^|\/)frontend\/(public|src\/assets)\//.test(rel);
if (inAssetDir && (IMAGE_EXT.includes(ext) || (ext === '.svg' && !base.startsWith('placeholder-')))) {
	decide(
		'deny',
		`guard-writes: "${rel}" is an art asset. Claude never creates art. Use lucide-react icons, CSS, or a placeholder-* box, and ask the owner to add real assets (listed in frontend/ASSETS.md).`,
	);
}

// Dependency gate: the user approves every dependency-file change.
const DEP_FILES = ['build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties',
	'libs.versions.toml', 'gradle-wrapper.properties', 'package.json'];
if (DEP_FILES.includes(base) && !rel.includes('node_modules/')) {
	decide('ask', `Dependency gate: "${rel}" controls dependencies/build. Approve only if the change is needed for the current issue.`);
}

process.exit(0);
