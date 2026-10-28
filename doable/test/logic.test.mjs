// Run: node doable/test/logic.test.mjs  — same cases as the Android JVM tests.
import * as L from '../js/logic.js';

let pass = 0, fail = 0;
const eq = (name, got, want) => {
  const g = JSON.stringify(got), w = JSON.stringify(want);
  if (g === w) pass++; else { fail++; console.log('FAIL', name, 'got', g, 'want', w); }
};
const now = new Date(2026, 9, 26, 10, 0); // Monday 26 Oct 2026 10:00
const q = (s) => L.quickAdd(s, now);
let r = q('Call bank tomorrow 9am #work !high');
eq('t1', [r.title, r.due, r.time, r.category, r.priority], ['Call bank', '2026-10-27', 540, 'work', 3]);
r = q('Pay rent every month on 1.11.');
eq('t2', [r.title, r.repeat, r.due], ['Pay rent', 'M1', '2026-11-01']);
r = q('team call every monday at 18:30 p2');
eq('t3', [r.title, r.repeat, r.due, r.time, r.priority], ['team call', 'W1', '2026-10-26', 1110, 2]);
r = q('buy 3 eggs');
eq('t4', [r.title, r.due, r.time, r.priority, r.category, r.repeat], ['buy 3 eggs', '', -1, -1, null, '']);
r = q('dentist friday 2:30pm');
eq('t5', [r.due, r.time, r.title], ['2026-10-30', 870, 'dentist']);
r = q('submit expenses in 3 days !!');
eq('t6', [r.due, r.priority, r.title], ['2026-10-29', 2, 'submit expenses']);
r = q('passport renewal 5 jan');
eq('t7', [r.due, r.title], ['2027-01-05', 'passport renewal']);
r = q('water plants every 3 days');
eq('t8', [r.repeat, r.due], ['D3', '2026-10-26']);
r = q('standup weekdays at 9');
eq('t9', [r.repeat, r.time, r.title], ['WD', 540, 'standup']);
eq('t10', q('call mom at 8am').due, '2026-10-27');
r = q('Report next week #Customer-A');
eq('t11', [r.due, r.category], ['2026-11-02', 'Customer A']);
eq('t12', q('Buy sun cream').title, 'Buy sun cream');
r = q('meeting 2026-12-24 noon');
eq('t13', [r.due, r.time], ['2026-12-24', 720]);
r = q('Call mum tomorrow 6pm !high every week');
eq('t14', [r.title, r.due, r.time, r.priority, r.repeat], ['Call mum', '2026-10-27', 1080, 3, 'W1']);

eq('rec WD', L.nextDue('2026-10-30', 'WD'), '2026-11-02');
eq('rec M1', L.nextDue('2027-01-31', 'M1'), '2027-02-28');
eq('rec W2', L.nextDue('2026-10-26', 'W2'), '2026-11-09');
eq('rec onOrAfter', L.nextOnOrAfter('2026-10-01', 'W1', '2026-10-26'), '2026-10-29');
eq('rec label', L.ruleLabel('W2'), 'Every 2 weeks');
eq('rec Y1 leap', L.nextDue('2028-02-29', 'Y1'), '2029-02-28');

eq('ean check', L.ean13Check('400638133393'), 1);
eq('ean valid', L.ean13('4006381333931') !== null, true);
eq('ean invalid', L.ean13('4006381333932'), null);
eq('ean len', L.ean13('4006381333931').length, 95);
eq('c128 len', L.code128('AB12').length, 11 * 6 + 13);

eq('aisle milk', L.aisle('Milch'), 'Dairy & eggs');
eq('aisle sun cream', L.aisle('Sun cream'), 'Personal care');
eq('aisle ice cream', L.aisle('Vanilla ice cream'), 'Frozen');
eq('aisle unknown', L.aisle('Gift card'), 'Other');
eq('qty', [L.splitQuantity('2 milk'), L.splitQuantity('500g flour'), L.splitQuantity('eggs x10'), L.splitQuantity('1,5 kg potatoes'),
  L.splitQuantity('Bananas'), L.splitQuantity('7up')],
  [['2', 'milk'], ['500 g', 'flour'], ['10', 'eggs'], ['1.5 kg', 'potatoes'], ['', 'Bananas'], ['', '7up']]);
const shared = L.groceryFormat('Groceries', [{ name: 'Milk', qty: '2', shop: 'Rewe' }, { name: 'Bread', qty: '', shop: 'Rewe' },
  { name: 'Eggs', qty: '10', shop: 'Lidl' }]) + '\n• Butter\n- 3 × Apples';
eq('share roundtrip', L.groceryParse(shared, ['Rewe', 'Lidl', 'Aldi']).map((l) => l.shop + ':' + l.qty + ':' + l.name).join(' '),
  'Rewe:2:Milk Rewe::Bread Lidl:10:Eggs Lidl::Butter Lidl:3:Apples');

// Grocery quantities and units (same cases as the Android LogicTest)
eq('qty units', ['Milk × 2 l', 'milk x2', '2 kilos apples', '3 Packungen Nudeln', 'Cola 0.5 l', 'Milk 1.5%', '2x eggs'].map(L.splitQuantity),
  [['2 l', 'Milk'], ['2', 'milk'], ['2 kg', 'apples'], ['3 pack', 'Nudeln'], ['0.5 l', 'Cola'], ['', 'Milk 1.5%'], ['2', 'eggs']]);
eq('default qty', ['Milch', 'Eggs', 'Kartoffeln', 'Toilet paper', 'Red wine', 'Bread'].map(L.defaultQty),
  ['1 l', '10 pcs', '1 kg', '1 pack', '1 bottle', '1 pcs']);
eq('step qty', [L.stepQty('1 kg', 1), L.stepQty('1 kg', -1), L.stepQty('0.5 kg', -1), L.stepQty('500 g', 1), L.stepQty('2 pcs', -1), L.stepQty('1 pack', -1)],
  ['1.5 kg', '0.5 kg', '0.5 kg', '600 g', '1 pcs', '1 pack']);
eq('with unit', [L.withUnit('2 kg', 'pack'), L.withUnit('', 'l')], ['2 pack', '1 l']);
eq('unit price', [L.unitPrice(129, '1 kg', 'EUR'), L.unitPrice(25, '1 pcs', 'EUR'), L.unitPrice(300, '2 kg', 'EUR')],
  ['1.29 EUR / kg', '0.25 EUR / piece', '3.00 EUR for 2 kg']);
eq('share new format', L.groceryFormat('G', [{ name: 'Milk', qty: '2 l', shop: '' }]).split('\n')[3], '• Milk × 2 l');
eq('parse both orders', L.groceryParse('• Milk × 2 l\n- 3 × Apples\n• Eggs × 10', []).map((l) => l.qty + ':' + l.name).join(' '), '2 l:Milk 3:Apples 10:Eggs');

const rates = L.parseRates('INR=0.0108;USD=0.92');
eq('conv', [L.toHome(100000, 'INR', 'EUR', rates), L.toHome(500, 'eur', 'EUR', rates), L.toHome(500, 'GBP', 'EUR', rates)], [1080, 500, -1]);
eq('perdiem', [L.perDiemDays('2026-10-05', '2026-10-05'), L.perDiemDays('2026-10-05', '2026-10-08')], [[0, 1], [2, 2]]);
eq('amount', [L.parseAmount('12,50'), L.parseAmount('1.234,50'), L.parseAmount('1,234.50'), L.parseAmount('abc')], [1250, 123450, 123450, -1]);

const log = {};
for (let i = 1; i <= 3; i++) log[L.addDays('2026-10-26', -i)] = 1;
for (const d of ['2026-10-20', '2026-10-19', '2026-10-18', '2026-10-17']) log[d] = 1;
eq('habit streak', L.streaks(false, 1, 127, log, '2026-10-26', '2026-01-01'), [3, 4]);
log['2026-10-26'] = 1;
eq('habit streak today', L.streaks(false, 1, 127, log, '2026-10-26', '2026-01-01'), [4, 4]);
eq('quit streak', L.streaks(true, 1, 127, { '2026-10-20': 1 }, '2026-10-26', '2026-10-10'), [6, 10]);
eq('times', L.reminderSpec(1, '8, 20:30; 12.15 99:00', '', '', ''), '08:00,20:30,12:15');
eq('every', L.reminderSpec(2, '', '1,5', '8', '20:00'), 'every:90:08:00-20:00');
eq('every bad', L.reminderSpec(2, '', '2', '20:00', '08:00'), '');
eq('habitTimes', L.habitTimes('every:120:08:00-12:00'), [480, 600, 720]);

const pasted = 'Packing:\r\n- Passport\n• Charger\n1. Socks\n2) socks.\n☑ Tickets\n[x] Wallet\n- [ ] Keys\n\n';
eq('paste', L.checklistParse(pasted).map((l) => (l.done ? '+' : '') + l.text).join('|'), 'Packing:|Passport|Charger|Socks|+Tickets|+Wallet|Keys');
eq('heading', [L.checklistHeading(pasted), L.checklistHeading('Milk\nEggs')], ['Packing', null]);

eq('csv', L.csvParse('a;b\n"x;y";"q""uote"\n'), [['a', 'b'], ['x;y', 'q"uote']]);
// Credentials search
const creds = [
  { title: 'Gmail', folder: 'Private', user: 'sid@gmail.com', url: 'mail.google.com', password: 'demo-secret', notes: '', type: 'Login' },
  { title: 'Salesforce demo org', folder: 'Demo lab', user: 'admin@demo.example', url: 'login.salesforce.com', notes: '', type: 'Login' },
  { title: 'ServiceNow', folder: 'Demo lab', user: 'sid@gmail.com', url: 'dev123.service-now.com', notes: '', type: 'Login' },
  { title: 'Netflix', folder: '', user: 'sid@gmail.com', url: 'netflix.com', notes: 'family plan', type: 'Login' },
  { title: 'Home Wi-Fi', folder: 'Private', user: '', url: '', notes: '', type: 'Wi-Fi' },
  { title: 'Café login', folder: 'Private', user: 'x', url: '', notes: '', type: 'Login' },
];
const names = (q) => L.searchCredentials(creds, q).map((c) => c.title);
eq('cs demo: name first, then folder; never the password', names('demo'), ['Salesforce demo org', 'ServiceNow']);
eq('cs gmail: name, then everything with a gmail login', names('gmail'), ['Gmail', 'Netflix', 'ServiceNow']);
eq('cs two words narrow down', names('gmail demo'), ['ServiceNow']);
eq('cs folder + type', names('private wi-fi'), ['Home Wi-Fi']);
eq('cs notes', names('family'), ['Netflix']);
eq('cs accents ignored', names('cafe'), ['Café login']);
eq('cs case', names('NETFLIX'), ['Netflix']);
eq('cs empty = all', L.searchCredentials(creds, '  ').length, 6);
eq('cs no match', names('zzz'), []);
eq('cs folders', L.credentialFolders(creds), ['Demo lab', 'Private']);
// Shared expenses
const ex = [
  { cents: 9000, paid_by: 'a', shares: '{"a":1,"b":1,"c":1}', currency: 'EUR' },   // dinner 90 €, three ways
  { cents: 1000, paid_by: 'b', shares: '{"a":1,"b":1,"c":1}', currency: 'EUR' },   // 10 € → 3.34 / 3.33 / 3.33
  { cents: 2000, paid_by: 'c', shares: '{"a":2,"c":1}', currency: 'EUR' },         // 20 € weighted 2:1
  { cents: 5000, paid_by: 'b', to_user: 'a', kind: 'settle', currency: 'EUR' },   // b pays a back 50 €
  { cents: 1200, paid_by: 'a', shares: '{"b":1}', currency: 'CHF' },
  { cents: 0, paid_by: 'a', shares: '{"a":1}' }, { cents: 500, paid_by: '', shares: '{"a":1}' }, { cents: 700, paid_by: 'a', shares: '' },
];
const bal = L.splitBalances(ex);
eq('split EUR', bal.EUR, { a: 9000 - 3000 - 334 - 1334 - 5000, b: 1000 - 3000 - 333 + 5000, c: 2000 - 3000 - 333 - 666 });
eq('split sums to zero', Object.values(bal.EUR).reduce((n, c) => n + c, 0), 0);
eq('split other currency kept apart', bal.CHF, { a: 1200, b: -1200 });
eq('settle up', L.settleUp(bal.EUR), [{ from: 'c', to: 'b', cents: 1999 }, { from: 'a', to: 'b', cents: 668 }]);
eq('settle up two', L.settleUp({ a: 500, b: -300, c: -200 }), [{ from: 'b', to: 'a', cents: 300 }, { from: 'c', to: 'a', cents: 200 }]);
eq('settle nothing', L.settleUp({}), []);
eq('shares parse', [L.splitShares('{"a":1,"b":0,"c":"2"}'), L.splitShares('junk')], [{ a: 1, c: 2 }, {}]);
console.log(`passes=${pass} fails=${fail}`);
process.exit(fail ? 1 : 0);
