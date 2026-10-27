// Photos, scans, PDFs and voice notes attached to notes, diary days, expenses, trip items and wallet cards.
// Blobs live in IndexedDB ("files"); rows in the attachments table point at them by file name.
import * as S from './store.js';
import { app } from './ctx.js';
import { h, menu, toast, confirm, pickFiles, sheet } from './ui.js';

const MAX_SIDE = 2048;
const urls = new Map();

export const isPdf = (f) => /\.pdf$/i.test(f);
export const isAudio = (f) => /\.(m4a|mp4|aac|webm|ogg|mp3|wav)$/i.test(f);

export function list(owner, ownerUid) {
  return S.where('attachments', (a) => a.owner === owner && a.owner_id === ownerUid).sort((a, b) => a.created - b.created);
}
export const count = (owner, ownerUid) => list(owner, ownerUid).length;

/** Object URL for a stored file; downloads it through sync if this device doesn't have it yet. */
export async function url(file) {
  if (urls.has(file)) return urls.get(file);
  let blob = await S.files.get(file);
  if (!blob && app.fetchFile) blob = await app.fetchFile(file);
  if (!blob) return null;
  const u = URL.createObjectURL(blob);
  urls.set(file, u);
  return u;
}

async function shrink(file) {
  if (!file.type.startsWith('image/') || file.type === 'image/gif') return file;
  try {
    const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
    const scale = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
    const c = document.createElement('canvas');
    c.width = Math.round(bmp.width * scale);
    c.height = Math.round(bmp.height * scale);
    c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
    return await new Promise((resolve) => c.toBlob((b) => resolve(b || file), 'image/jpeg', 0.86));
  } catch (e) {
    return file;
  }
}

function ext(file) {
  if (file.type === 'application/pdf' || /\.pdf$/i.test(file.name)) return 'pdf';
  if (file.type.startsWith('image/')) return 'jpg';
  if (file.type.includes('mp4') || file.type.includes('m4a') || file.type.includes('aac')) return 'm4a';
  if (file.type.includes('webm')) return 'webm';
  if (file.type.includes('ogg')) return 'ogg';
  const m = /\.(\w{2,4})$/.exec(file.name || '');
  return m ? m[1].toLowerCase() : 'bin';
}

/** Stores a file and links it to the owner row; returns the attachment uid. */
export async function addFile(owner, ownerUid, file, name = '') {
  const blob = await shrink(file);
  const fname = S.newUid() + '.' + ext(file);
  await S.files.put(fname, blob);
  return S.put('attachments', { owner, owner_id: ownerUid, file: fname, name: name || file.name || '', created: Date.now() });
}

export async function removeAll(owner, ownerUid) {
  for (const a of list(owner, ownerUid)) S.del('attachments', a.uid);
}

export async function pick(owner, ownerUid, { voice = false } = {}) {
  return new Promise((resolve) => {
    const take = async (accept, opts) => {
      const files = await pickFiles(accept, opts);
      for (const f of files) await addFile(owner, ownerUid, f);
      resolve(files.length);
    };
    menu('Add', [
      { label: '📷 Take a photo / scan', action: () => take('image/*', { multiple: false, capture: 'environment' }) },
      { label: '🖼 Choose photos', action: () => take('image/*') },
      { label: '📄 Choose a file (PDF or image)', action: () => take('image/*,application/pdf') },
      voice ? { label: '🎤 Record a voice note', action: async () => resolve(await record(owner, ownerUid)) } : null,
    ]);
  });
}

async function record(owner, ownerUid) {
  if (!navigator.mediaDevices || !window.MediaRecorder) { toast('Recording isn\'t supported here'); return 0; }
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); } catch (e) { toast('Microphone not allowed'); return 0; }
  const type = ['audio/mp4', 'audio/webm', 'audio/ogg'].find((t) => MediaRecorder.isTypeSupported(t)) || '';
  const rec = new MediaRecorder(stream, type ? { mimeType: type } : {});
  const chunks = [];
  rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
  const started = Date.now();
  const timer = h('div.hello', '0:00');
  const tick = setInterval(() => { const s = Math.floor((Date.now() - started) / 1000); timer.textContent = Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }, 500);
  rec.start();
  return new Promise((resolve) => {
    let save = false;
    sheet({
      title: '🎤 Recording…',
      body: timer,
      dismissable: false,
      buttons: [{ label: 'Cancel' }, { label: 'Stop & save', primary: true, action: () => { save = true; } }],
      onClose: () => {
        clearInterval(tick);
        rec.onstop = async () => {
          stream.getTracks().forEach((t) => t.stop());
          if (!save || !chunks.length) { resolve(0); return; }
          const blob = new Blob(chunks, { type: rec.mimeType || type || 'audio/webm' });
          const file = new File([blob], 'Voice note', { type: blob.type });
          await addFile(owner, ownerUid, file, 'Voice note');
          resolve(1);
        };
        rec.stop();
      },
    });
  });
}

/** Thumbnail tile for one attachment. */
export function tile(a, { onRemove } = {}) {
  const t = h('button.thumb', { type: 'button', title: a.name || '' }, isPdf(a.file) ? '📄' : isAudio(a.file) ? '🎤' : '');
  if (!isPdf(a.file) && !isAudio(a.file)) {
    url(a.file).then((u) => { if (u) t.style.backgroundImage = `url("${u}")`; else t.textContent = '☁'; });
  }
  t.addEventListener('click', () => open(a));
  if (onRemove) {
    t.addEventListener('contextmenu', async (e) => {
      e.preventDefault();
      if (await confirm('Remove this attachment?', null, 'Remove')) onRemove(a);
    });
    let timer;
    t.addEventListener('touchstart', () => { timer = setTimeout(async () => { if (await confirm('Remove this attachment?', null, 'Remove')) onRemove(a); }, 600); }, { passive: true });
    t.addEventListener('touchend', () => clearTimeout(timer));
    t.addEventListener('touchmove', () => clearTimeout(timer), { passive: true });
  }
  return t;
}

export async function open(a) {
  const u = await url(a.file);
  if (!u) { toast('Not downloaded yet — sync first'); return; }
  if (isAudio(a.file)) {
    const audio = h('audio', { src: u, controls: true, autoplay: true, style: { width: '100%' } });
    sheet({ title: a.name || 'Voice note', body: audio, buttons: [{ label: 'Close' }], onClose: () => audio.pause() });
    return;
  }
  if (isPdf(a.file)) { window.open(u, '_blank'); return; }
  const v = h('div.viewer', { onclick: () => v.remove() }, h('img', { src: u, alt: '' }));
  document.body.appendChild(v);
}

/** Editable strip: tiles + "add" tile. Changes are saved immediately. */
export function strip(owner, ownerUid, { label = 'Photo / file', voice = false, onAdded } = {}) {
  const el = h('div.strip');
  const render = () => {
    el.textContent = '';
    for (const a of list(owner, ownerUid)) el.appendChild(tile(a, { onRemove: (x) => { S.del('attachments', x.uid); render(); } }));
    el.appendChild(h('button.thumb.add', { type: 'button', onclick: async () => { const n = await pick(owner, ownerUid, { voice }); if (n) { render(); if (onAdded) onAdded(); } } }, '+ ' + label));
  };
  render();
  return el;
}
