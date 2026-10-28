// My Diary: one entry per day — mood, text, highlights, lowlights, place, weather, photos; printable book.
import * as S from './store.js';
import * as L from './logic.js';
import * as A from './attach.js';
import { app } from './ctx.js';
import { h, header, card, row, empty, input, textarea, field, chip, fmtDay, toast } from './ui.js';

export const MOODS = ['', '😞', '🙁', '😐', '🙂', '😄'];
const MOOD_NAMES = ['', 'Awful', 'Bad', 'Okay', 'Good', 'Great'];
const WEATHER = ['☀️', '🌤', '☁️', '🌧', '⛈', '❄️', '🌫', '💨'];
const WEATHER_NAMES = ['Sunny', 'Partly cloudy', 'Cloudy', 'Rain', 'Storm', 'Snow', 'Fog', 'Windy'];

let openDay = null;
let timer = 0;
let flushSave = null; // writes the open entry now (pending edits)

const isEmpty = (d) => !d || (!d.mood && !d.text.trim() && !d.highs.trim() && !d.lows.trim() && !d.place && !d.weather && !d.temp);
const dayLabel = (day) => {
  const n = L.daysFromToday(day);
  return n === 0 ? 'Today' : n === -1 ? 'Yesterday' : fmtDay(day, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
};

function list(view) {
  const page = h('div.page');
  const entries = S.all('diary').sort((a, b) => (a.day < b.day ? 1 : -1));
  const month = L.today().slice(0, 7);
  page.appendChild(h('div.stat', entries.length ? `${entries.length} entries · ${entries.filter((e) => e.day.startsWith(month)).length} this month` : ''));
  if (!S.get('diary', 'diary:' + L.today())) page.appendChild(card(row({ icon: '📝', title: 'Write today\'s entry', onclick: () => show(L.today()) })));
  if (!entries.length) page.appendChild(empty('Your diary is empty.\nOne entry per day: mood, what happened, highlights, photos.'));
  let m = null, c;
  for (const e of entries) {
    const mm = e.day.slice(0, 7);
    if (mm !== m) {
      m = mm;
      page.appendChild(header(L.toDate(e.day).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })));
      c = card();
      page.appendChild(c);
    }
    const pics = A.count('diary', e.uid);
    const text = e.text.trim() || (e.highs.trim() ? '☀ ' + e.highs : e.lows);
    c.appendChild(row({ icon: (MOODS[e.mood] || '📖') + (e.weather || ''), title: dayLabel(e.day),
      sub: [e.place ? '📍 ' + e.place : '', text.slice(0, 100), pics ? '📷 ' + pics : ''].filter(Boolean).join(' · '), onclick: () => show(e.day) }));
  }
  view.appendChild(page);
  view.appendChild(h('button.fab', { type: 'button', 'aria-label': 'Another day', onclick: () => {
    const d = h('input', { type: 'date', value: L.today(), style: { position: 'fixed', opacity: 0, top: 0 } });
    document.body.appendChild(d);
    d.addEventListener('change', () => { if (d.value) show(d.value); d.remove(); });
    d.addEventListener('blur', () => setTimeout(() => d.remove(), 500));
    if (d.showPicker) d.showPicker(); else d.click();
  } }, '+'));
}

function show(day) {
  if (flushSave) flushSave();
  openDay = day;
  app.refresh();
}

function editor(view) {
  const uid = 'diary:' + openDay;
  const d = S.get('diary', uid) || { ...S.defaults('diary'), day: openDay };
  const page = h('div.page');
  const day = openDay;
  const write = () => {
    clearTimeout(timer);
    flushSave = null;
    const cur = S.get('diary', uid);
    if (isEmpty(d) && !A.count('diary', uid)) { if (cur) S.del('diary', uid); return; }
    S.put('diary', { ...d, uid, day, modified: Date.now() });
  };
  const save = (patch) => {
    Object.assign(d, patch);
    clearTimeout(timer);
    flushSave = write;
    timer = setTimeout(write, 400);
  };
  page.appendChild(h('div.hello', dayLabel(openDay)));
  const moods = h('div.moods');
  MOODS.forEach((m, i) => {
    if (!i) return;
    const b = h('button' + (d.mood === i ? '.on' : ''), { type: 'button', onclick: () => {
      save({ mood: d.mood === i ? 0 : i });
      moods.querySelectorAll('button').forEach((x, k) => x.classList.toggle('on', k + 1 === d.mood));
    } }, h('span', m), MOOD_NAMES[i]);
    moods.appendChild(b);
  });
  page.appendChild(field('Mood', moods));
  const place = input(d.place, { placeholder: 'Where were you?' });
  place.addEventListener('input', () => save({ place: place.value.trim() }));
  const gps = h('button.btn', { type: 'button', style: { flex: 'none' }, onclick: () => {
    if (!navigator.geolocation) { toast('Location not available'); return; }
    navigator.geolocation.getCurrentPosition((p) => {
      save({ lat: p.coords.latitude, lon: p.coords.longitude });
      if (!place.value) { place.value = p.coords.latitude.toFixed(4) + ', ' + p.coords.longitude.toFixed(4); save({ place: place.value }); }
      toast('Location saved');
    }, () => toast('Location not allowed'), { timeout: 15000, maximumAge: 600000 });
  } }, '📍');
  page.appendChild(field('Where', h('div.form-inline', place, gps)));
  const weather = h('div.chips.wrap');
  WEATHER.forEach((w, i) => weather.appendChild(chip(w + ' ' + WEATHER_NAMES[i], { selected: d.weather === w, onclick: (e) => {
    save({ weather: d.weather === w ? '' : w });
    weather.querySelectorAll('.chip').forEach((c, k) => c.classList.toggle('on', WEATHER[k] === d.weather));
  } })));
  const temp = input(d.temp, { placeholder: 'e.g. 21°', style: { maxWidth: '120px' } });
  temp.addEventListener('input', () => save({ temp: temp.value.trim() }));
  page.appendChild(field('Weather', h('div', weather, temp)));
  const area = (key, label, ph, rows) => {
    const t = textarea(d[key], { rows, placeholder: ph });
    t.addEventListener('input', () => save({ [key]: t.value }));
    page.appendChild(field(label, t));
  };
  area('text', 'How was my day?', 'What happened, how you felt…', 7);
  area('highs', 'Highlights', 'Best moments', 2);
  area('lows', 'Lowlights', 'What didn\'t go well', 2);
  page.appendChild(field('Photos', A.strip('diary', uid, { label: 'Photo', onAdded: () => {
    if (!S.get('diary', uid)) S.put('diary', { ...d, uid, day: openDay, modified: Date.now() });
  } })));
  view.appendChild(page);
}

function close() {
  if (flushSave) flushSave();
  openDay = null;
}

function printBook() {
  const entries = S.all('diary').sort((a, b) => (a.day < b.day ? -1 : 1));
  if (!entries.length) { toast('Nothing to export yet'); return; }
  const w = window.open('', '_blank');
  if (!w) { toast('Allow pop-ups to export'); return; }
  const esc = (s) => String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
  w.document.write('<!doctype html><meta charset="utf-8"><title>My Diary</title><style>body{font:15px/1.5 Georgia,serif;max-width:700px;margin:40px auto;padding:0 20px}'
    + 'h2{font-size:18px;border-bottom:1px solid #ccc;margin-top:36px}.m{color:#666}</style><h1>My Diary</h1>'
    + entries.map((e) => `<h2>${esc(dayLabel(e.day))} ${MOODS[e.mood] || ''} ${e.weather}</h2>`
      + (e.place ? `<p class="m">📍 ${esc(e.place)}${e.temp ? ' · ' + esc(e.temp) : ''}</p>` : '')
      + `<p>${esc(e.text).replace(/\n/g, '<br>')}</p>`
      + (e.highs ? `<p><b>Highlights:</b> ${esc(e.highs)}</p>` : '') + (e.lows ? `<p><b>Lowlights:</b> ${esc(e.lows)}</p>` : '')).join(''));
  w.document.close();
  setTimeout(() => w.print(), 400);
}

export default {
  id: 'diary',
  name: 'My Diary',
  icon: 'diary',
  tables: ['diary', 'attachments'],
  get busy() { return !!openDay; },
  title: () => (openDay ? dayLabel(openDay) : 'My Diary'),
  render(view) { if (openDay) editor(view); else list(view); },
  isDeep: () => !!openDay,
  back() { close(); app.refresh(); },
  hide() { if (openDay) close(); },
  open(uid) { if (uid && uid.startsWith('diary:')) { close(); show(uid.slice(6)); } },
  quick() { close(); show(L.today()); },
  menu: () => [{ label: '📖 Export as a book', sub: 'Print or save as PDF', action: printBook }],
};
