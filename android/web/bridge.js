import { attachSession } from './session.js';
import { encodePNG } from '../../lib/core/export/png.js';
import { upscale } from '../../lib/core/export/scale.js';
import { exportSize } from '../../lib/editor/exporter.js';

if (window.ITPNative?.postMessage) {
  const pending = new Map(); let next = 0, editor, session;
  const native = window.ITPNative;
  native.onmessage = event => {
    const m = JSON.parse(event.data), p = pending.get(m.id);
    if (!p) return;
    clearTimeout(p.timer); pending.delete(m.id);
    m.error ? p.reject(new Error(m.error)) : p.resolve(m.result);
  };
  function call(method, args = {}) {
    return new Promise((resolve, reject) => {
      const id = ++next;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out`)); }, 300000);
      pending.set(id, { resolve, reject, timer });
      native.postMessage(JSON.stringify({ id, method, args }));
    });
  }
  function base64(bytes) {
    let s = '';
    for (let i = 0; i < bytes.length; i += 8192) s += String.fromCharCode(...bytes.subarray(i, i + 8192));
    return btoa(s);
  }
  async function upload(blob, name) {
    const token = await call('begin', { name, type: blob.type || 'application/octet-stream', size: blob.size });
    try {
      for (let offset = 0; offset < blob.size; offset += 192 * 1024) {
        const data = new Uint8Array(await blob.slice(offset, offset + 192 * 1024).arrayBuffer());
        await call('chunk', { token, data: base64(data) });
      }
      await call('finish', { token }); return token;
    } catch (e) { call('discard', { token }).catch(() => {}); throw e; }
  }

  const platform = window.itpPlatform = {
    paused: false,
    shareURL: 'https://tezumie.github.io/Image-to-Pixel/editor/',
    async download(blob, name) {
      const token = await upload(blob, name);
      const result = await call('save', { token });
      editor?.app.toast(`Saved ${name}`, { kind: 'ok', action: { label: 'Share', onClick: () => call('shareUri', result).catch(report) } });
      return result;
    },
    async sharePNG(app) {
      try {
        const s = app.store.settings;
        const opts = s.export.size === 'source' ? { ...exportSize(s, app.result, app.current), indexed: s.export.indexed }
          : { scale: s.export.scale, grid: s.export.grid, gridColor: s.export.gridColor, indexed: s.export.indexed };
        const bytes = await encodePNG(app.result, opts);
        const token = await upload(new Blob([bytes], { type: 'image/png' }), 'pixel-art.png');
        await call('share', { token });
      } catch (e) { report(e); }
    },
    videoSupport: (width, height) => call('videoSupport', { width, height }),
    async createVideoEncoder(opts) {
      const scale = Math.max(1, Math.round(opts.scale || 1));
      const even = n => n + (n & 1);
      const width = even(opts.width * scale), height = even(opts.height * scale);
      const encoder = await call('videoStart', { ...opts, width, height });
      return {
        width, height,
        async add(img) {
          try {
            const up = scale > 1 ? upscale(img, scale) : img;
            const token = await upload(new Blob([up.data], { type: 'application/octet-stream' }), 'frame.rgba');
            await call('videoFrame', { encoder, token, width: up.width, height: up.height });
          } catch (e) { call('videoCancel', { encoder }).catch(() => {}); throw e; }
        },
        async finish() {
          const result = await call('videoFinish', { encoder });
          try { const r = await fetch(result.url); if (!r.ok) throw new Error('Encoded video could not be read'); return await r.blob(); }
          finally { await call('discard', { token: result.token }); }
        },
        cancel: () => call('videoCancel', { encoder }),
      };
    },
    async attach(api) {
      editor = api;
      // Reveal the original history/theme/help controls on a second mobile row.
      const undo = document.querySelector('[aria-label="Undo (Ctrl+Z)"]');
      const row = undo.parentElement; row.classList.add('android-history');
      for (const b of document.querySelectorAll('.itp-top .hide-sm.contents button')) if (b.parentElement !== row) row.append(b);
      session = await attachSession(editor);
      window.itpAndroid.ready = true;
      await call('ready');
    },
  };
  function report(error) { editor?.app.toast(error.message || String(error), { kind: 'error' }); }
  window.addEventListener('unhandledrejection', event => { if (editor) { event.preventDefault(); report(event.reason); } });
  window.itpAndroid = {
    ready: false, call,
    back: () => editor?.handleBack() || false,
    async pause() { platform.paused = true; editor?.app.current?.source.pause?.(); await session?.flush(); },
    resume() { platform.paused = false; editor?.app.schedule(); },
    async memory() { editor?.app.engine.invalidate(); await session?.flush(); },
  };

  // WebView's clipboard API is absent/incomplete on many Android versions.
  if (!window.ClipboardItem) window.ClipboardItem = class {
    constructor(data) { this.data = data; this.types = Object.keys(data); }
    async getType(type) { return this.data[type]; }
  };
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
    writeText: text => call('clipboardText', { text }),
    readText: () => call('clipboardReadText'),
    async read() {
      const item = await call('clipboardRead');
      if (!item?.url) return [];
      try {
        const blob = await (await fetch(item.url)).blob();
        return [new ClipboardItem({ [blob.type || item.type]: blob })];
      } finally { await call('discard', { token: item.token }); }
    },
    async write(items) {
      for (const item of items) for (const type of item.types) {
        if (!type.startsWith('image/')) continue;
        const token = await upload(await item.getType(type), 'pixel-art.png');
        await call('clipboardWrite', { token }); return;
      }
      throw new Error('No image supplied to the clipboard');
    },
  } });

  // Built-in Lospec slugs work even on the very first offline launch. Other
  // explicit lookups retain the original feature and are cached after success.
  const originalFetch = window.fetch.bind(window);
  const palettes = originalFetch('../assets/offline-palettes.json').then(r => r.json());
  window.fetch = async (input, init) => {
    const url = typeof input === 'string' ? input : input.url;
    const m = /^https:\/\/lospec\.com\/palette-list\/([a-z0-9-]+)\.json$/.exec(url || '');
    if (!m) return originalFetch(input, init);
    const slug = m[1];
    const builtin = (await palettes).find(p => p.id === slug);
    let value = builtin ? { name: builtin.name, author: builtin.author, colors: builtin.colors } : null;
    if (!value) { try { value = JSON.parse(localStorage.getItem(`itp:lospec:${slug}`)); } catch {} }
    if (!value) { value = await call('lospec', { slug }); localStorage.setItem(`itp:lospec:${slug}`, JSON.stringify(value)); }
    return new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });
  };
}
