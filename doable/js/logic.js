// Pure logic shared in spirit with the Android app (QuickAdd, Recurrence, HabitLogic, TripMath, GroceryText,
// ChecklistText, Barcode, Csv). Days are "YYYY-MM-DD" strings in local time; "" = none.

// ------------------------------------------------------------------ days

export function dayOf(date) {
  return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0');
}
export const today = () => dayOf(new Date());
export function toDate(day) {
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d);
}
export function addDays(day, n) {
  const d = toDate(day);
  d.setDate(d.getDate() + n);
  return dayOf(d);
}
/** Adds months keeping the day where possible (31 Jan + 1 month = 28/29 Feb), like java.util.Calendar. */
export function addMonths(day, n) {
  const d = toDate(day);
  const want = d.getDate();
  d.setDate(1);
  d.setMonth(d.getMonth() + n);
  d.setDate(Math.min(want, daysInMonth(d.getFullYear(), d.getMonth())));
  return dayOf(d);
}
export const daysInMonth = (y, m0) => new Date(y, m0 + 1, 0).getDate();
export const weekday = (day) => toDate(day).getDay(); // 0 = Sunday
/** Whole days from a to b (b - a). */
export function daysBetween(a, b) {
  return Math.round((toDate(b) - toDate(a)) / 86400000);
}
export const daysFromToday = (day) => daysBetween(today(), day);
export function dateOf(y, m, d) {
  return dayOf(new Date(y, m - 1, Math.min(d, daysInMonth(y, m - 1))));
}

// ------------------------------------------------------------------ recurrence

export const RULES = ['', 'D1', 'WD', 'W1', 'W2', 'M1', 'Y1'];
export const RULE_LABELS = ['Does not repeat', 'Every day', 'Every weekday (Mon–Fri)', 'Every week', 'Every 2 weeks', 'Every month', 'Every year'];

const interval = (rule) => Math.max(1, parseInt(rule.slice(1), 10) || 1);

export function ruleLabel(rule) {
  if (!rule) return '';
  if (rule === 'WD') return 'Every weekday';
  const n = interval(rule);
  const unit = { D: 'day', W: 'week', M: 'month' }[rule[0]] || 'year';
  return n === 1 ? 'Every ' + unit : `Every ${n} ${unit}s`;
}

export function nextDue(due, rule) {
  if (rule === 'WD') {
    let d = due;
    do d = addDays(d, 1); while (weekday(d) === 0 || weekday(d) === 6);
    return d;
  }
  const n = interval(rule);
  switch (rule[0]) {
    case 'D': return addDays(due, n);
    case 'W': return addDays(due, 7 * n);
    case 'M': return addMonths(due, n);
    default: return addMonths(due, 12 * n);
  }
}

export function nextOnOrAfter(due, rule, notBefore) {
  let d = nextDue(due, rule);
  for (let g = 0; d < notBefore && g < 4000; g++) d = nextDue(d, rule);
  return d;
}

// ------------------------------------------------------------------ quick add

const DAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
const DAY_RE = '(sun(?:day)?|mon(?:day)?|tue(?:s|sday)?|wed(?:nesday)?|thu(?:rs|rsday)?|fri(?:day)?|sat(?:urday)?)';
const DAY_FULL_RE = '(sunday|monday|tuesday|wednesday|thursday|friday|saturday)';
const MONTH_RE = '(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)';

const dayIndex = (w) => Math.max(0, DAYS.findIndex((d) => d.startsWith(w.toLowerCase().slice(0, 3))));
const monthIndex = (w) => MONTHS.indexOf(w.toLowerCase().slice(0, 3)) + 1 || 1;
const number = (w) => ({ a: 1, an: 1, one: 1, two: 2, three: 3 }[w.toLowerCase()] || parseInt(w, 10));

/** Next date on the weekday (0 = Sunday); today counts if allowToday. */
export function nextWeekday(from, wd, allowToday) {
  let d = allowToday ? from : addDays(from, 1);
  while (weekday(d) !== wd) d = addDays(d, 1);
  return d;
}

function absDate(todayDay, year, month, day) {
  const t = toDate(todayDay);
  if (year > 0) return dateOf(year, month, day);
  let d = dateOf(t.getFullYear(), month, day);
  if (d < todayDay) d = dateOf(t.getFullYear() + 1, month, day);
  return d;
}

/**
 * Reads a task typed in plain language, e.g. "Call bank tomorrow 9am #work !high every monday".
 * Returns {title, due, time (-1), priority (-1), category (null), repeat}.
 */
export function quickAdd(input, now = new Date()) {
  const r = { title: '', due: '', time: -1, priority: -1, category: null, repeat: '' };
  let s = ' ' + input.trim() + ' ';
  const t0 = dayOf(now);
  const find = (re, flags = 'iu') => new RegExp(re, flags).exec(s);
  const cut = (m) => { s = s.slice(0, m.index) + ' ' + s.slice(m.index + m[0].length); };
  let m;

  if ((m = find('\\s(!{1,3}|!(?:high|h|medium|med|m|low|l|[0-3])|p[1-3])(?=\\s)'))) {
    const p = m[1].toLowerCase();
    if (/^(!!!|!high|!h|!3|p1)$/.test(p)) r.priority = 3;
    else if (/^(!!|!medium|!med|!m|!2|p2)$/.test(p)) r.priority = 2;
    else if (p === '!0') r.priority = 0;
    else r.priority = 1;
    cut(m);
  }
  if ((m = find('\\s#([\\p{L}\\p{N}_\\-/]+)(?=\\s)', 'u'))) {
    r.category = m[1].replace(/-/g, ' ').replace(/_/g, ' ');
    cut(m);
  }
  if ((m = find('\\s(every\\s+(\\d+)\\s+(day|week|month|year)s?|every\\s+(day|weekday|week|month|year)|every\\s+' + DAY_RE
      + '|daily|weekly|monthly|yearly|annually|weekdays)(?=\\s)'))) {
    const w = m[1].toLowerCase();
    if (m[2]) r.repeat = m[3][0].toUpperCase() + parseInt(m[2], 10);
    else if (m[5]) { r.repeat = 'W1'; r.due = nextWeekday(t0, dayIndex(m[5]), true); }
    else if (w.includes('weekday')) r.repeat = 'WD';
    else if (w.includes('day') || w === 'daily') r.repeat = 'D1';
    else if (w.includes('week')) r.repeat = 'W1';
    else if (w.includes('month')) r.repeat = 'M1';
    else r.repeat = 'Y1';
    cut(m);
  }

  if ((m = find('\\s(the\\s+)?day\\s+after\\s+tomorrow(?=\\s)'))) { r.due = addDays(t0, 2); cut(m); }
  else if ((m = find('\\sin\\s+(\\d+|a|an|one|two|three)\\s+(day|week|month)s?(?=\\s)'))) {
    const n = number(m[1]); const unit = m[2].toLowerCase();
    r.due = unit === 'day' ? addDays(t0, n) : unit === 'week' ? addDays(t0, 7 * n) : addMonths(t0, n);
    cut(m);
  } else if ((m = find('\\snext\\s+week(?=\\s)'))) { r.due = nextWeekday(t0, 1, false); cut(m); }
  else if ((m = find('\\snext\\s+month(?=\\s)'))) { r.due = addMonths(t0.slice(0, 8) + '01', 1); cut(m); }
  else if ((m = find('\\s(?:on\\s+|next\\s+)' + DAY_RE + '(?=\\s)'))) {
    const next = m[0].toLowerCase().includes('next');
    r.due = nextWeekday(t0, dayIndex(m[1]), !next);
    cut(m);
  } else if ((m = find('\\s' + DAY_FULL_RE + '(?=\\s)'))) { r.due = nextWeekday(t0, dayIndex(m[1]), true); cut(m); }

  if ((m = find('\\s(?:on\\s+)?(\\d{4})-(\\d{1,2})-(\\d{1,2})(?=\\s)'))) {
    r.due = dateOf(+m[1], +m[2], +m[3]); cut(m);
  } else if ((m = find('\\s(?:on\\s+)?(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?\\.?(?=\\s)')) && +m[1] >= 1 && +m[1] <= 31 && +m[2] >= 1 && +m[2] <= 12) {
    const y = m[3] ? (+m[3] < 100 ? 2000 + +m[3] : +m[3]) : -1;
    r.due = absDate(t0, y, +m[2], +m[1]); cut(m);
  } else if ((m = find('\\s(?:on\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?' + MONTH_RE + '(?:\\s+(\\d{4}))?(?=\\s)'))) {
    r.due = absDate(t0, m[3] ? +m[3] : -1, monthIndex(m[2]), +m[1]); cut(m);
  } else if ((m = find('\\s(?:on\\s+)?' + MONTH_RE + '\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+(\\d{4}))?(?=\\s)'))) {
    r.due = absDate(t0, m[3] ? +m[3] : -1, monthIndex(m[1]), +m[2]); cut(m);
  }

  if ((m = find('\\s(today|tonight|tomorrow|tmrw|tmr)(?=\\s)'))) {
    const w = m[1].toLowerCase();
    r.due = w.startsWith('tod') || w === 'tonight' ? t0 : addDays(t0, 1);
    if (w === 'tonight') r.time = 20 * 60;
    cut(m);
  }

  if ((m = find('\\s(?:at\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s?(am|pm)(?=\\s)'))) {
    let h = +m[1] % 12; const min = m[2] ? +m[2] : 0;
    if (m[3].toLowerCase() === 'pm') h += 12;
    if (h < 24 && min < 60) { r.time = h * 60 + min; cut(m); }
  } else if ((m = find('\\s(?:at\\s+)?([01]?\\d|2[0-3])[:](\\d{2})(?:\\s?(?:h|uhr))?(?=\\s)'))) {
    r.time = +m[1] * 60 + +m[2]; cut(m);
  } else if ((m = find('\\sat\\s+([01]?\\d|2[0-3])(?:[.](\\d{2}))?(?:\\s?(?:h|uhr))?(?=\\s)'))) {
    r.time = +m[1] * 60 + (m[2] ? +m[2] : 0); cut(m);
  } else if ((m = find('\\s(?:at\\s+)?noon(?=\\s)'))) { r.time = 720; cut(m); }

  if (r.time >= 0 && !r.due) {
    const nowMin = now.getHours() * 60 + now.getMinutes();
    r.due = r.time > nowMin ? t0 : addDays(t0, 1);
  }
  if (r.repeat && !r.due) r.due = t0;
  r.title = s.replace(/\s+/g, ' ').trim() || input.trim();
  return r;
}

export const clock = (minutes) => String(Math.floor(minutes / 60)).padStart(2, '0') + ':' + String(minutes % 60).padStart(2, '0');

// ------------------------------------------------------------------ habits

/** Days mask: bit 0 = Sunday … bit 6 = Saturday. */
export const scheduled = (mask, day) => (mask & (1 << weekday(day))) !== 0;
export function habitSuccess(quit, target, count) {
  const n = count || 0;
  return quit ? n === 0 : n >= Math.max(1, target);
}
/** {current, best} streaks; log maps day → count. */
export function streaks(quit, target, mask, log, todayDay, since) {
  let current = 0, best = 0, run = 0, currentDone = false, first = true;
  let d = todayDay;
  for (let i = 0; i < 3660; i++) {
    if (since && d < since) break;
    if (scheduled(mask, d)) {
      const ok = habitSuccess(quit, target, log[d]);
      if (first && d === todayDay && !ok && !quit) { /* today still open */ }
      else if (ok) run++;
      else {
        if (!currentDone) { current = run; currentDone = true; }
        best = Math.max(best, run);
        run = 0;
      }
      first = false;
    }
    d = addDays(d, -1);
  }
  if (!currentDone) current = run;
  best = Math.max(best, run, current);
  return [current, best];
}

export function parseTime(s) {
  const p = s.trim().replace('.', ':').split(':');
  const h = parseInt(p[0], 10), m = p.length > 1 ? parseInt(p[1], 10) : 0;
  if (!/^\d{1,2}(:\d{1,2})?$/.test(s.trim().replace('.', ':')) || h > 23 || m > 59) return -1;
  return h * 60 + m;
}
/** "08:00,20:00" or "every:120:08:00-20:00" → minutes after midnight. */
export function habitTimes(spec) {
  const out = [];
  if (!spec) return out;
  try {
    if (spec.startsWith('every:')) {
      const [, step, range] = spec.match(/^every:(\d+):(.+)$/);
      const [a, b] = range.split('-').map(parseTime);
      for (let m = a; m <= b; m += Math.max(15, +step)) out.push(m);
    } else {
      for (const t of spec.split(',')) if (t.trim()) out.push(parseTime(t));
    }
  } catch (e) { /* ignore */ }
  return out.filter((m) => m >= 0);
}
export function reminderSpec(mode, times, hours, from, to) {
  if (mode === 1) {
    return times.split(/[,; ]+/).filter((t) => t.trim()).map(parseTime).filter((m) => m >= 0).map(clock).join(',');
  }
  if (mode === 2) {
    const step = Math.round(parseFloat(String(hours).replace(',', '.')) * 60);
    const a = parseTime(from), b = parseTime(to);
    if (!(step >= 15) || a < 0 || b < a) return '';
    return `every:${step}:${clock(a)}-${clock(b)}`;
  }
  return '';
}

// ------------------------------------------------------------------ trips

export function parseRates(s) {
  const out = {};
  for (const part of (s || '').split(';')) {
    const kv = part.split('=');
    if (kv.length !== 2) continue;
    const v = parseFloat(kv[1]);
    if (!isNaN(v)) out[kv[0].trim().toUpperCase()] = v;
  }
  return out;
}
export const ratesString = (rates) => Object.entries(rates).map(([k, v]) => k + '=' + v).join(';');
/** Cents in the home currency, or -1 when no rate is known. */
export function toHome(cents, currency, home, rates) {
  if (currency.toUpperCase() === home.toUpperCase()) return cents;
  const r = rates[currency.toUpperCase()];
  return r > 0 ? Math.round(cents * r) : -1;
}
/** [fullDays, partialDays] German style. */
export function perDiemDays(start, finish) {
  if (!start) return [0, 0];
  if (!finish || finish < start) finish = start;
  const days = daysBetween(start, finish) + 1;
  return days === 1 ? [0, 1] : [days - 2, 2];
}
/** "12.50", "12,50", "1.234,50", "1,234.50" → cents, or -1. */
export function parseAmount(s) {
  s = String(s).replace(/[ €$£₹]/g, '').trim();
  if (!s) return -1;
  const comma = s.lastIndexOf(','), dot = s.lastIndexOf('.');
  if (comma >= 0 && dot >= 0) s = comma > dot ? s.replace(/\./g, '').replace(',', '.') : s.replace(/,/g, '');
  else if (comma >= 0) s = s.replace(',', '.');
  if (!/^-?\d*\.?\d+$/.test(s)) return -1;
  return Math.round(parseFloat(s) * 100);
}
export const money = (cents, currency) =>
  (cents / 100).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' ' + currency;

// ------------------------------------------------------------------ groceries

export const AISLES = ['Fruit & veg', 'Bakery', 'Dairy & eggs', 'Meat & fish', 'Frozen', 'Pantry', 'Snacks & sweets', 'Drinks',
  'Household', 'Personal care', 'Baby & pets', 'Other'];
const KEYWORDS = [
  ['apple', 'banana', 'orange', 'lemon', 'lime', 'grape', 'berr', 'tomato', 'potato', 'onion', 'garlic', 'carrot', 'salad', 'lettuce',
    'cucumber', 'pepper', 'paprika', 'avocado', 'mushroom', 'spinach', 'broccoli', 'zucchini', 'herb', 'basil', 'parsley', 'ginger',
    'fruit', 'veg', 'apfel', 'äpfel', 'banane', 'zitrone', 'traube', 'tomate', 'kartoffel', 'zwiebel', 'knoblauch', 'karotte', 'möhre',
    'gurke', 'pilz', 'champignon', 'spinat', 'obst', 'gemüse', 'mango', 'kiwi', 'melon', 'pear', 'birne', 'erdbeer', 'strawberr', 'kohl'],
  ['bread', 'bun', 'roll', 'toast', 'baguette', 'croissant', 'bagel', 'brot', 'brötchen', 'semmel', 'laugen', 'brezel', 'pretzel', 'cake', 'kuchen'],
  ['milk', 'cheese', 'yog', 'butter', 'cream', 'egg', 'quark', 'milch', 'käse', 'joghurt', 'sahne', 'eier', 'ei ', 'skyr', 'feta',
    'mozzarella', 'parmesan', 'gouda', 'frischkäse', 'schmand', 'kefir', 'margarine'],
  ['chicken', 'beef', 'pork', 'mince', 'steak', 'sausage', 'ham', 'bacon', 'salami', 'fish', 'salmon', 'tuna', 'shrimp', 'hähnchen',
    'huhn', 'rind', 'schwein', 'hack', 'wurst', 'schinken', 'speck', 'fisch', 'lachs', 'thunfisch', 'garnele', 'aufschnitt', 'turkey', 'pute'],
  ['frozen', 'ice cream', 'frozen pizza', 'pizza', 'tiefkühl', 'eis', 'fries', 'pommes'],
  ['rice', 'pasta', 'noodle', 'flour', 'sugar', 'salt', 'oil', 'vinegar', 'sauce', 'ketchup', 'mustard', 'mayo', 'cereal', 'muesli',
    'müsli', 'oat', 'hafer', 'honey', 'honig', 'jam', 'marmelade', 'coffee', 'kaffee', 'tea', 'tee', 'spice', 'gewürz', 'canned', 'dose',
    'bean', 'bohne', 'lentil', 'linse', 'reis', 'nudel', 'mehl', 'zucker', 'salz', 'öl', 'essig', 'soße', 'senf', 'stock', 'brühe', 'nut',
    'nuss', 'spaghetti'],
  ['chocolate', 'chips', 'crisps', 'cookie', 'biscuit', 'candy', 'sweet', 'snack', 'schokolade', 'keks', 'süß', 'gummi', 'popcorn', 'cracker'],
  ['water', 'juice', 'beer', 'wine', 'soda', 'cola', 'coke', 'lemonade', 'wasser', 'saft', 'bier', 'wein', 'sprudel', 'limo', 'drink',
    'getränk', 'sekt', 'prosecco'],
  ['toilet', 'paper', 'tissue', 'detergent', 'soap', 'dish', 'sponge', 'trash', 'bag', 'foil', 'battery', 'bulb', 'clean', 'spül', 'wasch',
    'klopapier', 'toilettenpapier', 'küchenrolle', 'taschentuch', 'müllbeutel', 'folie', 'batterie', 'reiniger', 'schwamm', 'tücher'],
  ['sun cream', 'hand cream', 'face cream', 'body lotion', 'shampoo', 'conditioner', 'toothpaste', 'toothbrush', 'deo', 'razor', 'lotion',
    'cream ', 'sunscreen', 'zahnpasta', 'zahnbürste', 'duschgel', 'shower', 'rasier', 'sonnencreme', 'pflaster', 'plaster', 'medic',
    'tablet', 'vitamin', 'make-up', 'makeup', 'tampon', 'binde', 'pad'],
  ['diaper', 'nappy', 'windel', 'baby', 'cat', 'dog', 'katze', 'hund', 'pet', 'futter'],
];
export function aisle(name) {
  const n = ' ' + name.toLowerCase() + ' ';
  for (let pass = 0; pass < 2; pass++) {
    for (let i = 0; i < KEYWORDS.length; i++) {
      for (const k of KEYWORDS[i]) {
        if (k.trim().includes(' ') === (pass === 0) && n.includes(' ' + k)) return AISLES[i];
      }
    }
  }
  return AISLES[AISLES.length - 1];
}
export const aisleIndex = (a) => (AISLES.indexOf(a) < 0 ? AISLES.length - 1 : AISLES.indexOf(a));

/** [qty, name]: "2 milk", "500g flour", "1,5 kg potatoes", "milk x2", "2x eggs", "eggs (10)". */
export function splitQuantity(input) {
  const s = input.trim();
  let m = /^(\d+(?:[.,]\d+)?)\s*(x|×|kg|g|l|ml|pack|packs|pcs|stk|st\.?|dozen|bottles?|cans?|bags?)?\s+(.+)$/i.exec(s);
  if (m) return [!m[2] || /^(x|×)$/i.test(m[2]) ? m[1] : m[1] + ' ' + m[2].toLowerCase(), m[3].trim()];
  m = /^(\d+)\s*[x×](\S.*)$/i.exec(s);
  if (m) return [m[1], m[2].trim()];
  m = /^(.+?)\s+[x×]\s*(\d+)$|^(.+?)\s*\((\d+(?:[.,]\d+)?\s*\w*)\)$/i.exec(s);
  if (m) return m[1] ? [m[2], m[1].trim()] : [m[4].trim(), m[3].trim()];
  return ['', s];
}
/** lines: [{name, qty, shop}] grouped by shop. */
export function groceryFormat(listName, lines) {
  let out = '🛒 ' + listName + '\n';
  let shop = null;
  for (const l of lines) {
    if (l.shop !== shop) { out += '\n' + (l.shop || 'Any shop') + '\n'; shop = l.shop; }
    out += '• ' + (l.qty ? l.qty + ' × ' : '') + l.name + '\n';
  }
  return out + '\n(Edit and share back to Doable to update the list)';
}
export function groceryParse(text, shopNames) {
  const out = [];
  let shop = '';
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('🛒') || line.startsWith('(')) continue;
    const bullet = /^([•\-*·☐☑✓✔]|\[[ xX]?\])\s*/.test(line);
    const clean = line.replace(/^([•\-*·☐☑✓✔]|\[[ xX]?\])\s*/, '').trim();
    if (!bullet) {
      const match = shopNames.find((s) => s.toLowerCase() === clean.toLowerCase());
      if (match || clean.toLowerCase() === 'any shop') { shop = match || ''; continue; }
    }
    if (!clean) continue;
    const m = /^(.+?)\s*×\s*(.+)$/.exec(clean);
    const [qty, name] = m ? [m[1].trim(), m[2].trim()] : splitQuantity(clean);
    out.push({ name, qty, shop });
  }
  return out;
}

// ------------------------------------------------------------------ checklists

/** Pasted text → [{text, done}]; markers removed, ☑/[x] kept ticked, duplicates skipped. */
export function checklistParse(pasted) {
  const out = [];
  const seen = new Set();
  for (const raw of pasted.replace(/\r/g, '\n').split('\n')) {
    let l = raw.trim();
    const done = /^(☑|✅|✔)/u.test(l) || /^[-*]?\s*\[[x✓]\]/iu.test(l);
    l = l.replace(/^(?:[-•*·–—>]|☐|☑|✅|✔|□|\[[ x✓]?\]|\d{1,3}[.)])+\s*/iu, '').trim();
    if (l.startsWith('[ ]') || l.startsWith('[]')) l = l.replace(/^\[ ?\]\s*/, '');
    if (!l || seen.has(checkKey(l))) continue;
    seen.add(checkKey(l));
    out.push({ text: l, done });
  }
  return out;
}
export function checklistHeading(pasted) {
  for (const raw of pasted.replace(/\r/g, '\n').split('\n')) {
    const l = raw.trim();
    if (!l) continue;
    return l.endsWith(':') && l.length > 1 ? l.slice(0, -1).trim() : null;
  }
  return null;
}
export const checkKey = (t) => t.toLowerCase().replace(/\s+/g, ' ').replace(/[.,;:!]+$/, '').trim();

// ------------------------------------------------------------------ barcodes

const C128 = [0x6cc, 0x66c, 0x666, 0x498, 0x48c, 0x44c, 0x4c8, 0x4c4, 0x464, 0x648, 0x644, 0x624, 0x59c, 0x4dc, 0x4ce,
  0x5cc, 0x4ec, 0x4e6, 0x672, 0x65c, 0x64e, 0x6e4, 0x674, 0x76e, 0x74c, 0x72c, 0x726, 0x764, 0x734, 0x732,
  0x6d8, 0x6c6, 0x636, 0x518, 0x458, 0x446, 0x588, 0x468, 0x462, 0x688, 0x628, 0x622, 0x5b8, 0x58e, 0x46e,
  0x5d8, 0x5c6, 0x476, 0x776, 0x68e, 0x62e, 0x6e8, 0x6e2, 0x6ee, 0x758, 0x746, 0x716, 0x768, 0x762, 0x71a,
  0x77a, 0x642, 0x78a, 0x530, 0x50c, 0x4b0, 0x486, 0x42c, 0x426, 0x590, 0x584, 0x4d0, 0x4c2, 0x434, 0x432,
  0x612, 0x650, 0x7ba, 0x614, 0x47a, 0x53c, 0x4bc, 0x49e, 0x5e4, 0x4f4, 0x4f2, 0x7a4, 0x794, 0x792, 0x6de,
  0x6f6, 0x7b6, 0x578, 0x51e, 0x45e, 0x5e8, 0x5e2, 0x7a8, 0x7a2, 0x5de, 0x5ee, 0x75e, 0x7ae, 0x684, 0x690, 0x69c];
const bits = (v, w) => v.toString(2).padStart(w, '0');
/** Code 128 (set C for an even number of digits, else set B) as a "1010…" module string, or null. */
export function code128(text) {
  if (!text) return null;
  const digits = /^\d+$/.test(text) && text.length % 2 === 0;
  const codes = [digits ? 105 : 104];
  if (digits) for (let i = 0; i < text.length; i += 2) codes.push(+text.slice(i, i + 2));
  else for (const ch of text) { const c = ch.charCodeAt(0); if (c < 32 || c > 126) return null; codes.push(c - 32); }
  let sum = codes[0];
  for (let i = 1; i < codes.length; i++) sum += codes[i] * i;
  return codes.map((c) => bits(C128[c], 11)).join('') + bits(C128[sum % 103], 11) + bits(0x18eb, 13);
}
const L = ['0001101', '0011001', '0010011', '0111101', '0100011', '0110001', '0101111', '0111011', '0110111', '0001011'];
const G = ['0100111', '0110011', '0011011', '0100001', '0011101', '0111001', '0000101', '0010001', '0001001', '0010111'];
const R = ['1110010', '1100110', '1101100', '1000010', '1011100', '1001110', '1010000', '1000100', '1001000', '1110100'];
const PARITY = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG', 'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];
export function ean13Check(twelve) {
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += +twelve[i] * (i % 2 === 0 ? 1 : 3);
  return (10 - (sum % 10)) % 10;
}
export function ean13(text) {
  if (!/^\d{12,13}$/.test(text)) return null;
  const check = ean13Check(text.slice(0, 12));
  if (text.length === 13 && +text[12] !== check) return null;
  const d = text.slice(0, 12) + check;
  let b = '101';
  const parity = PARITY[+d[0]];
  for (let i = 1; i <= 6; i++) b += (parity[i - 1] === 'L' ? L : G)[+d[i]];
  b += '01010';
  for (let i = 7; i <= 12; i++) b += R[+d[i]];
  return b + '101';
}
/** SVG markup for a barcode, or ''. */
export function barcodeSvg(code, height = 80) {
  const m = ean13(code) || code128(code);
  if (!m) return '';
  const q = 10, w = m.length + 2 * q;
  let rects = '';
  for (let i = 0; i < m.length; i++) if (m[i] === '1') rects += `<rect x="${i + q}" y="0" width="1" height="${height}"/>`;
  return `<svg viewBox="0 0 ${w} ${height}" preserveAspectRatio="none" class="barcode"><rect width="${w}" height="${height}" fill="#fff"/><g fill="#000">${rects}</g></svg>`;
}

// ------------------------------------------------------------------ csv

export function csvParse(text) {
  if (text.startsWith('﻿')) text = text.slice(1);
  const first = text.split('\n')[0];
  const sep = (first.match(/;/g) || []).length > (first.match(/,/g) || []).length ? ';' : ',';
  const rows = [];
  let row = [], cell = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') { if (text[i + 1] === '"') { cell += '"'; i++; } else quoted = false; } else cell += c;
    } else if (c === '"') quoted = true;
    else if (c === sep) { row.push(cell); cell = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(cell); cell = '';
      if (!(row.length === 1 && row[0] === '')) rows.push(row);
      row = [];
    } else cell += c;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  return rows;
}
export function csvCell(s, sep) {
  s = s == null ? '' : String(s);
  return s.includes(sep) || /["\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}
export const csvRow = (sep, cells) => cells.map((c) => csvCell(c, sep)).join(sep) + '\r\n';
