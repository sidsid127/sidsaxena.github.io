package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trips. Business trips collect expenses (with receipts) for the monthly expense report; private trips
 * hold the plan, bookings & passes, a photo journal and expenses.
 */
class TripsScreen extends Screen {

    private static final int MENU_REPORT_MONTH = 150;
    private static final int MENU_EDIT = 151;
    private static final int MENU_TRIP_REPORT = 152;
    private static final int MENU_TRAVEL_DIARY = 153;
    private static final int MENU_DELETE = 154;

    static final String[] EXPENSE_CATS = {"Taxi / Uber", "Hotel", "Breakfast", "Lunch", "Dinner", "Client meal",
            "Flight", "Train", "Public transport", "Car rental / fuel", "Parking", "Phone / internet",
            "Food & drinks", "Sightseeing", "Shopping", "Mileage (own car)", "Other"};
    private static final String[] EXPENSE_EMOJI = {"🚕", "🏨", "🥐", "🥪", "🍽", "🤝", "✈", "🚆", "🚇", "⛽", "🅿",
            "📶", "🍹", "🎟", "🛍", "🚗", "🧾"};
    static final String MILEAGE = "Mileage (own car)";
    private static final String[] PAYMENTS = {"Company card", "Personal card", "Cash", "Other"};
    private static final String[] BOOKING_TYPES = {"Stay", "Flight", "Train", "Bus", "Car", "Pass", "Ticket", "Other"};
    private static final String[] BOOKING_EMOJI = {"🏨", "✈", "🚆", "🚌", "🚗", "🎫", "🎟", "📄"};

    private static final String TAB_EXPENSES = "Expenses", TAB_BOOKINGS = "Bookings & passes",
            TAB_NOTES = "Notes", TAB_PLAN = "Places to go", TAB_JOURNAL = "Journal", TAB_PACKING = "Packing list";

    private final View listPanel, detailPanel;
    private final LinearLayout tripsList, tabs, content;
    private final ScrollView detailScroll;
    private final Button addItem;

    private Db.Trip trip;   // open trip, null while showing the list
    private String tab;
    private List<String> sharedFiles; // receipts shared from another app, for the next new expense

    TripsScreen(MainActivity activity) {
        super(activity, R.layout.screen_trips);
        listPanel = root.findViewById(R.id.list_panel);
        detailPanel = root.findViewById(R.id.detail_panel);
        tripsList = (LinearLayout) root.findViewById(R.id.trips_list);
        tabs = (LinearLayout) root.findViewById(R.id.tabs);
        content = (LinearLayout) root.findViewById(R.id.detail_content);
        detailScroll = (ScrollView) root.findViewById(R.id.detail_scroll);
        addItem = (Button) root.findViewById(R.id.add_item);

        root.findViewById(R.id.new_trip).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { newTrip(); }
        });
        addItem.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addForTab(); }
        });
    }

    @Override
    String title() {
        return trip != null ? trip.name : a.getString(R.string.module_trips);
    }

    @Override
    boolean homeIsBack() {
        return trip != null;
    }

    @Override
    void onShow() {
        render();
    }

    @Override
    boolean onBack() {
        if (trip == null) return false;
        closeTrip();
        return true;
    }

    private void render() {
        if (trip == null) renderList();
        else renderTrip();
    }

    // ---------------------------------------------------------------- formatting

    static String money(long cents, String currency) {
        return String.format(Locale.getDefault(), "%,.2f %s", cents / 100.0, currency);
    }

    /** Accepts "12.50", "12,50", "1.234,50" or "1,234.50". Returns cents, or -1 if unreadable. */
    static long parseAmount(String s) {
        s = s.replace(" ", "").replace("€", "").replace("$", "").trim();
        if (s.isEmpty()) return -1;
        int comma = s.lastIndexOf(','), dot = s.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            if (comma > dot) s = s.replace(".", "").replace(',', '.');
            else s = s.replace(",", "");
        } else if (comma >= 0) {
            s = s.replace(',', '.');
        }
        try {
            return new BigDecimal(s).movePointRight(2).setScale(0, BigDecimal.ROUND_HALF_UP).longValue();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String dateRange(Db.Trip t) {
        if (t.start == 0) return "";
        SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy", Locale.getDefault());
        if (t.finish == 0 || t.finish == t.start) return f.format(new Date(t.start));
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(t.start)) + " – " + f.format(new Date(t.finish));
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return arr.length - 1;
    }

    /** Totals per currency, e.g. "640.00 EUR + 120.00 USD". */
    private Map<String, Double> rates(Db.Expense e) {
        return TripMath.rates(e.tripRates != null ? e.tripRates : trip != null ? trip.rates : "");
    }

    private String homeOf(Db.Expense e) {
        return e.tripHome != null ? e.tripHome : trip != null ? trip.currency : "EUR";
    }

    /** Amount in the trip's home currency, -1 if no exchange rate is set for that currency. */
    private long home(Db.Expense e) {
        return TripMath.toHome(e.cents, e.currency, homeOf(e), rates(e));
    }

    /** "1,234.50 EUR" in the home currency(ies), with a note for amounts that have no rate yet. */
    private String homeTotal(List<Db.Expense> list) {
        Map<String, Long> sums = new LinkedHashMap<String, Long>();
        List<Db.Expense> missing = new ArrayList<Db.Expense>();
        for (Db.Expense e : list) {
            long h = home(e);
            if (h < 0) {
                missing.add(e);
                continue;
            }
            String cur = homeOf(e);
            sums.put(cur, (sums.containsKey(cur) ? sums.get(cur) : 0L) + h);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> en : sums.entrySet()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(money(en.getValue(), en.getKey()));
        }
        if (sb.length() == 0) sb.append(money(0, trip != null ? trip.currency : "EUR"));
        if (!missing.isEmpty()) sb.append(" + ").append(totals(missing)).append(" (no rate)");
        return sb.toString();
    }

    private static String totals(List<Db.Expense> list) {
        Map<String, Long> sums = new LinkedHashMap<String, Long>();
        for (Db.Expense e : list) sums.put(e.currency, (sums.containsKey(e.currency) ? sums.get(e.currency) : 0L) + e.cents);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> en : sums.entrySet()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(money(en.getValue(), en.getKey()));
        }
        return sb.length() == 0 ? money(0, "EUR") : sb.toString();
    }

    // ---------------------------------------------------------------- view helpers

    private TextView text(String s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Util.color(a, colorRes));
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s.toUpperCase(Locale.getDefault()), 12, R.color.muted, true);
        t.setPadding(Util.dp(a, 4), Util.dp(a, 18), 0, Util.dp(a, 2));
        return t;
    }

    /** A white rounded card row: optional emoji on the left, title + subtitle, optional text on the right. */
    private LinearLayout card(String emoji, String title, String subtitle, String right) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        row.setPadding(Util.dp(a, 14), Util.dp(a, 12), Util.dp(a, 14), Util.dp(a, 12));
        row.setMinimumHeight(Util.dp(a, 60));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(a, 8);
        row.setLayoutParams(lp);
        if (emoji != null) {
            TextView e = text(emoji, 24, R.color.text, false);
            e.setPadding(0, 0, Util.dp(a, 12), 0);
            row.addView(e);
        }
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 16, R.color.text, true);
        t.setTag("title");
        col.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView st = text(subtitle, 13, R.color.muted, false);
            st.setMaxLines(3);
            col.addView(st);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (right != null) {
            TextView r = text(right, 15, R.color.text, true);
            r.setPadding(Util.dp(a, 10), 0, 0, 0);
            row.addView(r);
        }
        return row;
    }

    private View badge(String s, int color) {
        TextView t = text(s, 11, R.color.text, true);
        t.setTextColor(0xFFFFFFFF);
        t.setPadding(Util.dp(a, 8), Util.dp(a, 2), Util.dp(a, 8), Util.dp(a, 2));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Util.dp(a, 8));
        bg.setColor(color);
        t.setBackground(bg);
        return t;
    }

    // ---------------------------------------------------------------- trip list

    private void renderList() {
        listPanel.setVisibility(View.VISIBLE);
        detailPanel.setVisibility(View.GONE);
        tripsList.removeAllViews();
        List<Db.Trip> trips = db.trips();
        if (trips.isEmpty()) {
            TextView empty = text("No trips yet.\n\nTap “New trip” and choose a business trip (expenses & receipts "
                    + "for your expense report) or a private trip (plans, bookings, passes and a photo journal).",
                    16, R.color.muted, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Util.dp(a, 24), Util.dp(a, 80), Util.dp(a, 24), 0);
            tripsList.addView(empty);
            return;
        }
        boolean anyBusiness = false;
        for (Db.Trip t : trips) anyBusiness |= t.kind == Db.BUSINESS;
        if (anyBusiness) {
            LinearLayout report = card("📊", "Monthly expense report", "All business expenses of a month, ready "
                    + "to type into your company's expense tool", "›");
            report.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { chooseReportMonth(); }
            });
            tripsList.addView(report);
        }
        long today = Util.startOfDay(System.currentTimeMillis());
        boolean pastHeader = false;
        tripsList.addView(header("Trips"));
        for (final Db.Trip t : trips) {
            boolean past = t.finish != 0 && t.finish < today;
            if (past && !pastHeader) {
                tripsList.addView(header("Past trips"));
                pastHeader = true;
            }
            String sub = (t.destination.isEmpty() ? "" : t.destination) + (t.destination.isEmpty() || t.start == 0 ? "" : " · ")
                    + dateRange(t);
            String extra;
            if (t.kind == Db.BUSINESS) {
                List<Db.Expense> ex = db.expenses(t.id);
                int open = 0;
                for (Db.Expense e : ex) if (!e.submitted) open++;
                extra = ex.size() + " expenses · " + totals(ex) + (open > 0 ? " · " + open + " not submitted" : "");
            } else {
                int places = db.tripItems(t.id, "place").size(), journal = db.tripItems(t.id, "journal").size();
                extra = places + " places · " + journal + " journal entries";
            }
            LinearLayout row = card(t.kind == Db.BUSINESS ? "💼" : "🌴", t.name, sub + (sub.isEmpty() ? "" : "\n") + extra, null);
            row.addView(badge(t.kind == Db.BUSINESS ? "BUSINESS" : "PRIVATE",
                    t.kind == Db.BUSINESS ? 0xFF3F2CB8 : 0xFF1E9C9A));
            if (past) row.setAlpha(0.75f);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { openTrip(t); }
            });
            tripsList.addView(row);
        }
    }

    private void newTrip() {
        new AlertDialog.Builder(a)
                .setTitle("What kind of trip?")
                .setItems(new String[]{"💼  Business trip\n      Expenses & receipts for your expense report",
                        "🌴  Private trip\n      Places to go, bookings, passes, journal & photos"},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                Db.Trip t = new Db.Trip();
                                t.kind = which == 0 ? Db.BUSINESS : Db.PRIVATE;
                                t.currency = a.prefs.getString("currency", "EUR");
                                editTrip(t);
                            }
                        })
                .show();
    }

    private void editTrip(final Db.Trip t) {
        LinearLayout box = Util.dialogBox(a);
        box.addView(Util.label(a, "Trip name"));
        final EditText name = Util.field(a, t.name);
        name.setHint(t.kind == Db.BUSINESS ? "e.g. Client workshop Munich" : "e.g. Summer in Lisbon");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(name);
        box.addView(Util.label(a, "Destination"));
        final EditText dest = Util.field(a, t.destination);
        dest.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(dest);
        final long[] dates = {t.start, t.finish};
        box.addView(Util.label(a, "Dates"));
        LinearLayout dateRow = new LinearLayout(a);
        final Button from = new Button(a, null, android.R.attr.borderlessButtonStyle);
        final Button to = new Button(a, null, android.R.attr.borderlessButtonStyle);
        from.setText(t.start == 0 ? "From…" : Util.shortDate(t.start));
        to.setText(t.finish == 0 ? "To…" : Util.shortDate(t.finish));
        from.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(dates[0], new Picked() {
                    public void day(long d) {
                        dates[0] = d;
                        from.setText(Util.shortDate(d));
                        if (dates[1] != 0 && dates[1] < d) {
                            dates[1] = d;
                            to.setText(Util.shortDate(d));
                        }
                    }
                });
            }
        });
        to.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(dates[1] != 0 ? dates[1] : dates[0], new Picked() {
                    public void day(long d) {
                        dates[1] = d;
                        to.setText(Util.shortDate(d));
                    }
                });
            }
        });
        dateRow.addView(from);
        dateRow.addView(to);
        box.addView(dateRow);
        box.addView(Util.label(a, "Home currency (totals and reports)"));
        final EditText cur = Util.field(a, t.currency);
        cur.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(cur);

        box.addView(Util.label(a, "Other currencies on this trip (your exchange rate)"));
        final LinearLayout rateRows = new LinearLayout(a);
        rateRows.setOrientation(LinearLayout.VERTICAL);
        box.addView(rateRows);
        for (Map.Entry<String, Double> en : TripMath.rates(t.rates).entrySet()) addRateRow(rateRows, en.getKey(), en.getValue());
        Button addRate = new Button(a, null, android.R.attr.borderlessButtonStyle);
        addRate.setText("+ Add a currency");
        addRate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addRateRow(rateRows, "", 0); }
        });
        box.addView(addRate);

        final EditText budget = Util.field(a, t.budget > 0 ? String.format(Locale.getDefault(), "%.2f", t.budget / 100.0) : "");
        final CheckBox perDiem = new CheckBox(a);
        final EditText pdFull = Util.field(a, String.format(Locale.getDefault(), "%.2f", t.pdFull / 100.0));
        final EditText pdPartial = Util.field(a, String.format(Locale.getDefault(), "%.2f", t.pdPartial / 100.0));
        final EditText kmRate = Util.field(a, String.format(Locale.getDefault(), "%.2f", t.kmRate / 100.0));
        for (EditText e : new EditText[]{budget, pdFull, pdPartial, kmRate}) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            e.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        }
        if (t.kind == Db.PRIVATE) {
            box.addView(Util.label(a, "Budget (home currency, optional)"));
            box.addView(budget);
        } else {
            perDiem.setText("Claim per-diem (meal) allowances");
            perDiem.setChecked(t.perDiem);
            box.addView(perDiem);
            box.addView(Util.label(a, "Per diem: full day (24 h) / arrival & departure day"));
            LinearLayout pd = new LinearLayout(a);
            pd.addView(pdFull, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            pd.addView(pdPartial, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(pd);
            box.addView(Util.label(a, "Mileage rate per km (own car)"));
            box.addView(kmRate);
            TextView hint = text("Defaults are German rates (28 / 14 € per diem, 0.30 €/km). Check your company's rules.", 12, R.color.muted, false);
            box.addView(hint);
        }

        new AlertDialog.Builder(a)
                .setTitle(t.id == 0 ? (t.kind == Db.BUSINESS ? "New business trip" : "New private trip") : "Edit trip")
                .setView(scroll(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String n = name.getText().toString().trim();
                        String dst = dest.getText().toString().trim();
                        t.name = !n.isEmpty() ? n : !dst.isEmpty() ? dst : (t.name.isEmpty() ? "Trip" : t.name);
                        t.destination = dst;
                        t.start = dates[0];
                        t.finish = dates[1] != 0 ? dates[1] : dates[0];
                        String c = cur.getText().toString().trim().toUpperCase(Locale.ROOT);
                        t.currency = c.isEmpty() ? "EUR" : c;
                        a.prefs.edit().putString("currency", t.currency).apply();
                        Map<String, Double> rates = new LinkedHashMap<String, Double>();
                        for (int i = 0; i < rateRows.getChildCount(); i++) {
                            View row = rateRows.getChildAt(i);
                            String code = ((EditText) row.findViewWithTag("code")).getText().toString().trim().toUpperCase(Locale.ROOT);
                            long rate = parseAmount(((EditText) row.findViewWithTag("rate")).getText().toString());
                            String raw = ((EditText) row.findViewWithTag("rate")).getText().toString().replace(',', '.').trim();
                            try {
                                double r = Double.parseDouble(raw);
                                if (!code.isEmpty() && r > 0 && !code.equals(t.currency)) rates.put(code, r);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        t.rates = TripMath.ratesString(rates);
                        long b = parseAmount(budget.getText().toString());
                        t.budget = Math.max(0, b);
                        t.perDiem = perDiem.isChecked();
                        if (parseAmount(pdFull.getText().toString()) >= 0) t.pdFull = parseAmount(pdFull.getText().toString());
                        if (parseAmount(pdPartial.getText().toString()) >= 0) t.pdPartial = parseAmount(pdPartial.getText().toString());
                        if (parseAmount(kmRate.getText().toString()) >= 0) t.kmRate = parseAmount(kmRate.getText().toString());
                        boolean isNew = t.id == 0;
                        db.saveTrip(t);
                        if (isNew) openTrip(t);
                        else render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        if (t.id == 0) Util.showKeyboard(name);
    }

    /** One "1 USD = 0.92 EUR" row in the trip editor. */
    private void addRateRow(LinearLayout rows, String code, double rate) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(text("1 ", 15, R.color.text, false));
        EditText c = Util.field(a, code);
        c.setTag("code");
        c.setHint("USD");
        c.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        row.addView(c, new LinearLayout.LayoutParams(Util.dp(a, 80), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(" = ", 15, R.color.text, false));
        EditText r = Util.field(a, rate > 0 ? String.valueOf(rate) : "");
        r.setTag("rate");
        r.setHint("0.92");
        r.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        r.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        row.addView(r, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text(" home", 13, R.color.muted, false));
        rows.addView(row);
    }

    // ---------------------------------------------------------------- trip detail

    private String[] tabsFor(Db.Trip t) {
        return t.kind == Db.BUSINESS
                ? new String[]{TAB_EXPENSES, TAB_BOOKINGS, TAB_PACKING, TAB_NOTES}
                : new String[]{TAB_PLAN, TAB_BOOKINGS, TAB_JOURNAL, TAB_EXPENSES, TAB_PACKING};
    }

    private void openTrip(Db.Trip t) {
        trip = t;
        tab = tabsFor(t)[0];
        a.screenChanged();
        render();
        detailScroll.scrollTo(0, 0);
    }

    @Override
    void open(long id) {
        for (Db.Trip t : db.trips()) {
            if (t.id == id) {
                openTrip(t);
                return;
            }
        }
    }

    /** The trip happening now, else the next one, else the latest; null if there are no trips. */
    static Db.Trip currentTrip(Db db) {
        long today = Util.startOfDay(System.currentTimeMillis());
        Db.Trip next = null, latest = null;
        for (Db.Trip t : db.trips()) {
            if (t.start != 0 && t.start <= today && (t.finish == 0 || t.finish >= today)) return t;
            if (t.start > today && (next == null || t.start < next.start)) next = t;
            if (latest == null || t.start > latest.start) latest = t;
        }
        return next != null ? next : latest;
    }

    /** Widget / shortcut "expense": a new expense on the current trip. */
    @Override
    void quick(String what) {
        Db.Trip t = currentTrip(db);
        if (t == null) {
            newTrip();
            return;
        }
        addReceipt(t, null);
    }

    /** Opens the trip's expenses and starts a new expense, with shared receipt files already attached. */
    void addReceipt(Db.Trip t, List<String> files) {
        openTrip(t);
        tab = TAB_EXPENSES;
        render();
        sharedFiles = files;
        addForTab();
    }

    private void closeTrip() {
        trip = null;
        a.screenChanged();
        render();
    }

    private void renderTrip() {
        listPanel.setVisibility(View.GONE);
        detailPanel.setVisibility(View.VISIBLE);
        tabs.removeAllViews();
        for (final String name : tabsFor(trip)) {
            TextView c = Util.chip(a, name, Util.color(a, R.color.primary), name.equals(tab));
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    tab = name;
                    render();
                    detailScroll.scrollTo(0, 0);
                }
            });
            tabs.addView(c);
        }
        content.removeAllViews();

        // Trip summary card
        String sub = (trip.destination.isEmpty() ? "" : trip.destination + "\n") + dateRange(trip);
        LinearLayout head = card(trip.kind == Db.BUSINESS ? "💼" : "🌴", trip.name, sub.trim(), "✎");
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editTrip(trip); }
        });
        content.addView(head);

        if (TAB_EXPENSES.equals(tab)) renderExpenses();
        else if (TAB_BOOKINGS.equals(tab)) renderItems("booking");
        else if (TAB_PLAN.equals(tab)) renderPlaces();
        else if (TAB_PACKING.equals(tab)) renderPacking();
        else renderItems("journal");

        addItem.setText(TAB_EXPENSES.equals(tab) ? "+  Expense" : TAB_BOOKINGS.equals(tab) ? "+  Booking or pass"
                : TAB_PLAN.equals(tab) ? "+  Place" : TAB_JOURNAL.equals(tab) ? "+  Journal entry"
                : TAB_PACKING.equals(tab) ? "+  Item" : "+  Note");
    }

    private void addForTab() {
        if (TAB_PACKING.equals(tab)) {
            final Store.Checklist l = st.checklistForTrip(trip.id);
            if (l == null) {
                startPacking();
                return;
            }
            Util.prompt(a, "Add to packing list", "", "e.g. Umbrella", "Add", new Util.TextEntered() {
                public void onText(String text) {
                    st.addCheckItem(l.id, text);
                    render();
                    addForTab();
                }
            });
            return;
        }
        if (TAB_EXPENSES.equals(tab)) {
            Db.Expense e = new Db.Expense();
            e.trip = trip.id;
            e.currency = trip.currency;
            long today = Util.startOfDay(System.currentTimeMillis());
            e.day = trip.start != 0 && (today < trip.start || (trip.finish != 0 && today > trip.finish)) ? trip.start : today;
            e.category = a.prefs.getString("lastCategory", EXPENSE_CATS[0]);
            e.payment = a.prefs.getString("lastPayment", trip.kind == Db.BUSINESS ? PAYMENTS[0] : PAYMENTS[1]);
            editExpense(e);
        } else {
            Db.TripItem i = new Db.TripItem();
            i.trip = trip.id;
            i.kind = TAB_BOOKINGS.equals(tab) ? "booking" : TAB_PLAN.equals(tab) ? "place" : "journal";
            if (i.kind.equals("booking")) i.subtype = BOOKING_TYPES[0];
            if (i.kind.equals("journal")) i.day = Util.startOfDay(System.currentTimeMillis());
            editItem(i);
        }
    }

    // ---------------------------------------------------------------- expenses

    private void renderExpenses() {
        final List<Db.Expense> list = db.expenses(trip.id);
        int open = 0;
        for (Db.Expense e : list) if (!e.submitted) open++;
        String line = list.size() + " expense" + (list.size() == 1 ? "" : "s")
                + (trip.kind == Db.BUSINESS && open > 0 ? " · " + open + " not yet submitted" : "");
        boolean multi = false;
        for (Db.Expense e : list) multi |= !e.currency.equalsIgnoreCase(trip.currency);
        if (multi) line += "\nPaid: " + totals(list);
        LinearLayout sum = card("💶", "Total " + homeTotal(list), line, list.isEmpty() ? null : "Report ›");
        sum.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!list.isEmpty()) {
                    reportingTrip = true;
                    showReport("Expenses · " + trip.name, list);
                }
            }
        });
        content.addView(sum);
        if (trip.kind == Db.PRIVATE && trip.budget > 0) content.addView(budgetCard(list));
        if (trip.kind == Db.BUSINESS && trip.perDiem) {
            LinearLayout pd = card("🍽", "Per diem " + money(perDiemTotal(), trip.currency), perDiemText(), null);
            content.addView(pd);
        }
        if (list.isEmpty()) {
            TextView hint = text("Tap “+ Expense” to note a taxi, hotel, dinner… right when it happens. Attach a "
                    + "photo of the receipt so you have everything for the expense report.", 14, R.color.muted, false);
            hint.setPadding(Util.dp(a, 6), Util.dp(a, 16), Util.dp(a, 6), 0);
            content.addView(hint);
            return;
        }
        long lastDay = -1;
        for (final Db.Expense e : list) {
            if (e.day != lastDay) {
                content.addView(header(Util.dayLabel(e.day)));
                lastDay = e.day;
            }
            int cat = indexOf(EXPENSE_CATS, e.category);
            StringBuilder sub = new StringBuilder(e.category);
            if (!e.attendees.isEmpty()) sub.append(" · with ").append(e.attendees);
            if (!e.payment.isEmpty()) sub.append(" · ").append(e.payment);
            int receipts = db.attachmentCount("expense", e.id);
            sub.append(receipts > 0 ? " · 📎 receipt" : (trip.kind == Db.BUSINESS ? " · no receipt" : ""));
            if (e.submitted) sub.append(" · ✓ submitted");
            if (e.km > 0) sub.append(" · ").append(String.format(Locale.getDefault(), "%.0f km", e.km));
            long h = home(e);
            if (!e.currency.equalsIgnoreCase(trip.currency)) sub.append(h >= 0 ? " · ≈ " + money(h, trip.currency) : " · set a rate for " + e.currency);
            LinearLayout row = card(EXPENSE_EMOJI[cat], e.merchant.isEmpty() ? e.category : e.merchant, sub.toString(),
                    money(e.cents, e.currency));
            if (e.submitted) row.setAlpha(0.7f);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { editExpense(e); }
            });
            content.addView(row);
        }
    }

    private long perDiemTotal() {
        int[] d = TripMath.perDiemDays(trip.start, trip.finish);
        return d[0] * trip.pdFull + d[1] * trip.pdPartial;
    }

    private String perDiemText() {
        if (trip.start == 0) return "Set the trip dates to calculate the allowance";
        int[] d = TripMath.perDiemDays(trip.start, trip.finish);
        return (d[0] > 0 ? d[0] + " full day" + (d[0] == 1 ? "" : "s") + " × " + money(trip.pdFull, trip.currency) + " + " : "")
                + d[1] + " arrival/departure day" + (d[1] == 1 ? "" : "s") + " × " + money(trip.pdPartial, trip.currency)
                + "\nMeals paid by others (e.g. hotel breakfast) usually reduce it; check your company's rules.";
    }

    private View budgetCard(List<Db.Expense> list) {
        long spent = 0;
        for (Db.Expense e : list) {
            long h = home(e);
            if (h > 0) spent += h;
        }
        int pct = (int) Math.min(999, spent * 100 / Math.max(1, trip.budget));
        long left = trip.budget - spent;
        LinearLayout c = card("🎯", "Budget " + money(trip.budget, trip.currency), "Spent " + money(spent, trip.currency) + " (" + pct + "%) · "
                + (left >= 0 ? money(left, trip.currency) + " left" : money(-left, trip.currency) + " over budget"), null);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(Math.min(100, pct));
        if (pct > 100) bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Util.color(a, R.color.overdue)));
        ((LinearLayout) c.getChildAt(1)).addView(bar);
        return c;
    }

    private void editExpense(final Db.Expense e) {
        LinearLayout box = Util.dialogBox(a);
        final long[] day = {e.day};
        box.addView(Util.label(a, "Date"));
        final Button dayBtn = new Button(a, null, android.R.attr.borderlessButtonStyle);
        dayBtn.setText(Util.shortDate(e.day));
        dayBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(day[0], new Picked() {
                    public void day(long d) {
                        day[0] = d;
                        dayBtn.setText(Util.shortDate(d));
                    }
                });
            }
        });
        box.addView(dayBtn);

        box.addView(Util.label(a, "Category"));
        final Spinner cat = spinner(EXPENSE_CATS, indexOf(EXPENSE_CATS, e.category));
        box.addView(cat);

        box.addView(Util.label(a, "Amount and currency"));
        LinearLayout amountRow = new LinearLayout(a);
        final EditText amount = Util.field(a, e.cents > 0 ? String.format(Locale.getDefault(), "%.2f", e.cents / 100.0) : "");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        amount.setHint("0.00");
        amountRow.addView(amount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final EditText cur = Util.field(a, e.currency);
        cur.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        amountRow.addView(cur, new LinearLayout.LayoutParams(Util.dp(a, 80), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(amountRow);
        LinearLayout curChips = new LinearLayout(a);
        List<String> currencies = new ArrayList<String>();
        currencies.add(trip.currency);
        currencies.addAll(TripMath.rates(trip.rates).keySet());
        if (currencies.size() > 1) {
            for (final String code : currencies) {
                TextView chip = Util.chip(a, code, Util.color(a, R.color.primary), false);
                chip.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { cur.setText(code); }
                });
                curChips.addView(chip);
            }
            box.addView(curChips);
        }
        final TextView converted = text("", 13, R.color.muted, false);
        box.addView(converted);
        final TextView kmLabel = Util.label(a, "Kilometres driven");
        final EditText km = Util.field(a, e.km > 0 ? String.format(Locale.getDefault(), "%.0f", e.km) : "");
        km.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        km.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        box.addView(kmLabel);
        box.addView(km);
        final Runnable updateConverted = new Runnable() {
            public void run() {
                boolean mileage = MILEAGE.equals(EXPENSE_CATS[cat.getSelectedItemPosition()]);
                kmLabel.setVisibility(mileage ? View.VISIBLE : View.GONE);
                km.setVisibility(mileage ? View.VISIBLE : View.GONE);
                if (mileage) {
                    long kmCents = parseAmount(km.getText().toString());
                    if (kmCents >= 0) {
                        long cents = Math.round(kmCents / 100.0 * trip.kmRate);
                        converted.setText(String.format(Locale.getDefault(), "%.0f km × %s = %s", kmCents / 100.0,
                                money(trip.kmRate, trip.currency), money(cents, trip.currency)));
                    }
                    return;
                }
                String c = cur.getText().toString().trim().toUpperCase(Locale.ROOT);
                long cents = parseAmount(amount.getText().toString());
                if (c.isEmpty() || c.equals(trip.currency) || cents <= 0) {
                    converted.setText("");
                    return;
                }
                long h = TripMath.toHome(cents, c, trip.currency, TripMath.rates(trip.rates));
                converted.setText(h >= 0 ? "≈ " + money(h, trip.currency) : "No rate for " + c + " yet: you'll be asked after saving");
            }
        };
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence x, int s1, int c1, int a1) {}
            public void onTextChanged(CharSequence x, int s1, int b1, int c1) {}
            public void afterTextChanged(android.text.Editable ed) { updateConverted.run(); }
        };
        amount.addTextChangedListener(watcher);
        cur.addTextChangedListener(watcher);
        km.addTextChangedListener(watcher);
        cat.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { updateConverted.run(); }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        box.addView(Util.label(a, "Where / what"));
        final EditText merchant = Util.field(a, e.merchant);
        merchant.setHint("e.g. Uber airport → hotel, Restaurant Lorenz");
        box.addView(merchant);

        box.addView(Util.label(a, "With whom (meals, entertainment)"));
        final EditText who = Util.field(a, e.attendees);
        who.setHint("Names, company");
        who.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(who);

        box.addView(Util.label(a, "Paid with"));
        final Spinner pay = spinner(PAYMENTS, indexOf(PAYMENTS, e.payment));
        box.addView(pay);

        box.addView(Util.label(a, "Notes"));
        final EditText note = Util.field(a, e.note);
        note.setHint("Business purpose, project / WBS code…");
        box.addView(note);

        box.addView(Util.label(a, "Receipt"));
        final AttachmentStrip receipts = new AttachmentStrip(a, "Receipt");
        receipts.load("expense", e.id);
        if (sharedFiles != null) {
            for (String f : sharedFiles) receipts.addPending(f);
            sharedFiles = null;
        }
        box.addView(receipts.view);

        final CheckBox submitted = new CheckBox(a);
        submitted.setText("Already submitted to the expense tool");
        submitted.setChecked(e.submitted);
        if (trip.kind == Db.BUSINESS) box.addView(submitted);

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(e.id == 0 ? "New expense" : "Edit expense")
                .setView(scroll(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        e.category = EXPENSE_CATS[cat.getSelectedItemPosition()];
                        long cents = parseAmount(amount.getText().toString());
                        String c = cur.getText().toString().trim().toUpperCase(Locale.ROOT);
                        e.currency = c.isEmpty() ? trip.currency : c;
                        e.km = 0;
                        if (MILEAGE.equals(e.category)) {
                            long kmCents = parseAmount(km.getText().toString());
                            e.km = Math.max(0, kmCents / 100.0);
                            cents = Math.round(e.km * trip.kmRate);
                            e.currency = trip.currency;
                        }
                        if (cents < 0) {
                            Toast.makeText(a, "Amount not saved: please enter a number like 12.50", Toast.LENGTH_LONG).show();
                            cents = e.cents;
                        }
                        e.day = day[0];
                        e.cents = Math.max(0, cents);
                        e.merchant = merchant.getText().toString().trim();
                        e.attendees = who.getText().toString().trim();
                        e.payment = PAYMENTS[pay.getSelectedItemPosition()];
                        e.note = note.getText().toString().trim();
                        e.submitted = submitted.isChecked();
                        a.prefs.edit().putString("lastCategory", e.category).putString("lastPayment", e.payment).apply();
                        db.saveExpense(e);
                        receipts.commit("expense", e.id);
                        render();
                        if (!e.currency.equals(trip.currency) && !TripMath.rates(trip.rates).containsKey(e.currency)) askRate(e.currency);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { receipts.discard(); }
                });
        if (e.id != 0) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    receipts.discard();
                    Util.confirm(a, "Delete this expense?", null, "Delete", new Runnable() {
                        public void run() {
                            Attachments.delete(a, db.deleteExpense(e.id));
                            render();
                        }
                    });
                }
            });
        }
        AlertDialog dlg = b.create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { receipts.discard(); }
        });
        dlg.show();
        if (e.id == 0) Util.showKeyboard(amount);
    }

    /** Asks "1 USD = ? EUR" for a currency that has no rate on this trip yet. */
    private void askRate(final String code) {
        final EditText rate = Util.field(a, "");
        rate.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        rate.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        rate.setHint("e.g. 0.92");
        new AlertDialog.Builder(a).setTitle("Exchange rate for " + code)
                .setMessage("1 " + code + " = how many " + trip.currency + "? (Use the rate from your bank or card statement.)")
                .setView(Util.wrap(a, rate))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        try {
                            double r = Double.parseDouble(rate.getText().toString().replace(',', '.').trim());
                            if (r <= 0) return;
                            Map<String, Double> rates = TripMath.rates(trip.rates);
                            rates.put(code, r);
                            trip.rates = TripMath.ratesString(rates);
                            db.saveTrip(trip);
                            render();
                        } catch (NumberFormatException ignored) {
                        }
                    }
                })
                .setNegativeButton("Later", null).show();
        Util.showKeyboard(rate);
    }

    // ---------------------------------------------------------------- packing list

    private void renderPacking() {
        final Store.Checklist l = st.checklistForTrip(trip.id);
        if (l == null) {
            TextView t = text("Start a packing list for this trip from one of your checklist templates (edit templates in "
                    + "the Checklists module), or from scratch.", 14, R.color.muted, false);
            t.setPadding(Util.dp(a, 6), Util.dp(a, 16), Util.dp(a, 6), Util.dp(a, 8));
            content.addView(t);
            LinearLayout start = card("🧳", "Start packing list", "Choose a template", "›");
            start.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { startPacking(); }
            });
            content.addView(start);
            return;
        }
        List<Store.CheckItem> items = st.checkItems(l.id);
        int done = 0;
        for (Store.CheckItem i : items) if (i.done) done++;
        LinearLayout head = card("🧳", "Packed " + done + " of " + items.size(), "Tap to tick · long-press to edit", "Reset");
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                st.resetChecklist(l.id);
                render();
            }
        });
        content.addView(head);
        for (final Store.CheckItem i : items) {
            LinearLayout row = card(i.done ? "☑" : "☐", i.text, null, null);
            if (i.done) {
                TextView t = (TextView) row.findViewWithTag("title");
                t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                t.setTextColor(Util.color(a, R.color.muted));
            }
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    st.setCheckItem(i.id, i.text, !i.done);
                    render();
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    Util.prompt(a, "Edit item", i.text, "Clear the text to delete", "Save", new Util.TextEntered() {
                        public void onText(String text) {
                            st.setCheckItem(i.id, text, i.done);
                            render();
                        }
                    });
                    return true;
                }
            });
            content.addView(row);
        }
    }

    private void startPacking() {
        final List<Store.Checklist> templates = st.checklists(true);
        List<String> names = new ArrayList<String>();
        for (Store.Checklist t : templates) names.add("📋 " + t.title + " (" + t.total + ")");
        names.add("Empty list");
        new AlertDialog.Builder(a).setTitle("Start from…").setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                String title = "Packing · " + trip.name;
                if (which < templates.size()) st.copyChecklist(templates.get(which).id, title, false, trip.id);
                else st.addChecklist(title, false, trip.id);
                render();
            }
        }).show();
    }

    // ---------------------------------------------------------------- bookings, places, journal

    private void renderItems(final String kind) {
        List<Db.TripItem> items = db.tripItems(trip.id, kind);
        if (items.isEmpty()) {
            String hint = kind.equals("booking")
                    ? "Keep hotel bookings, flights, train tickets and local passes here. Add screenshots or PDFs "
                    + "so you have everything at hand, even offline."
                    : trip.kind == Db.BUSINESS ? "Notes for this trip: agenda, contacts, addresses…"
                    : "Write down what you did and saw each day and add photos. Export it later as a travel diary (⋮ menu).";
            TextView t = text(hint, 14, R.color.muted, false);
            t.setPadding(Util.dp(a, 6), Util.dp(a, 16), Util.dp(a, 6), 0);
            content.addView(t);
            return;
        }
        long lastDay = -2;
        for (final Db.TripItem i : items) {
            if (kind.equals("journal") && i.day != lastDay) {
                content.addView(header(i.day == 0 ? "Undated" : Util.dayLabel(i.day)));
                lastDay = i.day;
            }
            int n = db.attachmentCount("trip_item", i.id);
            String emoji = kind.equals("booking") ? BOOKING_EMOJI[indexOf(BOOKING_TYPES, i.subtype)] : null;
            String when = kind.equals("booking") && i.day != 0 ? Util.shortDate(i.day) : "";
            String sub = when + (when.isEmpty() || i.body.isEmpty() ? "" : " · ") + i.body.trim();
            if (n > 0) sub += (sub.isEmpty() ? "" : "\n") + (kind.equals("booking") ? "📎 " : "📷 ") + n;
            String title = !i.title.isEmpty() ? i.title : kind.equals("booking") ? i.subtype : "Entry";
            LinearLayout row = card(emoji, title, sub, null);
            if (kind.equals("journal") && n > 0) {
                LinearLayout col = (LinearLayout) row.getChildAt(0);
                List<Db.Attachment> pics = db.attachments("trip_item", i.id);
                LinearLayout thumbs = new LinearLayout(a);
                for (int k = 0; k < Math.min(4, pics.size()); k++) {
                    android.widget.ImageView iv = new android.widget.ImageView(a);
                    iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                    android.graphics.Bitmap bm = Attachments.thumb(a, pics.get(k).file, Util.dp(a, 128));
                    if (bm != null) iv.setImageBitmap(bm);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Util.dp(a, 64), Util.dp(a, 64));
                    lp.rightMargin = Util.dp(a, 6);
                    lp.topMargin = Util.dp(a, 6);
                    thumbs.addView(iv, lp);
                }
                col.addView(thumbs);
            }
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { editItem(i); }
            });
            content.addView(row);
        }
    }

    private void renderPlaces() {
        List<Db.TripItem> items = db.tripItems(trip.id, "place");
        if (items.isEmpty()) {
            TextView t = text("Your list of places to go: sights, restaurants, beaches, museums… Tick them off as you "
                    + "visit them.", 14, R.color.muted, false);
            t.setPadding(Util.dp(a, 6), Util.dp(a, 16), Util.dp(a, 6), 0);
            content.addView(t);
            return;
        }
        int done = 0;
        for (Db.TripItem i : items) if (i.done) done++;
        content.addView(header("To go · " + (items.size() - done)));
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1 && done > 0) content.addView(header("Been there · " + done));
            for (final Db.TripItem i : items) {
                if (i.done != (pass == 1)) continue;
                String sub = (i.day != 0 ? Util.shortDate(i.day) : "") + (i.day != 0 && !i.body.isEmpty() ? " · " : "") + i.body.trim();
                LinearLayout row = card(null, i.title, sub, null);
                CheckBox cb = new CheckBox(a);
                cb.setChecked(i.done);
                cb.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        i.done = !i.done;
                        db.saveTripItem(i);
                        render();
                    }
                });
                row.addView(cb, 0);
                if (i.done) {
                    TextView t = (TextView) row.findViewWithTag("title");
                    t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                    t.setTextColor(Util.color(a, R.color.muted));
                }
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { editItem(i); }
                });
                content.addView(row);
            }
        }
    }

    private void editItem(final Db.TripItem i) {
        final boolean booking = i.kind.equals("booking"), place = i.kind.equals("place");
        LinearLayout box = Util.dialogBox(a);
        Spinner type = null;
        if (booking) {
            box.addView(Util.label(a, "Type"));
            type = spinner(BOOKING_TYPES, indexOf(BOOKING_TYPES, i.subtype));
            box.addView(type);
        }
        box.addView(Util.label(a, booking ? "Name" : place ? "Place" : "Place / title"));
        final EditText title = Util.field(a, i.title);
        title.setHint(booking ? "e.g. Hotel Adlon, ICE 597 FRA → BER, Lisboa Card" : place ? "e.g. Belém Tower"
                : "e.g. Alfama & Fado night");
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(title);

        final long[] day = {i.day};
        box.addView(Util.label(a, booking ? "Date (check-in / departure)" : place ? "Planned day (optional)" : "Date"));
        final Button dayBtn = new Button(a, null, android.R.attr.borderlessButtonStyle);
        dayBtn.setText(i.day == 0 ? "No date" : Util.shortDate(i.day));
        dayBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(day[0] != 0 ? day[0] : trip.start, new Picked() {
                    public void day(long d) {
                        day[0] = d;
                        dayBtn.setText(d == 0 ? "No date" : Util.shortDate(d));
                    }
                });
            }
        });
        box.addView(dayBtn);

        box.addView(Util.label(a, booking ? "Details" : place ? "Notes / address" : "What happened"));
        final EditText body = Util.field(a, i.body);
        body.setHint(booking ? "Confirmation number, address, times, seat…" : place ? "Address, opening hours, link…"
                : "What you did, saw, ate, who you met…");
        body.setMinLines(booking || place ? 2 : 4);
        box.addView(body);

        AttachmentStrip strip = null;
        if (!place) {
            box.addView(Util.label(a, booking ? "Screenshots / PDFs" : "Photos"));
            strip = new AttachmentStrip(a, booking ? "Add file" : "Add photo");
            strip.load("trip_item", i.id);
            box.addView(strip.view);
        }
        final AttachmentStrip files = strip;
        final Spinner typeSpinner = type;

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(i.id == 0 ? (booking ? "New booking or pass" : place ? "New place" : "New entry")
                        : (booking ? "Booking" : place ? "Place" : "Entry"))
                .setView(scroll(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String t = title.getText().toString().trim();
                        if (place && t.isEmpty()) {
                            if (files != null) files.discard();
                            return;
                        }
                        i.title = t;
                        i.body = body.getText().toString().trim();
                        i.day = day[0];
                        if (typeSpinner != null) i.subtype = BOOKING_TYPES[typeSpinner.getSelectedItemPosition()];
                        db.saveTripItem(i);
                        if (files != null) files.commit("trip_item", i.id);
                        render();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (files != null) files.discard();
                    }
                });
        if (i.id != 0) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    if (files != null) files.discard();
                    Util.confirm(a, "Delete this?", null, "Delete", new Runnable() {
                        public void run() {
                            Attachments.delete(a, db.deleteTripItem(i.id));
                            render();
                        }
                    });
                }
            });
        }
        AlertDialog dlg = b.create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) {
                if (files != null) files.discard();
            }
        });
        dlg.show();
        if (i.id == 0) Util.showKeyboard(title);
    }

    // ---------------------------------------------------------------- reports

    private void chooseReportMonth() {
        final List<Long> monthStarts = new ArrayList<Long>();
        List<String> labels = new ArrayList<String>();
        SimpleDateFormat f = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
        for (long day : db.businessExpenseDays()) {
            long m = monthStart(day);
            if (!monthStarts.contains(m)) {
                monthStarts.add(m);
                labels.add(f.format(new Date(m)));
            }
        }
        if (monthStarts.isEmpty()) {
            Toast.makeText(a, "No business expenses yet", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Expense report for…")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        long from = monthStarts.get(which);
                        Calendar c = Calendar.getInstance();
                        c.setTimeInMillis(from);
                        c.add(Calendar.MONTH, 1);
                        String title = "Expenses · " + new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(new Date(from));
                        reportingTrip = false;
                        showReport(title, db.businessExpenses(from, Util.startOfDay(c.getTimeInMillis())));
                    }
                })
                .show();
    }

    private static long monthStart(long day) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(day);
        c.set(Calendar.DAY_OF_MONTH, 1);
        return Util.startOfDay(c.getTimeInMillis());
    }

    /** True while showing a single trip's report (per diem is added); false for the monthly report. */
    private boolean reportingTrip;

    private String tripNameOf(Db.Expense e) {
        return e.tripName != null ? e.tripName : trip != null ? trip.name : "";
    }

    /** The list to copy into the company expense tool: one block per expense, oldest first. */
    private String reportText(String title, List<Db.Expense> list) {
        SimpleDateFormat f = new SimpleDateFormat("EEE dd.MM.yyyy", Locale.getDefault());
        StringBuilder sb = new StringBuilder(title).append('\n');
        sb.append("Total: ").append(homeTotal(list)).append(" (").append(list.size()).append(" expenses)\n");
        if (trip != null && trip.kind == Db.BUSINESS && trip.perDiem && reportingTrip) {
            sb.append("Per diem allowance: ").append(money(perDiemTotal(), trip.currency)).append(" (")
                    .append(perDiemText().split("\n")[0]).append(")\n");
        }
        sb.append('\n');
        Map<String, List<Db.Expense>> byCat = new LinkedHashMap<String, List<Db.Expense>>();
        for (String c : EXPENSE_CATS) byCat.put(c, new ArrayList<Db.Expense>());
        for (Db.Expense e : list) {
            if (!byCat.containsKey(e.category)) byCat.put(e.category, new ArrayList<Db.Expense>());
            byCat.get(e.category).add(e);
        }
        sb.append("By category\n");
        for (Map.Entry<String, List<Db.Expense>> en : byCat.entrySet()) {
            if (!en.getValue().isEmpty()) sb.append("  ").append(en.getKey()).append(": ").append(homeTotal(en.getValue())).append('\n');
        }
        sb.append("\nDetails\n");
        for (int k = 0; k < list.size(); k++) {
            Db.Expense e = list.get(k);
            sb.append("\n").append(f.format(new Date(e.day))).append(" · ").append(tripNameOf(e)).append('\n');
            sb.append("  ").append(e.category).append(" — ").append(money(e.cents, e.currency));
            if (!e.currency.equalsIgnoreCase(homeOf(e)) && home(e) >= 0) sb.append(" (≈ ").append(money(home(e), homeOf(e))).append(")");
            if (e.km > 0) sb.append(String.format(Locale.getDefault(), " — %.0f km", e.km));
            if (!e.merchant.isEmpty()) sb.append(" — ").append(e.merchant);
            sb.append('\n');
            if (!e.attendees.isEmpty()) sb.append("  With: ").append(e.attendees).append('\n');
            sb.append("  ").append(e.payment)
                    .append(db.attachmentCount("expense", e.id) > 0 ? " · receipt attached" : " · no receipt")
                    .append(e.submitted ? " · submitted" : "").append('\n');
            if (!e.note.isEmpty()) sb.append("  Note: ").append(e.note).append('\n');
        }
        return sb.toString();
    }

    private String reportHtml(String title, List<Db.Expense> list) {
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        StringBuilder h = new StringBuilder(Report.HEAD);
        h.append("<h1>").append(Report.esc(title)).append("</h1><p><b>Total: ").append(Report.esc(homeTotal(list)))
                .append("</b> · ").append(list.size()).append(" expenses</p>");
        if (trip != null && trip.kind == Db.BUSINESS && trip.perDiem && reportingTrip) {
            h.append("<p>Per diem allowance: <b>").append(Report.esc(money(perDiemTotal(), trip.currency))).append("</b> (")
                    .append(Report.esc(perDiemText().split("\n")[0])).append(")</p>");
        }
        h.append("<table><tr><th>#</th><th>Date</th><th>Trip</th><th>Category</th><th>Where / what</th><th>With</th>"
                + "<th>Paid with</th><th class='n'>Amount</th><th>Receipt</th></tr>");
        List<String[]> receipts = new ArrayList<String[]>();
        int n = 0;
        for (int k = 0; k < list.size(); k++) {
            Db.Expense e = list.get(k);
            n++;
            List<Db.Attachment> att = db.attachments("expense", e.id);
            for (Db.Attachment at : att) receipts.add(new String[]{String.valueOf(n), at.file});
            h.append("<tr><td>").append(n).append("</td><td>").append(f.format(new Date(e.day))).append("</td><td>")
                    .append(Report.esc(tripNameOf(e))).append("</td><td>").append(Report.esc(e.category)).append("</td><td>")
                    .append(Report.esc(e.merchant)).append(e.note.isEmpty() ? "" : "<br><span class='muted'>" + Report.esc(e.note) + "</span>")
                    .append("</td><td>").append(Report.esc(e.attendees)).append("</td><td>").append(Report.esc(e.payment))
                    .append("</td><td class='n'>").append(Report.esc(money(e.cents, e.currency)))
                    .append(!e.currency.equalsIgnoreCase(homeOf(e)) && home(e) >= 0 ? "<br><span class='muted'>≈ " + Report.esc(money(home(e), homeOf(e))) + "</span>" : "")
                    .append("</td><td>")
                    .append(att.isEmpty() ? "–" : "✓").append(e.submitted ? " <span class='muted'>(submitted)</span>" : "")
                    .append("</td></tr>");
        }
        h.append("</table>");
        for (String[] r : receipts) {
            h.append("<div class='receipt'><h3>Receipt for #").append(r[0]).append("</h3>");
            if (Attachments.isPdf(r[1])) h.append("<p class='muted'>PDF receipt (kept in the app)</p>");
            else h.append("<img src='att/").append(r[1]).append("'>");
            h.append("</div>");
        }
        return h.append("</body></html>").toString();
    }

    private void showReport(final String title, List<Db.Expense> expenses) {
        // Oldest first, the order an expense tool asks for them.
        final List<Db.Expense> list = new ArrayList<Db.Expense>(expenses);
        java.util.Collections.sort(list, new java.util.Comparator<Db.Expense>() {
            public int compare(Db.Expense x, Db.Expense y) {
                return x.day < y.day ? -1 : x.day > y.day ? 1 : (x.id < y.id ? -1 : x.id > y.id ? 1 : 0);
            }
        });
        TextView t = text(reportText(title, list), 13, R.color.text, false);
        t.setTextIsSelectable(true);
        t.setPadding(Util.dp(a, 20), Util.dp(a, 8), Util.dp(a, 20), Util.dp(a, 8));
        int open = 0;
        for (Db.Expense e : list) if (!e.submitted) open++;
        final boolean allSubmitted = open == 0;
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(scroll(t))
                .setPositiveButton("Export…", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { exportOptions(title, list, allSubmitted); }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void exportOptions(final String title, final List<Db.Expense> list, final boolean allSubmitted) {
        new AlertDialog.Builder(a).setTitle("Export")
                .setItems(new String[]{"📊 Excel / CSV file → share (e.g. email to work)", "💾 Save Excel / CSV file to the phone",
                        "📄 PDF with receipt photos", "✉ Share as text", allSubmitted ? "Mark all as not submitted" : "✓ Mark all as submitted"},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) shareCsv(title, list);
                                else if (which == 1) saveCsv(title, list);
                                else if (which == 2) a.printHtml(title, reportHtml(title, list));
                                else if (which == 3) a.shareText(title, reportText(title, list));
                                else {
                                    db.setSubmitted(list, !allSubmitted);
                                    Toast.makeText(a, allSubmitted ? "Marked as not submitted" : list.size() + " expenses marked as submitted",
                                            Toast.LENGTH_SHORT).show();
                                    render();
                                }
                            }
                        }).show();
    }

    /** Excel-friendly CSV: semicolons and decimal commas where the phone uses them (e.g. German Excel). */
    private String csv(List<Db.Expense> list) {
        char dec = java.text.DecimalFormatSymbols.getInstance().getDecimalSeparator();
        char sep = dec == ',' ? ';' : ',';
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(Csv.row(sep, "Date", "Trip", "Category", "Where / what", "With whom", "Paid with", "Amount", "Currency",
                "Amount (home)", "Home currency", "Km", "Receipt", "Submitted", "Notes"));
        for (Db.Expense e : list) {
            long h = home(e);
            sb.append(Csv.row(sep, f.format(new Date(e.day)), tripNameOf(e), e.category, e.merchant, e.attendees, e.payment,
                    num(e.cents, dec), e.currency, h >= 0 ? num(h, dec) : "", homeOf(e),
                    e.km > 0 ? String.format(Locale.ROOT, "%.0f", e.km) : "",
                    db.attachmentCount("expense", e.id) > 0 ? "yes" : "no", e.submitted ? "yes" : "no", e.note));
        }
        if (trip != null && trip.kind == Db.BUSINESS && trip.perDiem && reportingTrip) {
            sb.append(Csv.row(sep, trip.start == 0 ? "" : f.format(new Date(trip.start)), trip.name, "Per diem allowance",
                    perDiemText().split("\n")[0], "", "", num(perDiemTotal(), dec), trip.currency, num(perDiemTotal(), dec),
                    trip.currency, "", "", "", ""));
        }
        return sb.toString();
    }

    private static String num(long cents, char dec) {
        return String.format(Locale.ROOT, "%.2f", cents / 100.0).replace('.', dec);
    }

    private String csvName(String title) {
        return title.replaceAll("[^A-Za-z0-9äöüÄÖÜß ._-]", "").trim().replace(' ', '-') + ".csv";
    }

    private void shareCsv(String title, List<Db.Expense> list) {
        try {
            String name = java.util.UUID.randomUUID() + ".csv";
            java.io.OutputStream os = new java.io.FileOutputStream(Attachments.file(a, name));
            try {
                os.write(csv(list).getBytes("UTF-8"));
            } finally {
                os.close();
            }
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/csv")
                    .putExtra(android.content.Intent.EXTRA_STREAM, Files.attachment(name))
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                    .putExtra(android.content.Intent.EXTRA_TEXT, title + " (open the attached file with Excel)")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startExternal(android.content.Intent.createChooser(send, "Send expense file"));
            Attachments.file(a, name).deleteOnExit();
        } catch (java.io.IOException e) {
            Toast.makeText(a, "Could not create the file", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveCsv(final String title, final List<Db.Expense> list) {
        a.createDocument(csvName(title), "text/csv", new MainActivity.UriPicked() {
            public void onUri(android.net.Uri uri) {
                try {
                    java.io.OutputStream os = a.getContentResolver().openOutputStream(uri);
                    try {
                        os.write(csv(list).getBytes("UTF-8"));
                    } finally {
                        os.close();
                    }
                    Toast.makeText(a, "Saved", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(a, "Could not save the file", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** Travel diary for a private trip: places visited and the journal with photos. */
    private void travelDiary() {
        SimpleDateFormat f = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault());
        StringBuilder h = new StringBuilder(Report.HEAD);
        h.append("<div class='cover'><h1>").append(Report.esc(trip.name)).append("</h1><p>")
                .append(Report.esc(trip.destination)).append("</p><p>").append(Report.esc(dateRange(trip))).append("</p></div>");
        List<Db.TripItem> places = db.tripItems(trip.id, "place");
        StringBuilder visited = new StringBuilder();
        for (Db.TripItem p : places) if (p.done) visited.append("<li>").append(Report.esc(p.title)).append("</li>");
        if (visited.length() > 0) h.append("<h2>Places we went</h2><ul>").append(visited).append("</ul>");
        long lastDay = -2;
        for (Db.TripItem j : db.tripItems(trip.id, "journal")) {
            if (j.day != lastDay) {
                h.append("<h2>").append(j.day == 0 ? "More memories" : Report.esc(f.format(new Date(j.day)))).append("</h2>");
                lastDay = j.day;
            }
            h.append("<div class='entry'>");
            if (!j.title.isEmpty()) h.append("<h3>").append(Report.esc(j.title)).append("</h3>");
            if (!j.body.isEmpty()) h.append("<p>").append(Report.para(j.body)).append("</p>");
            h.append(Report.photos(db.attachments("trip_item", j.id))).append("</div>");
        }
        a.printHtml(trip.name, h.append("</body></html>").toString());
    }

    // ---------------------------------------------------------------- menu

    @Override
    void onCreateMenu(Menu menu) {
        menu.add(0, MENU_REPORT_MONTH, 0, "Monthly expense report");
        menu.add(0, MENU_EDIT, 1, "Edit trip");
        menu.add(0, MENU_TRIP_REPORT, 2, "Expense report for this trip");
        menu.add(0, MENU_TRAVEL_DIARY, 3, "Travel diary (PDF)");
        menu.add(0, MENU_DELETE, 4, "Delete trip");
    }

    @Override
    void onPrepareMenu(Menu menu) {
        boolean open = trip != null;
        menu.findItem(MENU_REPORT_MONTH).setVisible(!open);
        menu.findItem(MENU_EDIT).setVisible(open);
        menu.findItem(MENU_TRIP_REPORT).setVisible(open);
        menu.findItem(MENU_TRAVEL_DIARY).setVisible(open && trip.kind == Db.PRIVATE);
        menu.findItem(MENU_DELETE).setVisible(open);
    }

    @Override
    boolean onMenu(int id) {
        switch (id) {
            case MENU_REPORT_MONTH:
                chooseReportMonth();
                return true;
            case MENU_EDIT:
                editTrip(trip);
                return true;
            case MENU_TRIP_REPORT:
                List<Db.Expense> list = db.expenses(trip.id);
                if (list.isEmpty()) Toast.makeText(a, "No expenses on this trip yet", Toast.LENGTH_SHORT).show();
                else {
                    reportingTrip = true;
                    showReport("Expenses · " + trip.name, list);
                }
                return true;
            case MENU_TRAVEL_DIARY:
                travelDiary();
                return true;
            case MENU_DELETE:
                final Db.Trip t = trip;
                Util.confirm(a, "Delete \"" + t.name + "\"?", "All its expenses, bookings, notes and photos will be "
                        + "deleted for good.", "Delete", new Runnable() {
                    public void run() {
                        Attachments.delete(a, db.deleteTrip(t.id));
                        closeTrip();
                    }
                });
                return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- small helpers

    private interface Picked {
        void day(long day);
    }

    private void pickDate(long current, final Picked p) {
        Calendar c = Calendar.getInstance();
        if (current > 0) c.setTimeInMillis(current);
        new DatePickerDialog(a, new DatePickerDialog.OnDateSetListener() {
            public void onDateSet(DatePicker view, int y, int m, int d) {
                Calendar picked = Calendar.getInstance();
                picked.clear();
                picked.set(y, m, d);
                p.day(picked.getTimeInMillis());
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private Spinner spinner(String[] items, int selected) {
        Spinner s = new Spinner(a);
        s.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, items));
        s.setSelection(selected);
        return s;
    }

    private View scroll(View v) {
        ScrollView s = new ScrollView(a);
        s.addView(v);
        return s;
    }
}
