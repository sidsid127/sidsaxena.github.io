package com.sidsaxena.daybook;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * "Share → Doable" from other apps: text becomes a note, a task, grocery items (e.g. a WhatsApp list from
 * the family), a diary line or a checklist; photos and PDFs become a note, a trip receipt, a wallet
 * document or diary photos.
 */
final class ShareIn {
    private ShareIn() {}

    static boolean isShare(Intent i) {
        return i != null && (Intent.ACTION_SEND.equals(i.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(i.getAction()));
    }

    static void handle(final MainActivity a, Intent i) {
        String text = i.getStringExtra(Intent.EXTRA_TEXT);
        String subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
        final List<Uri> uris = new ArrayList<Uri>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Parcelable> list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Parcelable p : list) if (p instanceof Uri) uris.add((Uri) p);
        } else {
            Parcelable p = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) uris.add((Uri) p);
        }
        final String body = ((subject != null && !subject.trim().isEmpty() && (text == null || !text.contains(subject)) ? subject.trim() + "\n" : "")
                + (text == null ? "" : text)).trim();
        if (!uris.isEmpty()) files(a, uris, body);
        else if (!body.isEmpty()) text(a, body);
    }

    private static void text(final MainActivity a, final String text) {
        final String[] items = {"📝 New note", "✅ New task", "🛒 Add to shopping list", "📖 Add to today's diary", "☑ New checklist (one item per line)"};
        new AlertDialog.Builder(a)
                .setTitle("Save to Doable")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: {
                                long id = a.db.addNote(text);
                                a.go("notes", id, null);
                                break;
                            }
                            case 1: {
                                a.go("tasks", 0, null);
                                TasksScreen t = (TasksScreen) a.screen("tasks");
                                t.prefill(text.replace('\n', ' '));
                                break;
                            }
                            case 2: {
                                a.go("grocery", 0, null);
                                GroceryScreen g = (GroceryScreen) a.screen("grocery");
                                int n = g.importText(text);
                                g.onShow();
                                Toast.makeText(a, n == 0 ? "Nothing new to add" : n + " item(s) added", Toast.LENGTH_SHORT).show();
                                break;
                            }
                            case 3: {
                                a.go("diary", 0, null);  // first, so an open entry is saved before we add to it
                                Db.Diary entry = today(a);
                                entry.text = entry.text.trim().isEmpty() ? text : entry.text.trim() + "\n\n" + text;
                                a.db.saveDiary(entry);
                                a.go("diary", entry.day, null);
                                break;
                            }
                            default: {
                                String heading = ChecklistText.heading(text);
                                List<ChecklistText.Line> lines = ChecklistText.parse(text);
                                if (heading != null && !lines.isEmpty()) lines.remove(0);
                                long id = a.st.addChecklist(heading != null ? heading : "Shared list", false, 0);
                                a.st.importCheckItems(id, lines);
                                a.go("checklists", id, null);
                            }
                        }
                    }
                })
                .show();
    }

    private static Db.Diary today(MainActivity a) {
        long day = Util.startOfDay(System.currentTimeMillis());
        Db.Diary d = a.db.diaryFor(day);
        if (d == null) {
            d = new Db.Diary();
            d.day = day;
        }
        return d;
    }

    private static void files(final MainActivity a, final List<Uri> uris, final String text) {
        final String[] items = {"📝 New note with these files", "🧾 Receipt for a trip expense", "💳 Wallet card / document",
                "📖 Photos for today's diary"};
        new AlertDialog.Builder(a)
                .setTitle("Save to Doable")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, final int which) {
                        if (which == 1 && a.db.trips().isEmpty()) {
                            Toast.makeText(a, "Create a trip first, then share the receipt again", Toast.LENGTH_LONG).show();
                            a.go("trips", 0, null);
                            return;
                        }
                        a.importUris(uris, new MainActivity.FilesPicked() {
                            public void onFiles(List<String> files) { save(a, which, files, text); }
                        });
                    }
                })
                .show();
    }

    private static void save(final MainActivity a, int which, final List<String> files, String text) {
        switch (which) {
            case 0: {
                long id = a.db.addNote(text.isEmpty() ? "Shared files" : text);
                for (String f : files) a.db.addAttachment("note", id, f, "");
                a.go("notes", id, null);
                break;
            }
            case 1: {
                final List<Db.Trip> trips = a.db.trips();
                String[] names = new String[trips.size()];
                for (int k = 0; k < names.length; k++) names[k] = trips.get(k).name;
                new AlertDialog.Builder(a)
                        .setTitle("Which trip?")
                        .setItems(names, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int k) {
                                a.go("trips", 0, null);
                                ((TripsScreen) a.screen("trips")).addReceipt(trips.get(k), files);
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) { Attachments.delete(a, files); }
                        })
                        .show();
                break;
            }
            case 2: {
                a.go("wallet", 0, null);
                ((WalletScreen) a.screen("wallet")).addShared(files);
                break;
            }
            default: {
                a.go("diary", 0, null);
                Db.Diary entry = today(a);
                if (entry.id == 0) a.db.saveDiary(entry);
                for (String f : files) a.db.addAttachment("diary", entry.id, f, "");
                a.go("diary", entry.day, null);
            }
        }
    }
}
