import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createStore } from '../lib/editor/store.js';
import { defaultSettings } from '../lib/core/settings.js';

test('session history survives serialisation across shared and own settings', () => {
  const s = createStore(defaultSettings());
  s.update({ size: { width: 80 } });
  s.setTarget('own-file', s.settings);
  s.update({ dither: { method: 'atkinson' } });
  s.update({ size: { width: 32 } });
  s.undo();
  const restored = createStore(defaultSettings());
  restored.restore(JSON.parse(JSON.stringify(s.snapshot())));
  assert.equal(restored.target, 'own-file');
  assert.equal(restored.settings.size.width, 80);
  assert.equal(restored.canRedo, true);
  restored.redo(); assert.equal(restored.settings.size.width, 32);
  restored.undo(); restored.undo(); assert.equal(restored.settings.dither.method, 'bayer4');
  restored.setTarget('shared', { ...defaultSettings(), size: { width: 80 } });
  assert.equal(restored.canUndo, true);
  restored.undo(); assert.equal(restored.settings.size.width, defaultSettings().size.width);
});
