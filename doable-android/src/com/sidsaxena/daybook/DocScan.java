package com.sidsaxena.daybook;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Document scanner without any library: drag the four corners onto the edges of the card or page, and the
 * picture is straightened (perspective-corrected) and optionally turned into a clean black-and-white scan.
 */
final class DocScan {
    private DocScan() {}

    interface Done {
        void onScanned(String file);
    }

    static void crop(final MainActivity a, final File photo, final Done done) {
        final Bitmap src = Attachments.decode(photo, 2400);
        if (src == null) return;
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        TextView hint = new TextView(a);
        hint.setText("Drag the four corners onto the edges of the document");
        hint.setTextColor(0xFFFFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Util.dp(a, 14), 0, Util.dp(a, 6));
        root.addView(hint);
        final CropView view = new CropView(a, src);
        root.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(0, Util.dp(a, 8), 0, Util.dp(a, 16));
        Button cancel = new Button(a), color = new Button(a), bw = new Button(a);
        cancel.setText("Cancel");
        color.setText("Save in colour");
        bw.setText("Save as B&W");
        bar.addView(cancel);
        bar.addView(color);
        bar.addView(bw);
        root.addView(bar);
        d.setContentView(root);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        });
        View.OnClickListener save = new View.OnClickListener() {
            public void onClick(View v) {
                Bitmap out = view.warp(v.getTag() != null);
                try {
                    String name = UUID.randomUUID() + ".jpg";
                    OutputStream os = new FileOutputStream(Attachments.file(a, name));
                    try {
                        out.compress(Bitmap.CompressFormat.JPEG, 88, os);
                    } finally {
                        os.close();
                    }
                    d.dismiss();
                    done.onScanned(name);
                } catch (IOException e) {
                    android.widget.Toast.makeText(a, "Could not save the scan", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        };
        bw.setTag("bw");
        color.setOnClickListener(save);
        bw.setOnClickListener(save);
        d.show();
    }

    /** Shows the photo with a draggable quadrilateral. */
    static final class CropView extends View {
        private final Bitmap bmp;
        private final float[] pts = new float[8]; // corners in bitmap coordinates: TL, TR, BR, BL
        private final Matrix toView = new Matrix(), toBmp = new Matrix();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint();
        private int dragging = -1;

        CropView(Context c, Bitmap b) {
            super(c);
            bmp = b;
            float w = b.getWidth(), h = b.getHeight(), m = 0.08f;
            float[] init = {w * m, h * m, w * (1 - m), h * m, w * (1 - m), h * (1 - m), w * m, h * (1 - m)};
            System.arraycopy(init, 0, pts, 0, 8);
            line.setColor(0xFFFF8A5B);
            line.setStrokeWidth(Util.dp(c, 2));
            line.setStyle(Paint.Style.STROKE);
            dot.setColor(0xFFFFFFFF);
            shade.setColor(0x88000000);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            toView.setRectToRect(new RectF(0, 0, bmp.getWidth(), bmp.getHeight()), new RectF(0, 0, w, h), Matrix.ScaleToFit.CENTER);
            toView.invert(toBmp);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawBitmap(bmp, toView, null);
            float[] v = pts.clone();
            toView.mapPoints(v);
            Path p = new Path();
            p.moveTo(v[0], v[1]);
            for (int i = 2; i < 8; i += 2) p.lineTo(v[i], v[i + 1]);
            p.close();
            Path outside = new Path();
            outside.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            outside.addPath(p);
            outside.setFillType(Path.FillType.EVEN_ODD);
            c.drawPath(outside, shade);
            c.drawPath(p, line);
            float r = Util.dp(getContext(), 11);
            for (int i = 0; i < 8; i += 2) {
                c.drawCircle(v[i], v[i + 1], r, dot);
                c.drawCircle(v[i], v[i + 1], r, line);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float[] t = {e.getX(), e.getY()};
            toBmp.mapPoints(t);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float[] v = pts.clone();
                    toView.mapPoints(v);
                    float best = Util.dp(getContext(), 48);
                    dragging = -1;
                    for (int i = 0; i < 4; i++) {
                        float dist = (float) Math.hypot(v[i * 2] - e.getX(), v[i * 2 + 1] - e.getY());
                        if (dist < best) {
                            best = dist;
                            dragging = i;
                        }
                    }
                    return dragging >= 0;
                }
                case MotionEvent.ACTION_MOVE:
                    if (dragging < 0) return false;
                    pts[dragging * 2] = Math.max(0, Math.min(bmp.getWidth(), t[0]));
                    pts[dragging * 2 + 1] = Math.max(0, Math.min(bmp.getHeight(), t[1]));
                    invalidate();
                    return true;
                default:
                    dragging = -1;
                    return true;
            }
        }

        /** Straightened document; with bw a high-contrast greyscale "scan". */
        Bitmap warp(boolean bw) {
            float wTop = dist(0, 1), wBottom = dist(3, 2), hLeft = dist(0, 3), hRight = dist(1, 2);
            int w = Math.max(10, Math.round(Math.max(wTop, wBottom)));
            int h = Math.max(10, Math.round(Math.max(hLeft, hRight)));
            float scale = Math.min(1f, 2400f / Math.max(w, h));
            w = Math.round(w * scale);
            h = Math.round(h * scale);
            Matrix m = new Matrix();
            m.setPolyToPoly(pts, 0, new float[]{0, 0, w, 0, w, h, 0, h}, 0, 4);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(0xFFFFFFFF);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            if (bw) {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0);
                float contrast = 1.6f, offset = -0.25f * 255;
                ColorMatrix boost = new ColorMatrix(new float[]{
                        contrast, 0, 0, 0, offset, 0, contrast, 0, 0, offset, 0, 0, contrast, 0, offset, 0, 0, 0, 1, 0});
                cm.postConcat(boost);
                p.setColorFilter(new ColorMatrixColorFilter(cm));
            }
            c.drawBitmap(bmp, m, p);
            return out;
        }

        private float dist(int i, int j) {
            return (float) Math.hypot(pts[i * 2] - pts[j * 2], pts[i * 2 + 1] - pts[j * 2 + 1]);
        }
    }
}
