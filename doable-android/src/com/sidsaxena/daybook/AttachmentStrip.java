package com.sidsaxena.daybook;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * A row of photo / PDF thumbnails with an "add" tile, used in diary, expense and trip forms.
 * Changes are kept in memory until {@link #commit} (on save) or {@link #discard} (on cancel).
 */
class AttachmentStrip {

    private final MainActivity a;
    private final String addLabel;
    final HorizontalScrollView view;
    private final LinearLayout row;

    private final List<Db.Attachment> saved = new ArrayList<Db.Attachment>();
    private final List<Db.Attachment> removed = new ArrayList<Db.Attachment>();
    private final List<String> pending = new ArrayList<String>();   // new files, not in the database yet

    AttachmentStrip(MainActivity activity, String addLabel) {
        a = activity;
        this.addLabel = addLabel;
        view = new HorizontalScrollView(a);
        view.setHorizontalScrollBarEnabled(false);
        row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Util.dp(a, 6), 0, Util.dp(a, 6));
        view.addView(row);
        render();
    }

    /** Shows the attachments already saved for an item (ownerId 0 = new item, nothing saved yet). */
    void load(String owner, long ownerId) {
        saved.clear();
        removed.clear();
        pending.clear();
        if (ownerId != 0) saved.addAll(a.db.attachments(owner, ownerId));
        render();
    }

    int count() {
        return saved.size() + pending.size();
    }

    boolean dirty() {
        return !pending.isEmpty() || !removed.isEmpty();
    }

    /** Stores additions and removals for the (now saved) item. */
    void commit(String owner, long ownerId) {
        for (String f : pending) a.db.addAttachment(owner, ownerId, f, "");
        List<String> gone = new ArrayList<String>();
        for (Db.Attachment at : removed) {
            a.db.deleteAttachment(at.id);
            gone.add(at.file);
        }
        Attachments.delete(a, gone);
        load(owner, ownerId);
    }

    /** Throws away files added since the last commit. */
    void discard() {
        Attachments.delete(a, pending);
        pending.clear();
        removed.clear();
        render();
    }

    private void render() {
        row.removeAllViews();
        for (final Db.Attachment at : saved) {
            row.addView(tile(at.file, new Runnable() {
                public void run() {
                    saved.remove(at);
                    removed.add(at);
                    render();
                }
            }));
        }
        for (final String f : pending) {
            row.addView(tile(f, new Runnable() {
                public void run() {
                    pending.remove(f);
                    Attachments.file(a, f).delete();
                    render();
                }
            }));
        }
        TextView add = new TextView(a);
        add.setText("+\n" + addLabel);
        add.setGravity(Gravity.CENTER);
        add.setTextSize(12);
        add.setTextColor(Util.color(a, R.color.primary_text));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Util.dp(a, 10));
        bg.setStroke(Util.dp(a, 1), Util.color(a, R.color.muted), Util.dp(a, 4), Util.dp(a, 3));
        add.setBackground(bg);
        add.setLayoutParams(tileParams());
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                a.pickAttachments(new MainActivity.FilesPicked() {
                    public void onFiles(List<String> files) {
                        pending.addAll(files);
                        render();
                    }
                });
            }
        });
        row.addView(add);
    }

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Util.dp(a, 76), Util.dp(a, 76));
        lp.rightMargin = Util.dp(a, 8);
        return lp;
    }

    private View tile(final String file, final Runnable remove) {
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap b = Attachments.thumb(a, file, Util.dp(a, 152));
        if (b != null) iv.setImageBitmap(b);
        else iv.setImageResource(R.drawable.ic_note);
        iv.setBackgroundColor(Util.color(a, R.color.divider));
        iv.setLayoutParams(tileParams());
        iv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { open(a, file); }
        });
        iv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                Util.confirm(a, "Remove this " + (Attachments.isPdf(file) ? "file" : "photo") + "?", null, "Remove", remove);
                return true;
            }
        });
        return iv;
    }

    /** Full-screen photo viewer; PDFs open in the phone's PDF viewer. */
    static void open(final MainActivity a, String file) {
        if (Attachments.isPdf(file)) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Files.attachment(file), "application/pdf");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                a.startExternal(i);
            } catch (RuntimeException e) {
                Toast.makeText(a, "No PDF viewer installed", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFF000000);
        int side = Math.max(a.getResources().getDisplayMetrics().widthPixels, a.getResources().getDisplayMetrics().heightPixels);
        iv.setImageBitmap(Attachments.decode(Attachments.file(a, file), side));
        iv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        });
        d.setContentView(iv);
        d.show();
    }
}
