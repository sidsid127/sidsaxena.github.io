package com.sidsaxena.daybook;

import android.app.DatePickerDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One entry per day: mood, how the day was, highlights, lowlights and photos. Exports as a PDF book. */
class DiaryScreen extends Screen {

    private static final int MENU_BOOK = 140;
    private static final int MENU_SHARE = 141;
    private static final int MENU_REMIND = 144;

    static final String[] WEATHER = {"☀️", "🌤", "☁️", "🌧", "⛈", "❄️", "🌫", "💨"};
    private static final String[] WEATHER_NAMES = {"Sunny", "Partly cloudy", "Cloudy", "Rain", "Storm", "Snow", "Fog", "Windy"};
    private TextView placeView;
    private LinearLayout weatherRow;
    private EditText tempField;
    private String weather = "";
    private static final int MENU_DELETE = 142;
    private static final int MENU_DATE = 143;

    static final String[] MOODS = {"", "😞", "🙁", "😐", "🙂", "😄"};
    private static final String[] MOOD_NAMES = {"", "Awful", "Bad", "Okay", "Good", "Great"};

    private final View listPanel, editorPanel;
    private final ListView list;
    private final TextView summary, date;
    private final EditText text, highs, lows;
    private final LinearLayout moods;
    private final AttachmentStrip photos;

    private Db.Diary open;   // entry being edited
    private int mood;
    private final List<Db.Diary> entries = new ArrayList<Db.Diary>();
    private final Adapter adapter = new Adapter();

    DiaryScreen(MainActivity activity) {
        super(activity, R.layout.screen_diary);
        listPanel = root.findViewById(R.id.list_panel);
        editorPanel = root.findViewById(R.id.editor_panel);
        list = (ListView) root.findViewById(R.id.list);
        summary = (TextView) root.findViewById(R.id.summary);
        date = (TextView) root.findViewById(R.id.date);
        text = (EditText) root.findViewById(R.id.text);
        highs = (EditText) root.findViewById(R.id.highs);
        lows = (EditText) root.findViewById(R.id.lows);
        moods = (LinearLayout) root.findViewById(R.id.moods);
        photos = new AttachmentStrip(a, "Add photo");
        buildPlaceAndWeather();
        ((FrameLayout) root.findViewById(R.id.photos)).addView(photos.view);
        list.setAdapter(adapter);
        list.setEmptyView(root.findViewById(R.id.empty));

        root.findViewById(R.id.write_today).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openDay(Util.startOfDay(System.currentTimeMillis())); }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                edit(entries.get(position));
            }
        });
        date.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeDate(); }
        });
        for (int i = 1; i <= 5; i++) {
            final int m = i;
            TextView t = new TextView(a);
            t.setText(MOODS[i] + "\n" + MOOD_NAMES[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(12);
            t.setTextColor(Util.color(a, R.color.muted));
            t.setPadding(0, Util.dp(a, 8), 0, Util.dp(a, 8));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mood = mood == m ? 0 : m;
                    paintMoods();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.rightMargin = Util.dp(a, 6);
            moods.addView(t, lp);
        }
    }

    @Override
    String title() {
        return open != null ? "" : a.getString(R.string.module_diary);
    }

    @Override
    boolean homeIsBack() {
        return open != null;
    }

    @Override
    void onShow() {
        if (open == null) reload();
    }

    @Override
    void onHide() {
        close();
    }

    @Override
    void onStop() {
        save();
    }

    @Override
    boolean onBack() {
        if (open == null) return false;
        close();
        return true;
    }

    // ---------------------------------------------------------------- list

    private void reload() {
        entries.clear();
        entries.addAll(db.diary());
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        long monthStart = Util.startOfDay(c.getTimeInMillis());
        int thisMonth = 0;
        for (Db.Diary d : entries) if (d.day >= monthStart) thisMonth++;
        summary.setText(entries.isEmpty() ? "" : entries.size() + " entries · " + thisMonth + " this month · "
                + streak() + "-day streak");
        adapter.notifyDataSetChanged();
    }

    /** Consecutive days written, ending today or yesterday. */
    private int streak() {
        long today = Util.startOfDay(System.currentTimeMillis());
        long expect = 0;
        int n = 0;
        for (Db.Diary d : entries) { // newest first
            if (n == 0) {
                if (d.day == today) expect = prevDay(today);
                else if (d.day == prevDay(today)) expect = prevDay(d.day);
                else break;
                n = 1;
            } else if (d.day == expect) {
                n++;
                expect = prevDay(expect);
            } else {
                break;
            }
        }
        return n;
    }

    private static long prevDay(long day) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(day);
        c.add(Calendar.DAY_OF_MONTH, -1);
        return Util.startOfDay(c.getTimeInMillis());
    }

    private static String preview(Db.Diary d) {
        String s = !d.text.trim().isEmpty() ? d.text : !d.highs.trim().isEmpty() ? "☀ " + d.highs : d.lows;
        return s.trim().replace('\n', ' ');
    }

    // ---------------------------------------------------------------- editor

    @Override
    void open(long id) {
        if (id > 0) openDay(Util.startOfDay(id));
    }

    @Override
    void quick(String what) {
        openDay(Util.startOfDay(System.currentTimeMillis()));
    }

    /** Place (from GPS, or typed) and weather (one tap) under the mood row. */
    private void buildPlaceAndWeather() {
        LinearLayout parent = (LinearLayout) moods.getParent();
        int at = parent.indexOfChild(moods) + 1;
        TextView label = new TextView(a);
        label.setText("WHERE & WEATHER");
        label.setTextSize(12);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        label.setTextColor(Util.color(a, R.color.muted));
        label.setPadding(0, Util.dp(a, 18), 0, Util.dp(a, 6));
        parent.addView(label, at++);
        placeView = new TextView(a);
        placeView.setTextSize(15);
        placeView.setTextColor(Util.color(a, R.color.primary_text));
        placeView.setPadding(0, Util.dp(a, 6), 0, Util.dp(a, 6));
        placeView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { placeOptions(); }
        });
        parent.addView(placeView, at++);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        weatherRow = new LinearLayout(a);
        hs.addView(weatherRow);
        parent.addView(hs, at++);
        tempField = new EditText(a);
        tempField.setHint("Temperature, e.g. 18°");
        tempField.setTextSize(15);
        tempField.setSingleLine(true);
        tempField.setTextColor(Util.color(a, R.color.text));
        tempField.setHintTextColor(Util.color(a, R.color.muted));
        parent.addView(tempField, at);
    }

    private void paintPlace() {
        placeView.setText(open.place.isEmpty() ? "📍 Add location" : "📍 " + open.place + "   ✎");
    }

    private void paintWeather() {
        weatherRow.removeAllViews();
        for (int i = 0; i < WEATHER.length; i++) {
            final String w = WEATHER[i];
            TextView c = Util.chip(a, w + " " + WEATHER_NAMES[i], Util.color(a, R.color.primary), w.equals(weather));
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    weather = weather.equals(w) ? "" : w;
                    paintWeather();
                }
            });
            weatherRow.addView(c);
        }
    }

    private void placeOptions() {
        new android.app.AlertDialog.Builder(a).setItems(new String[]{"Use my current location", "Type a place", "Remove location"},
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            a.withPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                    "Doable uses your location only to tag this diary entry.", new Runnable() {
                                        public void run() { locate(); }
                                    });
                        } else if (which == 1) {
                            Util.prompt(a, "Where were you?", open.place, "e.g. Munich, Lisbon old town", "Save", new Util.TextEntered() {
                                public void onText(String text) {
                                    open.place = text;
                                    paintPlace();
                                }
                            });
                        } else {
                            open.place = "";
                            open.lat = 0;
                            open.lon = 0;
                            paintPlace();
                        }
                    }
                }).show();
    }

    /** Last known position from the phone (no internet needed), turned into a place name where possible. */
    @SuppressWarnings({"MissingPermission", "deprecation"})
    private void locate() {
        android.location.LocationManager lm = (android.location.LocationManager) a.getSystemService(android.content.Context.LOCATION_SERVICE);
        android.location.Location best = null;
        for (String p : lm.getProviders(true)) {
            android.location.Location l = lm.getLastKnownLocation(p);
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 30 * 60 * 1000) {
            usePosition(best);
            return;
        }
        String provider = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                ? android.location.LocationManager.NETWORK_PROVIDER : android.location.LocationManager.GPS_PROVIDER;
        if (!lm.isProviderEnabled(provider)) {
            if (best != null) usePosition(best);
            else Toast.makeText(a, "Turn on location in the phone's quick settings", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(a, "Finding your location…", Toast.LENGTH_SHORT).show();
        lm.requestSingleUpdate(provider, new android.location.LocationListener() {
            public void onLocationChanged(android.location.Location l) { usePosition(l); }
            public void onStatusChanged(String p, int status, android.os.Bundle extras) {}
            public void onProviderEnabled(String p) {}
            public void onProviderDisabled(String p) {}
        }, android.os.Looper.getMainLooper());
    }

    private void usePosition(final android.location.Location l) {
        if (open == null) return;
        final Db.Diary target = open;
        target.lat = l.getLatitude();
        target.lon = l.getLongitude();
        target.place = String.format(Locale.US, "%.4f, %.4f", l.getLatitude(), l.getLongitude());
        paintPlace();
        if (!android.location.Geocoder.isPresent()) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    List<android.location.Address> list = new android.location.Geocoder(a, Locale.getDefault())
                            .getFromLocation(l.getLatitude(), l.getLongitude(), 1);
                    if (list == null || list.isEmpty()) return;
                    android.location.Address ad = list.get(0);
                    final String name = (ad.getLocality() != null ? ad.getLocality() : ad.getSubAdminArea() != null ? ad.getSubAdminArea() : "")
                            + (ad.getCountryName() != null ? ", " + ad.getCountryName() : "");
                    if (name.replace(",", "").trim().isEmpty()) return;
                    a.runOnUiThread(new Runnable() {
                        public void run() {
                            target.place = name.startsWith(", ") ? name.substring(2) : name;
                            if (open == target) paintPlace();
                        }
                    });
                } catch (Exception ignored) {
                    // no place name available offline: keep the coordinates
                }
            }
        }).start();
    }

    private void reminderSettings() {
        final int cur = Reminders.diaryTime(a);
        new android.app.AlertDialog.Builder(a).setTitle("Daily diary reminder")
                .setMessage(cur >= 0 ? "On, every day at " + TasksScreen.clock(cur) + " (skipped when you've already written)." : "Off")
                .setPositiveButton(cur >= 0 ? "Change time" : "Turn on", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        int t = cur >= 0 ? cur : 21 * 60;
                        new android.app.TimePickerDialog(a, new android.app.TimePickerDialog.OnTimeSetListener() {
                            public void onTimeSet(android.widget.TimePicker v, int h, int m) {
                                a.prefs.edit().putInt("diaryRemind", h * 60 + m).apply();
                                a.ensureNotifications();
                                Reminders.refresh(a);
                                Toast.makeText(a, "Reminder every day at " + TasksScreen.clock(h * 60 + m), Toast.LENGTH_SHORT).show();
                            }
                        }, t / 60, t % 60, android.text.format.DateFormat.is24HourFormat(a)).show();
                    }
                })
                .setNegativeButton(cur >= 0 ? "Turn off" : "Cancel", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (cur < 0) return;
                        a.prefs.edit().putInt("diaryRemind", -1).apply();
                        Reminders.refresh(a);
                        Toast.makeText(a, "Diary reminder off", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void openDay(long day) {
        Db.Diary d = db.diaryFor(day);
        if (d == null) {
            d = new Db.Diary();
            d.day = day;
        }
        edit(d);
    }

    private void edit(Db.Diary d) {
        open = d;
        mood = d.mood;
        text.setText(d.text);
        highs.setText(d.highs);
        lows.setText(d.lows);
        photos.load("diary", d.id);
        weather = d.weather;
        tempField.setText(d.temp);
        paintPlace();
        paintWeather();
        paintDate();
        paintMoods();
        listPanel.setVisibility(View.GONE);
        editorPanel.setVisibility(View.VISIBLE);
        editorPanel.scrollTo(0, 0);
        a.screenChanged();
        if (d.id == 0) Util.showKeyboard(text);
    }

    private void paintDate() {
        date.setText(new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(new Date(open.day)) + "  ▾");
    }

    private void paintMoods() {
        for (int i = 0; i < 5; i++) {
            TextView t = (TextView) moods.getChildAt(i);
            boolean on = mood == i + 1;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Util.dp(a, 12));
            bg.setColor(on ? Util.color(a, R.color.selected) : 0);
            bg.setStroke(Util.dp(a, on ? 2 : 1), Util.color(a, on ? R.color.primary_text : R.color.divider));
            t.setBackground(bg);
            t.setTextColor(Util.color(a, on ? R.color.primary_text : R.color.muted));
        }
    }

    /** Saves the open entry; an entry with nothing in it is removed. */
    private void save() {
        if (open == null) return;
        open.mood = mood;
        open.text = text.getText().toString();
        open.highs = highs.getText().toString();
        open.lows = lows.getText().toString();
        open.weather = weather;
        open.temp = tempField.getText().toString().trim();
        if (open.isEmpty() && photos.count() == 0) {
            if (open.id != 0) {
                Attachments.delete(a, db.deleteDiaryWithFiles(open.id));
                open.id = 0;
            }
            photos.load("diary", 0);
            return;
        }
        db.saveDiary(open);
        if (photos.dirty()) photos.commit("diary", open.id);
    }

    private void close() {
        if (open == null) return;
        save();
        Util.hideKeyboard(text);
        open = null;
        editorPanel.setVisibility(View.GONE);
        listPanel.setVisibility(View.VISIBLE);
        a.screenChanged();
        reload();
    }

    private void changeDate() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(open.day);
        new DatePickerDialog(a, new DatePickerDialog.OnDateSetListener() {
            public void onDateSet(DatePicker view, int y, int m, int d) {
                Calendar picked = Calendar.getInstance();
                picked.clear();
                picked.set(y, m, d);
                long day = picked.getTimeInMillis();
                if (day == open.day) return;
                Db.Diary other = db.diaryFor(day);
                if (other != null) {
                    save();
                    Toast.makeText(a, "Opened your existing entry for that day", Toast.LENGTH_SHORT).show();
                    edit(other);
                    return;
                }
                open.day = day;
                paintDate();
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ---------------------------------------------------------------- menu & export

    @Override
    void onCreateMenu(Menu menu) {
        menu.add(0, MENU_DATE, 0, "Change date");
        menu.add(0, MENU_DELETE, 1, "Delete this entry");
        menu.add(0, MENU_BOOK, 2, "Export diary as a book (PDF)");
        menu.add(0, MENU_SHARE, 3, "Share diary as text");
        menu.add(0, MENU_REMIND, 4, "Daily reminder…");
    }

    @Override
    void onPrepareMenu(Menu menu) {
        boolean editing = open != null;
        menu.findItem(MENU_DATE).setVisible(editing);
        menu.findItem(MENU_DELETE).setVisible(editing);
        menu.findItem(MENU_BOOK).setVisible(!editing);
        menu.findItem(MENU_SHARE).setVisible(!editing);
    }

    @Override
    boolean onMenu(int id) {
        switch (id) {
            case MENU_DATE:
                changeDate();
                return true;
            case MENU_DELETE:
                final Db.Diary d = open;
                Util.confirm(a, "Delete this diary entry?", "Its text and photos will be gone for good.", "Delete", new Runnable() {
                    public void run() {
                        photos.discard();
                        if (d.id != 0) Attachments.delete(a, db.deleteDiaryWithFiles(d.id));
                        open = null;
                        editorPanel.setVisibility(View.GONE);
                        listPanel.setVisibility(View.VISIBLE);
                        a.screenChanged();
                        reload();
                    }
                });
                return true;
            case MENU_BOOK:
                exportBook();
                return true;
            case MENU_REMIND:
                reminderSettings();
                return true;
            case MENU_SHARE:
                a.shareText("My Diary", exportText());
                return true;
        }
        return false;
    }

    private List<Db.Diary> chronological() {
        List<Db.Diary> all = db.diary();
        Collections.reverse(all);
        return all;
    }

    private String exportText() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault());
        for (Db.Diary d : chronological()) {
            sb.append(f.format(new Date(d.day)));
            if (d.mood > 0) sb.append("  ").append(MOODS[d.mood]).append(' ').append(MOOD_NAMES[d.mood]);
            sb.append('\n');
            if (!d.text.trim().isEmpty()) sb.append(d.text.trim()).append('\n');
            if (!d.highs.trim().isEmpty()) sb.append("Highlights: ").append(d.highs.trim()).append('\n');
            if (!d.lows.trim().isEmpty()) sb.append("Lowlights: ").append(d.lows.trim()).append('\n');
            sb.append('\n');
        }
        return sb.length() == 0 ? "The diary is empty." : sb.toString();
    }

    private void exportBook() {
        List<Db.Diary> all = chronological();
        if (all.isEmpty()) {
            Toast.makeText(a, "Write a first entry, then export", Toast.LENGTH_SHORT).show();
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault());
        SimpleDateFormat monthF = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
        StringBuilder h = new StringBuilder(Report.HEAD);
        h.append("<div class='cover'><h1>My Diary</h1><p>").append(Report.esc(f.format(new Date(all.get(0).day))))
                .append(" – ").append(Report.esc(f.format(new Date(all.get(all.size() - 1).day)))).append("</p><p>")
                .append(all.size()).append(" days</p></div>");
        String month = "";
        for (Db.Diary d : all) {
            String m = monthF.format(new Date(d.day));
            if (!m.equals(month)) {
                h.append("<h1 class='month'>").append(Report.esc(m)).append("</h1>");
                month = m;
            }
            h.append("<div class='entry'><h2>").append(Report.esc(f.format(new Date(d.day))));
            if (d.mood > 0) h.append(" <span class='mood'>").append(MOODS[d.mood]).append(' ').append(MOOD_NAMES[d.mood]).append("</span>");
            h.append("</h2>");
            if (!d.text.trim().isEmpty()) h.append("<p>").append(Report.para(d.text)).append("</p>");
            if (!d.highs.trim().isEmpty()) h.append("<h3>☀ Highlights</h3><p>").append(Report.para(d.highs)).append("</p>");
            if (!d.lows.trim().isEmpty()) h.append("<h3>☁ Lowlights</h3><p>").append(Report.para(d.lows)).append("</p>");
            if (!d.place.isEmpty() || !d.weather.isEmpty()) {
                h.append("<p class='muted'>").append(d.place.isEmpty() ? "" : "📍 " + Report.esc(d.place) + "  ")
                        .append(d.weather).append(d.temp.isEmpty() ? "" : " " + Report.esc(d.temp)).append("</p>");
            }
            h.append(Report.photos(db.attachments("diary", d.id)));
            h.append("</div>");
        }
        h.append("</body></html>");
        a.printHtml("My Diary", h.toString());
    }

    // ---------------------------------------------------------------- adapter

    private class Adapter extends BaseAdapter {
        public int getCount() { return entries.size(); }
        public Object getItem(int position) { return entries.get(position); }
        public long getItemId(int position) { return position; }

        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_diary, parent, false);
            Db.Diary d = entries.get(position);
            Date day = new Date(d.day);
            ((TextView) view.findViewById(R.id.daynum)).setText(new SimpleDateFormat("d", Locale.getDefault()).format(day));
            ((TextView) view.findViewById(R.id.month)).setText(new SimpleDateFormat("MMM yy", Locale.getDefault()).format(day));
            int pics = db.attachmentCount("diary", d.id);
            ((TextView) view.findViewById(R.id.title)).setText(Util.dayLabel(d.day) + (pics > 0 ? "   📷 " + pics : ""));
            ((TextView) view.findViewById(R.id.preview)).setText(preview(d));
            ((TextView) view.findViewById(R.id.mood)).setText(MOODS[d.mood] + d.weather);
            if (!d.place.isEmpty()) ((TextView) view.findViewById(R.id.preview)).setText("📍 " + d.place + " · " + preview(d));
            return view;
        }
    }
}
