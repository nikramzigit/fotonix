package com.fotonix.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int PICK = 1001;
    private static final int MAX_DIM = 2048;

    private static final int BG = 0xFF111418, BAR = 0xFF1A1E24, BTN = 0xFF2A2F37;
    private static final int RED = 0xFFE53935, BLACK = 0xFF000000, BLUE = 0xFF1E63E9;

    private EditorView editor;
    private TextView title, fileLbl, briLbl, emptyLbl;
    private SeekBar bri;
    private Button btnCrop, btnReady;
    private View[] pens;
    private final View[] needImage = new View[5];
    private String imgName = "imej";
    private float dp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(BAR);
        buildUi();
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    // ================= Antara muka =================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Bar atas
        LinearLayout top = new LinearLayout(this);
        top.setBackgroundColor(BAR);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(px(14), px(10), px(14), px(10));
        title = new TextView(this);
        title.setText("FotoNix");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        fileLbl = new TextView(this);
        fileLbl.setText("Tiada imej");
        fileLbl.setTextColor(0xFF9AA0A6);
        fileLbl.setTextSize(12);
        fileLbl.setSingleLine(true);
        fileLbl.setGravity(Gravity.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(fileLbl, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        // Kanvas
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        editor = new EditorView(this);
        stage.addView(editor, new android.widget.FrameLayout.LayoutParams(-1, -1));
        emptyLbl = new TextView(this);
        emptyLbl.setText("Tekan \"Buka\" untuk pilih imej");
        emptyLbl.setTextColor(0xFF9AA0A6);
        emptyLbl.setTextSize(16);
        emptyLbl.setGravity(Gravity.CENTER);
        stage.addView(emptyLbl, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        // Kecerahan
        LinearLayout briRow = new LinearLayout(this);
        briRow.setBackgroundColor(BAR);
        briRow.setGravity(Gravity.CENTER_VERTICAL);
        briRow.setPadding(px(14), px(6), px(8), px(6));
        briLbl = new TextView(this);
        briLbl.setText("Kecerahan 100%");
        briLbl.setTextColor(Color.WHITE);
        briLbl.setTextSize(13);
        briLbl.setMinWidth(px(110));
        bri = new SeekBar(this);
        bri.setMax(180);          // 20% .. 200%
        bri.setProgress(80);
        bri.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                int pct = v + 20;
                briLbl.setText("Kecerahan " + pct + "%");
                editor.setBrightness(pct / 100f);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        briRow.addView(briLbl);
        briRow.addView(bri, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(briRow);

        // Bar alat: ikon sahaja, satu baris, lebar sama rata
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(4), px(8), px(4), px(12));

        Button open = icon("📂", "Buka imej", 0xFF1967D2);
        open.setOnClickListener(v -> pickImage());

        pens = new View[]{pen(RED, "Pen merah"), pen(BLACK, "Pen hitam"), pen(BLUE, "Pen biru")};
        selectPen(0);

        Button undo = icon("↩", "Undur", BTN);
        undo.setOnClickListener(v -> { if (!editor.undo()) toast("Tiada tindakan untuk diundur"); });
        Button rot = icon("↻", "Putar 90°", BTN);
        rot.setOnClickListener(v -> editor.rotate());
        btnCrop = icon("✂", "Pangkas", BTN);
        btnCrop.setOnClickListener(v -> {
            if (editor.getMode() != EditorView.MODE_CROP) { setMode(EditorView.MODE_CROP); return; }
            if (editor.applyCrop()) { setMode(EditorView.MODE_DRAW); toast("Imej telah dipangkas"); }
            else toast("Seret pada imej untuk pilih kawasan dahulu");
        });
        btnReady = icon("⬇", "Ready: simpan JPG", 0xFF1E8E3E);
        btnReady.setOnClickListener(v -> save());

        for (View v : new View[]{open, pens[0], pens[1], pens[2], undo, rot, btnCrop, btnReady}) {
            LinearLayout cell = new LinearLayout(this);
            cell.setGravity(Gravity.CENTER);
            cell.addView(v);
            bar.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        View[] gated = {undo, rot, btnCrop, btnReady, bri};
        System.arraycopy(gated, 0, needImage, 0, gated.length);
        setEnabledAll(false);

        root.addView(bar);
        setContentView(root);
    }

    private Button icon(String glyph, String label, int color) {
        Button b = new Button(this);
        b.setText(glyph);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(20);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setContentDescription(label);
        b.setOnLongClickListener(v -> { toast(label); return true; });
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(12));
        b.setBackground(g);
        b.setLayoutParams(new LinearLayout.LayoutParams(px(42), px(42)));
        b.setStateListAnimator(null);
        return b;
    }

    private View pen(int color, String label) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(36), px(36)));
        v.setTag(color);
        v.setContentDescription(label);
        v.setOnClickListener(x -> {
            for (int i = 0; i < pens.length; i++) if (pens[i] == x) selectPen(i);
            if (editor.hasImage()) setMode(EditorView.MODE_DRAW);
        });
        return v;
    }

    private void selectPen(int idx) {
        for (int i = 0; i < pens.length; i++) {
            int c = (Integer) pens[i].getTag();
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            g.setStroke(px(i == idx ? 3 : 2), i == idx ? Color.WHITE : 0xFF555B63);
            pens[i].setBackground(g);
            if (i == idx) editor.setPenColor(c);
        }
    }

    private void setMode(int m) {
        editor.setMode(m);
        boolean crop = m == EditorView.MODE_CROP;
        btnCrop.setText(crop ? "✔" : "✂");
        ((GradientDrawable) btnCrop.getBackground()).setColor(crop ? 0xFFF9AB00 : BTN);
        btnCrop.setTextColor(crop ? Color.BLACK : Color.WHITE);
        if (crop) toast("Seret kawasan, kemudian tekan ✔ untuk pangkas. Tekan pen untuk batal.");
    }

    private void setEnabledAll(boolean on) {
        for (View v : needImage) { v.setEnabled(on); v.setAlpha(on ? 1f : 0.35f); }
    }

    // ================= Buka imej =================

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Pilih Imej"), PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        toast("Memuatkan imej…");
        new Thread(() -> {
            try {
                final Bitmap bmp = loadBitmap(uri);
                final String name = displayName(uri);
                runOnUiThread(() -> {
                    imgName = name.replaceAll("\\.[^.]+$", "").replaceAll("[^\\w\\-]+", "_");
                    if (imgName.isEmpty()) imgName = "imej";
                    fileLbl.setText(name);
                    emptyLbl.setVisibility(View.GONE);
                    editor.setImage(bmp);
                    bri.setProgress(80);
                    setEnabledAll(true);
                    setMode(EditorView.MODE_DRAW);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> toast("Gagal buka imej: " + t.getMessage()));
            }
        }).start();
    }

    private Bitmap loadBitmap(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(is, null, o); }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > MAX_DIM * 2) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream is = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(is, null, o); }
        if (bmp == null) throw new Exception("format tidak disokong");

        // Skala ke had maksimum + betulkan orientasi kamera
        Matrix m = new Matrix();
        float s = Math.min(1f, (float) MAX_DIM / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (s < 1f) m.postScale(s, s);
        int rot = 0;
        if (Build.VERSION.SDK_INT >= 24) {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                int ori = new ExifInterface(is).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Exception ignored) {}
        }
        if (rot != 0) m.postRotate(rot);
        if (s < 1f || rot != 0) {
            Bitmap t = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (t != bmp) bmp.recycle();
            bmp = t;
        }
        if (bmp.isMutable()) return bmp;
        Bitmap mut = bmp.copy(Bitmap.Config.ARGB_8888, true);
        bmp.recycle();
        return mut;
    }

    private String displayName(Uri uri) {
        String n = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) {}
        if (n == null) n = uri.getLastPathSegment();
        return n == null ? "imej" : n;
    }

    // ================= Simpan (Ready) =================

    private int nextNumber() {
        SharedPreferences p = getSharedPreferences("fotonix", MODE_PRIVATE);
        int n = p.getInt("counter", 0) + 1;
        p.edit().putInt("counter", n).apply();
        return n;
    }

    private void save() {
        if (!editor.hasImage()) return;
        final Bitmap out = editor.export();
        final String fileName = nextNumber() + "_" + imgName + ".jpg";
        btnReady.setEnabled(false);
        new Thread(() -> {
            String msg;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FotoNix");
                    Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (u == null) throw new Exception("tidak dapat cipta fail");
                    try (OutputStream os = getContentResolver().openOutputStream(u)) {
                        out.compress(Bitmap.CompressFormat.JPEG, 92, os);
                    }
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FotoNix");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, fileName);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        out.compress(Bitmap.CompressFormat.JPEG, 92, os);
                    }
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
                }
                msg = "Disimpan: Pictures/FotoNix/" + fileName;
            } catch (Exception e) {
                msg = "Gagal simpan: " + e.getMessage();
            } finally {
                out.recycle();
            }
            final String m = msg;
            runOnUiThread(() -> { btnReady.setEnabled(true); toast(m); });
        }).start();
    }

    // ================= Bantuan =================

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private int px(int v) { return Math.round(v * dp); }
}
