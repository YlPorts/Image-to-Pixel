// Desktop Chromium validates the packaged web layer; Android instrumentation
// separately validates the real WebView, native storage and MediaCodec paths.
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = fileURLToPath(new URL('../', import.meta.url));
const packaged = join(root, 'android/app/build/generated/editorAssets/site');
const shots = join(root, '_android-test'); mkdirSync(shots, { recursive: true });
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.json': 'application/json' };
const server = createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
  const prefix = path.startsWith('/apk/') ? '/apk/' : '/web/';
  const dir = prefix === '/apk/' ? packaged : join(root, '_site');
  let file = resolve(dir, path.slice(prefix.length));
  if (path.endsWith('/')) file = join(file, 'index.html');
  if (!file.startsWith(dir + '/') || !existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.setHeader('Content-Type', mime[extname(file)] || 'application/octet-stream'); res.end(readFileSync(file));
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE || undefined, args: ['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const problems = [], report = { views: [], pixels: [], flows: [] };

function simulatedBridge() {
  let next = 0; const uploads = {}, saved = [];
  window.__nativeTransfers = { uploads, saved };
  window.ITPNative = { postMessage(text) {
    const { id, method, args } = JSON.parse(text);
    queueMicrotask(() => {
      try {
        let result = true;
        if (method === 'videoSupport') result = { mp4: false, webm: false };
        else if (method === 'begin') { result = String(++next); uploads[result] = { ...args, chunks: [] }; }
        else if (method === 'chunk') uploads[args.token].chunks.push(args.data);
        else if (method === 'finish') {
          const u = uploads[args.token];
          const bytes = Uint8Array.from(atob(u.chunks.join('')), c => c.charCodeAt(0));
          if (bytes.length !== u.size) throw Error('Transfer size mismatch');
          u.bytes = bytes;
        } else if (method === 'save') {
          saved.push(uploads[args.token]); result = { uri: 'content://test/' + args.token, type: uploads[args.token].type };
        } else if (method === 'lospec') throw Error('Network disabled by test');
        window.ITPNative.onmessage({ data: JSON.stringify({ id, result }) });
      } catch (e) { window.ITPNative.onmessage({ data: JSON.stringify({ id, error: e.message }) }); }
    });
  } };
}
const ready = async (page, android) => {
  await page.waitForFunction(a => a ? window.itpAndroid?.ready : window.itpEditor, android, { timeout: 60000 });
};
async function context(android, viewport, theme = 'dark') {
  const ctx = await browser.newContext({ viewport, hasTouch: true, deviceScaleFactor: 1, colorScheme: theme });
  await ctx.route('**/*', route => new URL(route.request().url()).origin === origin || /^(data|blob):/.test(route.request().url()) ? route.continue() : route.abort());
  await ctx.addInitScript(t => localStorage.setItem('itp:theme', JSON.stringify(t)), theme);
  if (android) await ctx.addInitScript(simulatedBridge);
  const page = await ctx.newPage();
  page.on('pageerror', e => problems.push(e.message));
  await page.goto(`${origin}/${android ? 'apk' : 'web'}/editor/`);
  await ready(page, android);
  return { ctx, page };
}
try {
  for (const viewport of [{ width: 360, height: 800 }, { width: 800, height: 360 }, { width: 1440, height: 900 }]) {
    for (const theme of ['dark', 'light']) {
      const pair = [];
      for (const android of [false, true]) {
        const { ctx, page } = await context(android, viewport, theme);
        await page.evaluate(async () => { await window.itpEditor.openSample('dusk'); await window.itpEditor.idle(); });
        const metrics = await page.evaluate(() => {
          const s = getComputedStyle(document.querySelector('.itp'));
          const rect = selector => { const r = document.querySelector(selector).getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; };
          return { font: s.fontFamily, accent: s.getPropertyValue('--accent'), panel: s.getPropertyValue('--panel'), top: rect('.itp-top'), inspector: rect('.itp-insp'), stage: rect('.itp-stage'), overflow: document.documentElement.scrollWidth > innerWidth };
        });
        assert.equal(metrics.overflow, false, `${viewport.width}: page overflow`);
        assert.ok(metrics.stage.width > 0 && metrics.stage.height > 0, 'Preview remains accessible');
        const name = `${android ? 'android-web' : 'original-web'}-${viewport.width}x${viewport.height}-${theme}`;
        await page.screenshot({ path: join(shots, `${name}.png`) });
        if (android) {
          assert.equal(await page.getByRole('button', { name: 'Undo (Ctrl+Z)', exact: true }).isVisible(), true);
          await page.getByRole('button', { name: 'Export', exact: true }).click();
          await page.screenshot({ path: join(shots, `${name}-menu.png`) });
          const box = await page.locator('.itp-menu').boundingBox();
          assert.ok(box.y >= 0 && box.y + box.height <= viewport.height + 1, 'Export menu fits the viewport');
          await page.keyboard.press('Escape');
        }
        pair.push(metrics);
        await ctx.close();
      }
      assert.equal(pair[0].font, pair[1].font); assert.equal(pair[0].accent, pair[1].accent); assert.equal(pair[0].panel, pair[1].panel);
      if (viewport.width > 680) assert.deepEqual(pair[0], pair[1], 'Original desktop/tablet layout remains identical');
      report.views.push({ viewport, theme, original: pair[0], android: pair[1] });
    }
  }

  const web = await context(false, { width: 1200, height: 800 });
  const apk = await context(true, { width: 1200, height: 800 });
  async function pixels(page) {
    return page.evaluate(async () => {
      const api = window.itpEditor; await api.openSample('potion');
      const { DITHER_METHODS } = await import('../lib/image-to-pixel.bundle.esm.js');
      const methods = DITHER_METHODS.map(d => d.id);
      const rows = [];
      for (const method of methods) {
        api.store.update({ size: { width: 40 }, dither: { method, matrix: [[0, 2], [3, 1]] }, adjust: { brightness: 0.1, saturation: -0.2 }, outline: { mode: 'outer' } });
        await api.idle();
        const r = api.result; rows.push({ method, width: r.width, height: r.height, data: Array.from(r.data) });
      }
      return rows;
    });
  }
  const a = await pixels(web.page), b = await pixels(apk.page);
  assert.deepEqual(a, b, 'Web and packaged Android produce exactly the same RGBA pixels for every tested dither');
  report.pixels = a.map(({ method, width, height }) => ({ method, width, height, differentBytes: 0 }));

  await apk.page.evaluate(async () => {
    const api = window.itpEditor;
    await api.app.exportPNG(); await api.openSample('dusk'); await api.idle();
    await api.app.exportZip();
    await api.app.exportAnimation({ source: 'files', format: 'gif', fps: 4 });
    await api.app.exportSheet({ source: 'files', fps: 4, columns: 2, padding: 1 });
    await api.app.exportTileset({ tile: 16, flips: true });
    await api.app.saveSettingsFile();
    await window.itpAndroid.pause();window.itpAndroid.resume();
  });
  const downloads = await apk.page.evaluate(() => window.__nativeTransfers.saved.map(f => ({ name: f.name, size: f.bytes.length, header: [...f.bytes.slice(0, 8)] })));
  assert.equal(downloads.length, 6);
  assert.deepEqual(downloads[0].header, [137, 80, 78, 71, 13, 10, 26, 10]);
  assert.deepEqual(downloads[1].header.slice(0, 4), [80, 75, 3, 4]);
  assert.equal(String.fromCharCode(...downloads[2].header.slice(0, 6)), 'GIF89a');
  report.flows.push(...downloads);
  const before = await apk.page.evaluate(() => ({ count: window.itpEditor.app.items.length, settings: window.itpEditor.store.settings, undo: window.itpEditor.store.canUndo }));
  await apk.page.reload(); await ready(apk.page, true);
  const after = await apk.page.evaluate(() => ({ count: window.itpEditor.app.items.length, settings: window.itpEditor.store.settings, undo: window.itpEditor.store.canUndo }));
  assert.deepEqual(before, after, 'Sources, settings and history restore after renderer reload');
  const choosing = apk.page.waitForEvent('filechooser');
  await apk.page.evaluate(() => { void window.itpEditor.app.importPalette(); });
  await (await choosing).setFiles({ name: 'fixture.hex', mimeType: 'text/plain', buffer: Buffer.from('#000000\n#ffffff\n#dd4499\n') });
  await apk.page.waitForFunction(() => window.itpEditor.store.settings.palette.source === 'custom');
  assert.equal(await apk.page.evaluate(() => window.itpEditor.store.settings.palette.colors.length), 3);
  report.flows.push({ paletteImport: 'HEX', colors: 3 });
  await web.ctx.close(); await apk.ctx.close();
  assert.deepEqual(problems, [], 'No page errors');
  writeFileSync(join(shots, 'comparison.json'), JSON.stringify(report, null, 2));
  console.log(`Passed: ${report.views.length} visual comparisons, ${report.pixels.length} pixel-exact dithers, ${downloads.length} exports and session recovery.`);
} finally { await browser.close(); await new Promise(r => server.close(r)); }
