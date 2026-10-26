'use strict';
/* Activity log: timestamped notes of what you did, grouped by day. */

const Log = {
  id: 'log',
  name: 'Activity log',
  icon: 'history',
  title: () => 'Activity log',

  render() {
    const entries = [...state.log].sort((a, b) => b.ts - a.ts);
    let html = '', lastDay = '', todayCount = 0;
    const today = todayStr();
    for (const e of entries) {
      const day = ymd(new Date(e.ts));
      if (day !== lastDay) { html += `<li class="section">${esc(dayLabel(e.ts))}</li>`; lastDay = day; }
      if (day === today) todayCount++;
      html += `<li class="card entry" data-entry="${e.id}"><time>${esc(timeOf(e.ts))}</time><div class="body">${esc(e.text)}</div></li>`;
    }
    $('log-list').innerHTML = html;
    $('log-summary').textContent = todayCount + ' logged today · ' + entries.length + ' total';
    $('log-empty').hidden = entries.length > 0;
  },

  editSheet(e) {
    const d = new Date(e.ts);
    const local = ymd(d) + 'T' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
    openSheet(`<h3>Edit entry</h3><textarea id="ee-text" rows="3">${esc(e.text)}</textarea>
      <label for="ee-time">When</label><input type="datetime-local" id="ee-time" value="${local}">
      <div class="row"><button class="btn danger" data-del>Delete</button><button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>Save</button></div>`, (s) => {
      s.querySelector('[data-x]').onclick = closeSheet;
      s.querySelector('[data-del]').onclick = () => confirmSheet('Delete this entry?', e.text, 'Delete', () => {
        state.log = state.log.filter((x) => x !== e); save(); this.render();
      });
      s.querySelector('[data-ok]').onclick = () => {
        e.text = s.querySelector('#ee-text').value.trim() || e.text;
        const v = s.querySelector('#ee-time').value;
        if (v) e.ts = new Date(v).getTime();
        save(); closeSheet(); this.render();
      };
    });
  },
};
registerModule(Log);

$('log-list').addEventListener('click', (ev) => {
  const li = ev.target.closest('[data-entry]');
  if (li) Log.editSheet(state.log.find((x) => x.id === +li.dataset.entry));
});
$('log-add').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const text = $('log-input').value.trim();
  if (!text) return;
  state.log.push({ id: newId(), text, ts: Date.now() });
  $('log-input').value = '';
  save();
  Log.render();
});
