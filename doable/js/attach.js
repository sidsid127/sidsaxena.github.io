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
      // One picture: turn / crop it first.
      if (files.length === 1 && files[0].type.startsWith('image/') && files[0].type !== 'image/gif') {
        const edited = await editImage(files[0]);
        if (!edited) { resolve(0); return; }
        await addFile(owner, ownerUid, new File([edited], files[0].name || 'Photo', { type: edited.type || 'image/jpeg' }));
        resolve(1);
        return;
      }
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
  const v = h('div.viewer', { onclick: () => v.remove() }, h('img', { src: u, alt: '' }),
    h('button.viewer-edit', { type: 'button', onclick: async (e) => {
      e.stopPropagation();
      v.remove();
      const blob = await S.files.get(a.file);
      if (!blob) return;
      const edited = await editImage(blob);
      if (!edited) return;
      // A new file name, so sync uploads the changed picture.
      const fname = S.newUid() + '.jpg';
      await S.files.put(fname, await shrink(new File([edited], 'photo.jpg', { type: 'image/jpeg' })));
      S.put('attachments', { uid: a.uid, file: fname });
      S.files.del(a.file).catch(() => {});
      toast('Picture saved');
    } }, '✏️ Turn / crop'));
  document.body.appendChild(v);
}

/**
 * Turn and crop a picture: ⟲ / ⟳, drag the corners (or the middle to move the frame). Resolves with a JPEG blob,
 * the original blob for "Use whole photo" without turning, or null when cancelled.
 */
export async function editImage(blob) {
  let bmp;
  try { bmp = await createImageBitmap(blob, { imageOrientation: 'from-image' }); } catch (e) { return blob; }
  let src = document.createElement('canvas');
  src.width = bmp.width; src.height = bmp.height;
  src.getContext('2d').drawImage(bmp, 0, 0);
  let turned = 0;
  let rect = { x: 0.04, y: 0.04, w: 0.92, h: 0.92 }; // crop frame, as fractions of the picture
  const view = h('canvas.crop-canvas');
  const ctx = view.getContext('2d');
  let box = { x: 0, y: 0, w: 1, h: 1 }; // where the picture is drawn on the view
  const draw = () => {
    const maxW = Math.min(640, window.innerWidth - 64), maxH = Math.max(240, window.innerHeight * 0.55);
    const scale = Math.min(maxW / src.width, maxH / src.height);
    const dpr = window.devicePixelRatio || 1;
    view.style.width = Math.round(src.width * scale) + 'px';
    view.style.height = Math.round(src.height * scale) + 'px';
    view.width = Math.round(src.width * scale * dpr); view.height = Math.round(src.height * scale * dpr);
    box = { x: 0, y: 0, w: view.width, h: view.height };
    ctx.drawImage(src, 0, 0, view.width, view.height);
    const r = { x: rect.x * view.width, y: rect.y * view.height, w: rect.w * view.width, h: rect.h * view.height };
    ctx.fillStyle = 'rgba(0,0,0,.45)';
    ctx.fillRect(0, 0, view.width, r.y);
    ctx.fillRect(0, r.y + r.h, view.width, view.height - r.y - r.h);
    ctx.fillRect(0, r.y, r.x, r.h);
    ctx.fillRect(r.x + r.w, r.y, view.width - r.x - r.w, r.h);
    ctx.strokeStyle = '#FF8A5B'; ctx.lineWidth = 2 * dpr; ctx.strokeRect(r.x, r.y, r.w, r.h);
    ctx.fillStyle = '#fff';
    for (const [cx, cy] of [[r.x, r.y], [r.x + r.w, r.y], [r.x, r.y + r.h], [r.x + r.w, r.y + r.h]]) {
      ctx.beginPath(); ctx.arc(cx, cy, 9 * dpr, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
    }
  };
  const turn = (dir) => {
    const c = document.createElement('canvas');
    c.width = src.height; c.height = src.width;
    const g = c.getContext('2d');
    g.translate(c.width / 2, c.height / 2);
    g.rotate(dir * Math.PI / 2);
    g.drawImage(src, -src.width / 2, -src.height / 2);
    src = c;
    turned = (turned + dir + 4) % 4;
    rect = { x: 0.04, y: 0.04, w: 0.92, h: 0.92 };
    draw();
  };
  // Dragging: a corner resizes, inside the frame moves it.
  let drag = null;
  const pos = (e) => { const b = view.getBoundingClientRect(); return { x: (e.clientX - b.left) / b.width, y: (e.clientY - b.top) / b.height, b }; };
  view.addEventListener('pointerdown', (e) => {
    const p = pos(e);
    const near = 28 / p.b.width;
    const corners = [['tl', rect.x, rect.y], ['tr', rect.x + rect.w, rect.y], ['bl', rect.x, rect.y + rect.h], ['br', rect.x + rect.w, rect.y + rect.h]];
    const hit = corners.find(([, cx, cy]) => Math.abs(cx - p.x) < near && Math.abs(cy - p.y) < near * p.b.width / p.b.height);
    if (hit) drag = { kind: hit[0] };
    else if (p.x > rect.x && p.x < rect.x + rect.w && p.y > rect.y && p.y < rect.y + rect.h) drag = { kind: 'move', dx: p.x - rect.x, dy: p.y - rect.y };
    if (drag) { view.setPointerCapture(e.pointerId); e.preventDefault(); }
  });
  view.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const p = pos(e);
    const cl = (v) => Math.max(0, Math.min(1, v));
    const min = 0.05;
    let { x, y, w, h: hh } = rect;
    if (drag.kind === 'move') { x = Math.max(0, Math.min(1 - w, p.x - drag.dx)); y = Math.max(0, Math.min(1 - hh, p.y - drag.dy)); }
    else {
      const right = x + w, bottom = y + hh;
      if (drag.kind[1] === 'l') { x = Math.min(cl(p.x), right - min); w = right - x; } else { w = Math.max(min, cl(p.x) - x); }
      if (drag.kind[0] === 't') { y = Math.min(cl(p.y), bottom - min); hh = bottom - y; } else { hh = Math.max(min, cl(p.y) - y); }
    }
    rect = { x, y, w, h: hh };
    draw();
  });
  const stop = () => { drag = null; };
  view.addEventListener('pointerup', stop);
  view.addEventListener('pointercancel', stop);
  const out = (crop) => new Promise((resolve) => {
    const r = crop ? rect : { x: 0, y: 0, w: 1, h: 1 };
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(r.w * src.width)); c.height = Math.max(1, Math.round(r.h * src.height));
    c.getContext('2d').drawImage(src, r.x * src.width, r.y * src.height, c.width, c.height, 0, 0, c.width, c.height);
    c.toBlob((b) => resolve(b), 'image/jpeg', 0.9);
  });
  return new Promise((resolve) => {
    let result = null;
    sheet({
      title: 'Turn & crop', wide: true, dismissable: false,
      body: [h('div.crop-tools', h('button.btn', { type: 'button', onclick: () => turn(-1) }, '⟲ Turn left'),
        h('button.btn', { type: 'button', onclick: () => turn(1) }, 'Turn right ⟳')),
      h('div.crop-wrap', view), h('p.muted', 'Drag the corners to crop, or the middle to move the frame.')],
      buttons: [{ label: 'Cancel' },
        { label: 'Use whole photo', action: async () => { result = turned ? await out(false) : blob; } },
        { label: 'Crop', primary: true, action: async () => { result = await out(true); } }],
      onClose: () => resolve(result),
    });
    requestAnimationFrame(draw);
  });
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
