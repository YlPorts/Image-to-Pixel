// Package the complete original site, then add only Android integration assets.
import { cpSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { esbuild } from './esbuild.mjs';
import { PALETTES } from '../lib/core/palette/builtin.js';

const root = fileURLToPath(new URL('../', import.meta.url));
const site = join(root, '_site');
export const out = join(root, 'android/app/build/generated/editorAssets/site');
if (!process.argv.includes('--no-build')) execFileSync('npm', ['run', 'build:site'], { cwd: root, stdio: 'inherit' });
if (!existsSync(join(site, 'editor/index.html'))) throw new Error('Build the complete web site first: npm run build:site');
rmSync(out, { recursive: true, force: true });
cpSync(site, out, { recursive: true });
const write = (path, text) => { mkdirSync(join(out, path, '..'), { recursive: true }); writeFileSync(join(out, path), text); };
await esbuild.build({ entryPoints: [join(root, 'android/web/bridge.js')], outfile: join(out, 'assets/android.js'), bundle: true, format: 'iife', target: 'es2020', minify: true, external: ['node:*'] });
cpSync(join(root, 'android/web/mobile.css'), join(out, 'assets/android.css'));
write('assets/offline-palettes.json', JSON.stringify(PALETTES));

const inject = html => html.replace('</head>', '<link rel="stylesheet" href="../assets/android.css">\n<script defer src="../assets/android.js"></script>\n</head>');
write('editor/index.html', inject(readFileSync(join(out, 'editor/index.html'), 'utf8')));

// The classic editor is reachable through the site's navigation. Preserve it too.
const modules = join(root, 'node_modules');
const vendor = join(out, 'classic/vendor');
mkdirSync(vendor, { recursive: true });
for (const [pkg, files] of Object.entries({
  '@fortawesome/fontawesome-free': ['css/all.min.css', 'webfonts', 'LICENSE.txt'],
  '@simonwep/pickr': ['dist/pickr.min.js', 'dist/themes/nano.min.css', 'LICENSE'],
  '@fontsource/inter': ['latin-400.css', 'latin-600.css', 'latin-700.css', 'files', 'LICENSE'],
})) {
  for (const path of files) {
    const dest = join(vendor, pkg.split('/').at(-1), path);
    mkdirSync(join(dest, '..'), { recursive: true });
    cpSync(join(modules, pkg, path), dest, { recursive: true });
  }
}
let classic = readFileSync(join(out, 'classic/index.html'), 'utf8');
classic = classic.replace(/<link\b[^>]*href="https:\/\/cdnjs[^>]*>/gs, '<link rel="stylesheet" href="vendor/fontawesome-free/css/all.min.css">')
  .replace(/<link\b[^>]*href="https:\/\/fonts.googleapis.com[^>]*>/gs, [400, 600, 700].map(w => `<link rel="stylesheet" href="vendor/inter/latin-${w}.css">`).join('\n'))
  .replace('https://cdn.jsdelivr.net/npm/@simonwep/pickr/dist/themes/nano.min.css', 'vendor/pickr/dist/themes/nano.min.css')
  .replace('https://cdn.jsdelivr.net/npm/@simonwep/pickr/dist/pickr.min.js', 'vendor/pickr/dist/pickr.min.js');
write('classic/index.html', classic);
cpSync(join(root, 'LICENSE'), join(out, 'LICENSE.txt'));
cpSync(join(modules, 'shadermate/LICENSE'), join(out, 'assets/shadermate-LICENSE.txt'));
cpSync(join(modules, '@fontsource-variable/jetbrains-mono/LICENSE'), join(out, 'assets/fonts/jetbrains-mono-LICENSE.txt'));

const files = [];
function walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path);
    else {
      const bytes = readFileSync(path);
      files.push({ path: relative(out, path).replaceAll('\\', '/'), bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
    }
  }
}
walk(out);
for (const f of files.filter(f => /\.(html|css)$/.test(f.path))) {
  const contents = readFileSync(join(out, f.path), 'utf8');
  if (/<(?:script|link)\b[^>]*(?:src|href)=["']https?:/i.test(contents) || /url\(\s*["']?https?:/i.test(contents)) throw new Error(`Remote runtime dependency in ${f.path}`);
}
write('android-asset-manifest.json', JSON.stringify({ editorVersion: JSON.parse(readFileSync(join(root, 'package.json'))).version, files }, null, 2));
console.log(`Android offline assets: ${files.length} files, ${(files.reduce((n, f) => n + f.bytes, 0) / 1048576).toFixed(2)} MiB`);
