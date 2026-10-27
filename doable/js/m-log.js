// Activity log: timestamped notes of what you did.
import * as S from './store.js';
import { h, header, card, row, empty, menu, prompt } from './ui.js';

function render(view) {
  const page = h('div.page.flush');
  const entries = S.all('log').sort((a, b) => b.ts - a.ts);
  if (!entries.length) page.appendChild(empty('Nothing logged yet.\nNote down what you did below.'));
  let day = null, c;
  for (const e of entries) {
    const d = new Date(e.ts).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
    if (d !== day) { day = d; page.appendChild(header(d)); c = card(); page.appendChild(c); }
    c.appendChild(row({ icon: '•', title: e.text, sub: new Date(e.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      onclick: () => menu(e.text, [
        { label: 'Edit', action: async () => { const t = await prompt('Edit', e.text, { multiline: true }); if (t) S.put('log', { uid: e.uid, text: t }); } },
        { label: 'Delete', danger: true, action: () => S.del('log', e.uid) },
      ]) }));
  }
  view.appendChild(page);
  const inp = h('input.field#log-input', { type: 'text', placeholder: 'What did you just do?', enterkeyhint: 'done' });
  const add = () => { if (inp.value.trim()) { S.put('log', { text: inp.value.trim(), ts: Date.now() }); inp.value = ''; } };
  inp.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.isComposing) add(); });
  view.appendChild(h('div.inputbar', inp, h('button.btn.primary', { type: 'button', onclick: add }, 'Add')));
}

export default {
  id: 'log',
  name: 'Activity log',
  icon: 'history',
  tables: ['log'],
  get busy() { const i = document.getElementById('log-input'); return !!(i && i.value && document.activeElement === i); },
  render,
};
