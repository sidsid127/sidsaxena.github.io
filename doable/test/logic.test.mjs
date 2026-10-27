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
  [['2', 'milk'], ['500 g', 'flour'], ['10', 'eggs'], ['1,5 kg', 'potatoes'], ['', 'Bananas'], ['', '7up']]);
const shared = L.groceryFormat('Groceries', [{ name: 'Milk', qty: '2', shop: 'Rewe' }, { name: 'Bread', qty: '', shop: 'Rewe' },
  { name: 'Eggs', qty: '10', shop: 'Lidl' }]) + '\n• Butter\n- 3 × Apples';
eq('share roundtrip', L.groceryParse(shared, ['Rewe', 'Lidl', 'Aldi']).map((l) => l.shop + ':' + l.qty + ':' + l.name).join(' '),
  'Rewe:2:Milk Rewe::Bread Lidl:10:Eggs Lidl::Butter Lidl:3:Apples');

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
console.log(`passes=${pass} fails=${fail}`);
process.exit(fail ? 1 : 0);
