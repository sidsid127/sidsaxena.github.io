// Notes: folders, colours, pinning, checklists, light formatting, [[links]], attachments, voice notes,
// locked notes (behind Face ID / PIN) and "Recently deleted" (30 days).
import * as S from './store.js';
import * as A from './attach.js';
import { app } from './ctx.js';
import * as SP from './spaces.js';
import { verifyUser } from './lock.js';
import { h, chip, menu, sheet, prompt, confirm, toast, header, card, empty, shareText, fmtStamp } from './ui.js';

const COLORS = [0, 0xFFFFE89A | 0, 0xFFBDEBC9 | 0, 0xFFBFDCFF | 0, 0xFFFFC9DD | 0, 0xFFDCCBFF | 0, 0xFFFFD2A6 | 0];
const COLOR_NAMES = ['None', 'Yellow', 'Green', 'Blue', 'Pink', 'Purple', 'Orange'];
const TRASH = '\u0001trash';
const PURGE_MS = 30 * 86400000;

let openUid = null;   // note being edited
let preview = false;
let query = '';
let unlocked = new Set(); // locked notes opened in this session

const titleOf = (body) => (body.split('\n').find((l) => l.trim()) || '').replace(/^#+\s*/, '').trim() || 'New note';
function snippet(body) {
  const lines = body.split('\n').filter((l) => l.trim());
  return lines.slice(1).join(' ').replace(/\*\*|__|\[\[|\]\]/g, '').slice(0, 120);
}

function folders() {
  const set = new Set((localStorage.getItem('doable.noteFolders') || '').split('\n').filter(Boolean));
  for (const n of S.all('notes')) if (n.folder && !n.deleted) set.add(n.folder);
  return [...set].sort();
}

function purge() {
  const cutoff = Date.now() - PURGE_MS;
  for (const n of S.where('notes', (x) => x.deleted && x.deleted < cutoff)) S.remove('notes', n.uid);
}

function list(view) {
  purge();
  const st = S.settings;
  const page = h('div.page');
  const search = h('input.field', { type: 'search', placeholder: 'Search notes', value: query });
  search.addEventListener('input', () => { query = search.value; renderList(); });
  page.appendChild(search);
  const chips = h('div.chips');
  const pickFolder = (f) => { st.noteFolder = f; S.saveSettings(); app.refresh(); };
  chips.appendChild(chip('All', { selected: !st.noteFolder, onclick: () => pickFolder('') }));
  for (const f of folders()) {
    chips.appendChild(chip('📁 ' + f, { selected: st.noteFolder === f, onclick: () => pickFolder(f), onlongpress: () => folderMenu(f) }));
  }
  chips.appendChild(chip('🗑 Recently deleted', { selected: st.noteFolder === TRASH, color: '#7A7891', onclick: () => pickFolder(TRASH) }));
  chips.appendChild(chip('+ Folder', { onclick: newFolder }));
  page.appendChild(chips);
  const results = h('div');
  page.appendChild(results);
  const renderList = () => {
    results.textContent = '';
    const trash = st.noteFolder === TRASH;
    const q = query.trim().toLowerCase();
    const notes = S.where('notes', (n) => (trash ? n.deleted : !n.deleted) && (trash || !st.noteFolder || n.folder === st.noteFolder)
      && (!q || (n.locked ? titleOf(n.body) : n.body).toLowerCase().includes(q))).sort((a, b) => b.modified - a.modified);
    if (!notes.length) {
      results.appendChild(empty(trash ? 'Nothing deleted in the last 30 days.' : q ? 'No notes match.' : 'No notes yet.\nTap + to write one.'));
      return;
    }
    const pinned = notes.filter((n) => n.pinned && !trash);
    const others = notes.filter((n) => !n.pinned || trash);
    const show = (title, arr) => {
      if (!arr.length) return;
      if (title) results.appendChild(header(title));
      results.appendChild(card(arr.map(noteRow)));
    };
    show(pinned.length ? '📌 Pinned' : '', pinned);
    show(pinned.length ? 'Notes' : '', others);
    if (trash) results.appendChild(h('p.muted', { style: { padding: '8px' } }, 'Notes here are deleted for good after 30 days. Tap one to restore it.'));
  };
  renderList();
  view.appendChild(page);
  view.appendChild(h('button.fab', { type: 'button', 'aria-label': 'New note', onclick: newNote }, '+'));
}

function noteRow(n) {
  const files = A.count('note', n.uid);
  const color = n.color ? S.colorCss(n.color) : 'transparent';
  return h('div.note-row', { style: { '--nc': color }, onclick: () => openNote(n),
    oncontextmenu: (e) => { e.preventDefault(); noteMenu(n); } },
    h('div.note-title', (n.locked ? '🔒 ' : '') + (SP.badge('notes', n.uid) ? SP.badge('notes', n.uid) + ' ' : '') + titleOf(n.body)),
    h('div.note-sub', fmtStamp(n.modified) + (n.folder && !S.settings.noteFolder ? ' · 📁 ' + n.folder : '') + (files ? ' · 📎 ' + files : '')
      + (n.locked ? ' · Locked' : n.deleted ? ' · Deleted' : snippet(n.body) ? ' · ' + snippet(n.body) : '')));
}

async function newFolder() {
  const f = await prompt('New folder', '', { placeholder: 'e.g. Work, Recipes' });
  if (!f) return;
  const set = new Set((localStorage.getItem('doable.noteFolders') || '').split('\n').filter(Boolean));
  set.add(f);
  localStorage.setItem('doable.noteFolders', [...set].join('\n'));
  S.settings.noteFolder = f;
  S.saveSettings();
  app.refresh();
}
function folderMenu(f) {
  menu('📁 ' + f, [
    { label: 'Rename', action: async () => {
      const nf = await prompt('Rename folder', f);
      if (!nf) return;
      for (const n of S.where('notes', (x) => x.folder === f)) S.put('notes', { uid: n.uid, folder: nf });
      localStorage.setItem('doable.noteFolders', folders().map((x) => (x === f ? nf : x)).join('\n'));
      S.settings.noteFolder = nf; S.saveSettings(); app.refresh();
    } },
    { label: 'Delete folder (notes move to All)', danger: true, action: () => {
      for (const n of S.where('notes', (x) => x.folder === f)) S.put('notes', { uid: n.uid, folder: '' });
      localStorage.setItem('doable.noteFolders', folders().filter((x) => x !== f).join('\n'));
      S.settings.noteFolder = ''; S.saveSettings(); app.refresh();
    } },
  ]);
}

function newNote() {
  const f = S.settings.noteFolder === TRASH ? '' : S.settings.noteFolder;
  const uid = S.put('notes', { body: '', created: Date.now(), modified: Date.now(), folder: f });
  openUid = uid;
  preview = false;
  app.refresh();
}

async function openNote(n) {
  if (n.deleted) {
    if (await confirm('Restore this note?', titleOf(n.body), 'Restore', false)) S.put('notes', { uid: n.uid, deleted: 0 });
    return;
  }
  if (n.locked && !unlocked.has(n.uid)) {
    if (!S.settings.lock) { toast('Turn on the app lock (Settings) to open locked notes here'); }
    if (!await verifyUser('PIN to open the locked note')) return;
    unlocked.add(n.uid);
  }
  openUid = n.uid;
  preview = !!n.body.trim();
  app.refresh();
}

function noteMenu(n) {
  menu(titleOf(n.body), n.deleted ? [
    { label: 'Restore', action: () => S.put('notes', { uid: n.uid, deleted: 0 }) },
    { label: 'Delete for good', danger: true, action: () => S.remove('notes', n.uid) },
  ] : [
    { label: n.pinned ? 'Unpin' : '📌 Pin to top', action: () => S.put('notes', { uid: n.uid, pinned: n.pinned ? 0 : 1 }) },
    { label: '🎨 Colour', action: () => menu('Colour', COLOR_NAMES.map((name, i) => ({ label: name, checked: n.color === COLORS[i], action: () => S.put('notes', { uid: n.uid, color: COLORS[i] }) }))) },
    { label: '📁 Move to folder', action: () => menu('Folder', [{ label: 'No folder', action: () => S.put('notes', { uid: n.uid, folder: '' }) },
      ...folders().map((f) => ({ label: f, checked: n.folder === f, action: () => S.put('notes', { uid: n.uid, folder: f }) }))]) },
    { label: n.locked ? '🔓 Unlock note' : '🔒 Lock note', action: async () => {
      if (!n.locked && !S.settings.lock) { toast('Turn on the app lock in Settings first, so locked notes need your PIN / Face ID'); return; }
      if (n.locked && !await verifyUser('PIN to unlock the note')) return;
      S.put('notes', { uid: n.uid, locked: n.locked ? 0 : 1 });
    } },
    ...SP.shareItems('notes', 'notes', n.uid, { people: true }),
    { label: '↗ Send a copy (WhatsApp, email…)', action: () => shareText(titleOf(n.body), plain(n.body)) },
    { label: '🗑 Delete', danger: true, action: () => { S.put('notes', { uid: n.uid, deleted: Date.now() }); toast('Moved to Recently deleted', 'Undo', () => S.put('notes', { uid: n.uid, deleted: 0 })); } },
  ]);
}

const plain = (body) => body.replace(/\*\*(.+?)\*\*/g, '$1').replace(/\[\[([^\]\n]+)\]\]/g, '$1');

// ------------------------------------------------------------------ editor

let saveTimer = 0;
function editor(view) {
  const n = S.get('notes', openUid);
  if (!n) { openUid = null; list(view); return; }
  const wrap = h('div.editor-wrap');
  const ta = h('textarea.note-editor', { placeholder: 'Title\nStart writing…', spellcheck: true });
  ta.value = n.body;
  const save = () => {
    clearTimeout(saveTimer);
    const cur = S.get('notes', openUid);
    if (cur && cur.body !== ta.value) S.put('notes', { uid: openUid, body: ta.value, modified: Date.now() });
  };
  ta.addEventListener('input', () => { clearTimeout(saveTimer); saveTimer = setTimeout(save, 600); });
  ta.addEventListener('blur', save);
  ta.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' || e.isComposing) return;
    const start = ta.selectionStart;
    const lineStart = ta.value.lastIndexOf('\n', start - 1) + 1;
    const line = ta.value.slice(lineStart, start);
    const m = /^(☐ |☑ |• |- )/.exec(line);
    if (!m) return;
    e.preventDefault();
    if (line.trim() === m[1].trim()) { ta.setRangeText('', lineStart, start, 'end'); return; } // empty item ends the list
    ta.setRangeText('\n' + (m[1] === '☑ ' ? '☐ ' : m[1]), start, ta.selectionEnd, 'end');
    ta.dispatchEvent(new Event('input'));
  });
  const wrapSel = (mark) => {
    const s = ta.selectionStart, e = ta.selectionEnd;
    ta.setRangeText(mark + ta.value.slice(s, e) + mark, s, e, 'end');
    if (s === e) ta.selectionStart = ta.selectionEnd = s + mark.length;
    ta.focus();
    ta.dispatchEvent(new Event('input'));
  };
  const prefix = (p, others = []) => {
    const s = ta.selectionStart;
    const ls = ta.value.lastIndexOf('\n', s - 1) + 1;
    const rest = ta.value.slice(ls);
    if (rest.startsWith(p)) ta.setRangeText('', ls, ls + p.length, 'end');
    else {
      const o = others.find((x) => rest.startsWith(x));
      if (o) ta.setRangeText('', ls, ls + o.length, 'end');
      ta.setRangeText(p, ls, ls, 'end');
    }
    ta.focus();
    ta.dispatchEvent(new Event('input'));
  };
  const strip = A.strip('note', n.uid, { voice: true, onAdded: () => S.put('notes', { uid: n.uid, modified: Date.now() }) });
  const pv = h('div.note-preview');
  const showPreview = (on) => {
    preview = on;
    save();
    ta.hidden = on;
    pv.hidden = !on;
    toggle.textContent = on ? '✎ Edit' : '👁 Preview';
    if (on) renderPreview(pv, S.get('notes', openUid).body, (body) => { ta.value = body; S.put('notes', { uid: openUid, body, modified: Date.now() }); });
    else ta.focus();
  };
  const toggle = h('button', { type: 'button', onclick: () => showPreview(!preview) });
  const bar = h('div.toolbar',
    toggle,
    h('button', { type: 'button', title: 'Bold', onclick: () => { showPreview(false); wrapSel('**'); } }, 'B'),
    h('button', { type: 'button', title: 'Italic', style: { fontStyle: 'italic' }, onclick: () => { showPreview(false); wrapSel('_'); } }, 'I'),
    h('button', { type: 'button', title: 'Heading', onclick: () => { showPreview(false); prefix('# ', ['## ']); } }, 'H'),
    h('button', { type: 'button', title: 'Bullet', onclick: () => { showPreview(false); prefix('• ', ['☐ ', '☑ ']); } }, '•'),
    h('button', { type: 'button', title: 'Checklist item', onclick: () => { showPreview(false); prefix('☐ ', ['• ', '☑ ']); } }, '☐'),
    h('button', { type: 'button', title: 'Link to a note', onclick: async () => {
      const others = S.where('notes', (x) => !x.deleted && x.uid !== n.uid);
      menu('Link to note', others.map((o) => ({ label: titleOf(o.body), action: () => { showPreview(false); ta.setRangeText('[[' + titleOf(o.body) + ']]', ta.selectionStart, ta.selectionEnd, 'end'); ta.dispatchEvent(new Event('input')); } })));
    } }, '🔗'));
  wrap.append(bar, ta, pv, h('div', { style: { padding: '0 12px', background: 'var(--card)' } }, strip),
    h('div.kbd-hint', { style: { padding: '6px 14px 14px' } }, 'First line = title · **bold** · _italic_ · # heading · ☐ checklist · [[Note title]] links · Preview to tick boxes and follow links'));
  view.appendChild(wrap);
  if (preview) showPreview(true); else { pv.hidden = true; toggle.textContent = '👁 Preview'; setTimeout(() => ta.focus(), 50); }
}

/** Formatted, read-only view: tap ☐ to tick, tap [[links]] to open (or create) that note. */
function renderPreview(el, body, onChange) {
  el.textContent = '';
  const lines = body.split('\n');
  lines.forEach((line, i) => {
    let cls = '';
    let text = line;
    if (/^# /.test(line)) { cls = '.h1'; text = line.slice(2); } else if (/^## /.test(line)) { cls = '.h2'; text = line.slice(3); } else if (i === 0 && line.trim()) cls = '.h1';
    const div = h('div' + cls);
    const box = /^(☐|☑) /.exec(text);
    if (box) {
      div.appendChild(h('span.box', { onclick: () => {
        lines[i] = (box[1] === '☐' ? '☑' : '☐') + line.slice(1);
        onChange(lines.join('\n'));
        renderPreview(el, lines.join('\n'), onChange);
      } }, box[1] + ' '));
      text = text.slice(2);
      if (box[1] === '☑') div.classList.add('done');
    }
    inline(div, text);
    if (!line) div.appendChild(document.createElement('br'));
    el.appendChild(div);
  });
}
function inline(parent, text) {
  // (no regex look-behind: older Safari can't parse it)
  const re = /\*\*(.+?)\*\*|(^|[^\w*_])_([^_\n]+?)_(?!\w)|\[\[([^\]\n]+)\]\]|(https?:\/\/\S+)/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    const start = m.index + (m[2] ? m[2].length : 0);
    if (start > last) parent.appendChild(document.createTextNode(text.slice(last, start)));
    if (m[1]) parent.appendChild(h('b', m[1]));
    else if (m[3]) parent.appendChild(h('i', m[3]));
    else if (m[4]) { const t = m[4]; parent.appendChild(h('a', { href: '#', onclick: (e) => { e.preventDefault(); followLink(t); } }, t)); }
    else parent.appendChild(h('a', { href: m[5], target: '_blank', rel: 'noopener' }, m[5]));
    last = m.index + m[0].length;
  }
  if (last < text.length) parent.appendChild(document.createTextNode(text.slice(last)));
}
async function followLink(title) {
  const t = title.toLowerCase();
  const target = S.where('notes', (n) => !n.deleted && titleOf(n.body).toLowerCase() === t)[0];
  if (target) { openNote(target); return; }
  if (await confirm('Create "' + title + '"?', 'There is no note with this title yet.', 'Create', false)) {
    openUid = S.put('notes', { body: title + '\n', created: Date.now(), modified: Date.now(), folder: S.settings.noteFolder === TRASH ? '' : S.settings.noteFolder });
    preview = false;
    app.refresh();
  }
}

function closeEditor() {
  const ta = document.querySelector('.note-editor');
  if (ta && openUid) {
    clearTimeout(saveTimer);
    const n = S.get('notes', openUid);
    if (n && n.body !== ta.value) S.put('notes', { uid: openUid, body: ta.value, modified: Date.now() });
  }
  const n = S.get('notes', openUid);
  if (n && !n.body.trim() && !A.count('note', n.uid)) S.remove('notes', n.uid); // empty note: drop it
  openUid = null;
}

export default {
  id: 'notes',
  name: 'Notes',
  icon: 'note',
  tables: ['notes', 'attachments'],
  get busy() { return !!openUid; },
  title() { const n = openUid && S.get('notes', openUid); return n ? titleOf(n.body) : 'Notes'; },
  render(view) { if (openUid) editor(view); else list(view); },
  isDeep: () => !!openUid,
  back() { closeEditor(); app.refresh(); },
  hide() { if (openUid) closeEditor(); unlocked = new Set(); },
  open(uid) { const n = S.get('notes', uid); if (n) { if (openUid) closeEditor(); openNote(n); } },
  quick() { if (openUid) closeEditor(); newNote(); },
  newWith(body) { if (openUid) closeEditor(); openUid = S.put('notes', { body, created: Date.now(), modified: Date.now() }); preview = false; app.refresh(); return openUid; },
  menu() {
    const n = openUid && S.get('notes', openUid);
    if (n) return [{ label: 'Note options…', action: () => noteMenu(n) }];
    return [{ label: '+ New folder', action: newFolder },
      S.settings.noteFolder === TRASH ? { label: 'Empty Recently deleted', danger: true, action: async () => {
        const d = S.where('notes', (x) => x.deleted);
        if (d.length && await confirm('Delete ' + d.length + ' note(s) for good?', null, 'Delete')) d.forEach((x) => S.remove('notes', x.uid));
      } } : null];
  },
};
