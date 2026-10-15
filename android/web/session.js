// Blobs go to IndexedDB, never localStorage/base64. A single atomic transaction
// writes the manifest and its sources, so process death cannot leave half a session.
const request = r => new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
const done = tx => new Promise((resolve, reject) => { tx.oncomplete = resolve; tx.onerror = tx.onabort = () => reject(tx.error || new Error('Session storage failed')); });

export async function attachSession(editor) {
  const opening = indexedDB.open('image-to-pixel-android', 1);
  opening.onupgradeneeded = () => { opening.result.createObjectStore('session'); opening.result.createObjectStore('sources'); };
  const db = await request(opening);
  const saved = await request(db.transaction('session').objectStore('session').get('current'));
  if (saved?.version === 1) {
    const sources = [];
    for (const meta of saved.files) {
      const blob = await request(db.transaction('sources').objectStore('sources').get(meta.sourceKey));
      if (!blob) throw new Error(`The saved source ${meta.name} is missing`);
      sources.push(new File([blob], meta.name, { type: blob.type || meta.mime }));
    }
    await editor.restoreSession(saved, sources);
  }

  let timer, writing = Promise.resolve(), dirty = false;
  const keys = new WeakMap();
  for (let i = 0; saved && i < editor.app.items.length; i++) keys.set(editor.app.items[i].source, saved.files[i].sourceKey);
  const known = new Set(await request(db.transaction('sources').objectStore('sources').getAllKeys()));

  async function sourceBlob(item) {
    if (item.file) return item.file;
    if (item.source.src?.startsWith('data:')) return (await fetch(item.source.src)).blob();
    const c = document.createElement('canvas'); c.width = item.width; c.height = item.height;
    c.getContext('2d').drawImage(item.source, 0, 0);
    return new Promise((resolve, reject) => c.toBlob(b => b ? resolve(b) : reject(new Error('Could not save the source')), 'image/png'));
  }
  async function save() {
    const state = structuredClone(editor.sessionState());
    state.version = 1;
    const items = editor.app.items.slice(), blobs = new Map();
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      let key = keys.get(item.source);
      if (!key) { key = crypto.randomUUID(); keys.set(item.source, key); }
      state.files[i].sourceKey = key;
      state.files[i].mime = item.file?.type || 'image/png';
      if (!known.has(key) && !blobs.has(key)) blobs.set(key, await sourceBlob(item));
    }
    const tx = db.transaction(['session', 'sources'], 'readwrite');
    const completion = done(tx);
    for (const [key, blob] of blobs) tx.objectStore('sources').put(blob, key);
    const retained = new Set(state.files.map(f => f.sourceKey));
    for (const key of known) if (!retained.has(key)) tx.objectStore('sources').delete(key);
    tx.objectStore('session').put(state, 'current');
    await completion;
    known.clear(); for (const key of retained) known.add(key);
  }
  function flush() {
    clearTimeout(timer);
    if (!dirty) return writing;
    dirty = false;
    writing = writing.catch(() => {}).then(save).catch(e => { editor.app.toast(`Session could not be saved: ${e.message}`, { kind: 'error' }); throw e; });
    return writing;
  }
  function mark() { dirty = true; clearTimeout(timer); timer = setTimeout(() => flush().catch(() => {}), 200); }
  editor.store.subscribe(mark);
  new MutationObserver(mark).observe(document.querySelector('.itp-lib'), { childList: true, subtree: true });
  new MutationObserver(mark).observe(document.querySelector('.itp-app'), { attributes: true, attributeFilter: ['data-lib', 'data-insp'] });
  document.querySelector('.itp-view').addEventListener('pointerup', mark);
  document.addEventListener('visibilitychange', () => { if (document.hidden) { mark(); flush().catch(() => {}); } });
  mark();
  return { flush: () => { dirty = true; return flush(); } };
}
