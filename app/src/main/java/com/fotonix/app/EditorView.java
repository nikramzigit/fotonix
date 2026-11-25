package com.fotonix.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/** Kanvas penyunting native: imej asas + lapisan conteng. */
public class EditorView extends View {

    public static final int MODE_DRAW = 0, MODE_CROP = 1;
    private static final int MAX_UNDO = 5;

    private Bitmap base, ink;
    private Canvas inkCanvas;
    private float brightness = 1f;
    private int mode = MODE_DRAW;
    private int penColor = 0xFFE53935;

    private final Matrix viewMatrix = new Matrix(), inverse = new Matrix();
    private final Paint basePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint penPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint cropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path livePath = new Path();
    private float lastX, lastY, startX, startY;
    private boolean touching;
    private RectF cropRect; // dalam koordinat bitmap

    private final ArrayList<Bitmap[]> undo = new ArrayList<>();

    public EditorView(Context c) {
        super(c);
        penPaint.setStyle(Paint.Style.STROKE);
        penPaint.setStrokeCap(Paint.Cap.ROUND);
        penPaint.setStrokeJoin(Paint.Join.ROUND);
        shadePaint.setColor(0x8C000000);
        cropPaint.setStyle(Paint.Style.STROKE);
        cropPaint.setColor(Color.WHITE);
        cropPaint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        cropPaint.setPathEffect(new DashPathEffect(new float[]{20, 12}, 0));
    }

    // ---------- API awam ----------

    public boolean hasImage() { return base != null; }

    public void setImage(Bitmap bmp) {
        clearUndo();
        base = bmp.isMutable() ? bmp : bmp.copy(Bitmap.Config.ARGB_8888, true);
        ink = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        inkCanvas = new Canvas(ink);
        brightness = 1f;
        updateBrightness();
        setMode(MODE_DRAW);
        updateMatrix();
        invalidate();
    }

    public void setPenColor(int c) { penColor = c; }

    public void setMode(int m) {
        mode = m;
        cropRect = null;
        invalidate();
    }

    public int getMode() { return mode; }

    public void setBrightness(float k) {
        brightness = k;
        updateBrightness();
        invalidate();
    }

    public boolean undo() {
        if (undo.isEmpty()) return false;
        Bitmap[] s = undo.remove(undo.size() - 1);
        base.recycle(); ink.recycle();
        base = s[0]; ink = s[1];
        inkCanvas = new Canvas(ink);
        updateMatrix();
        invalidate();
        return true;
    }

    public void rotate() {
        if (base == null) return;
        snapshot();
        Matrix m = new Matrix();
        m.postRotate(90);
        Bitmap nb = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), m, true);
        Bitmap ni = Bitmap.createBitmap(ink, 0, 0, ink.getWidth(), ink.getHeight(), m, true);
        replace(nb, ni);
    }

    /** @return false jika tiada kawasan pangkas yang sah */
    public boolean applyCrop() {
        if (base == null || cropRect == null) return false;
        Rect r = new Rect();
        cropRect.round(r);
        if (!r.intersect(0, 0, base.getWidth(), base.getHeight()) || r.width() < 10 || r.height() < 10) return false;
        snapshot();
        Bitmap nb = Bitmap.createBitmap(base, r.left, r.top, r.width(), r.height());
        Bitmap ni = Bitmap.createBitmap(ink, r.left, r.top, r.width(), r.height());
        replace(nb.copy(Bitmap.Config.ARGB_8888, true), ni.copy(Bitmap.Config.ARGB_8888, true));
        setMode(MODE_DRAW);
        return true;
    }

    /** Gabung imej + kecerahan + conteng untuk disimpan. */
    public Bitmap export() {
        Bitmap out = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(base, 0, 0, basePaint);
        c.drawBitmap(ink, 0, 0, null);
        return out;
    }

    // ---------- Dalaman ----------

    private void replace(Bitmap nb, Bitmap ni) {
        Bitmap ob = base, oi = ink;
        base = nb; ink = ni;
        if (ob != nb) ob.recycle();
        if (oi != ni) oi.recycle();
        inkCanvas = new Canvas(ink);
        updateMatrix();
        invalidate();
    }

    private void snapshot() {
        undo.add(new Bitmap[]{base.copy(Bitmap.Config.ARGB_8888, true), ink.copy(Bitmap.Config.ARGB_8888, true)});
        while (undo.size() > MAX_UNDO) {
            Bitmap[] old = undo.remove(0);
            old[0].recycle(); old[1].recycle();
        }
    }

    private void clearUndo() {
        for (Bitmap[] s : undo) { s[0].recycle(); s[1].recycle(); }
        undo.clear();
    }

    private void updateBrightness() {
        ColorMatrix cm = new ColorMatrix();
        cm.setScale(brightness, brightness, brightness, 1f);
        basePaint.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    private void updateMatrix() {
        if (base == null || getWidth() == 0) return;
        float pad = 12 * getResources().getDisplayMetrics().density;
        float aw = getWidth() - 2 * pad, ah = getHeight() - 2 * pad;
        float s = Math.min(aw / base.getWidth(), ah / base.getHeight());
        float dx = (getWidth() - base.getWidth() * s) / 2f, dy = (getHeight() - base.getHeight() * s) / 2f;
        viewMatrix.reset();
        viewMatrix.postScale(s, s);
        viewMatrix.postTranslate(dx, dy);
        viewMatrix.invert(inverse);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        updateMatrix();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (base == null) return;
        canvas.save();
        canvas.concat(viewMatrix);
        canvas.drawBitmap(base, 0, 0, basePaint);
        canvas.drawBitmap(ink, 0, 0, bmpPaint);
        canvas.restore();

        if (mode == MODE_CROP && cropRect != null) {
            RectF v = new RectF();
            viewMatrix.mapRect(v, cropRect);
            RectF img = new RectF(0, 0, base.getWidth(), base.getHeight());
            viewMatrix.mapRect(img);
            canvas.drawRect(img.left, img.top, img.right, v.top, shadePaint);
            canvas.drawRect(img.left, v.bottom, img.right, img.bottom, shadePaint);
            canvas.drawRect(img.left, v.top, v.left, v.bottom, shadePaint);
            canvas.drawRect(v.right, v.top, img.right, v.bottom, shadePaint);
            canvas.drawRect(v, cropPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (base == null) return false;
        float[] p = {e.getX(), e.getY()};
        inverse.mapPoints(p);
        float x = p[0], y = p[1];

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                if (mode == MODE_DRAW) {
                    snapshot();
                    penPaint.setColor(penColor);
                    penPaint.setStrokeWidth(Math.max(4f, base.getWidth() / 180f));
                    livePath.reset();
                    livePath.moveTo(x, y);
                    livePath.lineTo(x + 0.1f, y + 0.1f);
                    inkCanvas.drawPath(livePath, penPaint);
                    lastX = x; lastY = y;
                } else {
                    startX = clampX(x); startY = clampY(y);
                    cropRect = new RectF(startX, startY, startX, startY);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!touching) return true;
                if (mode == MODE_DRAW) {
                    // Gunakan titik sejarah untuk garisan lancar
                    int n = e.getHistorySize();
                    for (int i = 0; i <= n; i++) {
                        float[] q = (i < n) ? new float[]{e.getHistoricalX(i), e.getHistoricalY(i)} : new float[]{e.getX(), e.getY()};
                        inverse.mapPoints(q);
                        livePath.reset();
                        livePath.moveTo(lastX, lastY);
                        livePath.quadTo(lastX, lastY, (lastX + q[0]) / 2f, (lastY + q[1]) / 2f);
                        livePath.lineTo(q[0], q[1]);
                        inkCanvas.drawPath(livePath, penPaint);
                        lastX = q[0]; lastY = q[1];
                    }
                } else if (cropRect != null) {
                    float cx = clampX(x), cy = clampY(y);
                    cropRect.set(Math.min(startX, cx), Math.min(startY, cy), Math.max(startX, cx), Math.max(startY, cy));
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private float clampX(float x) { return Math.max(0, Math.min(base.getWidth(), x)); }
    private float clampY(float y) { return Math.max(0, Math.min(base.getHeight(), y)); }
}
