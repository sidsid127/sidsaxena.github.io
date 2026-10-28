// Wallet: loyalty cards with barcodes, ID, passport, licence, insurance cards — front/back photos, behind Face ID / Touch ID.
import * as S from './store.js';
import * as L from './logic.js';
import * as A from './attach.js';
import { app } from './ctx.js';
import * as P from './protect.js';
import { h, header, card, row, empty, sheet, field, input, textarea, select, confirm, toast, fmtDay } from './ui.js';

export const WALLET_TYPES = ['Loyalty card', 'Credit / debit card', 'ID card', 'Passport', 'Driving licence', 'Health insurance card', 'Insurance policy', 'Membership',
  'Ticket / pass', 'Vehicle', 'Warranty', 'Document', 'Other'];
const EMOJI = ['🏷', '💳', '🪪', '🛂', '🚗', '🏥', '📄', '🎫', '🎟', '🚙', '🧾', '📁', '💳'];
export const walletEmoji = (type) => EMOJI[WALLET_TYPES.indexOf(type)] || '💳';
const CARD = 'Credit / debit card';
/** Cards show month / year only ("03/28"), like printed on them. */
const validUntil = (w, opts) => (w.type === CARD ? w.expiry.slice(5, 7) + '/' + w.expiry.slice(2, 4) : fmtDay(w.expiry, opts));
let unlocked = false;

const mask = (n) => { const s = (n || '').replace(/ /g, ''); return s.length <= 4 ? s : '•••• ' + s.slice(-4); };

let autoTried = false;

async function unlock() {
  if (await P.verify()) { unlocked = true; app.refresh(); }
}

function render(view) {
  if (!P.isOn()) { view.appendChild(P.setupCard('your wallet')); return; }
  const page = h('div.page');
  if (!unlocked) {
    page.appendChild(empty('🔒\nYour cards and documents are protected.'));
    page.appendChild(h('button.btn.primary.block', { type: 'button', onclick: unlock }, P.usesFace() ? 'Unlock with Touch ID / Face ID' : 'Unlock'));
    view.appendChild(page);
    if (!autoTried) { autoTried = true; setTimeout(() => { if (app.current && app.current() && app.current().id === 'wallet') unlock(); }, 200); }
    return;
  }
  const list = S.all('wallet').sort((a, b) => a.type.localeCompare(b.type) || a.title.localeCompare(b.title));
  if (!list.length) page.appendChild(empty('Keep loyalty cards, ID, passport, driving licence and insurance cards here.\nTap + and photograph front and back.'));
  let type = null, c;
  const today = L.today();
  for (const w of list) {
    if (w.type !== type) { type = w.type; page.appendChild(header(type)); c = card(); page.appendChild(c); }
    let sub = mask(w.number);
    if (w.expiry) {
      const d = L.daysBetween(today, w.expiry);
      sub = [sub, d < 0 ? 'Expired ' + validUntil(w) : d <= 60 ? `Expires in ${d} days` : 'Valid until ' + validUntil(w, { day: 'numeric', month: 'short', year: 'numeric' })].filter(Boolean).join(' · ');
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
  if (w.expiry) f('Valid until', validUntil(w, { day: 'numeric', month: 'long', year: 'numeric' }));
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
  // Cards: month and year only; the expiry is the last day of that month.
  const now = new Date().getFullYear();
  const month = select([['', 'Month'], ...Array.from({ length: 12 }, (_, i) => [String(i + 1).padStart(2, '0'), String(i + 1).padStart(2, '0')])], (w.expiry || '').slice(5, 7));
  const year = select([['', 'Year'], ...Array.from({ length: 21 }, (_, i) => [String(now - 5 + i), String(now - 5 + i)])], (w.expiry || '').slice(0, 4));
  const cardExpiry = h('div.form-inline', month, h('span', ' / '), year);
  const expiryValue = () => {
    if (type.value !== CARD) return expiry.value || '';
    if (!month.value || !year.value) return '';
    const last = new Date(Number(year.value), Number(month.value), 0).getDate();
    return `${year.value}-${month.value}-${String(last).padStart(2, '0')}`;
  };
  const expiryField = field('Valid until', [expiry, cardExpiry]);
  const showExpiry = () => { expiry.hidden = type.value === CARD; cardExpiry.hidden = type.value !== CARD; };
  type.addEventListener('change', showExpiry);
  showExpiry();
  const barcode = input(w.barcode || '', { placeholder: 'Number printed under the barcode', autocomplete: 'off' });
  const notes = textarea(w.notes || '', { placeholder: 'Issuing authority, customer service number…' });
  let saved = false;
  const buttons = [{ label: 'Cancel' }, { label: 'Save', primary: true, action: () => {
    saved = true;
    S.put('wallet', { uid, type: type.value, title: title.value.trim() || type.value, number: number.value.trim(), holder: holder.value.trim(),
      expiry: expiryValue(), barcode: barcode.value.trim(), notes: notes.value.trim(), created: w.created || Date.now() });
    if (barcode.value.trim() && !L.barcodeSvg(barcode.value.trim())) toast('This barcode can\'t be drawn (letters, digits and symbols only)');
  } }];
  if (!isNew) buttons.unshift({ label: 'Delete', danger: true, action: async () => {
    if (await confirm('Delete "' + w.title + '"?', 'Its photos are deleted too.', 'Delete')) { saved = true; S.remove('wallet', uid); }
  } });
  sheet({ title: isNew ? 'New card or document' : 'Edit', dismissable: false,
    body: [field('Type', type), field('Name', title), field('Front & back (photo or scan)', A.strip('wallet', uid, { label: 'Front / back' })),
      field('Card / document number', number), field('Name on card', holder), expiryField, field('Barcode', barcode), field('Notes', notes)],
    buttons, onClose: () => { if (isNew && !saved) A.removeAll('wallet', uid); } });
}

export default {
  id: 'wallet',
  name: 'Wallet',
  icon: 'wallet',
  tables: ['wallet', 'attachments'],
  render,
  hide() { unlocked = false; autoTried = false; },
  async open(uid) {
    const w = S.get('wallet', uid);
    if (!w) return;
    if (!P.isOn() || !unlocked) { if (P.isOn()) await unlock(); if (!unlocked) return; }
    details(w);
  },
  /** Shared photos / PDFs become a new document. */
  async newWith(files) {
    if (!P.isOn() || !unlocked) { if (P.isOn()) await unlock(); if (!unlocked) return; }
    const draftUid = S.newUid();
    for (const f of files) await A.addFile('wallet', draftUid, f);
    edit({ type: 'Document', draftUid });
  },
};
