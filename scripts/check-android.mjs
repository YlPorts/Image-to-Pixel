import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const out = join(root, 'android/app/build/generated/editorAssets/site');
const manifest = JSON.parse(readFileSync(join(out, 'android-asset-manifest.json')));
for (const f of manifest.files) {
  const bytes = readFileSync(join(out, f.path));
  assert.equal(bytes.length, f.bytes, f.path);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), f.sha256, f.path);
}
for (const path of ['assets/editor.js', 'assets/editor.css', 'assets/fonts/geist.woff2', 'assets/fonts/jetbrains-mono.woff2', 'assets/site.css', 'lib/image-to-pixel.bundle.esm.js', 'lib/video.esm.js'])
  assert.deepEqual(readFileSync(join(out, path)), readFileSync(join(root, '_site', path)), `Original bytes preserved: ${path}`);
for (const path of ['assets/android.js', 'assets/android.css', 'assets/fonts/jetbrains-mono-LICENSE.txt', 'assets/shadermate-LICENSE.txt', 'docs/index.html', 'classic/vendor/pickr/dist/pickr.min.js']) assert.ok(existsSync(join(out, path)), path);
const androidManifest = readFileSync(join(root, 'android/app/src/main/AndroidManifest.xml'), 'utf8');
assert.match(androidManifest, /android:label="Image-to-Pixel"/);
assert.doesNotMatch(androidManifest, /READ_MEDIA|READ_EXTERNAL|WRITE_EXTERNAL|MANAGE_EXTERNAL|CAMERA|RECORD_AUDIO/);
console.log(`Verified ${manifest.files.length} offline assets; original JS, CSS and fonts are byte-identical to the site build.`);
