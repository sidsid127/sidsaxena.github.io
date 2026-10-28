// Small DOM toolkit: element builder, sheets/dialogs, prompts, toasts, chips, icons.

/** h('div.card#id', {onclick, ...attrs}, children…) — strings become text nodes (never HTML). */
export function h(tag, attrs, ...kids) {
  const m = /^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i.exec(tag);
  const el = document.createElement(m[1] || 'div');
  for (const part of (m[2] || '').match(/[.#][\w-]+/g) || []) {
    if (part[0] === '.') el.classList.add(part.slice(1));
    else el.id = part.slice(1);
  }
  if (attrs && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) {
    kids.unshift(attrs);
    attrs = null;
  }
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k === 'html') el.innerHTML = v; // only for trusted markup (icons, barcodes)
    else if (k in el && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  add(el, kids);
  return el;
}
function add(el, kids) {
  for (const k of kids.flat(Infinity)) {
    if (k == null || k === false) continue;
    el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
  }
}
export const $ = (sel, root = document) => root.querySelector(sel);
export const clear = (el) => { while (el.firstChild) el.removeChild(el.firstChild); return el; };

export const ICONS = {
  menu: 'M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z',
  back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
  add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  more: 'M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z',
  search: 'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  today: 'M19 3h-1V1h-2v2H8V1H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 16H5V8h14v11zM7 10h5v5H7z',
  todo: 'M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm-9 14-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z',
  note: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
  diary: 'M18 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zM9 4h2v5l-1-.75L9 9V4zm9 16H6V4h1v9l3-2.25L13 13V4h5v16z',
  trip: 'M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z',
  cart: 'M7 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM1 2v2h2l3.6 7.59-1.35 2.45A2 2 0 0 0 7 17h12v-2H7.42a.25.25 0 0 1-.25-.25l.03-.12.9-1.63h7.45c.75 0 1.41-.41 1.75-1.03l3.58-6.49A1 1 0 0 0 20 4H5.21l-.94-2H1zm16 16a2 2 0 1 0 0 4 2 2 0 0 0 0-4z',
  habit: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z',
  cake: 'M12 6a2 2 0 0 0 2-2c0-.38-.1-.73-.29-1.03L12 0l-1.71 2.97A2 2 0 0 0 12 6zm4.6 9.99-1.07-1.07-1.08 1.07a3.5 3.5 0 0 1-4.89 0l-1.07-1.07-1.09 1.07A3.46 3.46 0 0 1 3 16.39V21a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1v-4.61a3.44 3.44 0 0 1-4.4-.4zM18 9h-5V7h-2v2H6a3 3 0 0 0-3 3v1.54c0 1.08.88 1.96 1.96 1.96.52 0 1.02-.2 1.38-.57l2.14-2.13 2.13 2.13c.74.74 2.03.74 2.77 0l2.14-2.13 2.13 2.13c.37.37.86.57 1.38.57 1.08 0 1.96-.88 1.96-1.96V12a3 3 0 0 0-3-3z',
  checklist: 'M19 3h-4.18C14.4 1.84 13.3 1 12 1s-2.4.84-2.82 2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm-7 0a1 1 0 1 1 0 2 1 1 0 0 1 0-2zm-2 14-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z',
  wallet: 'M21 18v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v1h-9a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h9zm-9-2h10V8H12v8zm4-2.5a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z',
  key: 'M12.65 10A6 6 0 1 0 7 18a6 6 0 0 0 5.65-4H17v4h4v-4h2v-4H12.65zM7 14a2 2 0 1 1 0-4 2 2 0 0 1 0 4z',
  history: 'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6a7 7 0 1 1 2.06 4.94l-1.42 1.42A9 9 0 1 0 13 3zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
  sync: 'M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46A7.93 7.93 0 0 0 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74A7.93 7.93 0 0 0 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z',
  tune: 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
  settings: 'M19.14 12.94a7.07 7.07 0 0 0 0-1.88l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96a7 7 0 0 0-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84a.48.48 0 0 0-.48.41l-.36 2.54a7.4 7.4 0 0 0-1.62.94l-2.39-.96a.48.48 0 0 0-.59.22L2.74 8.87a.48.48 0 0 0 .12.61l2.03 1.58a7.07 7.07 0 0 0 0 1.88l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.04.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54a7.4 7.4 0 0 0 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.48.48 0 0 0-.12-.61l-2.01-1.58zM12 15.6a3.6 3.6 0 1 1 0-7.2 3.6 3.6 0 0 1 0 7.2z',
  close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  flag: 'M14.4 6 14 4H5v17h2v-7h5.6l.4 2h7V6z',
  person: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  chart: 'M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z',
};
export const icon = (name, cls = '') => h('span.icon' + (cls ? '.' + cls : ''),
  { html: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${ICONS[name] || ''}"/></svg>` });

// ------------------------------------------------------------------ overlays

const stack = [];
addEventListener('keydown', (e) => { if (e.key === 'Escape' && stack.length) stack[stack.length - 1].close(); });

/**
 * A bottom sheet (phone) / centred dialog (wide screen).
 * buttons: [{label, primary, danger, action(close) → false keeps it open}]
 */
export function sheet({ title, body, buttons = [], wide = false, onClose, dismissable = true }) {
  const overlay = h('div.overlay');
  const box = h('div.sheet' + (wide ? '.wide' : ''), { role: 'dialog', 'aria-modal': 'true' });
  const close = () => {
    if (!overlay.isConnected) return;
    overlay.remove();
    stack.splice(stack.indexOf(handle), 1);
    if (onClose) onClose();
  };
  const handle = { close, box };
  if (title) box.appendChild(h('div.sheet-title', title));
  const content = h('div.sheet-body');
  add(content, [body]);
  box.appendChild(content);
  if (buttons.length) {
    box.appendChild(h('div.sheet-buttons', buttons.map((b) => h('button.btn' + (b.primary ? '.primary' : '') + (b.danger ? '.danger' : ''), {
      type: 'button',
      onclick: async () => { const r = b.action ? await b.action(close) : undefined; if (r !== false) close(); },
    }, b.label))));
  }
  overlay.appendChild(box);
  overlay.addEventListener('click', (e) => { if (e.target === overlay && dismissable) close(); });
  document.body.appendChild(overlay);
  stack.push(handle);
  const f = box.querySelector('[autofocus]');
  if (f) setTimeout(() => f.focus(), 50);
  return handle;
}

/** Action menu: items [{label, action, danger, checked}] */
export function menu(title, items) {
  const s = sheet({
    title,
    body: h('div.menu', items.filter(Boolean).map((it) => h('button.menu-item' + (it.danger ? '.danger' : ''), {
      type: 'button',
      onclick: () => { s.close(); it.action(); },
    }, it.label, it.checked ? h('span.check', '✓') : null))),
    buttons: [{ label: 'Cancel' }],
  });
  return s;
}

export function prompt(title, value = '', { placeholder = '', ok = 'OK', multiline = false, type = 'text', message } = {}) {
  return new Promise((resolve) => {
    const input = multiline ? h('textarea.field', { rows: 6, placeholder, autofocus: true })
      : h('input.field', { type, placeholder, autofocus: true, autocomplete: 'off' });
    input.value = value;
    let done = false;
    const s = sheet({
      title,
      body: [message ? h('p.muted', message) : null, input],
      buttons: [{ label: 'Cancel' }, { label: ok, primary: true, action: () => { done = true; resolve(input.value.trim()); } }],
      onClose: () => { if (!done) resolve(null); },
    });
    if (!multiline) input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { done = true; resolve(input.value.trim()); s.close(); } });
  });
}

export function confirm(title, message, ok = 'OK', danger = true) {
  return new Promise((resolve) => {
    let done = false;
    sheet({
      title,
      body: message ? h('p', message) : null,
      buttons: [{ label: 'Cancel' }, { label: ok, primary: !danger, danger, action: () => { done = true; resolve(true); } }],
      onClose: () => { if (!done) resolve(false); },
    });
  });
}

export function alert(title, message) {
  return new Promise((resolve) => sheet({ title, body: h('p.pre', message), buttons: [{ label: 'OK', primary: true }], onClose: resolve }));
}

let toastEl, toastTimer;
export function toast(text, actionLabel, action, ms = 3500) {
  if (toastEl) toastEl.remove();
  toastEl = h('div.toast', h('span', text), actionLabel ? h('button', { type: 'button', onclick: () => { toastEl.remove(); action(); } }, actionLabel) : null);
  document.body.appendChild(toastEl);
  clearTimeout(toastTimer);
  const el = toastEl;
  toastTimer = setTimeout(() => el.remove(), ms);
}

// ------------------------------------------------------------------ bits

export function chip(text, { selected = false, color, onclick, onlongpress } = {}) {
  const c = h('button.chip' + (selected ? '.on' : ''), { type: 'button', onclick }, text);
  if (color) c.style.setProperty('--chip', color);
  if (onlongpress) longPress(c, onlongpress);
  return c;
}

/** Long press (touch) or right-click (Mac). */
export function longPress(el, fn) {
  let t;
  el.addEventListener('contextmenu', (e) => { e.preventDefault(); fn(e); });
  el.addEventListener('touchstart', (e) => { t = setTimeout(() => { t = null; fn(e); }, 550); }, { passive: true });
  const cancel = () => { if (t) clearTimeout(t); };
  el.addEventListener('touchend', (e) => { if (t === null) e.preventDefault(); cancel(); });
  el.addEventListener('touchmove', cancel, { passive: true });
}

export function row({ icon: ic, title, sub, end, onclick, onlongpress, cls = '', subClass = '' }) {
  const r = h('div.row' + (cls ? '.' + cls : ''), { onclick, role: onclick ? 'button' : null, tabindex: onclick ? 0 : null },
    ic != null ? h('span.row-icon', ic) : null,
    h('div.row-text', h('div.row-title', title), sub ? h('div.row-sub' + (subClass ? '.' + subClass : ''), sub) : null),
    end || null);
  if (onlongpress) longPress(r, onlongpress);
  if (onclick) r.addEventListener('keydown', (e) => { if (e.key === 'Enter') onclick(e); });
  return r;
}

export const header = (text, cls = '') => h('div.section' + (cls ? '.' + cls : ''), text);
export const card = (...kids) => h('div.card', ...kids);
export const empty = (text) => h('div.empty', text);
export function pill(text, { on = false, onclick } = {}) {
  return h('button.pill' + (on ? '.on' : ''), { type: 'button', onclick: (e) => { e.stopPropagation(); onclick && onclick(e); } }, text);
}

export function field(label, input) {
  return h('label.form-row', h('span.form-label', label), input);
}
export function input(value = '', attrs = {}) {
  const i = h('input.field', { type: 'text', ...attrs });
  i.value = value == null ? '' : value;
  return i;
}
export function textarea(value = '', attrs = {}) {
  const t = h('textarea.field', { rows: 3, ...attrs });
  t.value = value || '';
  return t;
}
export function select(options, value) {
  const s = h('select.field', options.map((o) => {
    const [v, label] = Array.isArray(o) ? o : [o, o];
    return h('option', { value: v }, label);
  }));
  s.value = value;
  return s;
}

export function fmtDay(day, opts = { weekday: 'short', day: 'numeric', month: 'short' }) {
  if (!day) return '';
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, opts);
}
export const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
export const fmtStamp = (ms) => new Date(ms).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

/** Opens a file picker; resolves with File[] ([] if cancelled — iOS never says). */
export function pickFiles(accept, { multiple = true, capture } = {}) {
  return new Promise((resolve) => {
    const i = h('input', { type: 'file', accept, multiple, style: { display: 'none' } });
    if (capture) i.setAttribute('capture', capture);
    i.addEventListener('change', () => { resolve(Array.from(i.files || [])); i.remove(); });
    document.body.appendChild(i);
    i.click();
  });
}

export function download(name, blob) {
  const a = h('a', { href: URL.createObjectURL(blob), download: name });
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
}

/** Share sheet where available (iPhone, Mac Safari), else copy to clipboard. */
export async function shareText(title, text) {
  if (navigator.share) {
    try { await navigator.share({ title, text }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  try { await navigator.clipboard.writeText(text); toast('Copied — paste it into WhatsApp, Mail…'); } catch (e) { alert(title, text); }
}
