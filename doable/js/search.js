// Search across all modules (credentials are searched inside Credentials, after unlocking).
import * as S from './store.js';
import * as L from './logic.js';
import { app } from './ctx.js';
import { fmtDay } from './ui.js';
import { MOODS } from './m-diary.js';
import { walletEmoji } from './m-wallet.js';
import { dateEmoji, dateWhen, nextOccurrence } from './m-dates.js';

const MAX = 80;

export function searchAll(query) {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const match = (...texts) => { const all = texts.filter(Boolean).join('\n').toLowerCase(); return words.every((w) => all.includes(w)); };
  const snippet = (text) => {
    const flat = (text || '').replace(/\s+/g, ' ').trim();
    const at = flat.toLowerCase().indexOf(words[0]);
    if (at < 0) return flat.length > 90 ? flat.slice(0, 90) + '…' : flat;
    const from = Math.max(0, at - 30);
    return (from ? '…' : '') + flat.slice(from, at + 60) + (at + 60 < flat.length ? '…' : '');
  };
  const out = [];
  const add = (module, uid, icon, title, sub) => { if (app.isVisible(module)) out.push({ module, uid, icon, title, sub: sub || '' }); };

  for (const t of S.all('tasks')) if (match(t.title, t.notes)) add('tasks', t.uid, t.done ? '✓' : '○', t.title, (t.done ? 'Done' : t.due ? fmtDay(t.due) : '') + (t.notes ? ' · ' + snippet(t.notes) : ''));
  for (const n of S.all('notes')) {
    if (n.deleted) continue;
    const title = n.body.split('\n')[0] || 'Untitled';
    if (n.locked ? match(title) : match(n.body)) add('notes', n.uid, n.locked ? '🔒' : '📝', title, n.locked ? 'Locked note' : snippet(n.body.slice(title.length)));
  }
  for (const d of S.all('diary')) if (match(d.text, d.highs, d.lows, d.place, d.weather)) add('diary', d.uid, MOODS[d.mood] || '📖', fmtDay(d.day, { day: 'numeric', month: 'long', year: 'numeric' }), snippet([d.text, d.highs, d.lows, d.place].join(' ')));
  for (const t of S.all('trips')) {
    if (match(t.name, t.destination)) add('trips', t.uid, t.kind === 0 ? '💼' : '🧳', t.name, t.destination);
    for (const e of S.where('expenses', (x) => x.trip === t.uid)) if (match(e.merchant, e.note, e.category, e.attendees)) add('trips', t.uid, '🧾', (e.merchant || e.category) + ' · ' + L.money(e.cents, e.currency), t.name + ' · ' + fmtDay(e.day));
    for (const i of S.where('trip_items', (x) => x.trip === t.uid)) if (match(i.title, i.body)) add('trips', t.uid, '📌', i.title || snippet(i.body), t.name);
  }
  for (const i of S.all('grocery')) if (!i.done && match(i.name)) add('grocery', '', '🛒', (i.qty ? i.qty + ' ' : '') + i.name, 'On the shopping list');
  for (const x of S.all('habits')) if (match(x.name)) add('habits', x.uid, x.emoji || '✔', x.name, '');
  const today = L.today();
  for (const d of S.all('dates')) if (match(d.title, d.kind, d.notes)) add('dates', d.uid, dateEmoji(d), d.title, dateWhen(d, nextOccurrence(d, today)));
  for (const l of S.all('checklists')) {
    const items = S.where('checklist_items', (i) => i.list === l.uid);
    const hit = items.find((i) => match(i.text));
    if (match(l.title) || hit) add('checklists', l.uid, '☑', l.title, hit ? (hit.done ? '☑ ' : '☐ ') + hit.text : l.template ? 'Template' : '');
  }
  for (const w of S.all('wallet')) if (match(w.title, w.type)) add('wallet', w.uid, walletEmoji(w.type), w.title, w.type);
  for (const e of S.all('log')) if (match(e.text)) add('log', e.uid, '🕘', snippet(e.text), new Date(e.ts).toLocaleString());
  return out.slice(0, MAX);
}
