// Rebuilds icons.js from the lucide-react package (ISC) that the frontend already depends on.
// Usage, from the repo root after `npm ci` in frontend/:  node docs/design/mockups/icons.mjs compass calendar ...
import { writeFileSync } from 'node:fs';

const dir = new URL('../../../frontend/node_modules/lucide-react/dist/esm/icons/', import.meta.url);
const out = {};
for (const n of process.argv.slice(2)) {
  const mod = await import(new URL(`${n}.mjs`, dir).href);
  const data = mod.__iconData ?? Object.values(mod).find((v) => v && v.node);
  if (!data) { console.error('no icon data for', n); continue; }
  out[n] = data.node
    .map(([tag, attrs]) => `<${tag} ${Object.entries(attrs).filter(([k]) => k !== 'key').map(([k, v]) => `${k}="${v}"`).join(' ')}/>`)
    .join('');
}
writeFileSync(new URL('./icons.js', import.meta.url), `window.ICONS=${JSON.stringify(out)};\n`);
console.log(Object.keys(out).length, 'icons');
