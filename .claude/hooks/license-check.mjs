// PostToolUse (Edit|Write|MultiEdit): Java files must carry the MIT header and a class-level @author.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { readInput, projectRoot, relPath } from './lib.mjs';

const input = readInput();
const file = input.tool_input && input.tool_input.file_path;
if (!file || !file.endsWith('.java')) process.exit(0);

const root = projectRoot(input);
const rel = relPath(root, file);
if (!rel.startsWith('backend/src/')) process.exit(0);

let text;
try {
	text = readFileSync(path.resolve(root, file), 'utf8');
} catch {
	process.exit(0);
}

const problems = [];
if (!/MIT License/.test(text) || !/Copyright \(c\) \d{4} McAwful-Works/.test(text)) {
	problems.push('missing the MIT license header (copy it verbatim from backend/src/main/java/app/confuzion/backend/aspect/RateLimiterAspect.java)');
}
const base = path.basename(file);
if (base !== 'package-info.java' && base !== 'module-info.java' && !/@author\s+\S+/.test(text)) {
	problems.push('missing a class-level Javadoc with @author');
}

if (problems.length) {
	process.stderr.write(`license-check: ${rel} is ${problems.join(' and ')}. Fix it now (backend/CLAUDE.md code rules).\n`);
	process.exit(2);
}
process.exit(0);
