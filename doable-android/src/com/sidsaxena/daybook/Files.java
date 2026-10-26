package com.sidsaxena.daybook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Hands out private files for a moment: the camera app writes a new photo to capture.jpg, and PDF viewers
 * read an attached receipt. Not exported; access is only ever granted per request.
 */
public class Files extends ContentProvider {

    static final String AUTHORITY = "com.sidsaxena.daybook.files";
    static final Uri CAPTURE = Uri.parse("content://" + AUTHORITY + "/capture.jpg");

    static Uri attachment(String file) {
        return Uri.parse("content://" + AUTHORITY + "/att/" + file);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() == 1 && seg.get(0).equals("capture.jpg")) {
            return new File(getContext().getFilesDir(), "capture.jpg");
        }
        if (seg.size() == 2 && seg.get(0).equals("att") && seg.get(1).matches("[A-Za-z0-9-]+\\.(jpg|pdf|m4a|csv|txt)")) {
            return Attachments.file(getContext(), seg.get(1));
        }
        throw new FileNotFoundException(uri.toString());
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        String p = uri.getPath();
        if (p.endsWith(".pdf")) return "application/pdf";
        if (p.endsWith(".m4a")) return "audio/mp4";
        if (p.endsWith(".csv")) return "text/csv";
        if (p.endsWith(".txt")) return "text/plain";
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
