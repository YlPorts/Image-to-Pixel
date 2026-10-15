// The /editor/ page: mount the editor full-screen.
import { mountEditor } from '../../lib/editor/index.js';

const root = document.getElementById('editor');
mountEditor(root, { homeHref: '../' })
  .then(editor => {
    window.itpEditor = editor;   // handy for debugging and for the screenshot script
    awaitPlatform(editor);
    const sample = new URLSearchParams(location.search).get('sample');
    if (sample) editor.openSample(sample);
  })
  .catch(err => {
    root.innerHTML = '';
    const p = document.createElement('p');
    p.className = 'editor-boot err';
    p.textContent = `The editor could not start: ${err.message}. Try a current version of Chrome, Edge, Firefox or Safari.`;
    root.append(p);
  });

function awaitPlatform(editor) {
  globalThis.itpPlatform?.attach?.(editor).catch(err => editor.app.toast(`Session: ${err.message}`, { kind: 'error' }));
}
