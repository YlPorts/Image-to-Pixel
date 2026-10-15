import { test } from 'node:test';
import assert from 'node:assert/strict';
import { downloadBlob } from '../lib/core/export/canvas.js';
import { videoSupport, createVideoEncoder } from '../lib/video.js';

test('native integrations receive the original bytes and original video options', async () => {
  const previous = globalThis.itpPlatform;
  const received = [];
  try {
    globalThis.itpPlatform = {
      download: async (blob, name) => { received.push({ bytes: [...new Uint8Array(await blob.arrayBuffer())], type: blob.type, name }); return 'saved'; },
      videoSupport: async () => ({ mp4: true, webm: true }),
      createVideoEncoder: async opts => ({ opts, native: true }),
    };
    assert.equal(await downloadBlob(new Uint8Array([0, 1, 127, 255]), 'pixels.png', 'image/png'), 'saved');
    assert.deepEqual(received, [{ bytes: [0, 1, 127, 255], type: 'image/png', name: 'pixels.png' }]);
    assert.deepEqual(await videoSupport(), { mp4: true, webm: true, webcodecs: false });
    const opts = { format: 'mp4', width: 160, height: 120, fps: 12, scale: 4 };
    assert.deepEqual((await createVideoEncoder(opts)).opts, opts);
  } finally { if (previous) globalThis.itpPlatform = previous; else delete globalThis.itpPlatform; }
});
