'use strict';
/* Notes, in the style of the iPhone Notes app: searchable list, pinned notes, full-screen editor. */

function noteTitle(body) {
  const first = body.trim().split('\n')[0].trim();
  return first || 'New note';
}
function notePreview(body) {
  const rest = body.trim().split('\n').slice(1).join(' ').trim();
  return rest || 'No additional text';
}

const Notes = {
  id: 'notes',
  name: 'Notes',
  icon: 'note',
  open: null, // note being edited
  title() { return this.open ? '' : 'Notes'; },
  isNested() { return !!this.open; },
  back() { this.close(); },
  onHide() { this.close(); },
  onBackground() { this.save(); },

  render() {
    const editing = !!this.open;
    $('notes-list-view').hidden = editing;
    $('note-new').hidden = editing;
    $('note-editor-view').hidden = !editing;
    if (editing) return;
    const q = $('note-search').value.trim().toLowerCase();
    const list = state.notes
      .filter((n) => !q || n.body.toLowerCase().includes(q))
      .sort((a, b) => (b.pinned - a.pinned) || (b.modified - a.modified));
    const anyPinned = list.some((n) => n.pinned);
    let html = '', other = false;
    if (anyPinned) html += '<li class="section">Pinned</li>';
    for (const n of list) {
      if (anyPinned && !n.pinned && !other) { html += '<li class="section">Notes</li>'; other = true; }
      html += `<li class="card note" data-note="${n.id}"><b>${esc(noteTitle(n.body))}</b>
        <small><time>${esc(relativeStamp(n.modified))}</time>${esc(notePreview(n.body))}</small></li>`;
    }
    $('note-list').innerHTML = html;
    $('note-empty').hidden = list.length > 0;
    $('note-empty').textContent = q ? 'No notes match your search.' : 'No notes yet.\nTap + to write one.';
  },

  edit(n) {
    this.open = n;
    const ed = $('note-editor');
    ed.textContent = n.body;
    $('note-date').textContent = n.body ? fullStamp(n.modified) : '';
    refreshTopbar();
    this.render();
    if (!n.body) ed.focus();
  },

  /** Saves the open note; an emptied note is removed. */
  save() {
    const n = this.open;
    if (!n) return;
    const body = $('note-editor').innerText.replace(/\n$/, '');
    if (body === n.body) return;
    n.body = body;
    n.modified = Date.now();
    if (!body.trim()) state.notes = state.notes.filter((x) => x !== n);
    else if (!state.notes.includes(n)) state.notes.push(n);
    save();
  },

  close() {
    if (!this.open) return;
    this.save();
    $('note-editor').blur();
    this.open = null;
    refreshTopbar();
    this.render();
  },

  menu() {
    const n = this.open;
    if (!n) return [{ label: 'New note', action: () => this.edit(this.blank()) }];
    return [
      { label: n.pinned ? 'Unpin' : 'Pin to top', action: () => { this.save(); if (state.notes.includes(n)) { n.pinned = !n.pinned; save(); } } },
      { label: 'Share note', action: () => { this.save(); shareText(noteTitle(n.body), n.body); } },
      { label: 'Delete note', danger: true, action: () => this.remove(n) },
    ];
  },

  remove(n) {
    confirmSheet('Delete this note?', noteTitle(n.body), 'Delete', () => {
      state.notes = state.notes.filter((x) => x !== n);
      save();
      if (this.open === n) { this.open = null; refreshTopbar(); }
      this.render();
    });
  },

  blank() {
    const now = Date.now();
    return { id: newId(), body: '', pinned: false, created: now, modified: now };
  },
};
registerModule(Notes);

$('note-new').innerHTML = ICONS.add;
$('note-new').addEventListener('click', () => Notes.edit(Notes.blank()));
$('note-search').addEventListener('input', () => Notes.render());
$('note-list').addEventListener('click', (e) => {
  const li = e.target.closest('[data-note]');
  if (li) Notes.edit(state.notes.find((n) => n.id === +li.dataset.note));
});
onLongPress($('note-list'), '[data-note]', (li) => {
  const n = state.notes.find((x) => x.id === +li.dataset.note);
  menuSheet(noteTitle(n.body), [
    { label: n.pinned ? 'Unpin' : 'Pin to top', action: () => { n.pinned = !n.pinned; save(); Notes.render(); } },
    { label: 'Delete', danger: true, action: () => Notes.remove(n) },
  ]);
});
// Save as you type (debounced), like Notes.
let noteTimer = null;
$('note-editor').addEventListener('input', () => {
  clearTimeout(noteTimer);
  noteTimer = setTimeout(() => Notes.save(), 600);
});
$('note-editor').addEventListener('blur', () => Notes.save());
