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
 * Shopping lists (groceries, drugstore, hardware…) grouped by shop and sorted by aisle. Ticking an item off
 * removes it (with Undo) and can ask what you paid, so you can see where a product is cheapest. Everything
 * ever added is remembered under "Buy again"; lists can be shared to WhatsApp and merged back in.
 */
class GroceryScreen extends Screen {

    private static final int MENU_CLEAR = 120, MENU_SHARE = 121, MENU_IMPORT = 122, MENU_ASK_PRICE = 123,
            MENU_RENAME_LIST = 124, MENU_DELETE_LIST = 125;
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
        add(q[1], q[0], selShop);
        input.setText("");
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

    /** Ticking an item off: optionally ask the price, then remove it with an Undo bar. */
    private void tick(final Db.Item it) {
        if (!askPrice()) {
            bought(it);
            return;
        }
        Map<Long, Store.Price> known = prices.get(key(it.name));
        Store.Price last = known == null ? null : known.get(it.shop);
        final EditText amount = Util.field(a, last == null ? "" : String.format(Locale.getDefault(), "%.2f", last.cents / 100.0));
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        amount.setHint("Price in € (optional)");
        amount.setSelectAllOnFocus(true);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("✓ " + it.name + (it.shop != 0 ? " at " + shopName(it.shop) : ""))
                .setMessage("What did you pay? It helps you compare shops later.")
                .setView(Util.wrap(a, amount))
                .setPositiveButton("Save price", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int which) {
                        long cents = TripsScreen.parseAmount(amount.getText().toString());
                        if (cents > 0) st.addPrice(it.name, it.shop, cents, it.qty);
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

    private void bought(Db.Item it) {
        db.deleteItem(it.id);
        justBought.add(it);
        long spent = st.spentToday();
        undoText.setText((justBought.size() == 1 ? "✓ Bought " + it.name : "✓ Bought " + justBought.size() + " items")
                + (spent > 0 ? "  ·  today " + TripsScreen.money(spent, "€") : ""));
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

        long spent = st.spentToday();
        TextView summary = text(items.isEmpty() ? "This list is empty. Add items below or tap one under Buy again."
                : items.size() + " to buy" + (spent > 0 ? " · spent today " + TripsScreen.money(spent, "€") : "")
                + " · tap an item when it's in your cart", 13, R.color.muted);
        summary.setPadding(Util.dp(a, 4), Util.dp(a, 12), 0, Util.dp(a, 2));
        content.addView(summary);

        // One section per shop (shops in order, "Any shop" last); items in aisle order within a shop.
        List<Long> order = new ArrayList<Long>();
        for (Db.Shop s : shops) order.add(s.id);
        order.add(0L);
        for (long shopId : order) {
            List<Db.Item> section = new ArrayList<Db.Item>();
            for (Db.Item it : items) if (it.shop == shopId || (shopId == 0 && shop(it.shop) == null)) section.add(it);
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
                    .append(TripsScreen.money(list.get(i).cents, "€"));
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
                    add(u.name, typed[0], shop(u.shop) != null ? u.shop : 0);
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

    private void priceHistory(String name) {
        List<Store.Price> list = st.prices(name);
        StringBuilder sb = new StringBuilder();
        if (list.isEmpty()) sb.append("No prices yet. When you tick this item off, enter what you paid.");
        for (Store.Price p : list) {
            sb.append(Util.shortDate(p.day)).append("   ").append(shopName(p.shop)).append("   ")
                    .append(TripsScreen.money(p.cents, "€")).append(p.qty.isEmpty() ? "" : "  (" + p.qty + ")").append('\n');
        }
        String best = priceLine(name);
        new AlertDialog.Builder(a).setTitle("Prices · " + name)
                .setMessage((best.isEmpty() ? "" : best + "\n\n") + sb.toString().trim())
                .setPositiveButton("OK", null).show();
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
            TextView c = Util.chip(a, l.name + (count > 0 ? "  " + count : ""), Util.color(a, R.color.primary), l.id == selList);
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
        new AlertDialog.Builder(a)
                .setTitle(s.name)
                .setItems(new String[]{"Rename", "Delete"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            Util.prompt(a, "Rename shop", s.name, "", "Save", new Util.TextEntered() {
                                public void onText(String name) {
                                    db.renameShop(s.id, name);
                                    reload();
                                }
                            });
                        } else {
                            Util.confirm(a, "Delete \"" + s.name + "\"?", "Its items move to \"Any shop\".", "Delete",
                                    new Runnable() {
                                        public void run() {
                                            db.deleteShop(s.id);
                                            reload();
                                        }
                                    });
                        }
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

    /** Tap a row to mark it bought; long-press to edit, see prices or delete. */
    private View itemRow(final Db.Item it) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        row.setMinimumHeight(Util.dp(a, 52));
        row.setPadding(Util.dp(a, 16), Util.dp(a, 6), Util.dp(a, 14), Util.dp(a, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(a, 6);
        row.setLayoutParams(lp);

        TextView circle = text("○", 22, R.color.prio_none);
        circle.setPadding(0, 0, Util.dp(a, 14), 0);
        row.addView(circle);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = text((it.qty.isEmpty() ? "" : it.qty + " × ") + it.name, 16, R.color.text);
        col.addView(name);
        String pl = priceLine(it.name);
        if (!pl.isEmpty()) col.addView(text(pl, 12, R.color.muted));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView aisle = text(aisle(it), 11, R.color.muted);
        row.addView(aisle);

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

    private void editItem(final Db.Item it) {
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "Item"));
        final EditText field = Util.field(a, it.name);
        box.addView(field);
        box.addView(Util.label(a, "Quantity"));
        final EditText qty = Util.field(a, it.qty);
        qty.setHint("e.g. 2, 500 g, 1 pack");
        box.addView(qty);
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
                        it.qty = qty.getText().toString().trim();
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
        menu.add(0, MENU_SHARE, 0, "Share list (WhatsApp…)");
        menu.add(0, MENU_IMPORT, 1, "Add items from a shared list");
        menu.add(0, MENU_ASK_PRICE, 2, "Ask for price when ticking off").setCheckable(true);
        menu.add(0, MENU_RENAME_LIST, 3, "Rename this list");
        menu.add(0, MENU_DELETE_LIST, 4, "Delete this list");
        menu.add(0, MENU_CLEAR, 5, "Clear this list");
    }

    @Override
    void onPrepareMenu(Menu menu) {
        menu.findItem(MENU_ASK_PRICE).setChecked(askPrice());
        menu.findItem(MENU_DELETE_LIST).setVisible(selList != 1);
    }

    @Override
    boolean onMenu(int id) {
        switch (id) {
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
