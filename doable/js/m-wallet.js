// Wallet: loyalty cards with barcodes, ID, passport, licence, insurance cards — front/back photos, behind Face ID / PIN.
import * as S from './store.js';
import * as L from './logic.js';
import * as A from './attach.js';
import { app } from './ctx.js';
import { verifyUser } from './lock.js';
import { h, header, card, row, empty, sheet, field, input, textarea, select, confirm, toast, fmtDay } from './ui.js';

export const WALLET_TYPES = ['Loyalty card', 'ID card', 'Passport', 'Driving licence', 'Health insurance card', 'Insurance policy', 'Membership',
  'Ticket / pass', 'Vehicle', 'Warranty', 'Document', 'Other'];
const EMOJI = ['🏷', '🪪', '🛂', '🚗', '🏥', '📄', '🎫', '🎟', '🚙', '🧾', '📁', '💳'];
export const walletEmoji = (type) => EMOJI[WALLET_TYPES.indexOf(type)] || '💳';
let unlocked = false;

const mask = (n) => { const s = (n || '').replace(/ /g, ''); return s.length <= 4 ? s : '•••• ' + s.slice(-4); };

async function unlock() {
  if (await verifyUser('PIN to open Wallet')) { unlocked = true; app.refresh(); }
}

function render(view) {
  const page = h('div.page');
  if (!unlocked && S.settings.walletLock && S.settings.lock) {
    page.appendChild(empty('🔒\nYour cards and documents are protected.'));
    page.appendChild(h('button.btn.block', { type: 'button', onclick: unlock }, 'Unlock'));
    view.appendChild(page);
    return;
  }
  const list = S.all('wallet').sort((a, b) => a.type.localeCompare(b.type) || a.title.localeCompare(b.title));
  if (!list.length) page.appendChild(empty('Keep loyalty cards, ID, passport, driving licence and insurance cards here.\nTap + and photograph front and back.'));
  if (!S.settings.lock) page.appendChild(h('div.banner', 'Tip: turn on the app lock (Settings) so Wallet asks for Face ID / PIN.'));
  let type = null, c;
  const today = L.today();
  for (const w of list) {
    if (w.type !== type) { type = w.type; page.appendChild(header(type)); c = card(); page.appendChild(c); }
    let sub = mask(w.number);
    if (w.expiry) {
      const d = L.daysBetween(today, w.expiry);
      sub = [sub, d < 0 ? 'Expired ' + fmtDay(w.expiry) : d <= 60 ? `Expires in ${d} days` : 'Valid until ' + fmtDay(w.expiry, { day: 'numeric', month: 'short', year: 'numeric' })].filter(Boolean).join(' · ');
    }
    const pics = A.count('wallet', w.uid);
    if (pics) sub += ' · 📷' + (pics > 1 ? ' ' + pics : '');
    c.appendChild(row({ icon: walletEmoji(w.type), title: w.title, sub, subClass: w.expiry && L.daysBetween(today, w.expiry) <= 60 ? 'red' : '',
      onclick: () => details(w), onlongpress: () => edit(w) }));
  }
  view.appendChild(page);
  view.appendChild(h('button.fab', { type: 'button', 'aria-label': 'New card', onclick: () => edit({}) }, '+'));
}

function details(w) {
  const body = [];
  const pics = A.list('wallet', w.uid);
  if (pics.length) body.push(h('div.strip', pics.map((a) => A.tile(a))));
  const f = (label, v) => {
    if (!v) return;
    body.push(field(label, h('div.secret', { style: { fontSize: '18px', userSelect: 'text' }, onclick: () => {
      navigator.clipboard && navigator.clipboard.writeText(v).then(() => toast('Copied'));
    } }, v)));
  };
  f('Number', w.number);
  f('Name on card', w.holder);
  if (w.expiry) f('Valid until', fmtDay(w.expiry, { day: 'numeric', month: 'long', year: 'numeric' }));
  f('Notes', w.notes);
  if (w.barcode) {
    const svg = L.barcodeSvg(w.barcode, 80);
    if (svg) body.push(field('Barcode · tap to enlarge', h('div', { html: svg, onclick: () => bigCode(w.title, w.barcode) })));
  }
  sheet({ title: walletEmoji(w.type) + ' ' + w.title, body, buttons: [{ label: 'Edit', action: () => edit(w) }, { label: 'Close', primary: true }] });
}

function bigCode(title, code) {
  const el = h('div.big-code', { onclick: () => el.remove() }, h('div', { style: { fontSize: '20px' } }, title),
    h('div', { style: { width: '100%' }, html: L.barcodeSvg(code, 160) }), h('div', { style: { fontSize: '18px', letterSpacing: '.1em' } }, code),
    h('div', { style: { color: '#777' } }, 'Turn up the screen brightness · tap to close'));
  document.body.appendChild(el);
}

function edit(w) {
  const isNew = !w.uid;
  const uid = w.uid || w.draftUid || S.newUid();
  const type = select(WALLET_TYPES, w.type || 'Loyalty card');
  const title = input(w.title || '', { placeholder: 'e.g. Payback, Passport' });
  const number = input(w.number || '', { autocapitalize: 'characters', autocomplete: 'off' });
  const holder = input(w.holder || '', { autocapitalize: 'words' });
  const expiry = input(w.expiry || '', { type: 'date' });
  const barcode = input(w.barcode || '', { placeholder: 'Number printed under the barcode', autocomplete: 'off' });
  const notes = textarea(w.notes || '', { placeholder: 'Issuing authority, customer service number…' });
  let saved = false;
  const buttons = [{ label: 'Cancel' }, { label: 'Save', primary: true, action: () => {
    saved = true;
    S.put('wallet', { uid, type: type.value, title: title.value.trim() || type.value, number: number.value.trim(), holder: holder.value.trim(),
      expiry: expiry.value || '', barcode: barcode.value.trim(), notes: notes.value.trim(), created: w.created || Date.now() });
    if (barcode.value.trim() && !L.barcodeSvg(barcode.value.trim())) toast('This barcode can\'t be drawn (letters, digits and symbols only)');
  } }];
  if (!isNew) buttons.unshift({ label: 'Delete', danger: true, action: async () => {
    if (await confirm('Delete "' + w.title + '"?', 'Its photos are deleted too.', 'Delete')) { saved = true; S.remove('wallet', uid); }
  } });
  sheet({ title: isNew ? 'New card or document' : 'Edit', dismissable: false,
    body: [field('Type', type), field('Name', title), field('Front & back (photo or scan)', A.strip('wallet', uid, { label: 'Front / back' })),
      field('Card / document number', number), field('Name on card', holder), field('Valid until', expiry), field('Barcode', barcode), field('Notes', notes)],
    buttons, onClose: () => { if (isNew && !saved) A.removeAll('wallet', uid); } });
}

export default {
  id: 'wallet',
  name: 'Wallet',
  icon: 'wallet',
  tables: ['wallet', 'attachments'],
  render,
  hide() { unlocked = false; },
  async open(uid) {
    const w = S.get('wallet', uid);
    if (!w) return;
    if (!unlocked && S.settings.walletLock && S.settings.lock) { await unlock(); if (!unlocked) return; }
    details(w);
  },
  /** Shared photos / PDFs become a new document. */
  async newWith(files) {
    if (!unlocked && S.settings.walletLock && S.settings.lock) { await unlock(); if (!unlocked) return; }
    const draftUid = S.newUid();
    for (const f of files) await A.addFile('wallet', draftUid, f);
    edit({ type: 'Document', draftUid });
  },
  menu: () => [{ label: (S.settings.walletLock ? '✓ ' : '') + 'Ask for Face ID / PIN', action: () => { S.settings.walletLock = !S.settings.walletLock; S.saveSettings(); } }],
};
