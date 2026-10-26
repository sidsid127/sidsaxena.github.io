package com.sidsaxena.daybook;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Notes in the style of the iPhone Notes app: a searchable list (pinned first) and a full-screen editor
 * whose first line is the title. Notes save automatically.
 */
class NotesScreen extends Screen {

    private static final int MENU_PIN = 110;
    private static final int MENU_SHARE = 111;
    private static final int MENU_DELETE = 112;

    private final View listPanel, editorPanel;
    private final EditText search, editor;
    private final TextView editorDate;
    private final ListView list;

    private Db.Note open;        // note being edited, null while showing the list
    private boolean dirty;

    /** Rows: Db.Note or Util.Header. */
    private final List<Object> rows = new ArrayList<Object>();
    private final RowAdapter adapter = new RowAdapter();

    NotesScreen(MainActivity activity) {
        super(activity, R.layout.screen_notes);
        listPanel = root.findViewById(R.id.list_panel);
        editorPanel = root.findViewById(R.id.editor_panel);
        search = (EditText) root.findViewById(R.id.search);
        editor = (EditText) root.findViewById(R.id.editor);
        editorDate = (TextView) root.findViewById(R.id.editor_date);
        list = (ListView) root.findViewById(R.id.list);
        TextView empty = (TextView) root.findViewById(R.id.empty);
        empty.setText(R.string.empty_notes);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        root.findViewById(R.id.new_note).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Db.Note n = new Db.Note();
                n.body = "";
                n.modified = System.currentTimeMillis();
                openEditor(n);
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (row instanceof Db.Note) openEditor((Db.Note) row);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (!(row instanceof Db.Note)) return false;
                final Db.Note n = (Db.Note) row;
                new android.app.AlertDialog.Builder(a)
                        .setTitle(titleOf(n.body))
                        .setItems(new String[]{n.pinned ? "Unpin" : "Pin to top", "Delete"},
                                new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int which) {
                                        if (which == 0) {
                                            db.setPinned(n.id, !n.pinned);
                                            reload();
                                        } else {
                                            confirmDelete(n);
                                        }
                                    }
                                })
                        .show();
                return true;
            }
        });
        search.addTextChangedListener(new SimpleWatcher() {
            public void afterTextChanged(Editable s) { reload(); }
        });
        editor.addTextChangedListener(new SimpleWatcher() {
            public void afterTextChanged(Editable s) {
                dirty = true;
                styleTitle(s);
            }
        });
    }

    @Override
    String title() {
        return open != null ? "" : a.getString(R.string.module_notes);
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
        closeEditor();
    }

    @Override
    void onStop() {
        save();
    }

    @Override
    boolean onBack() {
        if (open == null) return false;
        closeEditor();
        return true;
    }

    // ---------------------------------------------------------------- list

    private void reload() {
        rows.clear();
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        boolean pinnedHeader = false, othersHeader = false;
        List<Db.Note> notes = db.notes();
        boolean anyPinned = false;
        for (Db.Note n : notes) anyPinned |= n.pinned;
        for (Db.Note n : notes) {
            if (!q.isEmpty() && !n.body.toLowerCase(Locale.getDefault()).contains(q)) continue;
            if (n.pinned && !pinnedHeader) {
                rows.add(new Util.Header("Pinned", 0));
                pinnedHeader = true;
            } else if (!n.pinned && anyPinned && !othersHeader) {
                rows.add(new Util.Header("Notes", 0));
                othersHeader = true;
            }
            rows.add(n);
        }
        adapter.notifyDataSetChanged();
    }

    static String titleOf(String body) {
        String t = body.trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        return t.isEmpty() ? "New note" : t;
    }

    private static String previewOf(String body) {
        String t = body.trim();
        int nl = t.indexOf('\n');
        if (nl < 0) return "No additional text";
        String rest = t.substring(nl + 1).trim().replace('\n', ' ');
        return rest.isEmpty() ? "No additional text" : rest;
    }

    private void confirmDelete(final Db.Note n) {
        Util.confirm(a, "Delete this note?", titleOf(n.body), "Delete", new Runnable() {
            public void run() {
                if (n.id != 0) db.deleteNote(n.id);
                if (open == n) {
                    open = null;
                    dirty = false;
                    showEditor(false);
                }
                reload();
            }
        });
    }

    // ---------------------------------------------------------------- editor

    private void openEditor(Db.Note n) {
        open = n;
        editor.setText(n.body);
        dirty = false;
        editorDate.setText(n.id == 0 ? "" : Util.fullTimestamp(a, n.modified));
        showEditor(true);
        if (n.body.isEmpty()) Util.showKeyboard(editor);
    }

    private void closeEditor() {
        if (open == null) return;
        save();
        Util.hideKeyboard(editor);
        open = null;
        showEditor(false);
        reload();
    }

    private void showEditor(boolean show) {
        editorPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        listPanel.setVisibility(show ? View.GONE : View.VISIBLE);
        a.screenChanged();
    }

    /** Saves the open note; an emptied note is deleted. */
    private void save() {
        if (open == null || !dirty) return;
        String body = editor.getText().toString();
        if (body.trim().isEmpty()) {
            if (open.id != 0) db.deleteNote(open.id);
            open.id = 0;
        } else if (open.id == 0) {
            open.id = db.addNote(body);
        } else {
            db.updateNote(open.id, body);
        }
        open.body = body;
        open.modified = System.currentTimeMillis();
        dirty = false;
    }

    /** Makes the first line look like a title, as in Notes. */
    private void styleTitle(Editable s) {
        for (StyleSpan span : s.getSpans(0, s.length(), StyleSpan.class)) s.removeSpan(span);
        for (RelativeSizeSpan span : s.getSpans(0, s.length(), RelativeSizeSpan.class)) s.removeSpan(span);
        int end = s.toString().indexOf('\n');
        if (end < 0) end = s.length();
        if (end == 0) return;
        s.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        s.setSpan(new RelativeSizeSpan(1.3f), 0, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
    }

    // ---------------------------------------------------------------- menu

    @Override
    void onCreateMenu(Menu menu) {
        menu.add(0, MENU_PIN, 0, "Pin");
        menu.add(0, MENU_SHARE, 1, "Share note");
        menu.add(0, MENU_DELETE, 2, "Delete note");
    }

    @Override
    void onPrepareMenu(Menu menu) {
        boolean editing = open != null;
        menu.findItem(MENU_PIN).setVisible(editing).setTitle(editing && open.pinned ? "Unpin" : "Pin to top");
        menu.findItem(MENU_SHARE).setVisible(editing);
        menu.findItem(MENU_DELETE).setVisible(editing);
    }

    @Override
    boolean onMenu(int id) {
        if (open == null) return false;
        switch (id) {
            case MENU_PIN:
                save();
                if (open.id == 0) return true; // nothing written yet
                open.pinned = !open.pinned;
                db.setPinned(open.id, open.pinned);
                return true;
            case MENU_SHARE:
                save();
                a.shareText(titleOf(open.body), open.body);
                return true;
            case MENU_DELETE:
                confirmDelete(open);
                return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- adapter

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }
        public int getViewTypeCount() { return 2; }
        public boolean areAllItemsEnabled() { return false; }
        public int getItemViewType(int position) { return rows.get(position) instanceof Db.Note ? 0 : 1; }
        public boolean isEnabled(int position) { return getItemViewType(position) == 0; }

        public View getView(int position, View view, ViewGroup parent) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (getItemViewType(position) == 0) {
                if (view == null) view = inf.inflate(R.layout.item_note, parent, false);
                Db.Note n = (Db.Note) rows.get(position);
                ((TextView) view.findViewById(R.id.title)).setText((n.pinned ? "📌 " : "") + titleOf(n.body));
                ((TextView) view.findViewById(R.id.preview)).setText(
                        Util.relativeStamp(a, n.modified) + "   " + previewOf(n.body));
                return view;
            }
            if (view == null) view = inf.inflate(R.layout.item_header, parent, false);
            ((TextView) view).setText(((Util.Header) rows.get(position)).text);
            return view;
        }
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
