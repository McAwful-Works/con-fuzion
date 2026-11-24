// Fails when a file under public/ or src/assets/ is not listed in ASSETS.md (frontend/CLAUDE.md, "Art and assets").
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ASSET_DIRS = ['public', 'src/assets'];

function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(full) : [full];
  });
}

const toPosix = (file) => path.relative(root, file).split(path.sep).join('/');

const files = ASSET_DIRS.map((dir) => path.join(root, dir))
  .filter((dir) => existsSync(dir))
  .flatMap(walk)
  .map(toPosix);

// A listed asset is the first backticked path on a table row, e.g. | `public/placeholder-icon.svg` | ...
const manifest = readFileSync(path.join(root, 'ASSETS.md'), 'utf8');
const listed = new Set(
  manifest
    .split('\n')
    .map((line) => /^\|\s*`([^`]+)`/.exec(line)?.[1])
    .filter(Boolean),
);

const unlisted = files.filter((file) => !listed.has(file));
if (unlisted.length > 0) {
  console.error('check-assets: these files are not listed in frontend/ASSETS.md:');
  for (const file of unlisted) console.error(`  ${file}`);
  process.exit(1);
}
console.log(`check-assets: ${files.length} asset file(s), all listed.`);
