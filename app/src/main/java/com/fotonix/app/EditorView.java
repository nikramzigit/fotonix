package com.fotonix.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Kanvas penyunting: pratonton imej + lapisan conteng + kotak pangkas. */
public class EditorView extends View {

    public static final int MODE_NONE = 0, MODE_DRAW = 1, MODE_CROP = 2;

    private Bitmap src, base, ink;
    private Canvas inkCanvas;
    private EditDoc doc;
    private float scale = 1f;          // pratonton / asal
    private float viewScale = 1f;      // skrin / pratonton
    private float brightness = 1f;
    private int mode = MODE_NONE;
    private int penColor = 0xFFE53935;

    private final Matrix viewMatrix = new Matrix(), inverse = new Matrix();
    private final Paint basePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint penPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thirds = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    // Conteng semasa
    private float[] live = new float[256];
    private int liveN;
    private float strokeW, lastPX, lastPY;
    private boolean touching;

    // Leret ke gambar sebelum / seterusnya
    public interface SwipeListener { void onSwipe(int dir); }
    private SwipeListener swipeListener;
    private final GestureDetector gestures;

    // Zum & anjak
    private static final float MAX_ZOOM = 8f;
    private final ScaleGestureDetector scaleDet;
    private float zoom = 1f, panX, panY, fitScale = 1f, tx, ty;
    private boolean multi;
    private float lastFX, lastFY, lastSX, lastSY;

    // Pangkas
    private RectF crop;
    private boolean dL, dT, dR, dB, dMove;
    private float lastBX, lastBY;

    public EditorView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        penPaint.setStyle(Paint.Style.STROKE);
        penPaint.setStrokeCap(Paint.Cap.ROUND);
        penPaint.setStrokeJoin(Paint.Join.ROUND);
        shade.setColor(0x99000000);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(Color.WHITE);
        border.setStrokeWidth(1.5f * dp);
        thirds.setStyle(Paint.Style.STROKE);
        thirds.setColor(0x80FFFFFF);
        thirds.setStrokeWidth(1f * dp);
        handle.setStyle(Paint.Style.STROKE);
        handle.setColor(0xFF00C1BB);   // teal skill-phone-dark
        handle.setStrokeWidth(4f * dp);
        handle.setStrokeCap(Paint.Cap.SQUARE);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (zoom > 1.05f) resetZoom(); else zoomAt(2.5f / zoom, e.getX(), e.getY());
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || swipeListener == null || zoom > 1.05f) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > 60 * dp && Math.abs(dx) > Math.abs(dy) * 1.5f && Math.abs(vx) > 500) {
                    swipeListener.onSwipe(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        scaleDet = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAt(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
    }

    /** Zum dengan faktor f di sekitar titik skrin (fx, fy). */
    private void zoomAt(float f, float fx, float fy) {
        if (base == null) return;
        float nz = Math.max(1f, Math.min(MAX_ZOOM, zoom * f));
        float rf = nz / zoom;
        float ntx = fx - (fx - tx) * rf, nty = fy - (fy - ty) * rf;
        zoom = nz;
        panX = ntx - (getWidth() - base.getWidth() * fitScale * zoom) / 2f;
        panY = nty - (getHeight() - base.getHeight() * fitScale * zoom) / 2f;
        updateMatrix();
        invalidate();
    }

    public void resetZoom() { zoom = 1f; panX = panY = 0; updateMatrix(); invalidate(); }

    public boolean isZoomed() { return zoom > 1.05f; }

    public void setSwipeListener(SwipeListener l) { swipeListener = l; }

    /** Ada suntingan yang belum disimpan? */
    public boolean hasEdits() {
        return (doc != null && !doc.ops.isEmpty()) || Math.abs(brightness - 1f) > 0.001f;
    }

    // ================= API =================

    public boolean hasImage() { return base != null; }

    public void setSource(Bitmap preview, EditDoc d, float s) {
        clear();
        src = preview; doc = d; scale = s;
        brightness = 1f;
        updateBrightness();
        mode = MODE_NONE;
        crop = null;
        rebuild();
    }

    public void clear() {
        if (base != null && base != src) base.recycle();
        if (src != null) src.recycle();
        if (ink != null) ink.recycle();
        base = src = ink = null;
        doc = null;
        invalidate();
    }

    public void setPenColor(int c) { penColor = c; }

    public int getMode() { return mode; }

    public void setMode(int m) {
        mode = m;
        if (m != MODE_CROP) crop = null;
        invalidate();
    }

    public float getBrightness() { return brightness; }

    public void setBrightness(float k) { brightness = k; updateBrightness(); invalidate(); }

    public boolean undo() {
        if (doc == null || doc.ops.isEmpty()) return false;
        doc.ops.remove(doc.ops.size() - 1);
        rebuild();
        return true;
    }

    public void rotate() {
        if (doc == null) return;
        setMode(mode == MODE_CROP ? MODE_NONE : mode);
        doc.ops.add(new EditDoc.Rotate());
        rebuild();
    }

    public void startCrop() {
        if (base == null) return;
        mode = MODE_CROP;
        crop = new RectF(0, 0, base.getWidth(), base.getHeight());
        invalidate();
    }

    /** Pangkas mengikut kotak. @return false jika kotak tidak sah. */
    public boolean applyCrop() {
        if (doc == null || crop == null) return false;
        float[] sz = doc.currentSize();
        float l = Math.max(0, crop.left / scale), t = Math.max(0, crop.top / scale);
        float w = Math.min(crop.width() / scale, sz[0] - l), h = Math.min(crop.height() / scale, sz[1] - t);
        setMode(MODE_NONE);
        if (w < 4 || h < 4) return false;
        if (l < 1 && t < 1 && w > sz[0] - 1 && h > sz[1] - 1) return true; // tiada perubahan
        doc.ops.add(new EditDoc.Crop(Math.round(l), Math.round(t), Math.round(w), Math.round(h)));
        rebuild();
        return true;
    }

    // ================= Dalaman =================

    private void rebuild() {
        if (src == null || doc == null) return;
        Bitmap old = base;
        int ow = old == null ? -1 : old.getWidth(), oh = old == null ? -1 : old.getHeight();
        base = doc.applyGeometry(src, scale, false);
        if (base.getWidth() != ow || base.getHeight() != oh) { zoom = 1f; panX = panY = 0; }   // saiz berubah (putar/pangkas)
        if (old != null && old != src && old != base) old.recycle();
        if (ink != null) ink.recycle();
        ink = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        inkCanvas = new Canvas(ink);
        doc.drawStrokes(inkCanvas, scale);
        updateMatrix();
        invalidate();
    }

    private void updateBrightness() {
        ColorMatrix cm = new ColorMatrix();
        cm.setScale(brightness, brightness, brightness, 1f);
        basePaint.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    private void updateMatrix() {
        if (base == null || getWidth() == 0) return;
        float pad = 16 * dp;
        float aw = getWidth() - 2 * pad, ah = getHeight() - 2 * pad;
        fitScale = Math.min(aw / base.getWidth(), ah / base.getHeight());
        viewScale = fitScale * zoom;
        float cw = base.getWidth() * viewScale, ch = base.getHeight() * viewScale;
        float W = getWidth(), H = getHeight();
        // Hadkan anjakan supaya tepi gambar tidak terlepas dari skrin
        tx = cw <= W ? (W - cw) / 2f : Math.max(W - cw, Math.min(0, (W - cw) / 2f + panX));
        ty = ch <= H ? (H - ch) / 2f : Math.max(H - ch, Math.min(0, (H - ch) / 2f + panY));
        panX = tx - (W - cw) / 2f;
        panY = ty - (H - ch) / 2f;
        viewMatrix.reset();
        viewMatrix.postScale(viewScale, viewScale);
        viewMatrix.postTranslate(tx, ty);
        viewMatrix.invert(inverse);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { updateMatrix(); }

    @Override
    protected void onDraw(Canvas canvas) {
        if (base == null) return;
        canvas.save();
        canvas.concat(viewMatrix);
        canvas.drawBitmap(base, 0, 0, basePaint);
        canvas.drawBitmap(ink, 0, 0, bmpPaint);
        canvas.restore();

        if (mode == MODE_CROP && crop != null) {
            RectF img = new RectF(0, 0, base.getWidth(), base.getHeight());
            viewMatrix.mapRect(img);
            RectF v = new RectF();
            viewMatrix.mapRect(v, crop);
            canvas.drawRect(img.left, img.top, img.right, v.top, shade);
            canvas.drawRect(img.left, v.bottom, img.right, img.bottom, shade);
            canvas.drawRect(img.left, v.top, v.left, v.bottom, shade);
            canvas.drawRect(v.right, v.top, img.right, v.bottom, shade);
            for (int i = 1; i <= 2; i++) {
                float x = v.left + v.width() * i / 3f, y = v.top + v.height() * i / 3f;
                canvas.drawLine(x, v.top, x, v.bottom, thirds);
                canvas.drawLine(v.left, y, v.right, y, thirds);
            }
            canvas.drawRect(v, border);
            float L = Math.min(22 * dp, Math.min(v.width(), v.height()) / 3f);
            drawCorner(canvas, v.left, v.top, L, L);
            drawCorner(canvas, v.right, v.top, -L, L);
            drawCorner(canvas, v.left, v.bottom, L, -L);
            drawCorner(canvas, v.right, v.bottom, -L, -L);
        }
    }

    private void drawCorner(Canvas c, float x, float y, float lx, float ly) {
        c.drawLine(x, y, x + lx, y, handle);
        c.drawLine(x, y, x, y + ly, handle);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (base == null) return false;
        int act = e.getActionMasked();

        // Dua jari: zum + anjak (dalam semua mod)
        scaleDet.onTouchEvent(e);
        if (act == MotionEvent.ACTION_POINTER_DOWN) {
            if (touching && mode == MODE_DRAW) { liveN = 0; rebuild(); }   // batalkan conteng separuh jalan
            touching = false;
            multi = true;
            focus(e, -1);
            return true;
        }
        if (multi) {
            if (act == MotionEvent.ACTION_MOVE) {
                float fx = lastFX, fy = lastFY;
                focus(e, -1);
                panX += lastFX - fx;
                panY += lastFY - fy;
                updateMatrix();
                invalidate();
            } else if (act == MotionEvent.ACTION_POINTER_UP) {
                focus(e, e.getActionIndex());
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                multi = false;
            }
            return true;
        }

        if (mode == MODE_NONE) {
            gestures.onTouchEvent(e);
            // Satu jari: anjak gambar apabila dizum
            if (act == MotionEvent.ACTION_DOWN) { lastSX = e.getX(); lastSY = e.getY(); }
            else if (act == MotionEvent.ACTION_MOVE && zoom > 1.05f) {
                panX += e.getX() - lastSX;
                panY += e.getY() - lastSY;
                lastSX = e.getX(); lastSY = e.getY();
                updateMatrix();
                invalidate();
            }
            return true;
        }
        float[] p = {e.getX(), e.getY()};
        inverse.mapPoints(p);
        float bx = p[0], by = p[1];

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                if (mode == MODE_DRAW) {
                    float[] sz = doc.currentSize();
                    strokeW = Math.max(3f / scale, sz[0] / 180f);   // dalam piksel asal
                    penPaint.setColor(penColor);
                    penPaint.setStrokeWidth(strokeW * scale);
                    liveN = 0;
                    addLive(bx / scale, by / scale);
                    inkCanvas.drawPoint(bx, by, penPaint);
                    lastPX = bx; lastPY = by;
                } else {
                    beginCropDrag(e.getX(), e.getY());
                    lastBX = bx; lastBY = by;
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!touching) return true;
                if (mode == MODE_DRAW) {
                    int n = e.getHistorySize();
                    for (int i = 0; i <= n; i++) {
                        float[] q = (i < n) ? new float[]{e.getHistoricalX(i), e.getHistoricalY(i)} : new float[]{e.getX(), e.getY()};
                        inverse.mapPoints(q);
                        inkCanvas.drawLine(lastPX, lastPY, q[0], q[1], penPaint);
                        addLive(q[0] / scale, q[1] / scale);
                        lastPX = q[0]; lastPY = q[1];
                    }
                } else if (crop != null) {
                    dragCrop(bx - lastBX, by - lastBY);
                    lastBX = bx; lastBY = by;
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touching && mode == MODE_DRAW && liveN > 0) {
                    float[] pts = new float[liveN];
                    System.arraycopy(live, 0, pts, 0, liveN);
                    doc.ops.add(new EditDoc.Stroke(pts, penColor, strokeW));
                }
                touching = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** Titik tengah semua jari (kecuali jari yang sedang diangkat). */
    private void focus(MotionEvent e, int skip) {
        float sx = 0, sy = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            sx += e.getX(i); sy += e.getY(i); n++;
        }
        if (n > 0) { lastFX = sx / n; lastFY = sy / n; }
    }

    private void addLive(float x, float y) {
        if (liveN + 2 > live.length) {
            float[] n = new float[live.length * 2];
            System.arraycopy(live, 0, n, 0, liveN);
            live = n;
        }
        live[liveN++] = x;
        live[liveN++] = y;
    }

    private void beginCropDrag(float sx, float sy) {
        RectF v = new RectF();
        viewMatrix.mapRect(v, crop);
        float r = 32 * dp;
        boolean nearL = Math.abs(sx - v.left) < r, nearR = Math.abs(sx - v.right) < r;
        boolean nearT = Math.abs(sy - v.top) < r, nearB = Math.abs(sy - v.bottom) < r;
        boolean inY = sy > v.top - r && sy < v.bottom + r, inX = sx > v.left - r && sx < v.right + r;
        dL = nearL && inY; dR = nearR && inY && !dL;
        dT = nearT && inX; dB = nearB && inX && !dT;
        dMove = !(dL || dR || dT || dB) && v.contains(sx, sy);
    }

    private void dragCrop(float dx, float dy) {
        float W = base.getWidth(), H = base.getHeight();
        float min = 48 * dp / viewScale;
        if (dMove) {
            float ox = Math.max(-crop.left, Math.min(W - crop.right, dx));
            float oy = Math.max(-crop.top, Math.min(H - crop.bottom, dy));
            crop.offset(ox, oy);
            return;
        }
        if (dL) crop.left = Math.max(0, Math.min(crop.right - min, crop.left + dx));
        if (dR) crop.right = Math.min(W, Math.max(crop.left + min, crop.right + dx));
        if (dT) crop.top = Math.max(0, Math.min(crop.bottom - min, crop.top + dy));
        if (dB) crop.bottom = Math.min(H, Math.max(crop.top + min, crop.bottom + dy));
    }
}
