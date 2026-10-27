// Text shared into Doable (Android/Chrome share target) or pasted: note, task, grocery items, diary, checklist.
import * as S from './store.js';
import * as L from './logic.js';
import { app } from './ctx.js';
import { menu, toast } from './ui.js';
import { importItems } from './m-checklists.js';

export function shareIn(text) {
  menu('Save to Doable', [
    { label: '📝 New note', action: () => { app.go('notes'); app.modules.find((m) => m.id === 'notes').newWith(text); } },
    { label: '✅ New task', action: () => { app.go('tasks'); setTimeout(() => app.modules.find((m) => m.id === 'tasks').prefill(text.replace(/\n/g, ' ')), 50); } },
    { label: '🛒 Add to shopping list', action: () => { app.go('grocery'); const n = app.modules.find((m) => m.id === 'grocery').importText(text); toast(n ? n + ' item(s) added' : 'Nothing new to add'); } },
    { label: '📖 Add to today\'s diary', action: () => {
      const uid = 'diary:' + L.today();
      const d = S.get('diary', uid) || { ...S.defaults('diary'), day: L.today() };
      S.put('diary', { ...d, uid, text: d.text.trim() ? d.text.trim() + '\n\n' + text : text, modified: Date.now() });
      app.go('diary', uid);
    } },
    { label: '☑ New checklist (one item per line)', action: () => {
      const heading = L.checklistHeading(text);
      const lines = L.checklistParse(text);
      if (heading && lines.length) lines.shift();
      const uid = S.put('checklists', { title: heading || 'Shared list', created: Date.now() });
      importItems(uid, lines);
      app.go('checklists', uid);
    } },
  ]);
}
