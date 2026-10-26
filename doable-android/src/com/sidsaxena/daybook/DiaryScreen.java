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
            ((TextView) view.findViewById(R.id.mood)).setText(MOODS[d.mood]);
            return view;
        }
    }
}
