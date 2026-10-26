'use strict';
/* Grocery list grouped by shop, with a "Buy again" history of everything added before. */

const shop = (id) => state.shops.find((s) => s.id === id);
const shopName = (id) => (shop(id) ? shop(id).name : 'Any shop');
const shopColor = (id) => (shop(id) ? shop(id).color : 'var(--muted)');

const Grocery = {
  id: 'grocery',
  name: 'Groceries',
  icon: 'cart',
  title: () => 'Groceries',

  /** Adds an item to the list and remembers it (and its shop) for "Buy again". */
  add(name, shopId) {
    const now = Date.now();
    state.items.push({ id: newId(), name, shop: shopId, done: false, created: now });
    const u = state.usuals.find((x) => x.name.toLowerCase() === name.toLowerCase());
    if (u) { u.shop = shopId; u.uses++; u.last = now; } else state.usuals.push({ name, shop: shopId, uses: 1, last: now });
    save();
  },

  render() {
    const s = state.settings;
    if (s.selShop && !shop(s.selShop)) s.selShop = 0;
    let chips = chipHtml('Any shop', 'var(--primary)', !s.selShop, 'data-shop="0"');
    for (const k of state.shops) chips += chipHtml(k.name, k.color, s.selShop === k.id, `data-shop="${k.id}"`);
    chips += '<button class="chip ghost" data-new-shop>+ Shop</button>';
    $('shop-chips').innerHTML = chips;
    $('grocery-input').placeholder = 'Add an item' + (s.selShop ? ' for ' + shopName(s.selShop) : '') + '…';

    const items = state.items;
    const toBuy = items.filter((i) => !i.done).length;
    $('grocery-summary').textContent = items.length
      ? `${toBuy} to buy · ${items.length - toBuy} in the cart`
      : 'Your list is empty. Add items below or tap one under Buy again.';

    // One section per shop (shops in order, "Any shop" last); unbought items first.
    let html = '';
    for (const id of [...state.shops.map((k) => k.id), 0]) {
      const section = items.filter((i) => (shop(i.shop) ? i.shop : 0) === id)
        .sort((a, b) => (a.done - b.done) || (a.created - b.created));
      if (!section.length) continue;
      const left = section.filter((i) => !i.done).length;
      html += `<div class="section tint" style="--c:${shopColor(id)}">● ${esc(shopName(id))}${left ? '  ·  ' + left : ''}</div>`;
      html += section.map((i) => `<div class="card item${i.done ? ' done' : ''}" data-item="${i.id}">
        <span class="check"><span>${ICONS.tick}</span></span><div class="body title">${esc(i.name)}</div></div>`).join('');
    }
    $('grocery-list').innerHTML = html;

    // Buy again: everything bought before that isn't already on the list.
    const onList = new Set(items.filter((i) => !i.done).map((i) => i.name.toLowerCase()));
    const usuals = state.usuals.filter((u) => !onList.has(u.name.toLowerCase()))
      .sort((a, b) => (b.uses - a.uses) || (b.last - a.last)).slice(0, 80);
    $('grocery-usuals').innerHTML = usuals.length
      ? '<div class="section">Buy again · tap to add</div><div class="usuals">'
        + usuals.map((u) => chipHtml('+ ' + u.name, shopColor(u.shop), false, `data-usual="${esc(u.name)}"`)).join('') + '</div>'
      : '';
  },

  editSheet(it) {
    const opts = ['<option value="0">Any shop</option>']
      .concat(state.shops.map((k) => `<option value="${k.id}"${k.id === it.shop ? ' selected' : ''}>${esc(k.name)}</option>`)).join('');
    openSheet(`<h3>Edit item</h3><input type="text" id="gi-name" value="${esc(it.name)}">
      <label>Shop</label><select id="gi-shop">${opts}</select>
      <div class="row"><button class="btn danger" data-del>Delete</button><button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>Save</button></div>`, (s) => {
      s.querySelector('[data-x]').onclick = closeSheet;
      s.querySelector('[data-del]').onclick = () => { state.items = state.items.filter((x) => x !== it); save(); closeSheet(); this.render(); };
      s.querySelector('[data-ok]').onclick = () => {
        it.name = s.querySelector('#gi-name').value.trim() || it.name;
        it.shop = +s.querySelector('#gi-shop').value;
        const u = state.usuals.find((x) => x.name.toLowerCase() === it.name.toLowerCase());
        if (u) u.shop = it.shop;
        save(); closeSheet(); this.render();
      };
    });
  },

  shopSheet(k) {
    menuSheet(k.name, [
      { label: 'Rename', action: () => textSheet('Rename shop', k.name, '', 'Save', (v) => { k.name = v; save(); this.render(); }) },
      {
        label: 'Delete', danger: true, action: () => confirmSheet(`Delete “${k.name}”?`, 'Its items move to “Any shop”.', 'Delete', () => {
          state.items.forEach((i) => { if (i.shop === k.id) i.shop = 0; });
          state.usuals.forEach((u) => { if (u.shop === k.id) u.shop = 0; });
          state.shops = state.shops.filter((x) => x !== k);
          save(); this.render();
        }),
      },
    ]);
  },

  menu() {
    return [
      {
        label: 'Clear bought items', action: () => {
          const n = state.items.filter((i) => i.done).length;
          state.items = state.items.filter((i) => !i.done);
          save(); this.render();
          toast(n ? `Cleared ${n} item(s). They stay under Buy again.` : 'Nothing ticked off yet');
        },
      },
      {
        label: 'Share list', action: () => {
          let out = '';
          for (const id of [...state.shops.map((k) => k.id), 0]) {
            const names = state.items.filter((i) => !i.done && (shop(i.shop) ? i.shop : 0) === id).map((i) => '• ' + i.name);
            if (names.length) out += shopName(id) + '\n' + names.join('\n') + '\n\n';
          }
          shareText('Shopping list', out.trim() || 'Nothing to buy');
        },
      },
    ];
  },
};
registerModule(Grocery);

$('shop-chips').addEventListener('click', (e) => {
  const b = e.target.closest('button');
  if (!b) return;
  if ('newShop' in b.dataset) {
    textSheet('New shop', '', 'e.g. Edeka, dm, Kaufland', 'Add', (name) => {
      const k = { id: newId(), name, color: PALETTE[(state.shops.length + 2) % PALETTE.length] };
      state.shops.push(k);
      state.settings.selShop = k.id;
      save(); Grocery.render();
    });
    return;
  }
  state.settings.selShop = +b.dataset.shop;
  save();
  Grocery.render();
});
onLongPress($('shop-chips'), '[data-shop]', (el) => { const k = shop(+el.dataset.shop); if (k) Grocery.shopSheet(k); });

// Tap an item to tick it off (or back on); press and hold to edit.
$('grocery-list').addEventListener('click', (e) => {
  const el = e.target.closest('[data-item]');
  if (!el) return;
  const it = state.items.find((i) => i.id === +el.dataset.item);
  it.done = !it.done;
  save();
  Grocery.render();
});
onLongPress($('grocery-list'), '[data-item]', (el) => Grocery.editSheet(state.items.find((i) => i.id === +el.dataset.item)));

$('grocery-usuals').addEventListener('click', (e) => {
  const b = e.target.closest('[data-usual]');
  if (!b) return;
  const u = state.usuals.find((x) => x.name === b.dataset.usual);
  Grocery.add(u.name, shop(u.shop) ? u.shop : 0);
  Grocery.render();
});
onLongPress($('grocery-usuals'), '[data-usual]', (el) => {
  const name = el.dataset.usual;
  confirmSheet(`Remove “${name}” from Buy again?`, '', 'Remove', () => {
    state.usuals = state.usuals.filter((u) => u.name !== name);
    save(); Grocery.render();
  });
});

$('grocery-add').addEventListener('submit', (e) => {
  e.preventDefault();
  const name = $('grocery-input').value.trim();
  if (!name) return;
  Grocery.add(name, state.settings.selShop);
  $('grocery-input').value = '';
  Grocery.render();
});
