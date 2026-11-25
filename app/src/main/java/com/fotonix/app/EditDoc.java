package com.fotonix.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.ArrayList;

/**
 * Senarai suntingan dalam koordinat piksel imej ASAL (resolusi penuh).
 * Pratonton dilukis pada skala kecil; simpanan dilukis semula pada resolusi penuh.
 */
public class EditDoc {

    public static class Op {}

    public static class Rotate extends Op {}

    public static class Crop extends Op {
        final float l, t, w, h;
        Crop(float l, float t, float w, float h) { this.l = l; this.t = t; this.w = w; this.h = h; }
    }

    public static class Stroke extends Op {
        final float[] pts;
        final int color;
        final float width;
        Stroke(float[] pts, int color, float width) { this.pts = pts; this.color = color; this.width = width; }
    }

    public final int origW, origH;
    public final ArrayList<Op> ops = new ArrayList<>();

    public EditDoc(int w, int h) { origW = w; origH = h; }

    public EditDoc copy() {
        EditDoc d = new EditDoc(origW, origH);
        d.ops.addAll(ops);
        return d;
    }

    /** Saiz imej selepas k operasi pertama. */
    public float[] sizeAfter(int k) {
        float w = origW, h = origH;
        for (int i = 0; i < k; i++) {
            Op o = ops.get(i);
            if (o instanceof Rotate) { float t = w; w = h; h = t; }
            else if (o instanceof Crop) { w = ((Crop) o).w; h = ((Crop) o).h; }
        }
        return new float[]{w, h};
    }

    public float[] currentSize() { return sizeAfter(ops.size()); }

    /** Petakan titik (koordinat pada masa operasi idx) ke koordinat akhir. */
    private void mapForward(float[] p, int idx) {
        float[] sz = sizeAfter(idx + 1);
        float w = sz[0], h = sz[1];
        for (int j = idx + 1; j < ops.size(); j++) {
            Op o = ops.get(j);
            if (o instanceof Rotate) {
                for (int k = 0; k < p.length; k += 2) {
                    float x = p[k], y = p[k + 1];
                    p[k] = h - y;
                    p[k + 1] = x;
                }
                float t = w; w = h; h = t;
            } else if (o instanceof Crop) {
                Crop c = (Crop) o;
                for (int k = 0; k < p.length; k += 2) { p[k] -= c.l; p[k + 1] -= c.t; }
                w = c.w; h = c.h;
            }
        }
    }

    /** Gunakan putaran & pangkasan pada bitmap. s = skala bitmap berbanding imej asal. */
    public Bitmap applyGeometry(Bitmap src, float s, boolean recycleSrc) {
        Bitmap cur = src;
        for (Op o : ops) {
            Bitmap next = null;
            if (o instanceof Rotate) {
                next = Bitmap.createBitmap(cur.getHeight(), cur.getWidth(), Bitmap.Config.ARGB_8888);
                Matrix m = new Matrix();
                m.postRotate(90);
                m.postTranslate(cur.getHeight(), 0);
                new Canvas(next).drawBitmap(cur, m, null);
            } else if (o instanceof Crop) {
                Crop c = (Crop) o;
                int cw = cur.getWidth(), ch = cur.getHeight();
                int x = clamp(Math.round(c.l * s), 0, cw - 1);
                int y = clamp(Math.round(c.t * s), 0, ch - 1);
                int w = clamp(Math.round(c.w * s), 1, cw - x);
                int h = clamp(Math.round(c.h * s), 1, ch - y);
                next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                new Canvas(next).drawBitmap(cur, -x, -y, null);
            }
            if (next != null) {
                if (cur != src || recycleSrc) cur.recycle();
                cur = next;
            }
        }
        return cur;
    }

    /** Lukis semua conteng (dipetakan ke koordinat akhir) pada kanvas berskala s. */
    public void drawStrokes(Canvas c, float s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < ops.size(); i++) {
            if (!(ops.get(i) instanceof Stroke)) continue;
            Stroke st = (Stroke) ops.get(i);
            float[] pts = st.pts.clone();
            mapForward(pts, i);
            for (int k = 0; k < pts.length; k++) pts[k] *= s;
            p.setColor(st.color);
            p.setStrokeWidth(Math.max(1f, st.width * s));
            c.drawPath(buildPath(pts), p);
        }
    }

    public static Path buildPath(float[] pts) {
        Path path = new Path();
        int n = pts.length;
        if (n < 2) return path;
        path.moveTo(pts[0], pts[1]);
        if (n == 2) { path.lineTo(pts[0] + 0.1f, pts[1] + 0.1f); return path; }
        for (int k = 2; k < n - 2; k += 2) {
            float mx = (pts[k] + pts[k + 2]) / 2f, my = (pts[k + 1] + pts[k + 3]) / 2f;
            path.quadTo(pts[k], pts[k + 1], mx, my);
        }
        path.lineTo(pts[n - 2], pts[n - 1]);
        return path;
    }

    /** Laras kecerahan secara langsung pada bitmap (sama seperti ColorMatrix pratonton). */
    public static void applyBrightness(Bitmap b, float k) {
        if (Math.abs(k - 1f) < 0.001f) return;
        int w = b.getWidth(), h = b.getHeight();
        int rows = Math.max(1, 262144 / w);
        int[] px = new int[w * rows];
        for (int y = 0; y < h; y += rows) {
            int n = Math.min(rows, h - y);
            b.getPixels(px, 0, w, 0, y, w, n);
            for (int i = 0; i < w * n; i++) {
                int c = px[i];
                int r = Math.min(255, (int) (((c >> 16) & 255) * k));
                int g = Math.min(255, (int) (((c >> 8) & 255) * k));
                int bl = Math.min(255, (int) ((c & 255) * k));
                px[i] = (c & 0xFF000000) | (r << 16) | (g << 8) | bl;
            }
            b.setPixels(px, 0, w, 0, y, w, n);
        }
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
