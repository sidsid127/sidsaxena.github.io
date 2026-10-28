package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shopping lists (groceries, drugstore, hardware…) grouped by shop and sorted by aisle; urgent items first.
 * Each item has a quantity with a unit ("2 kg", "1 pack"): − / + and a unit picker change it without typing,
 * and the last quantity of a product is used again next time. Ticking an item off removes it (with Undo) and
 * can ask the price per unit, so you can see where a product is cheapest. Everything ever added is remembered
 * under "Buy again"; lists can be shared to WhatsApp and merged back in.
 */
class GroceryScreen extends Screen {

    private static final int MENU_CLEAR = 120, MENU_SHARE = 121, MENU_IMPORT = 122, MENU_ASK_PRICE = 123,
            MENU_RENAME_LIST = 124, MENU_DELETE_LIST = 125, MENU_PRICES = 126, MENU_HOUSE = 127;
    private static final long UNDO_MS = 6000;

    private final LinearLayout content, shopChips, listChips;
    private final EditText input;
    private final View undoBar;
    private final TextView undoText;
    private long selShop; // shop new items are added to, 0 = any shop
    private long selList; // the list being shown
    private List<Db.Shop> shops = new ArrayList<Db.Shop>();
    private Map<String, Map<Long, Store.Price>> prices;

    /** Items ticked off since the undo bar appeared; Undo puts them all back. */
    private final List<Db.Item> justBought = new ArrayList<Db.Item>();
    private final Runnable hideUndo = new Runnable() {
        public void run() {
            justBought.clear();
            undoBar.setVisibility(View.GONE);
        }
    };

    GroceryScreen(MainActivity activity) {
        super(activity, R.layout.screen_grocery);
        content = (LinearLayout) root.findViewById(R.id.grocery_content);
        shopChips = (LinearLayout) root.findViewById(R.id.shop_chips);
        listChips = (LinearLayout) root.findViewById(R.id.lists);
        input = (EditText) root.findViewById(R.id.input);
        undoBar = root.findViewById(R.id.undo_bar);
        undoText = (TextView) root.findViewById(R.id.undo_text);
        selShop = a.prefs.getLong("selShop", 0);
        selList = a.prefs.getLong("selList", 1);

        root.findViewById(R.id.add_btn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addFromInput(); }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addFromInput();
                    return true; // keep the keyboard open for the next item
                }
                return false;
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) { reload(); }
        });
        root.findViewById(R.id.undo_btn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { undo(); }
        });
    }

    @Override
    String title() {
        return a.getString(R.string.module_grocery);
    }

    @Override
    void onShow() {
        reload();
    }

    @Override
    void onHide() {
        undoBar.removeCallbacks(hideUndo);
        hideUndo.run();
    }

    @Override
    void quick(String what) {
        Util.showKeyboard(input);
    }

    private boolean askPrice() {
        return a.prefs.getBoolean("askPrice", true);
    }

    private Db.Shop shop(long id) {
        for (Db.Shop s : shops) if (s.id == id) return s;
        return null;
    }

    private String shopName(long id) {
        Db.Shop s = shop(id);
        return s == null ? "Any shop" : s.name;
    }

    private int shopColor(long id) {
        Db.Shop s = shop(id);
        return s == null ? Util.color(a, R.color.muted) : s.color;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.getDefault());
    }

    private List<Db.Item> listItems() {
        List<Db.Item> out = new ArrayList<Db.Item>();
        for (Db.Item it : db.items()) if (it.list == selList) out.add(it);
        return out;
    }

    // ---------------------------------------------------------------- adding & ticking off

    private void addFromInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        String[] q = GroceryText.splitQuantity(text);
        add(q[1], quantityFor(q[1], q[0]), selShop);
        input.setText("");
    }

    /** What was typed, else the quantity used last time, else the usual one for the product ("1 l" milk, "10 pcs" eggs). */
    private String quantityFor(String name, String typed) {
        String last = "";
        for (Db.Usual u : db.usuals()) if (key(u.name).equals(key(name))) last = u.qty;
        String usual = last.isEmpty() ? GroceryText.defaultQty(name) : last;
        if (typed.isEmpty()) return usual;
        return GroceryText.unit(typed).isEmpty() ? GroceryText.withUnit(typed, GroceryText.unit(usual)) : typed;
    }

    /** Adds an item unless it is already on this list. */
    private boolean add(String name, String qty, long shopId) {
        for (Db.Item it : listItems()) {
            if (key(it.name).equals(key(name))) {
                Toast.makeText(a, "\"" + it.name + "\" is already on the list (" + shopName(it.shop) + ")", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        db.addItem(name, qty, shopId, selList);
        reload();
        return true;
    }

    /** Ticking an item off: optionally ask the price (per unit), then remove it with an Undo bar. */
    private void tick(final Db.Item it) {
        if (!askPrice()) {
            bought(it);
            return;
        }
        Map<Long, Store.Price> known = prices.get(key(it.name));
        Store.Price last = known == null ? null : known.get(it.shop);
        final String itemUnit = GroceryText.unit(it.qty).isEmpty() ? "pcs" : GroceryText.unit(it.qty);
        final double bought = GroceryText.amount(it.qty);
        final EditText amount = Util.field(a, last == null || GroceryText.amount(last.qty) != 1 ? ""
                : String.format(Locale.getDefault(), "%.2f", last.cents / 100.0));
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        amount.setHint("Price in € (optional)");
        amount.setSelectAllOnFocus(true);
        // "per kg" (the item's unit) first; "for all 2 kg" when more than one was bought.
        final List<String> per = new ArrayList<String>();
        per.add(itemUnit);
        for (String u : GroceryText.UNITS) if (!per.contains(u) && !u.equals("g") && !u.equals("ml")) per.add(u);
        List<String> labels = new ArrayList<String>();
        for (String u : per) labels.add("per " + unitLabel(u));
        if (bought > 0 && bought != 1) labels.add("for all " + it.qty);
        final Spinner perSpinner = new Spinner(a);
        perSpinner.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, labels));
        LinearLayout box = Util.dialogBox(a);
        box.addView(amount);
        box.addView(perSpinner);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("✓ " + it.name + (it.shop != 0 ? " at " + shopName(it.shop) : ""))
                .setMessage("What did it cost? Doable keeps the price per unit to compare shops.")
                .setView(box)
                .setPositiveButton("Save price", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int which) {
                        long cents = TripsScreen.parseAmount(amount.getText().toString());
                        int sel = perSpinner.getSelectedItemPosition();
                        if (cents > 0 && sel >= per.size()) {
                            st.addPrice(it.name, it.shop, Math.round(cents / bought), "1 " + itemUnit); // total → per unit
                        } else if (cents > 0) {
                            st.addPrice(it.name, it.shop, cents, "1 " + per.get(sel));
                        }
                        bought(it);
                    }
                })
                .setNegativeButton("Skip", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int which) { bought(it); }
                })
                .create();
        d.show();
        Util.showKeyboard(amount);
    }

    static String unitLabel(String unit) {
        return unit.equals("pcs") ? "piece" : unit;
    }

    private void bought(Db.Item it) {
        db.deleteItem(it.id);
        justBought.add(it);
        undoText.setText(justBought.size() == 1 ? "✓ Bought " + it.name : "✓ Bought " + justBought.size() + " items");
        undoBar.setVisibility(View.VISIBLE);
        undoBar.removeCallbacks(hideUndo);
        undoBar.postDelayed(hideUndo, UNDO_MS);
        reload();
        Widgets.updateAll(a);
    }

    private void undo() {
        for (Db.Item it : justBought) db.restoreItem(it);
        int n = justBought.size();
        undoBar.removeCallbacks(hideUndo);
        hideUndo.run();
        Toast.makeText(a, n == 1 ? "Put back on the list" : n + " items put back", Toast.LENGTH_SHORT).show();
        reload();
    }

    // ---------------------------------------------------------------- list

    void reload() {
        shops = db.shops();
        prices = st.latestPrices();
        if (selShop != 0 && shop(selShop) == null) selShop = 0;
        buildListChips();
        buildShopChips();
        input.setHint("Add an item" + (selShop != 0 ? " for " + shopName(selShop) : "") + " (e.g. 2 milk)…");

        content.removeAllViews();
        List<Db.Item> items = listItems();
        Set<String> onList = new HashSet<String>();
        for (Db.Item it : items) onList.add(key(it.name));
        String q = key(GroceryText.splitQuantity(input.getText().toString())[1]);

        // While typing, matching items from the history come first so one tap adds them.
        List<Db.Usual> usuals = new ArrayList<Db.Usual>();
        for (Db.Usual u : db.usuals()) {
            if (u.list != selList || onList.contains(key(u.name))) continue;
            if (!q.isEmpty() && !key(u.name).contains(q)) continue;
            usuals.add(u);
        }
        if (!q.isEmpty()) {
            if (!usuals.isEmpty()) {
                content.addView(header("Bought before · tap to add", 0));
                content.addView(usualChips(usuals));
            }
            for (Db.Item it : items) {
                if (key(it.name).contains(q)) {
                    TextView t = text("\"" + it.name + "\" is already on the list (" + shopName(it.shop) + ")", 13, R.color.muted);
                    t.setPadding(Util.dp(a, 4), Util.dp(a, 8), 0, 0);
                    content.addView(t);
                }
            }
        }

        int urgentCount = 0;
        for (Db.Item it : items) if (it.urgent) urgentCount++;
        TextView summary = text(items.isEmpty() ? "This list is empty. Add items below or tap one under Buy again."
                : items.size() + (items.size() == 1 ? " item" : " items") + " to buy" + (urgentCount > 0 ? " · " + urgentCount + " urgent" : "")
                + " · tap an item when it's in your cart", 13, R.color.muted);
        summary.setPadding(Util.dp(a, 4), Util.dp(a, 12), 0, Util.dp(a, 2));
        content.addView(summary);

        // Urgent items first, then one section per shop (shops in order, "Any shop" last), aisle order within.
        if (urgentCount > 0) {
            content.addView(header("❗ Needed now  ·  " + urgentCount, Util.color(a, R.color.overdue)));
            for (Db.Item it : items) if (it.urgent) content.addView(itemRow(it));
        }
        List<Long> order = new ArrayList<Long>();
        for (Db.Shop s : shops) order.add(s.id);
        order.add(0L);
        for (long shopId : order) {
            List<Db.Item> section = new ArrayList<Db.Item>();
            for (Db.Item it : items) if (!it.urgent && (it.shop == shopId || (shopId == 0 && shop(it.shop) == null))) section.add(it);
            if (section.isEmpty()) continue;
            Collections.sort(section, new Comparator<Db.Item>() {
                public int compare(Db.Item x, Db.Item y) {
                    int ax = GroceryText.aisleIndex(aisle(x)), ay = GroceryText.aisleIndex(aisle(y));
                    return ax != ay ? ax - ay : x.name.compareToIgnoreCase(y.name);
                }
            });
            content.addView(header("● " + shopName(shopId) + "  ·  " + section.size(), Util.readable(a, shopColor(shopId))));
            for (Db.Item it : section) content.addView(itemRow(it));
        }

        if (q.isEmpty() && !usuals.isEmpty()) {
            content.addView(header("Buy again · tap to add", 0));
            content.addView(usualChips(usuals));
        }
    }

    private static String aisle(Db.Item it) {
        return it.aisle.isEmpty() ? GroceryText.aisle(it.name) : it.aisle;
    }

    /** "Lidl 0.25 € · Rewe 0.29 €", cheapest first; marks the cheapest. */
    private String priceLine(String name) {
        Map<Long, Store.Price> known = prices.get(key(name));
        if (known == null || known.isEmpty()) return "";
        List<Store.Price> list = new ArrayList<Store.Price>(known.values());
        Collections.sort(list, new Comparator<Store.Price>() {
            public int compare(Store.Price x, Store.Price y) { return Long.compare(x.cents, y.cents); }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            if (i > 0) sb.append("  ·  ");
            sb.append(i == 0 && list.size() > 1 ? "cheapest " : "").append(shopName(list.get(i).shop)).append(' ')
                    .append(GroceryText.unitPrice(TripsScreen.money(list.get(i).cents, "€"), list.get(i).qty));
        }
        return sb.toString();
    }

    private FlowLayout usualChips(List<Db.Usual> usuals) {
        FlowLayout flow = new FlowLayout(a);
        for (final Db.Usual u : usuals) {
            TextView c = Util.chip(a, "+ " + u.name, shopColor(u.shop), false);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String[] typed = GroceryText.splitQuantity(input.getText().toString());
                    if (input.getText().length() > 0) input.setText(""); // also refreshes the list
                    add(u.name, quantityFor(u.name, typed[0]), shop(u.shop) != null ? u.shop : 0);
                }
            });
            c.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    usualOptions(u);
                    return true;
                }
            });
            flow.addView(c);
        }
        return flow;
    }

    private void usualOptions(final Db.Usual u) {
        new AlertDialog.Builder(a).setTitle(u.name).setItems(new String[]{"Price history", "Remove from Buy again"},
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) priceHistory(u.name);
                        else {
                            db.forgetUsual(u.name);
                            reload();
                        }
                    }
                }).show();
    }

    /** One product's prices, newest first: tap one to correct or delete it; "Add price" for a missing one. */
    private void priceHistory(final String name) {
        final List<Store.Price> list = st.prices(name);
        String best = priceLine(name);
        List<String> rows = new ArrayList<String>();
        for (Store.Price p : list) {
            rows.add(Util.shortDate(p.day) + "   " + shopName(p.shop) + "   " + GroceryText.unitPrice(TripsScreen.money(p.cents, "€"), p.qty));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Prices · " + name)
                .setPositiveButton("Close", null)
                .setNeutralButton("Add price", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Store.Price p = new Store.Price();
                        p.id = 0;
                        p.name = name;
                        p.qty = "1 " + GroceryText.unit(GroceryText.defaultQty(name));
                        p.day = Util.startOfDay(System.currentTimeMillis());
                        p.shop = selShop;
                        editPrice(p);
                    }
                });
        if (list.isEmpty()) b.setMessage("No prices yet. When you tick this item off, enter what it cost — or add one here.");
        else b.setItems(rows.toArray(new String[0]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) { editPrice(list.get(which)); }
        });
        if (!best.isEmpty() && !list.isEmpty()) b.setTitle("Prices · " + name + "\n" + best);
        b.show();
    }

    /** Correct or delete a price (id 0 = a new one). */
    private void editPrice(final Store.Price p) {
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "Price in €"));
        final EditText amount = Util.field(a, p.id == 0 ? "" : String.format(Locale.getDefault(), "%.2f", p.cents / 100.0));
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        box.addView(amount);
        box.addView(Util.label(a, "Per"));
        final List<String> units = new ArrayList<String>(java.util.Arrays.asList(GroceryText.UNITS));
        List<String> labels = new ArrayList<String>();
        for (String u : units) labels.add("per " + unitLabel(u));
        boolean legacy = GroceryText.amount(p.qty) != 1 || GroceryText.unit(p.qty).isEmpty();
        if (legacy) labels.add(p.qty.isEmpty() ? "(not given)" : "for " + p.qty);
        final Spinner per = new Spinner(a);
        per.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, labels));
        per.setSelection(legacy ? labels.size() - 1 : Math.max(0, units.indexOf(GroceryText.unit(p.qty))));
        box.addView(per);
        box.addView(Util.label(a, "Shop"));
        final List<Long> ids = new ArrayList<Long>();
        List<String> names = new ArrayList<String>();
        ids.add(0L);
        names.add("Any shop");
        for (Db.Shop sh : shops) {
            ids.add(sh.id);
            names.add(sh.name);
        }
        final Spinner shop = new Spinner(a);
        shop.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, names));
        shop.setSelection(Math.max(0, ids.indexOf(p.shop)));
        box.addView(shop);
        box.addView(Util.label(a, "Date"));
        final long[] day = {p.day};
        final TextView date = text(Util.shortDate(p.day), 16, R.color.primary_text);
        date.setPadding(Util.dp(a, 4), Util.dp(a, 10), 0, Util.dp(a, 10));
        date.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.setTimeInMillis(day[0]);
                new android.app.DatePickerDialog(a, new android.app.DatePickerDialog.OnDateSetListener() {
                    public void onDateSet(android.widget.DatePicker view, int y, int m, int d) {
                        day[0] = Store.dayOf(y, m + 1, d);
                        date.setText(Util.shortDate(day[0]));
                    }
                }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show();
            }
        });
        box.addView(date);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle((p.id == 0 ? "Add price · " : "Price · ") + p.name).setView(Util.wrap(a, box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        long cents = TripsScreen.parseAmount(amount.getText().toString());
                        if (cents <= 0) {
                            Toast.makeText(a, "Enter a price", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        p.cents = cents;
                        int sel = per.getSelectedItemPosition();
                        if (sel < units.size()) p.qty = "1 " + units.get(sel);
                        p.shop = ids.get(shop.getSelectedItemPosition());
                        p.day = day[0];
                        if (p.id == 0) st.addPrice(p.name, p.shop, p.cents, p.qty, p.day);
                        else st.updatePrice(p);
                        reload();
                        priceHistory(p.name);
                    }
                })
                .setNegativeButton("Cancel", null);
        if (p.id != 0) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    st.deletePrice(p.id);
                    Toast.makeText(a, "Price deleted", Toast.LENGTH_SHORT).show();
                    reload();
                    priceHistory(p.name);
                }
            });
        }
        b.show();
    }

    /** All products with prices: typical (median) price per unit, cheapest shop, lowest and highest. */
    private void priceOverview() {
        final java.util.TreeMap<String, List<Store.Price>> all = st.allPrices();
        if (all.isEmpty()) {
            new AlertDialog.Builder(a).setTitle("Prices").setMessage("No prices yet. When you tick an item off, enter what it cost "
                    + "(turn on \"Ask for the price\" in the menu). Then you see here what things usually cost and where they're cheapest.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final List<String> names = new ArrayList<String>(all.keySet());
        List<String> rows = new ArrayList<String>();
        for (String n : names) rows.add(n + "\n" + priceSummary(all.get(n)));
        new AlertDialog.Builder(a).setTitle("Prices · tap a product to see or correct them")
                .setItems(rows.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { priceHistory(names.get(which)); }
                })
                .setPositiveButton("Close", null).show();
    }

    /** "usually 1.29 € / kg · 0.99–1.49 · cheapest at Lidl · 5 prices" for the most common unit. */
    private String priceSummary(List<Store.Price> list) {
        Map<String, List<Store.Price>> byUnit = new java.util.HashMap<String, List<Store.Price>>();
        String common = null;
        for (Store.Price p : list) {
            String k = GroceryText.amount(p.qty) == 1 ? p.qty : "";
            if (!byUnit.containsKey(k)) byUnit.put(k, new ArrayList<Store.Price>());
            byUnit.get(k).add(p);
            if (common == null || byUnit.get(k).size() > byUnit.get(common).size()) common = k;
        }
        List<Store.Price> same = byUnit.get(common);
        List<Long> cents = new ArrayList<Long>();
        Store.Price cheapest = same.get(0);
        for (Store.Price p : same) {
            cents.add(p.cents);
            if (p.cents < cheapest.cents) cheapest = p;
        }
        Collections.sort(cents);
        long median = cents.get(cents.size() / 2);
        String per = common.isEmpty() ? "" : " / " + unitLabel(GroceryText.unit(common));
        return "usually " + TripsScreen.money(median, "€") + per
                + (cents.size() > 1 ? " · " + TripsScreen.money(cents.get(0), "€") + "–" + TripsScreen.money(cents.get(cents.size() - 1), "€") : "")
                + (shop(cheapest.shop) != null ? " · cheapest at " + shopName(cheapest.shop) : "")
                + " · " + list.size() + (list.size() == 1 ? " price" : " prices");
    }

    private void buildListChips() {
        listChips.removeAllViews();
        List<Store.GList> lists = st.groceryLists();
        boolean found = false;
        for (Store.GList l : lists) found |= l.id == selList;
        if (!found) selList = 1;
        for (final Store.GList l : lists) {
            int count = 0;
            for (Db.Item it : db.items()) if (it.list == l.id) count++;
            TextView c = Util.chip(a, SpacesUi.badge(a, "grocery_lists", l.id) + l.name + (count > 0 ? " (" + count + (count == 1 ? " item)" : " items)") : ""),
                    Util.color(a, R.color.primary), l.id == selList);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selList = l.id;
                    a.prefs.edit().putLong("selList", selList).apply();
                    reload();
                }
            });
            listChips.addView(c);
        }
        TextView add = Util.chip(a, "+ List", Util.color(a, R.color.muted), false);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Util.prompt(a, "New list", "", "e.g. Party, Pharmacy, IKEA", "Add", new Util.TextEntered() {
                    public void onText(String name) {
                        selList = st.addGroceryList(name);
                        a.prefs.edit().putLong("selList", selList).apply();
                        reload();
                    }
                });
            }
        });
        listChips.addView(add);
    }

    private void buildShopChips() {
        shopChips.removeAllViews();
        TextView any = Util.chip(a, "Any shop", Util.color(a, R.color.primary), selShop == 0);
        any.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { selectShop(0); }
        });
        shopChips.addView(any);
        for (final Db.Shop s : shops) {
            TextView c = Util.chip(a, s.name, s.color, selShop == s.id);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectShop(s.id); }
            });
            c.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    shopOptions(s);
                    return true;
                }
            });
            shopChips.addView(c);
        }
        TextView add = Util.chip(a, "+ Shop", Util.color(a, R.color.muted), false);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Util.prompt(a, "New shop", "", "e.g. Edeka, dm, Kaufland, OBI", "Add", new Util.TextEntered() {
                    public void onText(String name) {
                        selectShop(db.addShop(name));
                    }
                });
            }
        });
        shopChips.addView(add);
    }

    private void selectShop(long id) {
        selShop = id;
        a.prefs.edit().putLong("selShop", id).apply();
        reload();
    }

    private void shopOptions(final Db.Shop s) {
        Menus.of(a, s.name)
                .add("✏️", "Rename shop", new Runnable() {
                    public void run() {
                        Util.prompt(a, "Rename shop", s.name, "", "Save", new Util.TextEntered() {
                            public void onText(String name) {
                                db.renameShop(s.id, name);
                                reload();
                            }
                        });
                    }
                })
                .danger("🗑", "Delete shop", new Runnable() {
                    public void run() {
                        Util.confirm(a, "Delete \"" + s.name + "\"?", "Its items move to \"Any shop\".", "Delete",
                                new Runnable() {
                                    public void run() {
                                        db.deleteShop(s.id);
                                        reload();
                                    }
                                });
                    }
                })
                .show();
    }

    private TextView text(String s, int sp, int colorRes) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Util.color(a, colorRes));
        return t;
    }

    private TextView header(String s, int color) {
        TextView t = text(s, 13, R.color.muted);
        if (color != 0) t.setTextColor(color);
        t.setAllCaps(true);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(Util.dp(a, 4), Util.dp(a, 18), 0, Util.dp(a, 4));
        return t;
    }

    /**
     * Tap a row to mark it bought; long-press to edit, see prices or delete. Below the name: − quantity + and
     * the unit (tap to pick another); on the right ❗ marks it as needed right away.
     */
    private View itemRow(final Db.Item it) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        row.setMinimumHeight(Util.dp(a, 56));
        row.setPadding(Util.dp(a, 16), Util.dp(a, 6), Util.dp(a, 6), Util.dp(a, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(a, 6);
        row.setLayoutParams(lp);

        TextView circle = text("○", 22, it.urgent ? R.color.overdue : R.color.prio_none);
        circle.setPadding(0, 0, Util.dp(a, 14), 0);
        row.addView(circle);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(it.name, 16, R.color.text);
        if (it.urgent) name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(name);

        LinearLayout qtyRow = new LinearLayout(a);
        qtyRow.setOrientation(LinearLayout.HORIZONTAL);
        qtyRow.setGravity(Gravity.CENTER_VERTICAL);
        final String qty = it.qty.isEmpty() ? GroceryText.defaultQty(it.name) : it.qty;
        qtyRow.addView(stepButton("−", new View.OnClickListener() {
            public void onClick(View v) { setQty(it, GroceryText.stepQty(qty, -1)); }
        }));
        TextView amount = text(GroceryText.formatQty(GroceryText.amount(qty), ""), 15, R.color.text);
        amount.setPadding(Util.dp(a, 6), 0, Util.dp(a, 6), 0);
        qtyRow.addView(amount);
        qtyRow.addView(stepButton("+", new View.OnClickListener() {
            public void onClick(View v) { setQty(it, GroceryText.stepQty(qty, 1)); }
        }));
        String u = GroceryText.unit(qty);
        TextView unit = text((u.isEmpty() ? "pcs" : u) + " ▾", 14, R.color.primary_text);
        unit.setPadding(Util.dp(a, 10), Util.dp(a, 6), Util.dp(a, 10), Util.dp(a, 6));
        unit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(a).setTitle("Unit for " + it.name).setItems(GroceryText.UNITS, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { setQty(it, GroceryText.withUnit(qty, GroceryText.UNITS[which])); }
                }).show();
            }
        });
        qtyRow.addView(unit);
        col.addView(qtyRow);
        String pl = priceLine(it.name);
        if (!pl.isEmpty()) col.addView(text(pl, 12, R.color.muted));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView flag = text(it.urgent ? "❗" : "!", 20, it.urgent ? R.color.overdue : R.color.prio_none);
        flag.setGravity(Gravity.CENTER);
        flag.setMinWidth(Util.dp(a, 44));
        flag.setMinHeight(Util.dp(a, 44));
        flag.setContentDescription(it.urgent ? "Urgent — tap to unmark" : "Mark as urgent");
        flag.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                db.setItemUrgent(it.id, !it.urgent);
                Toast.makeText(a, it.urgent ? it.name + ": not urgent" : "❗ " + it.name + " is needed right away", Toast.LENGTH_SHORT).show();
                reload();
            }
        });
        row.addView(flag);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { tick(it); }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                editItem(it);
                return true;
            }
        });
        return row;
    }

    private TextView stepButton(String label, View.OnClickListener click) {
        TextView b = text(label, 18, R.color.primary_text);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(Util.dp(a, 36));
        b.setMinHeight(Util.dp(a, 32));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Util.dp(a, 16));
        bg.setStroke(Util.dp(a, 1), Util.color(a, R.color.divider));
        b.setBackground(bg);
        b.setOnClickListener(click);
        return b;
    }

    private void setQty(Db.Item it, String qty) {
        db.setItemQty(it, qty);
        reload();
    }

    private void editItem(final Db.Item it) {
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "Item"));
        final EditText field = Util.field(a, it.name);
        box.addView(field);
        box.addView(Util.label(a, "Quantity"));
        LinearLayout qtyBox = new LinearLayout(a);
        qtyBox.setOrientation(LinearLayout.HORIZONTAL);
        final EditText qty = Util.field(a, GroceryText.formatQty(GroceryText.amount(it.qty), ""));
        qty.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        qty.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        qty.setHint("e.g. 2");
        qtyBox.addView(qty, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final Spinner unitSpinner = new Spinner(a);
        unitSpinner.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, GroceryText.UNITS));
        String curUnit = GroceryText.unit(it.qty.isEmpty() ? GroceryText.defaultQty(it.name) : it.qty);
        unitSpinner.setSelection(Math.max(0, java.util.Arrays.asList(GroceryText.UNITS).indexOf(curUnit)));
        qtyBox.addView(unitSpinner, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(qtyBox);
        final android.widget.CheckBox urgent = new android.widget.CheckBox(a);
        urgent.setText("❗ Urgent — needed right away");
        urgent.setChecked(it.urgent);
        box.addView(urgent);
        box.addView(Util.label(a, "Shop"));
        final List<Long> ids = new ArrayList<Long>();
        List<String> names = new ArrayList<String>();
        ids.add(0L);
        names.add("Any shop");
        for (Db.Shop s : shops) {
            ids.add(s.id);
            names.add(s.name);
        }
        final Spinner spinner = new Spinner(a);
        spinner.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, names));
        spinner.setSelection(Math.max(0, ids.indexOf(it.shop)));
        box.addView(spinner);
        box.addView(Util.label(a, "Aisle"));
        final Spinner aisle = new Spinner(a);
        aisle.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, GroceryText.AISLES));
        aisle.setSelection(GroceryText.aisleIndex(aisle(it)));
        box.addView(aisle);
        box.addView(Util.label(a, "List"));
        final List<Store.GList> lists = st.groceryLists();
        List<String> listNames = new ArrayList<String>();
        int sel = 0;
        for (int i = 0; i < lists.size(); i++) {
            listNames.add(lists.get(i).name);
            if (lists.get(i).id == it.list) sel = i;
        }
        final Spinner listSpinner = new Spinner(a);
        listSpinner.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, listNames));
        listSpinner.setSelection(sel);
        box.addView(listSpinner);
        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        scroll.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("Edit item")
                .setView(scroll)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String n = field.getText().toString().trim();
                        if (!n.isEmpty()) it.name = n;
                        double amountN = TripsScreen.parseAmount(qty.getText().toString()) / 100.0;
                        it.qty = GroceryText.formatQty(amountN, GroceryText.UNITS[unitSpinner.getSelectedItemPosition()]);
                        it.urgent = urgent.isChecked();
                        it.shop = ids.get(spinner.getSelectedItemPosition());
                        it.aisle = GroceryText.AISLES[aisle.getSelectedItemPosition()];
                        it.list = lists.get(listSpinner.getSelectedItemPosition()).id;
                        db.updateItemFull(it);
                        reload();
                    }
                })
                .setNeutralButton("Prices", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { priceHistory(it.name); }
                })
                .setNegativeButton("Remove", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        db.deleteItem(it.id);
                        reload();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- share & import

    private String listName() {
        for (Store.GList l : st.groceryLists()) if (l.id == selList) return l.name;
        return "Shopping list";
    }

    String shareText() {
        List<GroceryText.Line> lines = new ArrayList<GroceryText.Line>();
        List<Long> order = new ArrayList<Long>();
        for (Db.Shop s : shops) order.add(s.id);
        order.add(0L);
        List<Db.Item> items = listItems();
        for (long shopId : order) {
            for (Db.Item it : items) {
                if (!(it.shop == shopId || (shopId == 0 && shop(it.shop) == null))) continue;
                lines.add(new GroceryText.Line(it.name, it.qty, shopId == 0 ? "" : shopName(shopId)));
            }
        }
        return GroceryText.format(listName(), lines);
    }

    /** Adds items from a shared / pasted list that aren't on the current list yet. Returns how many. */
    int importText(String text) {
        shops = db.shops();
        List<String> shopNames = new ArrayList<String>();
        for (Db.Shop s : shops) shopNames.add(s.name);
        Set<String> onList = new HashSet<String>();
        for (Db.Item it : listItems()) onList.add(key(it.name));
        int added = 0;
        for (GroceryText.Line l : GroceryText.parse(text, shopNames)) {
            if (l.name.isEmpty() || onList.contains(key(l.name))) continue;
            long shopId = 0;
            for (Db.Shop s : shops) if (s.name.equalsIgnoreCase(l.shop)) shopId = s.id;
            db.addItem(l.name, l.qty, shopId, selList);
            onList.add(key(l.name));
            added++;
        }
        return added;
    }

    private void importDialog() {
        final EditText field = Util.field(a, "");
        field.setHint("Paste a list, e.g. from WhatsApp");
        field.setMinLines(5);
        new AlertDialog.Builder(a).setTitle("Add items from a list")
                .setMessage("Paste a list someone sent you (one item per line). Items already on the list are skipped. "
                        + "Tip: you can also share a WhatsApp message straight to Doable.")
                .setView(Util.wrap(a, field))
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int n = importText(field.getText().toString());
                        Toast.makeText(a, n == 0 ? "Nothing new to add" : n + " item(s) added", Toast.LENGTH_SHORT).show();
                        reload();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    @Override
    void onCreateMenu(Menu menu) {
        menu.add(0, MENU_HOUSE, 0, "Share this list with household / person…");
        menu.add(0, MENU_PRICES, 0, "Prices & price history");
        menu.add(0, MENU_SHARE, 1, "Share list (WhatsApp, Messages…)");
        menu.add(0, MENU_IMPORT, 2, "Add items from a shared list");
        menu.add(0, MENU_ASK_PRICE, 3, "Ask for the price when ticking off").setCheckable(true);
        menu.add(0, MENU_RENAME_LIST, 4, "Rename this list");
        menu.add(0, MENU_CLEAR, 5, "Remove all items from this list");
        menu.add(0, MENU_DELETE_LIST, 6, "Delete this list");
    }

    @Override
    void onPrepareMenu(Menu menu) {
        menu.findItem(MENU_ASK_PRICE).setChecked(askPrice());
        menu.findItem(MENU_DELETE_LIST).setVisible(selList != 1);
    }

    @Override
    boolean onMenu(int id) {
        switch (id) {
            case MENU_HOUSE:
                SpacesUi.shareMenu(a, "grocery", "grocery_lists", selList, new Runnable() {
                    public void run() {
                        selList = a.prefs.getLong("selList", selList);
                        reload();
                    }
                });
                return true;
            case MENU_PRICES:
                priceOverview();
                return true;
            case MENU_SHARE:
                a.shareText(listName(), shareText());
                return true;
            case MENU_IMPORT:
                importDialog();
                return true;
            case MENU_ASK_PRICE:
                a.prefs.edit().putBoolean("askPrice", !askPrice()).apply();
                return true;
            case MENU_RENAME_LIST:
                Util.prompt(a, "Rename list", listName(), "", "Save", new Util.TextEntered() {
                    public void onText(String name) {
                        st.renameGroceryList(selList, name);
                        reload();
                    }
                });
                return true;
            case MENU_DELETE_LIST:
                Util.confirm(a, "Delete the list \"" + listName() + "\"?", "Its items are removed; Buy again keeps them.", "Delete", new Runnable() {
                    public void run() {
                        st.deleteGroceryList(selList);
                        selList = 1;
                        a.prefs.edit().putLong("selList", 1).apply();
                        reload();
                    }
                });
                return true;
            case MENU_CLEAR:
                Util.confirm(a, "Clear \"" + listName() + "\"?", "Items stay under Buy again.", "Clear", new Runnable() {
                    public void run() {
                        for (Db.Item it : listItems()) db.deleteItem(it.id);
                        reload();
                    }
                });
                return true;
        }
        return false;
    }
}
