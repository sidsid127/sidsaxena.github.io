// Reusable checklists and templates, paste-a-list import, ticked-to-bottom and untick-all.
import * as S from './store.js';
import * as L from './logic.js';
import { app } from './ctx.js';
import * as SP from './spaces.js';
import { h, chip, card, row, empty, pill, sheet, field, input, textarea, select, menu, prompt, confirm, toast, shareText } from './ui.js';

let templates = false;
let openUid = null;

const items = (list) => S.where('checklist_items', (i) => i.list === list).sort((a, b) => (a.position - b.position) || (a.created || 0) - (b.created || 0));
const lists = (tpl) => S.where('checklists', (l) => !!l.template === tpl).sort((a, b) => (b.created || 0) - (a.created || 0));
const nextPos = (list) => items(list).reduce((m, i) => Math.max(m, i.position), 0) + 1;

export function importItems(list, lines) {
  const have = new Set(items(list).map((i) => L.checkKey(i.text)));
  let pos = nextPos(list), added = 0, skipped = 0;
  for (const l of lines) {
    const k = L.checkKey(l.text);
    if (have.has(k)) { skipped++; continue; }
    have.add(k);
    S.put('checklist_items', { list, text: l.text, done: l.done ? 1 : 0, position: pos++ });
    added++;
  }
  return [added, skipped];
}
export function copyList(from, title, template, trip = '') {
  const uid = S.put('checklists', { title, template: template ? 1 : 0, trip, created: Date.now() });
  items(from).forEach((i, n) => S.put('checklist_items', { list: uid, text: i.text, done: 0, position: n + 1 }));
  return uid;
}

function render(view) {
  const page = h('div.page');
  if (openUid) { renderList(page); view.appendChild(page); return; }
  page.appendChild(h('div.chips', chip('My checklists', { selected: !templates, onclick: () => { templates = false; app.refresh(); } }),
    chip('Templates', { selected: templates, onclick: () => { templates = true; app.refresh(); } })));
  page.appendChild(h('div.chips', chip('📋 Paste a list from Notes, Notepad…', { onclick: () => pasteDialog(null) })));
  const ls = lists(templates);
  if (!ls.length) page.appendChild(empty(templates ? 'No templates yet.' : 'No checklists yet.\nTap + and start from a template.'));
  else {
    page.appendChild(card(ls.map((l) => {
      const its = items(l.uid);
      const done = its.filter((i) => i.done).length;
      return row({ icon: templates ? '📋' : its.length && done === its.length ? '✅' : '☑', title: (SP.badge('checklists', l.uid) ? SP.badge('checklists', l.uid) + ' ' : '') + l.title,
        sub: templates ? `${its.length} items` : `${done} of ${its.length} done` + (l.trip ? ' · trip packing list' : ''),
        onclick: () => { openUid = l.uid; app.refresh(); }, onlongpress: () => listMenu(l) });
    })));
  }
  view.appendChild(page);
  view.appendChild(h('button.fab', { type: 'button', 'aria-label': 'New checklist', onclick: newList }, '+'));
}

function renderList(page) {
  const l = S.get('checklists', openUid);
  if (!l) { openUid = null; return; }
  const its = items(l.uid);
  const done = its.filter((i) => i.done).length;
  page.appendChild(h('div.section', templates ? `Template · ${its.length} items` : `${done} of ${its.length} done` + (its.length && done === its.length ? ' 🎉' : '')));
  if (its.length) {
    page.appendChild(h('div.chips',
      pill('⇅ Ticked to bottom', { onclick: () => {
        let pos = 1;
        for (const pass of [0, 1]) for (const i of its) if (!!i.done === !!pass) S.put('checklist_items', { uid: i.uid, position: pos++ });
      } }),
      done ? pill('↺ Untick all', { onclick: async () => {
        if (await confirm('Untick all items?', 'Ready to use this list again next time.', 'Untick all', false)) its.forEach((i) => i.done && S.put('checklist_items', { uid: i.uid, done: 0 }));
      } }) : null,
      pill('📋 Paste', { onclick: () => pasteDialog(l) })));
  }
  page.appendChild(card(its.map((i) => {
    const cb = h('input', { type: 'checkbox', checked: !!i.done });
    const el = h('label.check-item' + (i.done ? '.done' : ''), cb, h('span', i.text));
    cb.addEventListener('change', () => S.put('checklist_items', { uid: i.uid, done: cb.checked ? 1 : 0 }));
    el.addEventListener('contextmenu', (e) => { e.preventDefault(); itemMenu(i); });
    let t;
    el.addEventListener('touchstart', () => { t = setTimeout(() => itemMenu(i), 600); }, { passive: true });
    el.addEventListener('touchend', () => clearTimeout(t));
    el.addEventListener('touchmove', () => clearTimeout(t), { passive: true });
    return el;
  })));
  const add = h('input.field#check-add', { type: 'text', placeholder: 'Add an item…', enterkeyhint: 'done' });
  const doAdd = () => {
    const v = add.value.trim();
    if (!v) return;
    importItems(l.uid, L.checklistParse(v));
    add.value = '';
    setTimeout(() => { const a = document.getElementById('check-add'); if (a) a.focus(); }, 30);
  };
  add.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.isComposing) doAdd(); });
  page.appendChild(h('div.form-inline', { style: { marginTop: '10px' } }, add, h('button.btn.primary', { type: 'button', style: { flex: 'none' }, onclick: doAdd }, 'Add')));
}

function itemMenu(i) {
  menu(i.text, [
    { label: 'Edit', action: async () => { const t = await prompt('Edit item', i.text); if (t) S.put('checklist_items', { uid: i.uid, text: t }); } },
    { label: 'Delete', danger: true, action: () => S.del('checklist_items', i.uid) },
  ]);
}

function listMenu(l) {
  menu(l.title, [
    { label: 'Open', action: () => { openUid = l.uid; app.refresh(); } },
    ...SP.shareItems('checklists', 'checklists', l.uid, { people: true }),
    { label: 'Duplicate', action: () => copyList(l.uid, l.title + ' (copy)', !!l.template) },
    { label: 'Delete', danger: true, action: () => deleteList(l) },
  ]);
}
async function deleteList(l) {
  if (!await confirm('Delete "' + l.title + '"?', null, 'Delete')) return;
  S.remove('checklists', l.uid);
  if (openUid === l.uid) openUid = null;
  app.refresh();
}

function pasteDialog(into) {
  const name = input('', { placeholder: 'e.g. Camping' });
  const text = textarea('', { rows: 8, placeholder: 'Passport\nCharger\nSocks…' });
  if (navigator.clipboard && navigator.clipboard.readText) {
    navigator.clipboard.readText().then((t) => { if (t && t.includes('\n') && !text.value) text.value = t; }).catch(() => {});
  }
  sheet({
    title: into ? `Add items to "${into.title}"` : 'New checklist from text',
    body: [into ? null : field('Name (optional)', name), field('Items — one per line', text),
      h('p.kbd-hint', 'Bullets, numbers and ☐ are removed; ☑ or [x] items stay ticked.' + (into ? ' Items already on the list are skipped.' : ''))],
    buttons: [{ label: 'Cancel' }, { label: into ? 'Add' : 'Create', primary: true, action: () => {
      const heading = L.checklistHeading(text.value);
      const lines = L.checklistParse(text.value);
      if (heading && lines.length) lines.shift();
      if (!lines.length) { toast('No items found'); return false; }
      const uid = into ? into.uid : S.put('checklists', { title: name.value.trim() || heading || 'Pasted list', template: templates ? 1 : 0, created: Date.now() });
      const [a, s] = importItems(uid, lines);
      toast(`${a} item${a === 1 ? '' : 's'} added` + (s ? ` · ${s} already on the list` : ''));
      openUid = uid;
      app.refresh();
    } }],
  });
}

function newList() {
  const name = input('', { placeholder: templates ? 'e.g. Customer workshop' : 'e.g. Weekend in Paris' });
  const tpls = lists(true);
  const from = select([['', 'Empty list'], ...tpls.map((t) => [t.uid, t.title])], '');
  sheet({
    title: templates ? 'New template' : 'New checklist',
    body: [field('Name', name), templates || !tpls.length ? null : field('Start from template', from)],
    buttons: [{ label: 'Cancel' }, { label: 'Create', primary: true, action: () => {
      const tpl = tpls.find((t) => t.uid === from.value);
      const n = name.value.trim() || (tpl ? tpl.title : 'Checklist');
      openUid = tpl && !templates ? copyList(tpl.uid, n, false) : S.put('checklists', { title: n, template: templates ? 1 : 0, created: Date.now() });
      app.refresh();
    } }],
  });
}

export default {
  id: 'checklists',
  name: 'Checklists',
  icon: 'checklist',
  tables: ['checklists', 'checklist_items'],
  get busy() { const a = document.getElementById('check-add'); return !!(a && a.value && document.activeElement === a); },
  title() { const l = openUid && S.get('checklists', openUid); return l ? l.title : 'Checklists'; },
  render,
  isDeep: () => !!openUid,
  back() { openUid = null; app.refresh(); },
  open(uid) { const l = S.get('checklists', uid); if (l) { templates = !!l.template; openUid = uid; app.refresh(); } },
  menu() {
    const l = openUid && S.get('checklists', openUid);
    if (!l) return [{ label: '📋 Paste a list', action: () => pasteDialog(null) }];
    return [
      { label: '📋 Paste items from text', action: () => pasteDialog(l) },
      { label: '↗ Send as text', sub: 'WhatsApp, email…', action: () => shareText(l.title, l.title + '\n' + items(l.uid).map((i) => (i.done ? '☑ ' : '☐ ') + i.text).join('\n')) },
      { label: '✏️ Rename', action: async () => { const t = await prompt('Rename', l.title); if (t) S.put('checklists', { uid: l.uid, title: t }); } },
      ...SP.shareItems('checklists', 'checklists', l.uid, { people: true }),
      l.template ? { label: '▶️ Start a checklist from this', action: () => { const uid = copyList(l.uid, l.title, false); templates = false; openUid = uid; app.refresh(); } }
        : { label: '📑 Save as template', action: () => { copyList(l.uid, l.title, true); toast('Saved as a template'); } },
      { label: '🗑 Delete checklist', danger: true, action: () => deleteList(l) },
    ];
  },
};
