package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Grocery list grouped by shop. Ticking an item off removes it (with Undo); everything ever added is
 * remembered under "Buy again", and typing filters those suggestions.
 */
class GroceryScreen extends Screen {

    private static final int MENU_CLEAR = 120;
    private static final int MENU_SHARE = 121;
    private static final long UNDO_MS = 6000;

    private final LinearLayout content, shopChips;
    private final EditText input;
    private final View undoBar;
    private final TextView undoText;
    private long selShop; // shop new items are added to, 0 = any shop
    private List<Db.Shop> shops = new ArrayList<Db.Shop>();

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
        input = (EditText) root.findViewById(R.id.input);
        undoBar = root.findViewById(R.id.undo_bar);
        undoText = (TextView) root.findViewById(R.id.undo_text);
        selShop = a.prefs.getLong("selShop", 0);

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

    // ---------------------------------------------------------------- adding & ticking off

    private void addFromInput() {
        String name = input.getText().toString().trim();
        if (name.isEmpty()) return;
        add(name, selShop);
        input.setText("");
    }

    /** Adds an item unless it is already on the list (that was how "Eggs" ended up there twice). */
    private void add(String name, long shopId) {
        for (Db.Item it : db.items()) {
            if (key(it.name).equals(key(name))) {
                Toast.makeText(a, "\"" + it.name + "\" is already on your list (" + shopName(it.shop) + ")",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        db.addItem(name, shopId);
        reload();
    }

    /** Ticking an item off means it's bought: it leaves the list, and Undo can bring it back. */
    private void bought(Db.Item it) {
        db.deleteItem(it.id);
        justBought.add(it);
        undoText.setText(justBought.size() == 1 ? "✓ Bought " + it.name : "✓ Bought " + justBought.size() + " items");
        undoBar.setVisibility(View.VISIBLE);
        undoBar.removeCallbacks(hideUndo);
        undoBar.postDelayed(hideUndo, UNDO_MS);
        reload();
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
        if (selShop != 0 && shop(selShop) == null) selShop = 0;
        buildShopChips();
        input.setHint("Add an item" + (selShop != 0 ? " for " + shopName(selShop) : "") + "…");

        content.removeAllViews();
        List<Db.Item> items = db.items();
        Set<String> onList = new HashSet<String>();
        for (Db.Item it : items) onList.add(key(it.name));
        String q = key(input.getText().toString());

        // While typing, matching items from the history come first so one tap adds them.
        List<Db.Usual> usuals = new ArrayList<Db.Usual>();
        for (Db.Usual u : db.usuals()) {
            if (onList.contains(key(u.name))) continue;
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

        TextView summary = text(items.isEmpty() ? "Your list is empty. Add items below or tap one under Buy again."
                : items.size() + " to buy · tap an item once it's in your cart", 13, R.color.muted);
        summary.setPadding(Util.dp(a, 4), Util.dp(a, 12), 0, Util.dp(a, 2));
        content.addView(summary);

        // One section per shop (shops in order, "Any shop" last).
        List<Long> order = new ArrayList<Long>();
        for (Db.Shop s : shops) order.add(s.id);
        order.add(0L);
        for (long shopId : order) {
            List<Db.Item> section = new ArrayList<Db.Item>();
            for (Db.Item it : items) if (it.shop == shopId || (shopId == 0 && shop(it.shop) == null)) section.add(it);
            if (section.isEmpty()) continue;
            content.addView(header("● " + shopName(shopId) + "  ·  " + section.size(), Util.readable(a, shopColor(shopId))));
            for (Db.Item it : section) content.addView(itemRow(it));
        }

        if (q.isEmpty() && !usuals.isEmpty()) {
            content.addView(header("Buy again · tap to add", 0));
            content.addView(usualChips(usuals));
        }
    }

    private FlowLayout usualChips(List<Db.Usual> usuals) {
        FlowLayout flow = new FlowLayout(a);
        for (final Db.Usual u : usuals) {
            TextView c = Util.chip(a, "+ " + u.name, shopColor(u.shop), false);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (input.getText().length() > 0) input.setText(""); // also refreshes the list
                    add(u.name, shop(u.shop) != null ? u.shop : 0);
                }
            });
            c.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    Util.confirm(a, "Remove \"" + u.name + "\" from Buy again?", null, "Remove", new Runnable() {
                        public void run() {
                            db.forgetUsual(u.name);
                            reload();
                        }
                    });
                    return true;
                }
            });
            flow.addView(c);
        }
        return flow;
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
                Util.prompt(a, "New shop", "", "e.g. Edeka, dm, Kaufland", "Add", new Util.TextEntered() {
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

    /** Tap the circle or the row to mark it bought; long-press to edit or delete. */
    private View itemRow(final Db.Item it) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        row.setMinimumHeight(Util.dp(a, 52));
        row.setPadding(Util.dp(a, 16), 0, Util.dp(a, 14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(a, 6);
        row.setLayoutParams(lp);

        TextView circle = text("○", 22, R.color.prio_none);
        circle.setPadding(0, 0, Util.dp(a, 14), 0);
        row.addView(circle);
        row.addView(text(it.name, 16, R.color.text), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { bought(it); }
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
        final EditText field = Util.field(a, it.name);
        box.addView(field);
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
        new AlertDialog.Builder(a)
                .setTitle("Edit item")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String n = field.getText().toString().trim();
                        db.updateItem(it.id, n.isEmpty() ? it.name : n, ids.get(spinner.getSelectedItemPosition()));
                        reload();
                    }
                })
                .setNeutralButton("Remove from list", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        db.deleteItem(it.id);
                        reload();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    void onCreateMenu(Menu menu) {
        menu.add(0, MENU_SHARE, 0, "Share list");
        menu.add(0, MENU_CLEAR, 1, "Clear the whole list");
    }

    @Override
    boolean onMenu(int id) {
        if (id == MENU_CLEAR) {
            Util.confirm(a, "Clear the whole list?", "Items stay under Buy again.", "Clear", new Runnable() {
                public void run() {
                    for (Db.Item it : db.items()) db.deleteItem(it.id);
                    reload();
                }
            });
            return true;
        }
        if (id == MENU_SHARE) {
            StringBuilder sb = new StringBuilder();
            List<Long> order = new ArrayList<Long>();
            for (Db.Shop s : shops) order.add(s.id);
            order.add(0L);
            List<Db.Item> items = db.items();
            for (long shopId : order) {
                StringBuilder part = new StringBuilder();
                for (Db.Item it : items) {
                    if (!(it.shop == shopId || (shopId == 0 && shop(it.shop) == null))) continue;
                    part.append("• ").append(it.name).append('\n');
                }
                if (part.length() > 0) sb.append(shopName(shopId)).append('\n').append(part).append('\n');
            }
            a.shareText("Shopping list", sb.length() == 0 ? "Nothing to buy" : sb.toString().trim());
            return true;
        }
        return false;
    }
}
