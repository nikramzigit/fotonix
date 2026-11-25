package com.fotonix.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 10, REQ_PICK = 11;
    private static final int PREVIEW_MAX = 2048;
    private static final int BG = 0xFF111418, BAR = 0xFF1A1E24, BTN = 0xFF2A2F37, ACCENT = 0xFF4DA3FF, MUTED = 0xFF9AA0A6;
    private static final int[] PEN_COLORS = {0xFFE53935, 0xFF1E63E9, 0xFF000000};
    private static final String[] PEN_NAMES = {"Merah", "Biru", "Hitam"};
    private static final int T_NONE = 0, T_CROP = 1, T_DRAW = 2, T_BRI = 3;

    private float dp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    // Galeri
    private LinearLayout galleryRoot;
    private GridView grid;
    private TextView emptyMsg;
    private Button permBtn;
    private final ArrayList<Long> ids = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private GalleryAdapter adapter;
    private LruCache<Long, Bitmap> cache;
    private boolean askedOnce;

    // Penyunting
    private LinearLayout editorRoot, colorPanel, briPanel, cropPanel;
    private EditorView editor;
    private TextView nameLbl, briLbl, drawIcon;
    private SeekBar bri;
    private Button saveBtn;
    private LinearLayout[] tools = new LinearLayout[4];
    private View[] dots = new View[3];
    private int activeTool = T_NONE, penIdx = 0;
    private Uri curUri;
    private String curName = "imej";
    private EditDoc doc;
    private boolean fromShare, saving;
    private int curIndex = -1;   // kedudukan dalam galeri, -1 jika dari Kongsi / fail

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(BAR);
        getWindow().setNavigationBarColor(BAR);
        cache = new LruCache<Long, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
            @Override protected int sizeOf(Long k, Bitmap v) { return v.getByteCount() / 1024; }
        };

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(buildGallery(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildEditor(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        Uri shared = extractUri(getIntent());
        if (shared != null) {
            fromShare = true;
            openEditor(shared, null);
        } else {
            showGallery();
            if (hasPerm()) loadGallery(); else requestPerm();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (galleryRoot.getVisibility() == View.VISIBLE && hasPerm() && !ids.isEmpty()) loadGallery();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (editorRoot.getVisibility() == View.VISIBLE) {
            if (fromShare) { finish(); return; }
            editor.clear();
            doc = null;
            showGallery();
            if (hasPerm()) loadGallery();
        } else super.onBackPressed();
    }

    private Uri extractUri(Intent i) {
        if (i == null) return null;
        String a = i.getAction();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u == null) {
                ClipData cd = i.getClipData();
                if (cd != null && cd.getItemCount() > 0) u = cd.getItemAt(0).getUri();
            }
            return u;
        }
        if (Intent.ACTION_EDIT.equals(a) || Intent.ACTION_VIEW.equals(a)) return i.getData();
        return null;
    }

    // ================= Kebenaran =================

    private String[] perms() {
        if (Build.VERSION.SDK_INT >= 34)
            return new String[]{"android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"};
        if (Build.VERSION.SDK_INT >= 33) return new String[]{"android.permission.READ_MEDIA_IMAGES"};
        return new String[]{"android.permission.READ_EXTERNAL_STORAGE"};
    }

    private boolean hasPerm() {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : perms()) if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true;
        return false;
    }

    private void requestPerm() {
        if (Build.VERSION.SDK_INT < 23) return;
        askedOnce = true;
        requestPermissions(perms(), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req != REQ_PERM) return;
        if (hasPerm()) loadGallery();
        else {
            emptyMsg.setText("FotoNix perlukan kebenaran untuk memaparkan gambar terkini di telefon anda.");
            emptyMsg.setVisibility(View.VISIBLE);
            permBtn.setVisibility(View.VISIBLE);
        }
    }

    // ================= Galeri =================

    private View buildGallery() {
        galleryRoot = new LinearLayout(this);
        galleryRoot.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = bar();
        TextView title = text("FotoNix", 21, Color.WHITE, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button pick = iconBtn("📂", "Pilih dari fail", BTN);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Pilih Imej"), REQ_PICK);
        });
        top.addView(pick);
        galleryRoot.addView(top);

        FrameLayout body = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(px(2));
        grid.setVerticalSpacing(px(2));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0x334DA3FF));
        adapter = new GalleryAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> openAt(pos));
        grid.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (bt - t != ob - ot) adapter.notifyDataSetChanged();
        });
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(px(32), 0, px(32), 0);
        emptyMsg = text("", 15, MUTED, false);
        emptyMsg.setGravity(Gravity.CENTER);
        emptyMsg.setVisibility(View.GONE);
        empty.addView(emptyMsg);
        permBtn = new Button(this);
        permBtn.setText("Beri Kebenaran");
        permBtn.setAllCaps(false);
        permBtn.setTextColor(Color.WHITE);
        permBtn.setBackground(round(0xFF1967D2, 12));
        permBtn.setVisibility(View.GONE);
        permBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && askedOnce && !shouldShowRequestPermissionRationale(perms()[0])) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
            } else requestPerm();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(48));
        lp.topMargin = px(16);
        empty.addView(permBtn, lp);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        galleryRoot.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        return galleryRoot;
    }

    private void showGallery() {
        galleryRoot.setVisibility(View.VISIBLE);
        editorRoot.setVisibility(View.GONE);
    }

    private void loadGallery() {
        emptyMsg.setVisibility(View.GONE);
        permBtn.setVisibility(View.GONE);
        pool.execute(() -> {
            final ArrayList<Long> li = new ArrayList<>();
            final ArrayList<String> ln = new ArrayList<>();
            String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                    null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) while (c.moveToNext() && li.size() < 1000) {
                    li.add(c.getLong(0));
                    ln.add(c.getString(1));
                }
            } catch (Exception ignored) {}
            ui.post(() -> {
                ids.clear(); ids.addAll(li);
                names.clear(); names.addAll(ln);
                adapter.notifyDataSetChanged();
                if (ids.isEmpty()) {
                    emptyMsg.setText("Tiada gambar ditemui.\nTekan 📂 untuk pilih dari fail.");
                    emptyMsg.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private class GalleryAdapter extends BaseAdapter {
        public int getCount() { return ids.size(); }
        public Object getItem(int p) { return ids.get(p); }
        public long getItemId(int p) { return ids.get(p); }

        public View getView(int pos, View cv, ViewGroup parent) {
            ImageView iv = (cv instanceof ImageView) ? (ImageView) cv : new ImageView(MainActivity.this);
            int h = grid.getHeight() > 0 ? (grid.getHeight() - px(2) * 4) / 5 : getResources().getDisplayMetrics().heightPixels / 6;
            iv.setLayoutParams(new AbsListView.LayoutParams(-1, h));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF22262D);
            final long id = ids.get(pos);
            iv.setTag(id);
            Bitmap bm = cache.get(id);
            iv.setImageBitmap(bm);
            if (bm == null) pool.execute(() -> {
                if (!Long.valueOf(id).equals(iv.getTag())) return;
                Bitmap t = loadThumb(id);
                if (t == null) return;
                cache.put(id, t);
                ui.post(() -> { if (Long.valueOf(id).equals(iv.getTag())) iv.setImageBitmap(t); });
            });
            return iv;
        }
    }

    private Bitmap loadThumb(long id) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                return getContentResolver().loadThumbnail(u, new Size(360, 360), null);
            }
            return MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            curIndex = -1;
            openEditor(data.getData(), null);
        }
    }

    // ================= Penyunting =================

    private View buildEditor() {
        editorRoot = new LinearLayout(this);
        editorRoot.setOrientation(LinearLayout.VERTICAL);

        // Bar atas: Undur (kiri) | nama | Simpan (kanan)
        LinearLayout top = bar();
        Button undo = iconBtn("↩", "Undur", BTN);
        undo.setOnClickListener(v -> {
            if (activeTool == T_CROP) { cancelCrop(); return; }
            if (!editor.undo()) toast("Tiada tindakan untuk diundur");
        });
        top.addView(undo);
        nameLbl = text("", 13, MUTED, false);
        nameLbl.setGravity(Gravity.CENTER);
        nameLbl.setSingleLine(true);
        nameLbl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(nameLbl, new LinearLayout.LayoutParams(0, -2, 1));
        saveBtn = iconBtn("💾", "Simpan", 0xFF1E8E3E);
        saveBtn.setOnClickListener(v -> save());
        top.addView(saveBtn);
        editorRoot.addView(top);

        editor = new EditorView(this);
        editor.setSwipeListener(this::swipe);
        editorRoot.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));

        // Panel warna
        colorPanel = panel();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            View d = new View(this);
            d.setContentDescription("Pen " + PEN_NAMES[i]);
            d.setOnClickListener(v -> { selectPen(idx); colorPanel.setVisibility(View.GONE); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(40), px(40));
            lp.setMargins(px(14), 0, px(14), 0);
            colorPanel.addView(d, lp);
            dots[i] = d;
        }

        // Panel kecerahan
        briPanel = panel();
        briLbl = text("100%", 13, Color.WHITE, true);
        briLbl.setMinWidth(px(52));
        briPanel.addView(briLbl);
        bri = new SeekBar(this);
        bri.setMax(180);
        bri.setProgress(80);
        bri.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                briLbl.setText((v + 20) + "%");
                editor.setBrightness((v + 20) / 100f);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        briPanel.addView(bri, new LinearLayout.LayoutParams(0, -2, 1));

        // Panel pangkas
        cropPanel = panel();
        Button cancel = pill("✕  Batal", BTN);
        cancel.setOnClickListener(v -> cancelCrop());
        Button apply = pill("✓  Pangkas", 0xFFF9AB00);
        apply.setTextColor(Color.BLACK);
        apply.setOnClickListener(v -> {
            if (!editor.applyCrop()) toast("Kawasan pangkas terlalu kecil");
            setTool(T_NONE);
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, px(44), 1);
        plp.setMargins(px(8), 0, px(8), 0);
        cropPanel.addView(cancel, plp);
        cropPanel.addView(apply, new LinearLayout.LayoutParams(plp));

        editorRoot.addView(colorPanel);
        editorRoot.addView(briPanel);
        editorRoot.addView(cropPanel);

        // Bar alat bawah: Crop, Conteng, Rotate, Kecerahan
        LinearLayout toolBar = new LinearLayout(this);
        toolBar.setBackgroundColor(BAR);
        toolBar.setPadding(px(4), px(6), px(4), px(10));
        String[][] spec = {{"✂︎", "Pangkas"}, {"✎︎", "Conteng"}, {"↻", "Putar"}, {"☀︎", "Kecerahan"}};
        for (int i = 0; i < 4; i++) {
            tools[i] = tool(spec[i][0], spec[i][1]);
            toolBar.addView(tools[i], new LinearLayout.LayoutParams(0, -2, 1));
        }
        drawIcon = (TextView) tools[1].getChildAt(0);
        tools[0].setOnClickListener(v -> {
            if (activeTool == T_CROP) return;
            editor.startCrop();
            setTool(T_CROP);
        });
        tools[1].setOnClickListener(v -> {
            if (activeTool != T_DRAW) {
                if (activeTool == T_CROP) editor.setMode(EditorView.MODE_NONE);
                setTool(T_DRAW);
                editor.setMode(EditorView.MODE_DRAW);
            } else {
                colorPanel.setVisibility(colorPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        tools[2].setOnClickListener(v -> {
            if (activeTool == T_CROP) setTool(T_NONE);
            editor.rotate();
        });
        tools[3].setOnClickListener(v -> {
            if (activeTool == T_BRI) { setTool(T_NONE); return; }
            editor.setMode(EditorView.MODE_NONE);
            setTool(T_BRI);
        });
        editorRoot.addView(toolBar);

        selectPen(0);
        setTool(T_NONE);
        return editorRoot;
    }

    private void cancelCrop() {
        editor.setMode(EditorView.MODE_NONE);
        setTool(T_NONE);
    }

    private void setTool(int t) {
        activeTool = t;
        if (t != T_DRAW && t != T_CROP && editor.getMode() == EditorView.MODE_DRAW) editor.setMode(EditorView.MODE_NONE);
        colorPanel.setVisibility(View.GONE);
        briPanel.setVisibility(t == T_BRI ? View.VISIBLE : View.GONE);
        cropPanel.setVisibility(t == T_CROP ? View.VISIBLE : View.GONE);
        int[] map = {T_CROP, T_DRAW, T_NONE, T_BRI};
        for (int i = 0; i < 4; i++) {
            boolean on = map[i] == t && t != T_NONE;
            tools[i].setBackground(on ? round(0xFF263A55, 12) : null);
            TextView ic = (TextView) tools[i].getChildAt(0), lb = (TextView) tools[i].getChildAt(1);
            lb.setTextColor(on ? ACCENT : MUTED);
            if (i != 1) ic.setTextColor(on ? ACCENT : Color.WHITE);
        }
    }

    private void selectPen(int idx) {
        penIdx = idx;
        editor.setPenColor(PEN_COLORS[idx]);
        if (drawIcon != null) drawIcon.setTextColor(idx == 2 ? Color.WHITE : PEN_COLORS[idx]);
        for (int i = 0; i < 3; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(PEN_COLORS[i]);
            g.setStroke(px(i == idx ? 3 : 1), i == idx ? Color.WHITE : 0xFF555B63);
            dots[i].setBackground(g);
        }
        if (idx == 2 && drawIcon != null) {
            // pen hitam: tunjuk ikon putih dengan garis bawah hitam supaya kelihatan
            drawIcon.setShadowLayer(px(2), 0, 0, Color.BLACK);
        } else if (drawIcon != null) drawIcon.setShadowLayer(0, 0, 0, 0);
    }

    private void openAt(int pos) {
        if (pos < 0 || pos >= ids.size()) return;
        curIndex = pos;
        openEditor(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ids.get(pos)), names.get(pos));
    }

    /** dir = +1 gambar seterusnya, -1 gambar sebelumnya. */
    private void swipe(int dir) {
        if (curIndex < 0 || saving) return;
        final int n = curIndex + dir;
        if (n < 0 || n >= ids.size()) {
            toast(dir > 0 ? "Ini gambar terakhir" : "Ini gambar pertama");
            return;
        }
        if (editor.hasEdits()) {
            new AlertDialog.Builder(this)
                    .setMessage("Suntingan belum disimpan. Buang dan beralih ke gambar lain?")
                    .setPositiveButton("Buang", (d, w) -> slideTo(n, dir))
                    .setNegativeButton("Batal", null)
                    .show();
        } else slideTo(n, dir);
    }

    private void slideTo(int n, int dir) {
        float w = editor.getWidth();
        editor.animate().translationX(-dir * w).alpha(0f).setDuration(140).withEndAction(() -> {
            editor.setTranslationX(dir * w * 0.35f);
            openAt(n);
        }).start();
    }

    private void openEditor(Uri uri, String knownName) {
        galleryRoot.setVisibility(View.GONE);
        editorRoot.setVisibility(View.VISIBLE);
        nameLbl.setText("Memuatkan…");
        editor.clear();
        pool.execute(() -> {
            try {
                final int[] dims = new int[2];
                final Bitmap prev = decode(uri, true, dims);
                final String name = knownName != null ? knownName : displayName(uri);
                ui.post(() -> {
                    curUri = uri;
                    curName = name.replaceAll("\\.[^.]+$", "").replaceAll("[^\\w\\-]+", "_");
                    if (curName.isEmpty()) curName = "imej";
                    nameLbl.setText((curIndex >= 0 ? (curIndex + 1) + "/" + ids.size() + "  ·  " : "")
                            + name + "  ·  " + dims[0] + "×" + dims[1]);
                    doc = new EditDoc(dims[0], dims[1]);
                    editor.setSource(prev, doc, prev.getWidth() / (float) dims[0]);
                    bri.setProgress(80);
                    selectPen(0);
                    setTool(T_NONE);
                    editor.animate().translationX(0f).alpha(1f).setDuration(180).start();
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    editor.setTranslationX(0f);
                    editor.setAlpha(1f);
                    toast("Gagal buka imej: " + t.getMessage());
                    if (fromShare) finish(); else showGallery();
                });
            }
        });
    }

    // ================= Nyahkod imej =================

    private int exifRotation(Uri uri) {
        if (Build.VERSION.SDK_INT < 24) return 0;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            int o = new ExifInterface(is).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * Nyahkod imej dengan orientasi yang betul.
     * preview=true: dikecilkan ke PREVIEW_MAX. preview=false: resolusi penuh (atau separuh jika sample=2).
     * dims[0..1] diisi dengan saiz asal (selepas orientasi).
     */
    private Bitmap decode(Uri uri, boolean preview, int[] dims) throws Exception {
        return decode(uri, preview, dims, 1);
    }

    private Bitmap decode(Uri uri, boolean preview, int[] dims, int forceSample) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(is, null, o); }
        if (o.outWidth <= 0) throw new Exception("format tidak disokong");
        int rot = exifRotation(uri);
        boolean swap = rot == 90 || rot == 270;
        if (dims != null) { dims[0] = swap ? o.outHeight : o.outWidth; dims[1] = swap ? o.outWidth : o.outHeight; }

        int sample = forceSample;
        if (preview) while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= PREVIEW_MAX) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        d.inMutable = true;
        Bitmap bmp;
        try (InputStream is = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(is, null, d); }
        if (bmp == null) throw new Exception("format tidak disokong");

        float s = preview ? Math.min(1f, (float) PREVIEW_MAX / Math.max(bmp.getWidth(), bmp.getHeight())) : 1f;
        if (s >= 1f && rot == 0) return bmp;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int nw = Math.round((swap ? h : w) * s), nh = Math.round((swap ? w : h) * s);
        Bitmap out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
        Matrix m = new Matrix();
        m.postRotate(rot);
        if (rot == 90) m.postTranslate(h, 0);
        else if (rot == 180) m.postTranslate(w, h);
        else if (rot == 270) m.postTranslate(0, w);
        m.postScale(s, s);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        new Canvas(out).drawBitmap(bmp, m, p);
        bmp.recycle();
        return out;
    }

    private String displayName(Uri uri) {
        String n = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) {}
        if (n == null) n = uri.getLastPathSegment();
        return n == null ? "imej" : n;
    }

    // ================= Simpan =================

    private int nextNumber() {
        SharedPreferences p = getSharedPreferences("fotonix", MODE_PRIVATE);
        int n = p.getInt("counter", 0) + 1;
        p.edit().putInt("counter", n).apply();
        return n;
    }

    private void save() {
        if (doc == null || curUri == null || saving) return;
        if (activeTool == T_CROP) cancelCrop();
        saving = true;
        saveBtn.setAlpha(0.4f);
        toast("Menyimpan pada resolusi penuh…");
        final EditDoc snap = doc.copy();
        final float k = editor.getBrightness();
        final Uri uri = curUri;
        final String fileName = nextNumber() + "_" + curName + ".jpg";
        pool.execute(() -> {
            String msg;
            Bitmap out = null;
            boolean half = false;
            try {
                Bitmap full;
                try {
                    full = decode(uri, false, null, 1);
                } catch (OutOfMemoryError oom) {
                    full = decode(uri, false, null, 2);
                    half = true;
                }
                float s = full.getWidth() / (float) snap.origW;
                out = snap.applyGeometry(full, s, true);
                if (!out.isMutable()) { Bitmap m = out.copy(Bitmap.Config.ARGB_8888, true); out.recycle(); out = m; }
                EditDoc.applyBrightness(out, k);
                Canvas c = new Canvas(out);
                if (out.hasAlpha()) c.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER);
                snap.drawStrokes(c, s);
                writeJpeg(out, fileName);
                msg = "Disimpan: Pictures/FotoNix/" + fileName + "  (" + out.getWidth() + "×" + out.getHeight() + ")"
                        + (half ? "\nMemori tidak cukup, disimpan pada separuh resolusi." : "");
            } catch (Throwable e) {
                msg = "Gagal simpan: " + e.getMessage();
            } finally {
                if (out != null) out.recycle();
            }
            final String m = msg;
            ui.post(() -> {
                saving = false;
                saveBtn.setAlpha(1f);
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void writeJpeg(Bitmap out, String fileName) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FotoNix");
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("tidak dapat cipta fail");
            try (OutputStream os = getContentResolver().openOutputStream(u)) {
                out.compress(Bitmap.CompressFormat.JPEG, 100, os);
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FotoNix");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, fileName);
            try (FileOutputStream os = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, 100, os);
            }
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
        }
    }

    // ================= Bantuan UI =================

    private LinearLayout bar() {
        LinearLayout l = new LinearLayout(this);
        l.setBackgroundColor(BAR);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(px(12), px(8), px(12), px(8));
        return l;
    }

    private LinearLayout panel() {
        LinearLayout l = new LinearLayout(this);
        l.setBackgroundColor(0xFF20252C);
        l.setGravity(Gravity.CENTER);
        l.setPadding(px(14), px(10), px(14), px(10));
        l.setVisibility(View.GONE);
        return l;
    }

    private LinearLayout tool(String glyph, String label) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, px(6), 0, px(6));
        l.setContentDescription(label);
        TextView ic = text(glyph, 24, Color.WHITE, false);
        ic.setGravity(Gravity.CENTER);
        TextView lb = text(label, 11, MUTED, false);
        lb.setGravity(Gravity.CENTER);
        l.addView(ic);
        l.addView(lb);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button iconBtn(String glyph, String label, int color) {
        Button b = new Button(this);
        b.setText(glyph);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(19);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setContentDescription(label);
        b.setOnLongClickListener(v -> { toast(label); return true; });
        b.setBackground(round(color, 12));
        b.setLayoutParams(new LinearLayout.LayoutParams(px(44), px(44)));
        b.setStateListAnimator(null);
        return b;
    }

    private Button pill(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(color, 12));
        b.setStateListAnimator(null);
        return b;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(r));
        return g;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private int px(int v) { return Math.round(v * dp); }
}
