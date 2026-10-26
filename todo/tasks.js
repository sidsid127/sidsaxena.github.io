'use strict';
/* To-do: categories, priorities, due dates. */

const DONE_VISIBLE_MS = 12 * 60 * 60 * 1000;
const PRIORITY = ['None', 'Low', 'Medium', 'High'];

const cat = (id) => state.categories.find((c) => c.id === id);
const topCats = () => state.categories.filter((c) => c.parent === 0);
const subCats = (id) => state.categories.filter((c) => c.parent === id);
/** Categories in display order: each top-level one followed by its sub-categories. */
const orderedCats = () => topCats().flatMap((t) => [t, ...subCats(t.id)]);
function catPath(id) {
  const k = cat(id);
  if (!k) return '';
  const p = cat(k.parent);
  return p ? p.name + ' › ' + k.name : k.name;
}

function addCategory(name, parent) {
  const k = { id: newId(), name, parent, color: PALETTE[state.categories.length % PALETTE.length] };
  state.categories.push(k);
  return k;
}

/** Tasks are kept: a sub-category's move to its parent, a top-level one's become uncategorised. */
function deleteCategory(k) {
  const doomed = new Set([k.id, ...subCats(k.id).map((c) => c.id)]);
  state.tasks.forEach((t) => { if (doomed.has(t.category)) t.category = k.parent; });
  state.categories = state.categories.filter((c) => !doomed.has(c.id));
}

const Tasks = {
  id: 'tasks',
  name: 'To-do',
  icon: 'todo',
  pendingDue: '',
  grouped: false,
  title: () => APP_NAME,

  validateFilter() {
    const s = state.settings;
    if (s.selTop && !cat(s.selTop)) s.selTop = 0;
    if (s.selSub && (!cat(s.selSub) || cat(s.selSub).parent !== s.selTop)) s.selSub = 0;
  },

  matches(t) {
    const { selTop, selSub } = state.settings;
    if (!selTop) return true;
    if (selSub) return t.category === selSub;
    if (t.category === selTop) return true;
    const k = cat(t.category);
    return !!k && k.parent === selTop;
  },

  render() {
    this.validateFilter();
    $('due-dot').hidden = !this.pendingDue;
    this.renderChips();
    const k = cat(state.settings.selSub || state.settings.selTop);
    let hint = 'Add a task' + (k ? ' to ' + k.name : '');
    if (this.pendingDue) hint += ' · ' + dueLabel(this.pendingDue).toLowerCase();
    $('task-input').placeholder = hint + '…';
    this.renderList();
  },

  renderChips() {
    const { selTop, selSub } = state.settings;
    let top = chipHtml('All', 'var(--primary)', !selTop, 'data-top="0"');
    for (const k of topCats()) top += chipHtml(k.name, k.color, selTop === k.id, `data-top="${k.id}" data-cat="${k.id}"`);
    top += '<button class="chip ghost" data-new="0">+ Category</button>';
    $('chips-top').innerHTML = top;

    const sub = $('chips-sub');
    if (!selTop) { sub.hidden = true; return; }
    const t = cat(selTop);
    let html = chipHtml('All ' + t.name, t.color, !selSub, `data-top="${t.id}" data-sub="0"`);
    for (const k of subCats(t.id)) html += chipHtml(k.name, k.color, selSub === k.id, `data-top="${t.id}" data-sub="${k.id}" data-cat="${k.id}"`);
    html += `<button class="chip ghost" data-new="${t.id}">+ Sub-category</button>`;
    sub.innerHTML = html;
    sub.hidden = false;
  },

  renderList() {
    const now = Date.now();
    const today = todayStr();
    const open = [], done = [];
    let high = 0, overdue = 0, hidden = 0;
    for (const t of state.tasks) {
      if (!this.matches(t)) continue;
      if (!t.done) {
        open.push(t);
        if (t.priority === 3) high++;
        if (t.due && t.due < today) overdue++;
      } else if (state.settings.showDone || now - t.doneAt < DONE_VISIBLE_MS) {
        done.push(t);
      } else {
        hidden++;
      }
    }
    // Highest priority first, then soonest due date, then newest.
    const byImportance = (a, b) => (b.priority - a.priority)
      || ((a.due || '9999') < (b.due || '9999') ? -1 : (a.due || '9999') > (b.due || '9999') ? 1 : 0)
      || (b.created - a.created);
    open.sort(byImportance);
    done.sort((a, b) => b.doneAt - a.doneAt);

    // Group by (sub-)category when tasks span more than one; high priority ones go on top.
    const groupOf = (t) => (cat(t.category) ? t.category : 0);
    const groupIds = [...orderedCats().map((k) => k.id), 0];
    const used = new Set(open.map(groupOf));
    this.grouped = used.size > 1;
    let html = '';
    if (!this.grouped) {
      html = open.map((t) => this.taskHtml(t)).join('');
    } else {
      const urgent = open.filter((t) => t.priority === 3);
      if (urgent.length) html += '<li class="section tint" style="--c:var(--p3)">⚑ High priority</li>' + urgent.map((t) => this.taskHtml(t, true)).join('');
      for (const g of groupIds) {
        const rest = open.filter((t) => groupOf(t) === g && t.priority !== 3);
        if (!rest.length) continue;
        const k = cat(g);
        html += k ? `<li class="section tint" style="--c:${k.color}">● ${esc(catPath(g))}</li>` : '<li class="section">No category</li>';
        html += rest.map((t) => this.taskHtml(t)).join('');
      }
    }
    if (done.length) {
      html += `<li class="section">${state.settings.showDone ? 'Completed' : 'Completed · hidden after 12 hours'}</li>`;
      html += done.map((t) => this.taskHtml(t, true)).join('');
    }
    $('task-list').innerHTML = html;

    let s = open.length + ' open';
    if (high) s += ' · ' + high + ' high priority';
    if (overdue) s += ' · ' + overdue + ' overdue';
    if (hidden) s += ' · ' + hidden + ' older completed hidden';
    $('task-summary').textContent = s;
    $('task-empty').hidden = open.length + done.length > 0;
  },

  /** forceCategory: show the category label even inside grouped sections (high priority / completed). */
  taskHtml(t, forceCategory) {
    let due;
    if (t.done) due = `<span class="due">Done ${esc(fullStamp(t.doneAt))}</span>`;
    else if (t.due) due = `<button class="due${daysFromToday(t.due) < 0 ? ' overdue' : ''}" data-act="due">${esc(dueLabel(t.due))}</button>`;
    else due = '<button class="due add" data-act="due">+ Add due date</button>';
    const k = cat(t.category);
    const showCat = k && t.category !== state.settings.selSub && (!this.grouped || forceCategory);
    const catHtml = showCat ? `<span class="cat tint" style="--c:${k.color}">${esc(catPath(k.id))}</span>` : '';
    return `<li class="card task${t.done ? ' done' : ''}" data-id="${t.id}">
      <button class="check" data-act="check" aria-label="${t.done ? 'Mark not done' : 'Mark done'}"><span>${ICONS.tick}</span></button>
      <div class="body"><div class="title">${esc(t.title)}</div><div class="meta">${due}${catHtml}</div></div>
      <button class="prio" data-act="prio" style="--pc:var(--p${t.priority})" aria-label="Priority: ${PRIORITY[t.priority]}">
        ${t.priority ? ICONS.flag : ICONS.flagOutline}<span>${t.priority ? PRIORITY[t.priority] : ''}</span>
      </button>
    </li>`;
  },

  menu() {
    return [
      { label: 'Show all completed tasks', toggle: state.settings.showDone, action: () => { state.settings.showDone = !state.settings.showDone; save(); this.render(); } },
      { label: 'Manage categories', action: () => this.manageCategories() },
      { label: 'Share tasks as text', action: () => shareText(APP_NAME + ' tasks', this.exportText()) },
      {
        label: 'Delete completed tasks', danger: true, action: () => confirmSheet('Delete all completed tasks?', 'This cannot be undone.', 'Delete', () => {
          const n = state.tasks.filter((t) => t.done).length;
          state.tasks = state.tasks.filter((t) => !t.done);
          save(); this.render(); toast('Deleted ' + n + ' task(s)');
        }),
      },
    ];
  },

  exportText() {
    let out = 'TO-DO\n';
    for (const g of [...orderedCats().map((k) => k.id), 0]) {
      const part = state.tasks.filter((t) => (cat(t.category) ? t.category : 0) === g).map((t) => {
        let line = (t.done ? '[x] ' : '[ ] ') + t.title;
        if (t.priority) line += '  !' + PRIORITY[t.priority];
        if (t.due && !t.done) line += '  (' + dueLabel(t.due) + ')';
        return line;
      });
      if (part.length) out += '\n' + (g ? catPath(g) : 'No category') + '\n' + part.join('\n') + '\n';
    }
    return out;
  },

  // ---------- sheets

  dueSheet(current, onPick) {
    const nextMon = (() => { const d = new Date(); d.setDate(d.getDate() + ((8 - d.getDay()) % 7 || 7)); return ymd(d); })();
    openSheet(`<h3>Due date</h3>
      <div class="quick">
        <button data-d="${todayStr()}">Today</button><button data-d="${addDays(1)}">Tomorrow</button>
        <button data-d="${nextMon}">Next Monday</button><button data-d="${addDays(7)}">In a week</button>
      </div>
      <label for="ds-date">Or pick a date</label><input type="date" id="ds-date" value="${current || ''}">
      <div class="row"><button class="btn danger" data-d="">No date</button><button class="btn primary" data-ok>Set</button></div>`, (s) => {
      s.querySelectorAll('[data-d]').forEach((b) => { b.onclick = () => { closeSheet(); onPick(b.dataset.d); }; });
      s.querySelector('[data-ok]').onclick = () => { const v = s.querySelector('#ds-date').value; closeSheet(); onPick(v || ''); };
    });
  },

  editSheet(t) {
    const opts = ['<option value="0">No category</option>']
      .concat(orderedCats().map((k) => `<option value="${k.id}"${k.id === t.category ? ' selected' : ''}>${k.parent ? '   ' + esc(catPath(k.id)) : esc(k.name)}</option>`))
      .join('');
    let prio = t.priority;
    openSheet(`<h3>Edit task</h3>
      <textarea id="et-title" rows="2">${esc(t.title)}</textarea>
      <label>Category</label><select id="et-cat">${opts}</select>
      <label>Priority</label><div class="seg" id="et-prio">${PRIORITY.map((p, i) => `<button data-p="${i}" style="--pc:var(--p${i})">${p}</button>`).join('')}</div>
      <label for="et-due">Due date</label><input type="date" id="et-due" value="${t.due || ''}">
      <div class="row"><button class="btn danger" data-del>Delete</button><button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>Save</button></div>`, (s) => {
      const seg = s.querySelector('#et-prio');
      const paint = () => seg.querySelectorAll('button').forEach((b) => b.classList.toggle('on', +b.dataset.p === prio));
      seg.onclick = (e) => { const b = e.target.closest('button'); if (b) { prio = +b.dataset.p; paint(); } };
      paint();
      s.querySelector('[data-x]').onclick = closeSheet;
      s.querySelector('[data-del]').onclick = () => confirmSheet('Delete this task?', t.title, 'Delete', () => {
        state.tasks = state.tasks.filter((x) => x !== t); save(); this.render();
      });
      s.querySelector('[data-ok]').onclick = () => {
        t.title = s.querySelector('#et-title').value.trim() || t.title;
        t.category = +s.querySelector('#et-cat').value;
        t.priority = prio;
        t.due = s.querySelector('#et-due').value || '';
        save(); closeSheet(); this.render();
      };
    });
  },

  categorySheet(k) {
    const items = [{ label: 'Rename', action: () => textSheet('Rename category', k.name, '', 'Save', (v) => { k.name = v; save(); this.render(); }) }];
    if (!k.parent) items.push({ label: 'Add sub-category', action: () => this.newCategory(k.id) });
    items.push({
      label: 'Delete', danger: true, action: () => {
        const where = k.parent ? 'move to ' + cat(k.parent).name : 'will have no category';
        confirmSheet(`Delete “${k.name}”?`, (k.parent ? '' : 'Its sub-categories are deleted too. ') + 'Tasks are not deleted, they ' + where + '.', 'Delete', () => {
          deleteCategory(k); save(); this.render();
        });
      },
    });
    menuSheet(catPath(k.id), items);
  },

  newCategory(parent) {
    textSheet(parent ? 'New sub-category in ' + cat(parent).name : 'New category', '', parent ? 'e.g. Customer A' : 'e.g. Family, Health', 'Add', (name) => {
      const k = addCategory(name, parent);
      state.settings.selTop = parent || k.id;
      state.settings.selSub = parent ? k.id : 0;
      save(); this.render();
    });
  },

  manageCategories() {
    const items = orderedCats().map((k) => ({ label: (k.parent ? '      ' : '') + '● ' + k.name, hint: 'Edit', action: () => this.categorySheet(k) }));
    items.push({ label: '+ New category', action: () => this.newCategory(0) });
    items.footer = '<p class="note-text">Tip: press and hold a category chip to edit it.</p>';
    menuSheet('Categories', items);
  },
};
registerModule(Tasks);

// ---------- events

function onChipClick(e) {
  const b = e.target.closest('button');
  if (!b) return;
  if (b.dataset.new !== undefined) { Tasks.newCategory(+b.dataset.new); return; }
  state.settings.selTop = +b.dataset.top;
  state.settings.selSub = b.dataset.sub ? +b.dataset.sub : 0;
  save();
  Tasks.render();
}
$('chips-top').addEventListener('click', onChipClick);
$('chips-sub').addEventListener('click', onChipClick);
onLongPress($('chips-top'), '[data-cat]', (el) => Tasks.categorySheet(cat(+el.dataset.cat)));
onLongPress($('chips-sub'), '[data-cat]', (el) => Tasks.categorySheet(cat(+el.dataset.cat)));

$('task-list').addEventListener('click', (e) => {
  const li = e.target.closest('.task');
  if (!li) return;
  const t = state.tasks.find((x) => x.id === +li.dataset.id);
  const act = e.target.closest('[data-act]');
  const a = act && act.dataset.act;
  if (a === 'check') {
    t.done = !t.done;
    t.doneAt = t.done ? Date.now() : 0;
    save(); Tasks.render();
  } else if (a === 'prio') {
    t.priority = (t.priority + 1) % 4;
    save(); Tasks.render();
  } else if (a === 'due') {
    Tasks.dueSheet(t.due, (d) => { t.due = d; save(); Tasks.render(); });
  } else {
    Tasks.editSheet(t);
  }
});
onLongPress($('task-list'), '[data-act=prio]', (el) => {
  const t = state.tasks.find((x) => x.id === +el.closest('.task').dataset.id);
  menuSheet('Priority', PRIORITY.map((p, i) => ({ label: p + (t.priority === i ? '  ✓' : ''), action: () => { t.priority = i; save(); Tasks.render(); } })));
});

$('task-add').addEventListener('submit', (e) => {
  e.preventDefault();
  const text = $('task-input').value.trim();
  if (!text) return;
  state.tasks.push({
    id: newId(), title: text, done: false, doneAt: 0, due: Tasks.pendingDue, created: Date.now(),
    category: state.settings.selSub || state.settings.selTop, priority: 0,
  });
  Tasks.pendingDue = '';
  $('task-input').value = '';
  save();
  Tasks.render();
  document.querySelector('#mod-tasks .scroll').scrollTop = 0;
});
$('due-btn').addEventListener('click', () => Tasks.dueSheet(Tasks.pendingDue, (d) => { Tasks.pendingDue = d; Tasks.render(); }));
