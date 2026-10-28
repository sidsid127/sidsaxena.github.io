package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.security.keystore.UserNotAuthenticatedException;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Notes in the style of the iPhone Notes app: folders, colours, pinned notes, search, checklists, light
 * formatting, links between notes, photos / scans / voice notes, locked notes and "Recently deleted".
 */
class NotesScreen extends Screen {

    private static final int MENU_PIN = 110, MENU_SHARE = 111, MENU_DELETE = 112, MENU_COLOR = 113,
            MENU_FOLDER = 114, MENU_LOCK = 115, MENU_EMPTY_TRASH = 116;
    private static final String TRASH = "\u0000trash";
    /** Separates a locked note's visible title from its encrypted content. */
    static final String LOCK_MARK = "⁠LOCKED:";

    static final int[] COLORS = {0, 0xFFFFE89A, 0xFFBDEBC9, 0xFFBFDCFF, 0xFFFFC9DD, 0xFFDCCBFF, 0xFFFFD2A6};
    static final String[] COLOR_NAMES = {"None", "Yellow", "Green", "Blue", "Pink", "Purple", "Orange"};

    private final View listPanel, editorPanel;
    private final EditText search, editor;
    private final TextView editorDate;
    private final ListView list;
    private final LinearLayout folders, toolbar;
    private final AttachmentStrip attachments;
    private final SecureBox box;

    private String folder = "";  // "" = all notes, TRASH = recently deleted
    private Db.Note open;        // note being edited
    private boolean dirty, formatting;
    private int newlineAt = -1;

    private final List<Object> rows = new ArrayList<Object>();
    private final RowAdapter adapter = new RowAdapter();

    private interface Op {
        void run() throws Exception;
    }

    NotesScreen(MainActivity activity) {
        super(activity, R.layout.screen_notes);
        listPanel = root.findViewById(R.id.list_panel);
        editorPanel = root.findViewById(R.id.editor_panel);
        search = (EditText) root.findViewById(R.id.search);
        editor = (EditText) root.findViewById(R.id.editor);
        editorDate = (TextView) root.findViewById(R.id.editor_date);
        list = (ListView) root.findViewById(R.id.list);
        folders = (LinearLayout) root.findViewById(R.id.folders);
        toolbar = (LinearLayout) root.findViewById(R.id.toolbar);
        attachments = new AttachmentStrip(a, "Attach", true);
        ((FrameLayout) root.findViewById(R.id.attachments)).addView(attachments.view);
        attachments.setOnAdded(new Runnable() {
            public void run() { dirty = true; }
        });
        box = new SecureBox(a);
        list.setAdapter(adapter);
        list.setEmptyView(root.findViewById(R.id.empty));
        folder = a.prefs.getString("noteFolder", "");

        root.findViewById(R.id.new_note).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { newNote(); }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (!(row instanceof Db.Note)) return;
                Db.Note n = (Db.Note) row;
                if (n.deleted > 0) trashOptions(n);
                else openNote(n);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (!(row instanceof Db.Note)) return false;
                Db.Note n = (Db.Note) row;
                if (n.deleted > 0) trashOptions(n);
                else noteOptions(n);
                return true;
            }
        });
        search.addTextChangedListener(new SimpleWatcher() {
            public void afterTextChanged(Editable s) { reload(); }
        });
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!formatting && count == 1 && before == 0 && start < s.length() && s.charAt(start) == '\n') newlineAt = start;
            }

            public void afterTextChanged(Editable s) {
                if (formatting) return;
                dirty = true;
                formatting = true;
                continueList(s);
                NoteFormat.style(s, Util.color(a, R.color.muted), Util.color(a, R.color.primary_text));
                formatting = false;
            }
        });
        editor.setOnTouchListener(new View.OnTouchListener() {
            private float x, y;
            private long down;

            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    x = e.getX();
                    y = e.getY();
                    down = e.getEventTime();
                } else if (e.getAction() == MotionEvent.ACTION_UP && e.getEventTime() - down < 350
                        && Math.hypot(e.getX() - x, e.getY() - y) < Util.dp(a, 10)) {
                    return tapped(editor.getOffsetForPosition(e.getX(), e.getY()));
                }
                return false;
            }
        });
        buildToolbar();
    }

    @Override
    String title() {
        return open != null ? "" : folder.equals(TRASH) ? "Recently deleted" : a.getString(R.string.module_notes);
    }

    @Override
    boolean homeIsBack() {
        return open != null || folder.equals(TRASH);
    }

    @Override
    void onShow() {
        if (open == null) {
            Attachments.delete(a, db.purgeDeletedNotes());
            reload();
        }
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
        if (open != null) {
            closeEditor();
            return true;
        }
        if (folder.equals(TRASH)) {
            setFolder("");
            return true;
        }
        return false;
    }

    @Override
    void open(long id) {
        for (Db.Note n : db.notes()) if (n.id == id && n.deleted == 0) openNote(n);
    }

    @Override
    void quick(String what) {
        newNote();
    }

    // ---------------------------------------------------------------- folders

    private List<String> folderNames() {
        Set<String> names = new LinkedHashSet<String>();
        for (String f : a.prefs.getString("noteFolders", "").split("\n")) if (!f.trim().isEmpty()) names.add(f.trim());
        for (Db.Note n : db.notes()) if (!n.folder.isEmpty() && n.deleted == 0) names.add(n.folder);
        return new ArrayList<String>(names);
    }

    private void saveFolderNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(n).append('\n');
        a.prefs.edit().putString("noteFolders", sb.toString()).apply();
    }

    private void setFolder(String f) {
        folder = f;
        a.prefs.edit().putString("noteFolder", f.equals(TRASH) ? "" : f).apply();
        a.screenChanged();
        reload();
    }

    private void buildFolders() {
        folders.removeAllViews();
        int primary = Util.color(a, R.color.primary);
        addChip("All notes", primary, folder.isEmpty(), new View.OnClickListener() {
            public void onClick(View v) { setFolder(""); }
        }, null);
        for (final String f : folderNames()) {
            addChip("📁 " + f, primary, folder.equals(f), new View.OnClickListener() {
                public void onClick(View v) { setFolder(f); }
            }, new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    folderOptions(f);
                    return true;
                }
            });
        }
        addChip("+ Folder", Util.color(a, R.color.muted), false, new View.OnClickListener() {
            public void onClick(View v) {
                Util.prompt(a, "New folder", "", "e.g. Work, Ideas, Recipes", "Add", new Util.TextEntered() {
                    public void onText(String name) {
                        List<String> names = folderNames();
                        if (!names.contains(name)) names.add(name);
                        saveFolderNames(names);
                        setFolder(name);
                    }
                });
            }
        }, null);
        int trash = 0;
        for (Db.Note n : db.notes()) if (n.deleted > 0) trash++;
        if (trash > 0 || folder.equals(TRASH)) {
            addChip("🗑 Recently deleted (" + trash + ")", Util.color(a, R.color.muted), folder.equals(TRASH), new View.OnClickListener() {
                public void onClick(View v) { setFolder(TRASH); }
            }, null);
        }
    }

    private void addChip(String text, int color, boolean on, View.OnClickListener click, View.OnLongClickListener longClick) {
        TextView c = Util.chip(a, text, color, on);
        c.setOnClickListener(click);
        if (longClick != null) c.setOnLongClickListener(longClick);
        folders.addView(c);
    }

    private void folderOptions(final String f) {
        new AlertDialog.Builder(a).setTitle(f).setItems(new String[]{"Rename", "Delete folder (keeps notes)"},
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            Util.prompt(a, "Rename folder", f, "", "Save", new Util.TextEntered() {
                                public void onText(String name) { moveFolder(f, name); }
                            });
                        } else {
                            moveFolder(f, "");
                        }
                    }
                }).show();
    }

    private void moveFolder(String from, String to) {
        for (Db.Note n : db.notes()) {
            if (n.folder.equals(from)) {
                n.folder = to;
                db.saveNoteMeta(n);
            }
        }
        List<String> names = folderNames();
        names.remove(from);
        if (!to.isEmpty() && !names.contains(to)) names.add(to);
        saveFolderNames(names);
        setFolder(to);
    }

    // ---------------------------------------------------------------- list

    private void reload() {
        buildFolders();
        rows.clear();
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        boolean trash = folder.equals(TRASH);
        List<Db.Note> shown = new ArrayList<Db.Note>();
        for (Db.Note n : db.notes()) {
            if (trash != (n.deleted > 0)) continue;
            if (!trash && !folder.isEmpty() && !n.folder.equals(folder)) continue;
            String searchable = n.locked ? titleOf(n.body) : n.body;
            if (!q.isEmpty() && !searchable.toLowerCase(Locale.getDefault()).contains(q)) continue;
            shown.add(n);
        }
        boolean anyPinned = false;
        for (Db.Note n : shown) anyPinned |= n.pinned && !trash;
        boolean pinnedHeader = false, othersHeader = false;
        for (Db.Note n : shown) {
            if (!trash && n.pinned && !pinnedHeader) {
                rows.add(new Util.Header("Pinned", 0));
                pinnedHeader = true;
            } else if (!trash && !n.pinned && anyPinned && !othersHeader) {
                rows.add(new Util.Header("Notes", 0));
                othersHeader = true;
            }
            rows.add(n);
        }
        if (trash && !shown.isEmpty()) rows.add(0, new Util.Header("Deleted notes are removed for good after 30 days", 0));
        ((TextView) root.findViewById(R.id.empty)).setText(trash ? "Nothing in Recently deleted." : q.isEmpty()
                ? "No notes here yet.\nTap + to write one." : "No notes match your search.");
        adapter.notifyDataSetChanged();
    }

    static String titleOf(String body) {
        String t = body.trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        t = t.replaceFirst("^#+\\s*", "").replace("**", "");
        return t.isEmpty() ? "New note" : t;
    }

    private static String previewOf(Db.Note n) {
        if (n.locked) return "🔒 Locked";
        String t = n.body.trim();
        int nl = t.indexOf('\n');
        if (nl < 0) return "No additional text";
        String rest = NoteFormat.plain(t.substring(nl + 1)).trim().replace('\n', ' ');
        return rest.isEmpty() ? "No additional text" : rest;
    }

    private void noteOptions(final Db.Note n) {
        new AlertDialog.Builder(a)
                .setTitle(titleOf(n.body))
                .setItems(new String[]{n.pinned ? "Unpin" : "Pin to top", "Colour…", "Move to folder…", "Delete", "Share…"},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                if (which == 4) {
                                    SpacesUi.shareMenu(a, "notes", "notes", n.id, new Runnable() {
                                        public void run() { reload(); }
                                    });
                                } else if (which == 0) {
                                    n.pinned = !n.pinned;
                                    db.saveNoteMeta(n);
                                    reload();
                                } else if (which == 1) {
                                    chooseColor(n);
                                } else if (which == 2) {
                                    chooseFolder(n);
                                } else {
                                    moveToTrash(n);
                                }
                            }
                        })
                .show();
    }

    private void trashOptions(final Db.Note n) {
        new AlertDialog.Builder(a)
                .setTitle(titleOf(n.body))
                .setItems(new String[]{"Restore", "Delete for good"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            n.deleted = 0;
                            db.saveNoteMeta(n);
                            Toast.makeText(a, "Note restored", Toast.LENGTH_SHORT).show();
                        } else {
                            deleteForGood(n);
                        }
                        reload();
                    }
                })
                .show();
    }

    private void deleteForGood(Db.Note n) {
        List<String> files = new ArrayList<String>();
        for (Db.Attachment at : db.attachments("note", n.id)) {
            files.add(at.file);
            db.deleteAttachment(at.id);
        }
        Attachments.delete(a, files);
        db.deleteNote(n.id);
    }

    private void moveToTrash(Db.Note n) {
        if (n.id == 0) return;
        n.deleted = System.currentTimeMillis();
        n.pinned = false;
        db.saveNoteMeta(n);
        Toast.makeText(a, "Moved to Recently deleted", Toast.LENGTH_SHORT).show();
        reload();
    }

    private void chooseColor(final Db.Note n) {
        new AlertDialog.Builder(a).setTitle("Colour").setItems(COLOR_NAMES, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                n.color = COLORS[which];
                if (n.id != 0) db.saveNoteMeta(n);
                reload();
            }
        }).show();
    }

    private void chooseFolder(final Db.Note n) {
        final List<String> names = folderNames();
        List<String> items = new ArrayList<String>();
        items.add("No folder");
        items.addAll(names);
        items.add("+ New folder…");
        new AlertDialog.Builder(a).setTitle("Move to folder").setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which == names.size() + 1) {
                    Util.prompt(a, "New folder", "", "", "Add", new Util.TextEntered() {
                        public void onText(String name) {
                            names.add(name);
                            saveFolderNames(names);
                            n.folder = name;
                            if (n.id != 0) db.saveNoteMeta(n);
                            reload();
                        }
                    });
                    return;
                }
                n.folder = which == 0 ? "" : names.get(which - 1);
                if (n.id != 0) db.saveNoteMeta(n);
                reload();
            }
        }).show();
    }

    // ---------------------------------------------------------------- locked notes

    private boolean deviceSecure() {
        return ((KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE)).isDeviceSecure();
    }

    /** Runs an operation that needs the notes key; asks for fingerprint / screen lock first if needed. */
    private void withKey(final Op op) {
        try {
            if (!box.isOpen()) box.open();
            op.run();
        } catch (UserNotAuthenticatedException e) {
            a.confirmUser("Unlock note", true, new MainActivity.AuthResult() {
                public void done(boolean ok, int error) {
                    if (!ok) return;
                    try {
                        box.open();
                        op.run();
                    } catch (Exception again) {
                        Toast.makeText(a, "Could not unlock: use your fingerprint or phone PIN", Toast.LENGTH_LONG).show();
                    }
                }
            });
        } catch (Exception e) {
            Toast.makeText(a, "Could not unlock this note (" + e.getClass().getSimpleName() + ")", Toast.LENGTH_LONG).show();
        }
    }

    private String plainBody(Db.Note n) throws Exception {
        if (!n.locked) return n.body;
        int at = n.body.indexOf(LOCK_MARK);
        if (at < 0) return n.body;
        String sealed = n.body.substring(at + LOCK_MARK.length());
        // With sync on, locked notes are sealed with the sync key ("S:…"); the fingerprint check still guards opening them.
        return sealed.startsWith(SyncEngine.SEALED) ? SyncEngine.openSealed(a, sealed) : box.decrypt(sealed);
    }

    private void setLocked(final boolean lock) {
        if (lock && !deviceSecure()) {
            Toast.makeText(a, "Set up a fingerprint and phone PIN in Settings first", Toast.LENGTH_LONG).show();
            return;
        }
        save();
        withKey(new Op() {
            public void run() throws Exception {
                if (open.id == 0) open.id = db.addNote(editor.getText().toString());
                open.locked = lock;
                open.body = editor.getText().toString();
                dirty = true;
                save();
                db.saveNoteMeta(open);
                Toast.makeText(a, lock ? "Note locked: it opens only with your fingerprint" : "Note unlocked", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------------------------------------------------------------- editor

    private void newNote() {
        Db.Note n = new Db.Note();
        n.body = "";
        n.folder = folder.equals(TRASH) ? "" : folder;
        n.modified = System.currentTimeMillis();
        showNote(n, "");
    }

    private void openNote(final Db.Note n) {
        if (!n.locked) {
            showNote(n, n.body);
            return;
        }
        withKey(new Op() {
            public void run() throws Exception { showNote(n, plainBody(n)); }
        });
    }

    private void showNote(Db.Note n, String body) {
        open = n;
        formatting = true;
        editor.setText(body);
        formatting = false;
        NoteFormat.style(editor.getText(), Util.color(a, R.color.muted), Util.color(a, R.color.primary_text));
        dirty = false;
        attachments.load("note", n.id);
        editorDate.setText(n.id == 0 ? "" : (n.locked ? "🔒 " : "") + Util.fullTimestamp(a, n.modified)
                + (n.folder.isEmpty() ? "" : "  ·  📁 " + n.folder));
        editorPanel.setBackgroundColor(n.color == 0 ? Util.color(a, R.color.card) : tint(n.color));
        editorPanel.setVisibility(View.VISIBLE);
        listPanel.setVisibility(View.GONE);
        a.screenChanged();
        if (body.isEmpty()) Util.showKeyboard(editor);
    }

    private void closeEditor() {
        if (open == null) return;
        save();
        Voice.stop();
        Util.hideKeyboard(editor);
        open = null;
        box.close();
        editorPanel.setVisibility(View.GONE);
        listPanel.setVisibility(View.VISIBLE);
        a.screenChanged();
        reload();
    }

    /** Saves the open note (encrypting it when locked); an empty note without attachments is removed. */
    private void save() {
        if (open == null || !dirty) return;
        String body = editor.getText().toString();
        if (body.trim().isEmpty() && attachments.count() == 0) {
            if (open.id != 0) deleteForGood(open);
            open.id = 0;
            dirty = false;
            return;
        }
        String stored = body;
        if (open.locked) {
            if (!box.isOpen()) return; // cannot happen while editing; never store a locked note in plain text
            try {
                String sealed = SyncEngine.seal(a, body);
                stored = titleOf(body) + "\n" + LOCK_MARK + (sealed != null ? sealed : box.encrypt(body));
            } catch (Exception e) {
                Toast.makeText(a, "Could not encrypt the note", Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (open.id == 0) {
            open.id = db.addNote(stored);
            db.saveNoteMeta(open);
        } else {
            db.updateNote(open.id, stored);
        }
        open.body = stored;
        open.modified = System.currentTimeMillis();
        if (attachments.dirty()) attachments.commit("note", open.id);
        dirty = false;
    }

    /** After Enter on a checklist / bullet line, starts the next item; Enter on an empty item ends the list. */
    private void continueList(Editable s) {
        int nl = newlineAt;
        newlineAt = -1;
        if (nl < 0 || nl >= s.length()) return;
        String t = s.toString();
        int lineStart = t.lastIndexOf('\n', nl - 1) + 1;
        String prev = t.substring(lineStart, nl);
        String prefix = NoteFormat.continuation(prev);
        if (prefix == null) return;
        if (prev.trim().equals(prefix.trim()) || prev.equals(prefix)) {
            s.delete(lineStart, nl + 1); // empty item: end the list
            return;
        }
        s.insert(nl + 1, prefix);
    }

    /** Taps on a checkbox toggle it; taps on a [[link]] open that note. */
    private boolean tapped(int offset) {
        String t = editor.getText().toString();
        if (offset < 0 || offset > t.length()) return false;
        int lineStart = t.lastIndexOf('\n', offset - 1) + 1;
        if (offset <= lineStart + 2 && (t.startsWith(NoteFormat.BOX, lineStart) || t.startsWith(NoteFormat.TICK, lineStart))) {
            boolean ticked = t.startsWith(NoteFormat.TICK, lineStart);
            editor.getText().replace(lineStart, lineStart + 1, ticked ? "☐" : "☑");
            return true;
        }
        Matcher m = NoteFormat.LINK.matcher(t);
        while (m.find()) {
            if (offset >= m.start() && offset <= m.end()) {
                followLink(m.group(1).trim());
                return true;
            }
        }
        return false;
    }

    private void followLink(String title) {
        save();
        for (Db.Note n : db.notes()) {
            if (n.deleted == 0 && titleOf(n.body).equalsIgnoreCase(title)) {
                openNote(n);
                return;
            }
        }
        Db.Note n = new Db.Note();
        n.body = "";
        n.folder = open != null ? open.folder : "";
        showNote(n, title + "\n");
        dirty = true;
        editor.setSelection(editor.length());
        Toast.makeText(a, "New linked note “" + title + "”", Toast.LENGTH_SHORT).show();
    }

    private void buildToolbar() {
        String[][] tools = {{"B", "bold"}, {"I", "italic"}, {"H", "heading"}, {"•", "bullet"}, {"☑", "check"},
                {"🔗", "link"}, {"📎", "attach"}, {"🎤", "voice"}};
        for (final String[] tool : tools) {
            TextView b = new TextView(a);
            b.setText(tool[0]);
            b.setTextSize(18);
            b.setGravity(android.view.Gravity.CENTER);
            b.setTextColor(Util.color(a, R.color.text));
            if (tool[0].equals("B")) b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
            if (tool[0].equals("I")) b.setTypeface(b.getTypeface(), android.graphics.Typeface.ITALIC);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { tool(tool[1]); }
            });
            toolbar.addView(b, new LinearLayout.LayoutParams(Util.dp(a, 46), ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void tool(String which) {
        if (which.equals("bold")) NoteFormat.wrap(editor, "**");
        else if (which.equals("italic")) NoteFormat.wrap(editor, "_");
        else if (which.equals("heading")) NoteFormat.togglePrefix(editor, "# ", "## ");
        else if (which.equals("bullet")) NoteFormat.togglePrefix(editor, NoteFormat.BULLET, NoteFormat.BOX, NoteFormat.TICK);
        else if (which.equals("check")) NoteFormat.togglePrefix(editor, NoteFormat.BOX, NoteFormat.TICK, NoteFormat.BULLET);
        else if (which.equals("link")) insertLink();
        else if (which.equals("attach")) attachments.view.getChildAt(0).findViewWithTag("add").performClick();
        else if (which.equals("voice")) {
            a.withPermission(android.Manifest.permission.RECORD_AUDIO, "Doable needs the microphone to record voice notes.", new Runnable() {
                public void run() {
                    Voice.record(a, new Voice.Done() {
                        public void onRecorded(String file) {
                            attachments.addPending(file);
                            dirty = true;
                        }
                    });
                }
            });
        }
    }

    private void insertLink() {
        final List<String> titles = new ArrayList<String>();
        for (Db.Note n : db.notes()) if (n.deleted == 0 && (open == null || n.id != open.id)) titles.add(titleOf(n.body));
        titles.add(0, "+ Link to a new note…");
        new AlertDialog.Builder(a).setTitle("Link to note").setItems(titles.toArray(new String[0]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    Util.prompt(a, "Title of the new note", "", "", "Link", new Util.TextEntered() {
                        public void onText(String text) { insertAtCursor("[[" + text + "]]"); }
                    });
                } else {
                    insertAtCursor("[[" + titles.get(which) + "]]");
                }
            }
        }).show();
    }

    private void insertAtCursor(String s) {
        int at = Math.max(0, editor.getSelectionStart());
        editor.getText().insert(at, s);
    }

    private int tint(int color) {
        if (!Util.isNight(a)) return color;
        int card = Util.color(a, R.color.card);
        return android.graphics.Color.rgb(
                (android.graphics.Color.red(card) * 3 + android.graphics.Color.red(color)) / 4,
                (android.graphics.Color.green(card) * 3 + android.graphics.Color.green(color)) / 4,
                (android.graphics.Color.blue(card) * 3 + android.graphics.Color.blue(color)) / 4);
    }

    // ---------------------------------------------------------------- menu

    @Override
    void onCreateMenu(Menu menu) {
        menu.add(0, MENU_PIN, 0, "Pin");
        menu.add(0, MENU_COLOR, 1, "Colour…");
        menu.add(0, MENU_FOLDER, 2, "Move to folder…");
        menu.add(0, MENU_LOCK, 3, "Lock note");
        menu.add(0, MENU_SHARE, 4, "Share note");
        menu.add(0, MENU_DELETE, 5, "Delete note");
        menu.add(0, MENU_EMPTY_TRASH, 6, "Empty Recently deleted");
    }

    @Override
    void onPrepareMenu(Menu menu) {
        boolean editing = open != null;
        menu.findItem(MENU_PIN).setVisible(editing).setTitle(editing && open.pinned ? "Unpin" : "Pin to top");
        menu.findItem(MENU_COLOR).setVisible(editing);
        menu.findItem(MENU_FOLDER).setVisible(editing);
        menu.findItem(MENU_LOCK).setVisible(editing).setTitle(editing && open.locked ? "Remove lock" : "Lock note (fingerprint)");
        menu.findItem(MENU_SHARE).setVisible(editing);
        menu.findItem(MENU_DELETE).setVisible(editing);
        menu.findItem(MENU_EMPTY_TRASH).setVisible(!editing && folder.equals(TRASH));
    }

    @Override
    boolean onMenu(int id) {
        if (id == MENU_EMPTY_TRASH) {
            Util.confirm(a, "Delete all notes in Recently deleted?", "They cannot be restored afterwards.", "Delete", new Runnable() {
                public void run() {
                    for (Db.Note n : db.notes()) if (n.deleted > 0) deleteForGood(n);
                    reload();
                }
            });
            return true;
        }
        if (open == null) return false;
        switch (id) {
            case MENU_PIN:
                save();
                open.pinned = !open.pinned;
                if (open.id != 0) db.saveNoteMeta(open);
                return true;
            case MENU_COLOR:
                chooseColor(open);
                editorPanel.postDelayed(new Runnable() {
                    public void run() {
                        if (open != null) editorPanel.setBackgroundColor(open.color == 0 ? Util.color(a, R.color.card) : tint(open.color));
                    }
                }, 1500);
                return true;
            case MENU_FOLDER:
                chooseFolder(open);
                return true;
            case MENU_LOCK:
                setLocked(!open.locked);
                return true;
            case MENU_SHARE:
                save();
                a.shareText(titleOf(editor.getText().toString()), NoteFormat.plain(editor.getText().toString()));
                return true;
            case MENU_DELETE:
                save();
                Db.Note n = open;
                open = null;
                box.close();
                editorPanel.setVisibility(View.GONE);
                listPanel.setVisibility(View.VISIBLE);
                a.screenChanged();
                moveToTrash(n);
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
                int files = db.attachmentCount("note", n.id);
                ((TextView) view.findViewById(R.id.title)).setText((n.pinned ? "📌 " : "") + (n.locked ? "🔒 " : "") + SpacesUi.badge(a, "notes", n.id) + titleOf(n.body));
                String stamp = n.deleted > 0 ? "Deleted " + Util.relativeStamp(a, n.deleted) : Util.relativeStamp(a, n.modified);
                ((TextView) view.findViewById(R.id.preview)).setText(stamp + "   " + previewOf(n)
                        + (files > 0 ? "   📎 " + files : "") + (!n.folder.isEmpty() && folder.isEmpty() ? "   📁 " + n.folder : ""));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(Util.dp(a, 12));
                bg.setColor(n.color == 0 ? Util.color(a, R.color.card) : tint(n.color));
                view.setBackground(bg);
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
