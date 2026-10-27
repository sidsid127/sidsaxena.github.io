package com.sidsaxena.daybook;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Photos, screenshots and PDF receipts, copied into the app's private storage. */
final class Attachments {
    private Attachments() {}

    private static final int MAX_SIDE = 2048;
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "att");
        d.mkdirs();
        return d;
    }

    static File file(Context c, String name) {
        return new File(dir(c), name);
    }

    static boolean isPdf(String name) {
        return name.endsWith(".pdf");
    }

    static boolean isAudio(String name) {
        return name.endsWith(".m4a") || name.endsWith(".webm") || name.endsWith(".ogg") || name.endsWith(".mp4") || name.endsWith(".aac");
    }

    /** Copies picked content in; images are shrunk to at most 2048 px and turned upright. Returns the file name. */
    static String importUri(Context c, Uri uri) throws IOException {
        String type = c.getContentResolver().getType(uri);
        String name = displayName(c, uri);
        boolean pdf = "application/pdf".equals(type) || name.toLowerCase(Locale.ROOT).endsWith(".pdf");
        if (pdf) {
            String out = UUID.randomUUID() + ".pdf";
            copy(c.getContentResolver().openInputStream(uri), file(c, out));
            return out;
        }
        File tmp = new File(c.getCacheDir(), "import.tmp");
        copy(c.getContentResolver().openInputStream(uri), tmp);
        try {
            return importImage(c, tmp);
        } finally {
            tmp.delete();
        }
    }

    /** Stores an image file (e.g. a camera capture) as a new attachment. */
    static String importImage(Context c, File src) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), o);
        if (o.outWidth <= 0) throw new IOException("Not an image");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > MAX_SIDE * 2) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(src.getPath(), o);
        if (b == null) throw new IOException("Not an image");

        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(b.getWidth(), b.getHeight()));
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        int rotation = 0;
        try {
            int orientation = new ExifInterface(src.getPath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (IOException ignored) {
        }
        m.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);

        String name = UUID.randomUUID() + ".jpg";
        OutputStream os = new FileOutputStream(file(c, name));
        try {
            out.compress(Bitmap.CompressFormat.JPEG, 85, os);
        } finally {
            os.close();
        }
        return name;
    }

    static String displayName(Context c, Uri uri) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
        } catch (RuntimeException ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private static void copy(InputStream in, File out) throws IOException {
        if (in == null) throw new IOException("Cannot open");
        OutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            os.close();
            in.close();
        }
    }

    /** Small preview; for PDFs the first page. */
    static Bitmap thumb(Context c, String name, int px) {
        String key = name + "@" + px;
        Bitmap cached = THUMBS.get(key);
        if (cached != null) return cached;
        if (isAudio(name)) return null;
        Bitmap b = isPdf(name) ? pdfPage(c, name, px) : decode(file(c, name), px);
        if (b != null) THUMBS.put(key, b);
        return b;
    }

    /** Decodes an image no larger than about maxSide pixels. */
    static Bitmap decode(File f, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static Bitmap pdfPage(Context c, String name, int px) {
        ParcelFileDescriptor fd = null;
        PdfRenderer r = null;
        try {
            fd = ParcelFileDescriptor.open(file(c, name), ParcelFileDescriptor.MODE_READ_ONLY);
            r = new PdfRenderer(fd);
            if (r.getPageCount() == 0) return null;
            PdfRenderer.Page page = r.openPage(0);
            int w = px, h = Math.max(1, px * page.getHeight() / Math.max(1, page.getWidth()));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) r.close();
            try {
                if (fd != null) fd.close();
            } catch (IOException ignored) {
            }
        }
    }

    static void delete(Context c, List<String> names) {
        for (String n : names) file(c, n).delete();
    }
}
