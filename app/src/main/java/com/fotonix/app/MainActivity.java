package com.fotonix.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
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
import android.view.HapticFeedbackConstants;
import android.view.ViewConfiguration;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 10, REQ_PICK = 11, REQ_DELETE = 12, REQ_MOVE = 13;
    private static final int PREVIEW_MAX = 2048;
    // Palet skill-phone-dark (flat: tiada bayang, tiada gradien)
    private static final int BG = 0xFF16203B, BAR = 0xFF324065, BTN = 0xFF4B597C, INPUT = 0xFF3E4B6E, DIVIDER = 0xFF2D3A5D;
    private static final int ACCENT = 0xFF00ABAC, TEAL_BTN = 0xFF00C1BB, CORAL = 0xFFFF4C67, SKY = 0xFF00ADDF;
    private static final int MUTED = 0xFF7A8299, RADIUS = 3;
    private static final int[] PEN_COLORS = {0xFFE53935, 0xFF1E63E9, 0xFF000000};
    private static final String[] PEN_NAMES = {"Merah", "Biru", "Hitam"};
    private static final int T_NONE = 0, T_CROP = 1, T_DRAW = 2, T_BRI = 3;

    private float dp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ExecutorService peekPool = Executors.newSingleThreadExecutor();   // tidak beratur di belakang lakaran kecil

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
    private TextView galTitle;
    private ImageView selBtn, pickBtn, cancelSelBtn, delBtn, moveBtn;
    private String moveTarget, moveTargetName;

    // Duplikat
    private ImageView dupBtn;
    private LinearLayout dupRoot;
    private TextView dupSummary;
    private TextView dupDelBtn;
    private DupAdapter dupAdapter;
    private final ArrayList<List<DupFinder.Item>> dupGroups = new ArrayList<>();
    private volatile boolean dupCancel;
    private boolean deleteFromDup;
    private ArrayList<Long> pendingMove = new ArrayList<>();
    private boolean selecting;
    private final HashSet<Long> selected = new HashSet<>();
    private ArrayList<Long> pendingDelete = new ArrayList<>();

    // Intai (tekan lama)
    private ImageView peek;
    private boolean peeking;
    private long peekId = -1;

    // Saiz grid (cubit untuk ubah)
    private int cols = 3, minCols = 2, maxCols = 6;
    private ScaleGestureDetector scaleDet;
    private float scaleAcc = 1f;

    // Seret untuk pilih
    private boolean dragSel;
    private int dragStart = -1, dragLast = -1, touchSlop;
    private boolean dragAdd;
    private HashSet<Long> dragBase = new HashSet<>();
    private float downX, downY, lastX = -1, lastY = -1;
    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (!dragSel) return;
            if (lastY >= 0) {
                int edge = px(80), h = grid.getHeight(), dy = 0;
                if (lastY < edge) dy = -Math.max(4, (int) ((edge - lastY) / edge * px(28)));
                else if (lastY > h - edge) dy = Math.max(4, (int) ((lastY - (h - edge)) / edge * px(28)));
                if (dy != 0) {
                    grid.scrollListBy(dy);
                    updateDragTo(grid.pointToPosition((int) lastX, (int) Math.max(1, Math.min(h - 1, lastY))));
                }
            }
            ui.postDelayed(this, 16);
        }
    };

    // Penyunting
    private LinearLayout editorRoot, colorPanel, briPanel, cropPanel;
    private EditorView editor;
    private TextView nameLbl, metaLbl, briLbl;
    private View penDot;
    private boolean deleteFromEditor;
    private SeekBar bri;
    private ImageView saveBtn, editDelBtn;
    private LinearLayout[] tools = new LinearLayout[4];
    private View[] toolLines = new View[4];
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
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BAR);
        cache = new LruCache<Long, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
            @Override protected int sizeOf(Long k, Bitmap v) { return v.getByteCount() / 1024; }
        };

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(buildGallery(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildEditor(), new FrameLayout.LayoutParams(-1, -1));
        peek = new ImageView(this);
        peek.setBackgroundColor(0xF516203B);
        peek.setScaleType(ImageView.ScaleType.FIT_CENTER);
        peek.setVisibility(View.GONE);
        root.addView(buildDupScreen(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(peek, new FrameLayout.LayoutParams(-1, -1));   // intai sentiasa di atas
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
        } else if (dupRoot.getVisibility() == View.VISIBLE) {
            dupRoot.setVisibility(View.GONE);
        } else if (selecting) {
            setSelecting(false);
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
        cancelSelBtn = iconBtn(R.drawable.ic_close, "Batal pilihan", BTN);
        cancelSelBtn.setOnClickListener(v -> setSelecting(false));
        top.addView(cancelSelBtn);
        galTitle = text("FOTONIX", 17, Color.WHITE, true);
        galTitle.setLetterSpacing(0.08f);
        galTitle.setPadding(px(10), 0, px(10), 0);
        top.addView(galTitle, new LinearLayout.LayoutParams(0, -2, 1));
        dupBtn = iconBtn(R.drawable.ic_dup, "Cari & padam duplikat", BTN);
        dupBtn.setOnClickListener(v -> startDupScan());
        top.addView(dupBtn);
        selBtn = iconBtn(R.drawable.ic_select, "Pilih gambar", BTN);
        ((LinearLayout.LayoutParams) selBtn.getLayoutParams()).leftMargin = px(8);
        selBtn.setOnClickListener(v -> setSelecting(true));
        top.addView(selBtn);
        moveBtn = iconBtn(R.drawable.ic_move, "Pindah ke album", BTN);
        moveBtn.setOnClickListener(v -> chooseAlbum());
        top.addView(moveBtn);
        delBtn = iconBtn(R.drawable.ic_delete, "Padam", CORAL);
        delBtn.setOnClickListener(v -> deleteSelected());
        top.addView(delBtn);
        ImageView pick = iconBtn(R.drawable.ic_folder, "Pilih dari fail", BTN);
        pickBtn = pick;
        ((LinearLayout.LayoutParams) pick.getLayoutParams()).leftMargin = px(8);
        ((LinearLayout.LayoutParams) delBtn.getLayoutParams()).leftMargin = px(8);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Pilih Imej"), REQ_PICK);
        });
        top.addView(pick);
        galleryRoot.addView(top);
        cancelSelBtn.setVisibility(View.GONE);
        delBtn.setVisibility(View.GONE);
        moveBtn.setVisibility(View.GONE);

        FrameLayout body = new FrameLayout(this);
        grid = new GridView(this);
        boolean tablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        maxCols = tablet ? 10 : 6;
        cols = Math.max(minCols, Math.min(maxCols, getSharedPreferences("fotonix", MODE_PRIVATE).getInt("cols", tablet ? 5 : 3)));
        grid.setNumColumns(cols);
        scaleDet = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaleAcc = 1f; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                scaleAcc *= d.getScaleFactor();
                if (scaleAcc > 1.25f) { setCols(cols - 1); scaleAcc = 1f; }      // cubit keluar: gambar lebih besar
                else if (scaleAcc < 0.8f) { setCols(cols + 1); scaleAcc = 1f; }  // cubit masuk: lebih banyak gambar
                return true;
            }
        });
        grid.setHorizontalSpacing(px(2));
        grid.setVerticalSpacing(px(2));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0x3300ABAC));
        adapter = new GalleryAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            if (selecting) toggleSelect(pos); else openAt(pos);
        });
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (selecting) { dragStart = pos; beginDragSelect(); }   // mod pilih: tekan lama + seret
            else startPeek(pos);
            return true;
        });
        grid.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (peeking) {
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    endPeek();
                    cancelGridTouch(ev);
                }
                return true;
            }
            if (selecting && ev.getPointerCount() == 1 && handleDragSelect(ev, a)) return true;
            scaleDet.onTouchEvent(ev);
            if (a == MotionEvent.ACTION_POINTER_DOWN) cancelGridTouch(ev);
            return ev.getPointerCount() > 1 || scaleDet.isInProgress();
        });
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
        permBtn.setBackground(round(CORAL, RADIUS));
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
            // Susun ikut tarikh gambar diambil, supaya hasil suntingan duduk di sebelah gambar asal
            String[] orders = {
                    "COALESCE(" + MediaStore.Images.Media.DATE_TAKEN + ", " + MediaStore.Images.Media.DATE_ADDED + " * 1000) DESC",
                    MediaStore.Images.Media.DATE_TAKEN + " DESC, " + MediaStore.Images.Media.DATE_ADDED + " DESC",
                    MediaStore.Images.Media.DATE_ADDED + " DESC"};
            for (String order : orders) {
                li.clear(); ln.clear();
                try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, order)) {
                    if (c == null) continue;
                    while (c.moveToNext() && li.size() < 1000) {
                        li.add(c.getLong(0));
                        ln.add(c.getString(1));
                    }
                    break;
                } catch (Exception ignored) {}
            }
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
            FrameLayout cell;
            ImageView iv;
            View dim;
            TextView badge;
            if (cv instanceof FrameLayout) {
                cell = (FrameLayout) cv;
                iv = (ImageView) cell.getChildAt(0);
                dim = cell.getChildAt(1);
                badge = (TextView) cell.getChildAt(2);
            } else {
                cell = new FrameLayout(MainActivity.this);
                iv = new ImageView(MainActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(BAR);
                cell.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                dim = new View(MainActivity.this);
                dim.setBackgroundColor(0x6600ABAC);
                cell.addView(dim, new FrameLayout.LayoutParams(-1, -1));
                badge = new TextView(MainActivity.this);
                badge.setGravity(Gravity.CENTER);
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(14);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(px(26), px(26), Gravity.TOP | Gravity.END);
                blp.setMargins(0, px(6), px(6), 0);
                cell.addView(badge, blp);
            }
            int rows = Math.max(2, Math.round(cols * 5f / 3f));   // 3 lajur = 5 baris
            int gh = grid.getHeight() > 0 ? grid.getHeight() : getResources().getDisplayMetrics().heightPixels * 4 / 5;
            int h = (gh - px(2) * (rows - 1)) / rows;
            cell.setLayoutParams(new AbsListView.LayoutParams(-1, h));

            final long id = ids.get(pos);
            boolean sel = selected.contains(id);
            dim.setVisibility(sel ? View.VISIBLE : View.GONE);
            if (selecting) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(sel ? ACCENT : 0x5516203B);
                g.setStroke(px(2), Color.WHITE);
                badge.setBackground(g);
                badge.setText(sel ? "✓" : "");
                badge.setVisibility(View.VISIBLE);
            } else badge.setVisibility(View.GONE);

            final ImageView img = iv;
            img.setTag(id);
            Bitmap bm = cache.get(id);
            img.setImageBitmap(bm);
            if (bm == null) pool.execute(() -> {
                if (!Long.valueOf(id).equals(img.getTag())) return;
                Bitmap t = loadThumb(id);
                if (t == null) return;
                cache.put(id, t);
                ui.post(() -> { if (Long.valueOf(id).equals(img.getTag())) img.setImageBitmap(t); });
            });
            return cell;
        }
    }

    /** @return true jika sentuhan digunakan untuk seret-pilih. */
    private boolean handleDragSelect(MotionEvent ev, int a) {
        switch (a) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX(); downY = ev.getY();
                lastX = -1; lastY = -1;
                dragStart = grid.pointToPosition((int) downX, (int) downY);
                dragSel = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!dragSel && dragStart != GridView.INVALID_POSITION) {
                    float dx = ev.getX() - downX, dy = ev.getY() - downY;
                    // seret mendatar memulakan pilihan; seret menegak = skrol biasa
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) beginDragSelect();
                }
                if (dragSel) {
                    lastX = ev.getX(); lastY = ev.getY();
                    updateDragTo(grid.pointToPosition((int) lastX, (int) lastY));
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragSel) { endDragSelect(); return true; }
                return false;
        }
        return dragSel;
    }

    private void beginDragSelect() {
        if (dragStart < 0 || dragStart >= ids.size()) return;
        dragSel = true;
        dragBase = new HashSet<>(selected);
        dragAdd = !selected.contains(ids.get(dragStart));
        dragLast = -1;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        grid.onTouchEvent(c);
        c.recycle();
        grid.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        updateDragTo(dragStart);
        ui.removeCallbacks(autoScroll);
        ui.post(autoScroll);
    }

    private void updateDragTo(int pos) {
        if (pos == GridView.INVALID_POSITION || pos == dragLast || pos >= ids.size()) return;
        dragLast = pos;
        selected.clear();
        selected.addAll(dragBase);
        int lo = Math.min(dragStart, pos), hi = Math.max(dragStart, pos);
        for (int i = lo; i <= hi; i++) {
            if (dragAdd) selected.add(ids.get(i)); else selected.remove(ids.get(i));
        }
        updateSelTitle();
        adapter.notifyDataSetChanged();
    }

    private void endDragSelect() {
        dragSel = false;
        ui.removeCallbacks(autoScroll);
    }

    private void cancelGridTouch(MotionEvent ev) {
        MotionEvent c = MotionEvent.obtain(ev);
        c.setAction(MotionEvent.ACTION_CANCEL);
        grid.onTouchEvent(c);
        c.recycle();
    }

    private void setCols(int n) {
        n = Math.max(minCols, Math.min(maxCols, n));
        if (n == cols) return;
        int first = grid.getFirstVisiblePosition();
        cols = n;
        getSharedPreferences("fotonix", MODE_PRIVATE).edit().putInt("cols", n).apply();
        grid.setNumColumns(n);
        adapter.notifyDataSetChanged();
        grid.setSelection(first);
        toast(n + " lajur × " + Math.round(n * 5f / 3f) + " baris");
    }

    // ================= Pilih & Padam =================

    private void setSelecting(boolean on) {
        selecting = on;
        if (!on) { selected.clear(); endDragSelect(); }
        cancelSelBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        delBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        moveBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        selBtn.setVisibility(on ? View.GONE : View.VISIBLE);
        dupBtn.setVisibility(on ? View.GONE : View.VISIBLE);
        pickBtn.setVisibility(on ? View.GONE : View.VISIBLE);
        updateSelTitle();
        adapter.notifyDataSetChanged();
        if (on) askManageMediaOnce();
    }

    private void updateSelTitle() {
        galTitle.setText(selecting ? selected.size() + " DIPILIH" : "FOTONIX");
        delBtn.setAlpha(selected.isEmpty() ? 0.4f : 1f);
        moveBtn.setAlpha(selected.isEmpty() ? 0.4f : 1f);
    }

    private void toggleSelect(int pos) {
        long id = ids.get(pos);
        if (!selected.remove(id)) selected.add(id);
        updateSelTitle();
        adapter.notifyDataSetChanged();
    }

    /** Android 12+: minta akses "Pengurusan media" sekali supaya padam tanpa pengesahan sistem. */
    private void askManageMediaOnce() {
        if (Build.VERSION.SDK_INT < 31 || MediaStore.canManageMedia(this)) return;
        SharedPreferences p = getSharedPreferences("fotonix", MODE_PRIVATE);
        if (p.getBoolean("askedManageMedia", false)) return;
        p.edit().putBoolean("askedManageMedia", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Padam tanpa pengesahan")
                .setMessage("Supaya gambar dipadam terus tanpa tetingkap pengesahan Android, benarkan FotoNix dalam tetapan \"Pengurusan media\".")
                .setPositiveButton("Buka Tetapan", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", getPackageName(), null)));
                    } catch (Exception e) { toast("Tetapan tidak ditemui"); }
                })
                .setNegativeButton("Kemudian", null)
                .show();
    }

    private void deleteSelected() {
        if (selected.isEmpty()) { toast("Pilih gambar dahulu"); return; }
        trashIds(new ArrayList<>(selected));
    }

    private void trashIds(ArrayList<Long> list) {
        pendingDelete = list;
        final ArrayList<Uri> uris = new ArrayList<>();
        for (long id : pendingDelete) uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                // Dipindah ke tong sampah sistem (dipadam kekal oleh Android selepas 30 hari)
                PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), uris, true);
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) {
                toast("Gagal padam: " + e.getMessage());
            }
            return;
        }
        pool.execute(() -> {
            final ArrayList<Long> done = new ArrayList<>();
            for (int i = 0; i < uris.size(); i++) {
                try { if (getContentResolver().delete(uris.get(i), null, null) > 0) done.add(pendingDelete.get(i)); }
                catch (Exception ignored) {}
            }
            ui.post(() -> removeIds(done));
        });
    }

    /** Padam gambar yang sedang dibuka, kemudian terus ke gambar seterusnya. */
    private void deleteCurrent() {
        if (curIndex < 0 || curIndex >= ids.size()) { toast("Hanya gambar dari galeri boleh dipadam di sini"); return; }
        if (saving) return;
        askManageMediaOnce();
        deleteFromEditor = true;
        selected.clear();
        selected.add(ids.get(curIndex));
        deleteSelected();
    }

    private void afterEditorDelete() {
        deleteFromEditor = false;
        if (ids.isEmpty()) { editor.clear(); doc = null; showGallery(); return; }
        int n = Math.min(curIndex, ids.size() - 1);
        editor.setTranslationX(editor.getWidth() * 0.35f);
        editor.setAlpha(0f);
        openAt(n);
    }

    private void removeIds(ArrayList<Long> gone) {
        HashSet<Long> g = new HashSet<>(gone);
        for (int i = ids.size() - 1; i >= 0; i--) {
            if (g.contains(ids.get(i))) { cache.remove(ids.get(i)); ids.remove(i); names.remove(i); }
        }
        toast(gone.size() + " gambar dipadam");
        setSelecting(false);
        if (deleteFromDup) {
            deleteFromDup = false;
            dupRoot.setVisibility(View.GONE);
            loadGallery();
        }
        if (deleteFromEditor) {
            if (gone.isEmpty()) deleteFromEditor = false; else afterEditorDelete();
        }
    }

    // ================= Duplikat =================

    private void startDupScan() {
        if (!hasPerm()) { requestPerm(); return; }
        askManageMediaOnce();
        dupCancel = false;
        final TextView msg = text("Menyediakan…", 15, Color.WHITE, false);
        msg.setPadding(px(24), px(20), px(24), px(8));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Mencari gambar duplikat")
                .setView(msg)
                .setCancelable(false)
                .setNegativeButton("Batal", (d, w) -> dupCancel = true)
                .show();
        new Thread(() -> {
            List<List<DupFinder.Item>> res = null;
            try {
                res = new DupFinder(this).find(new DupFinder.Progress() {
                    @Override public void onProgress(int done, int total) {
                        ui.post(() -> msg.setText("Mengimbas " + done + " / " + total + " gambar…\n(Imbasan pertama lebih lama; seterusnya pantas)"));
                    }
                    @Override public boolean cancelled() { return dupCancel; }
                });
            } catch (Throwable ignored) {}
            final List<List<DupFinder.Item>> r = res;
            ui.post(() -> {
                try { dlg.dismiss(); } catch (Exception ignored) {}
                if (dupCancel || r == null) return;
                if (r.isEmpty()) { toast("Tiada gambar duplikat ditemui"); return; }
                dupGroups.clear();
                dupGroups.addAll(r);
                dupAdapter.notifyDataSetChanged();
                updateDupSummary();
                dupRoot.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    private View buildDupScreen() {
        dupRoot = new LinearLayout(this);
        dupRoot.setOrientation(LinearLayout.VERTICAL);
        dupRoot.setBackgroundColor(BG);
        dupRoot.setClickable(true);
        dupRoot.setVisibility(View.GONE);

        LinearLayout top = bar();
        ImageView close = iconBtn(R.drawable.ic_close, "Tutup", BTN);
        close.setOnClickListener(v -> dupRoot.setVisibility(View.GONE));
        top.addView(close);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(px(12), 0, 0, 0);
        TextView t = text("GAMBAR DUPLIKAT", 15, Color.WHITE, true);
        t.setLetterSpacing(0.08f);
        dupSummary = text("", 11, MUTED, false);
        info.addView(t);
        info.addView(dupSummary);
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        dupRoot.addView(top);

        TextView hint = text("Gambar bertanda SIMPAN ialah resolusi terbaik. Tekan mana-mana gambar untuk tukar SIMPAN / PADAM.", 12, MUTED, false);
        hint.setPadding(px(14), px(10), px(14), px(6));
        dupRoot.addView(hint);

        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(DIVIDER));
        lv.setDividerHeight(px(1));
        lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
        dupAdapter = new DupAdapter();
        lv.setAdapter(dupAdapter);
        dupRoot.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));

        dupDelBtn = text("", 14, Color.WHITE, true);
        dupDelBtn.setGravity(Gravity.CENTER);
        dupDelBtn.setLetterSpacing(0.08f);
        dupDelBtn.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), round(CORAL, RADIUS), null));
        dupDelBtn.setOnClickListener(v -> {
            ArrayList<Long> del = new ArrayList<>();
            for (List<DupFinder.Item> g : dupGroups) for (DupFinder.Item it : g) if (it.del) del.add(it.id);
            if (del.isEmpty()) { toast("Tiada gambar ditanda PADAM"); return; }
            deleteFromDup = true;
            trashIds(del);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, px(50));
        blp.setMargins(px(12), px(10), px(12), px(14));
        dupRoot.addView(dupDelBtn, blp);
        return dupRoot;
    }

    private void updateDupSummary() {
        int n = 0;
        long bytes = 0;
        for (List<DupFinder.Item> g : dupGroups) for (DupFinder.Item it : g) if (it.del) { n++; bytes += it.size; }
        dupSummary.setText(dupGroups.size() + " kumpulan  ·  " + n + " untuk dipadam  ·  " + mb(bytes) + " dijimatkan");
        dupDelBtn.setText("PADAM " + n + " DUPLIKAT");
        dupDelBtn.setAlpha(n == 0 ? 0.4f : 1f);
    }

    private String mb(long b) {
        return b >= 1024 * 1024 ? String.format(Locale.US, "%.1f MB", b / 1048576f) : (b / 1024) + " KB";
    }

    private class DupAdapter extends BaseAdapter {
        public int getCount() { return dupGroups.size(); }
        public Object getItem(int p) { return dupGroups.get(p); }
        public long getItemId(int p) { return p; }

        public View getView(int pos, View cv, ViewGroup parent) {
            final List<DupFinder.Item> g = dupGroups.get(pos);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(px(12), px(10), px(12), px(12));
            TextView head = text("KUMPULAN " + (pos + 1) + "  ·  " + g.size() + " GAMBAR", 11, MUTED, true);
            head.setLetterSpacing(0.08f);
            row.addView(head);
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(MainActivity.this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout cells = new LinearLayout(MainActivity.this);
            int cw = (getResources().getDisplayMetrics().widthPixels - px(24) - px(16)) / 3;
            for (final DupFinder.Item it : g) {
                cells.addView(dupCell(it, g, cw), new LinearLayout.LayoutParams(cw, -2));
                ((LinearLayout.LayoutParams) cells.getChildAt(cells.getChildCount() - 1).getLayoutParams()).rightMargin = px(8);
            }
            hs.addView(cells);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.topMargin = px(8);
            row.addView(hs, hlp);
            return row;
        }
    }

    private View dupCell(final DupFinder.Item it, final List<DupFinder.Item> group, int cw) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setBackgroundColor(BAR);
        FrameLayout imgBox = new FrameLayout(this);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imgBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        View frame = new View(this);
        GradientDrawable fr = new GradientDrawable();
        fr.setColor(it.del ? 0x55FF4C67 : 0);
        fr.setStroke(px(3), it.del ? CORAL : ACCENT);
        frame.setBackground(fr);
        imgBox.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        TextView tag = text(it.del ? "PADAM" : "SIMPAN", 11, Color.WHITE, true);
        tag.setLetterSpacing(0.08f);
        tag.setPadding(px(6), px(2), px(6), px(2));
        tag.setBackgroundColor(it.del ? CORAL : ACCENT);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tlp.setMargins(px(3), px(3), 0, 0);
        imgBox.addView(tag, tlp);
        cell.addView(imgBox, new LinearLayout.LayoutParams(cw, cw));
        TextView res = text((it.w > 0 ? it.w + "×" + it.h : "?") + "\n" + mb(it.size), 11, it.del ? MUTED : Color.WHITE, false);
        res.setPadding(px(6), px(4), px(6), px(6));
        cell.addView(res);

        iv.setTag(it.id);
        Bitmap bm = cache.get(it.id);
        iv.setImageBitmap(bm);
        if (bm == null) pool.execute(() -> {
            Bitmap tb = loadThumb(it.id);
            if (tb == null) return;
            cache.put(it.id, tb);
            ui.post(() -> { if (Long.valueOf(it.id).equals(iv.getTag())) iv.setImageBitmap(tb); });
        });

        cell.setOnClickListener(v -> {
            it.del = !it.del;
            boolean anyKeep = false;
            for (DupFinder.Item x : group) if (!x.del) anyKeep = true;
            if (!anyKeep) { it.del = false; toast("Sekurang-kurangnya satu gambar mesti disimpan"); return; }
            dupAdapter.notifyDataSetChanged();
            updateDupSummary();
        });
        cell.setOnLongClickListener(v -> {
            // tekan lama: lihat penuh
            int idx = ids.indexOf(it.id);
            if (idx >= 0) { startPeek(idx); v.setOnTouchListener((vv, ev) -> {
                if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP || ev.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                    endPeek(); vv.setOnTouchListener(null);
                }
                return false;
            }); }
            return true;
        });
        return cell;
    }

    // ================= Pindah ke album =================

    /** Senarai album sedia ada (folder yang mengandungi gambar), paling banyak gambar di atas. */
    private void chooseAlbum() {
        if (selected.isEmpty()) { toast("Pilih gambar dahulu"); return; }
        pool.execute(() -> {
            final HashMap<String, Integer> count = new HashMap<>();
            final HashMap<String, String> label = new HashMap<>();
            String col = Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA;
            try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{col, MediaStore.Images.Media.BUCKET_DISPLAY_NAME}, null, null, null)) {
                if (c != null) while (c.moveToNext()) {
                    String path = c.getString(0);
                    if (path == null) continue;
                    if (Build.VERSION.SDK_INT < 29) path = relFromData(path);
                    if (path == null) continue;
                    Integer n = count.get(path);
                    count.put(path, n == null ? 1 : n + 1);
                    String b = c.getString(1);
                    label.put(path, b != null ? b : path);
                }
            } catch (Exception ignored) {}
            final ArrayList<String> paths = new ArrayList<>(count.keySet());
            java.util.Collections.sort(paths, (x, y) -> count.get(y) - count.get(x));
            ui.post(() -> {
                String[] items = new String[paths.size() + 1];
                items[0] = "＋  Album baharu…";
                for (int i = 0; i < paths.size(); i++) {
                    String pth = paths.get(i);
                    items[i + 1] = label.get(pth) + "   (" + count.get(pth) + ")\n" + pth;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Pindah " + selected.size() + " gambar ke…")
                        .setItems(items, (d, w) -> {
                            if (w == 0) newAlbumDialog();
                            else startMove(paths.get(w - 1), label.get(paths.get(w - 1)));
                        })
                        .setNegativeButton("Batal", null)
                        .show();
            });
        });
    }

    private String relFromData(String data) {
        String root = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        int slash = data.lastIndexOf('/');
        if (!data.startsWith(root) || slash < root.length()) return null;
        return data.substring(root.length(), slash + 1);
    }

    private void newAlbumDialog() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("Nama album");
        in.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(px(20), px(8), px(20), 0);
        box.addView(in, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Album baharu")
                .setView(box)
                .setPositiveButton("Pindah", (d, w) -> {
                    String name = in.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
                    if (name.isEmpty()) { toast("Nama album kosong"); return; }
                    startMove(Environment.DIRECTORY_PICTURES + "/" + name + "/", name);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void startMove(String relPath, String name) {
        moveTarget = relPath;
        moveTargetName = name;
        pendingMove = new ArrayList<>(selected);
        if (Build.VERSION.SDK_INT >= 30) {
            ArrayList<Uri> uris = new ArrayList<>();
            for (long id : pendingMove) uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
            try {
                // Minta kebenaran ubah (tanpa tetingkap jika "Pengurusan media" dibenarkan)
                PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), uris);
                startIntentSenderForResult(pi.getIntentSender(), REQ_MOVE, null, 0, 0, 0);
            } catch (Exception e) { toast("Gagal pindah: " + e.getMessage()); }
        } else {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_PERM);
                toast("Beri kebenaran, kemudian cuba lagi");
                return;
            }
            doMove();
        }
    }

    /** Tukar folder gambar terus dalam MediaStore: tiada salinan, tarikh & kualiti kekal. */
    private void doMove() {
        final ArrayList<Long> list = new ArrayList<>(pendingMove);
        final String target = moveTarget, tname = moveTargetName;
        pool.execute(() -> {
            int ok = 0;
            for (long id : list) {
                Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, target);
                        if (getContentResolver().update(u, v, null, null) > 0) ok++;
                    } else {
                        String data = null;
                        try (Cursor c = getContentResolver().query(u, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
                            if (c != null && c.moveToFirst()) data = c.getString(0);
                        }
                        if (data == null) continue;
                        File src = new File(data);
                        File dir = new File(Environment.getExternalStorageDirectory(), target);
                        if (!dir.exists()) dir.mkdirs();
                        File dst = new File(dir, src.getName());
                        if (dst.exists() || !src.renameTo(dst)) continue;
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DATA, dst.getAbsolutePath());
                        getContentResolver().update(u, v, null, null);
                        ok++;
                    }
                } catch (Exception ignored) {}
            }
            final int n = ok;
            ui.post(() -> {
                toast(n + " gambar dipindah ke " + tname + (n < list.size() ? "  (" + (list.size() - n) + " gagal)" : ""));
                setSelecting(false);
                loadGallery();
            });
        });
    }

    // ================= Intai: tekan lama =================

    private void startPeek(int pos) {
        if (pos < 0 || pos >= ids.size()) return;
        final long id = ids.get(pos);
        peeking = true;
        peekId = id;
        peek.setImageBitmap(cache.get(id));
        peek.setAlpha(0f);
        peek.setVisibility(View.VISIBLE);
        peek.animate().alpha(1f).setDuration(120).start();
        // Nyahkod gambar sebenar pada saiz skrin (bukan lakaran kecil sistem yang kabur)
        final int max = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        peekPool.execute(() -> {
            if (!peeking || peekId != id) return;
            Bitmap big = null;
            Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            try { big = decodeMax(u, max, null, 1); } catch (Throwable ignored) {}
            final Bitmap b = big;
            if (b != null) ui.post(() -> { if (peeking && peekId == id) peek.setImageBitmap(b); });
        });
    }

    private void endPeek() {
        peeking = false;
        peekId = -1;
        peek.animate().cancel();
        peek.setVisibility(View.GONE);
        peek.setImageDrawable(null);
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
        if (req == REQ_MOVE) {
            if (res == RESULT_OK) doMove(); else toast("Pindah dibatalkan");
            return;
        }
        if (req == REQ_DELETE) {
            if (res == RESULT_OK) removeIds(pendingDelete);
            else { toast("Padam dibatalkan"); deleteFromEditor = false; deleteFromDup = false; selected.clear(); }
            return;
        }
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
        ImageView undo = iconBtn(R.drawable.ic_undo, "Undur", BTN);
        undo.setOnClickListener(v -> {
            if (activeTool == T_CROP) { cancelCrop(); return; }
            if (!editor.undo()) toast("Tiada tindakan untuk diundur");
        });
        top.addView(undo);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(px(12), 0, px(8), 0);
        nameLbl = text("", 14, Color.WHITE, true);
        nameLbl.setSingleLine(true);
        nameLbl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        metaLbl = text("", 11, MUTED, false);
        metaLbl.setSingleLine(true);
        info.addView(nameLbl);
        info.addView(metaLbl);
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        editDelBtn = iconBtn(R.drawable.ic_delete, "Padam gambar ini", CORAL);
        editDelBtn.setOnClickListener(v -> deleteCurrent());
        top.addView(editDelBtn);
        saveBtn = iconBtn(R.drawable.ic_save, "Simpan", TEAL_BTN);
        ((LinearLayout.LayoutParams) saveBtn.getLayoutParams()).leftMargin = px(10);
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
        bri.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bri.setThumbTintList(android.content.res.ColorStateList.valueOf(TEAL_BTN));
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
        ImageView cancel = iconBtn(R.drawable.ic_close, "Batal", BTN);
        cancel.setOnClickListener(v -> cancelCrop());
        ImageView apply = iconBtn(R.drawable.ic_check, "Pangkas", TEAL_BTN);
        apply.setOnClickListener(v -> {
            if (!editor.applyCrop()) toast("Kawasan pangkas terlalu kecil");
            setTool(T_NONE);
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, px(46), 1);
        plp.setMargins(px(10), 0, px(10), 0);
        cropPanel.addView(cancel, plp);
        cropPanel.addView(apply, new LinearLayout.LayoutParams(plp));

        editorRoot.addView(colorPanel);
        editorRoot.addView(briPanel);
        editorRoot.addView(cropPanel);

        // Bar alat bawah: Crop, Conteng, Rotate, Kecerahan
        LinearLayout toolBar = new LinearLayout(this);
        toolBar.setBackgroundColor(BAR);
        toolBar.setPadding(0, 0, 0, px(6));
        int[] icons = {R.drawable.ic_crop, R.drawable.ic_pen, R.drawable.ic_rotate, R.drawable.ic_brightness};
        String[] labels = {"Pangkas", "Conteng", "Putar", "Kecerahan"};
        for (int i = 0; i < 4; i++) {
            tools[i] = tool(icons[i], labels[i]);
            toolBar.addView(tools[i], new LinearLayout.LayoutParams(0, -2, 1));
            toolLines[i] = new View(this);
            tools[i].addView(toolLines[i], 0, new LinearLayout.LayoutParams(-1, px(3)));   // garis aktif teal di atas
        }
        // titik kecil di bawah ikon pen menunjukkan warna semasa
        penDot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(8), px(8));
        dlp.topMargin = -px(8);
        dlp.bottomMargin = px(4);
        tools[1].addView(penDot, dlp);
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
            ImageView ic = (ImageView) tools[i].getChildAt(1);
            tools[i].setBackgroundColor(on ? BTN : 0);
            ic.setColorFilter(on ? Color.WHITE : 0xFFB8BFD3);
            toolLines[i].setBackgroundColor(on ? ACCENT : 0);
        }
    }

    private void selectPen(int idx) {
        penIdx = idx;
        editor.setPenColor(PEN_COLORS[idx]);
        if (penDot != null) {
            GradientDrawable pd = new GradientDrawable();
            pd.setShape(GradientDrawable.OVAL);
            pd.setColor(PEN_COLORS[idx]);
            pd.setStroke(px(1), Color.WHITE);
            penDot.setBackground(pd);
        }
        for (int i = 0; i < 3; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(PEN_COLORS[i]);
            g.setStroke(px(i == idx ? 3 : 1), i == idx ? ACCENT : MUTED);
            dots[i].setBackground(g);
        }
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
        metaLbl.setText("");
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
                    nameLbl.setText(name);
                    metaLbl.setText((curIndex >= 0 ? (curIndex + 1) + " / " + ids.size() + "   ·   " : "")
                            + dims[0] + " × " + dims[1] + " px");
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
        return decodeMax(uri, preview ? PREVIEW_MAX : 0, dims, forceSample);
    }

    /** maxDim > 0: kecilkan supaya sisi terpanjang <= maxDim. maxDim = 0: resolusi penuh. */
    private Bitmap decodeMax(Uri uri, int maxDim, int[] dims, int forceSample) throws Exception {
        final boolean preview = maxDim > 0;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(is, null, o); }
        if (o.outWidth <= 0) throw new Exception("format tidak disokong");
        int rot = exifRotation(uri);
        boolean swap = rot == 90 || rot == 270;
        if (dims != null) { dims[0] = swap ? o.outHeight : o.outWidth; dims[1] = swap ? o.outWidth : o.outHeight; }

        int sample = forceSample;
        if (preview) while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        d.inMutable = true;
        Bitmap bmp;
        try (InputStream is = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(is, null, d); }
        if (bmp == null) throw new Exception("format tidak disokong");

        float s = preview ? Math.min(1f, (float) maxDim / Math.max(bmp.getWidth(), bmp.getHeight())) : 1f;
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
        final HashMap<String, String> exif = readExif(uri);
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
                long taken = originalTaken(uri, exif);
                writeJpeg(out, fileName, taken > 0 ? taken + 1000 : 0, exif);   // +1 saat: betul-betul di sebelah gambar asal
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

    // Tag EXIF yang disalin daripada gambar asal (tarikh, kamera, lokasi)
    private static final String[] EXIF_TAGS = {
            "DateTimeOriginal", "DateTimeDigitized", "DateTime", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
            "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized", "Make", "Model",
            "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef",
            "GPSDateStamp", "GPSTimeStamp"};

    private HashMap<String, String> readExif(Uri uri) {
        HashMap<String, String> m = new HashMap<>();
        if (Build.VERSION.SDK_INT < 24) return m;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            ExifInterface x = new ExifInterface(is);
            for (String t : EXIF_TAGS) {
                String v = x.getAttribute(t);
                if (v != null) m.put(t, v);
            }
        } catch (Exception ignored) {}
        return m;
    }

    /** Masa gambar asal diambil (ms). 0 jika tidak diketahui. */
    private long originalTaken(Uri uri, HashMap<String, String> exif) {
        long t = 0;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) t = c.getLong(0);
                if (t <= 0 && !c.isNull(1)) t = c.getLong(1) * 1000L;
            }
        } catch (Exception ignored) {}
        if (t <= 0) {
            String d = exif.get("DateTimeOriginal");
            if (d == null) d = exif.get("DateTime");
            if (d != null) {
                try { t = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(d).getTime(); }
                catch (Exception ignored) {}
            }
        }
        return t;
    }

    private void applyExif(ExifInterface x, HashMap<String, String> exif, long taken) throws Exception {
        for (java.util.Map.Entry<String, String> e : exif.entrySet()) x.setAttribute(e.getKey(), e.getValue());
        if (taken > 0) {
            String d = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new java.util.Date(taken));
            x.setAttribute("DateTimeOriginal", d);
            x.setAttribute("DateTimeDigitized", d);
            x.setAttribute("DateTime", d);
        }
        x.setAttribute("Orientation", "1");   // putaran sudah dimasukkan ke dalam piksel
        x.saveAttributes();
    }

    private void writeJpeg(Bitmap out, String fileName, long taken, HashMap<String, String> exif) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FotoNix");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            if (taken > 0) v.put(MediaStore.Images.Media.DATE_TAKEN, taken);
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("tidak dapat cipta fail");
            try (OutputStream os = getContentResolver().openOutputStream(u)) {
                out.compress(Bitmap.CompressFormat.JPEG, 100, os);
            }
            try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(u, "rw")) {
                if (pfd != null) applyExif(new ExifInterface(pfd.getFileDescriptor()), exif, taken);
            } catch (Exception ignored) {}
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (taken > 0) done.put(MediaStore.Images.Media.DATE_TAKEN, taken);
            getContentResolver().update(u, done, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FotoNix");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, fileName);
            try (FileOutputStream os = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, 100, os);
            }
            try { applyExif(new ExifInterface(f.getAbsolutePath()), exif, taken); } catch (Exception ignored) {}
            if (taken > 0) f.setLastModified(taken);
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
        l.setBackgroundColor(INPUT);
        l.setGravity(Gravity.CENTER);
        l.setPadding(px(14), px(10), px(14), px(10));
        l.setVisibility(View.GONE);
        return l;
    }

    private LinearLayout tool(int icon, String label) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setContentDescription(label);
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setColorFilter(0xFFB8BFD3);
        ic.setScaleType(ImageView.ScaleType.CENTER);
        ic.setContentDescription(label);
        l.addView(ic, new LinearLayout.LayoutParams(px(52), px(54)));
        l.setOnLongClickListener(v -> { toast(label); return true; });
        return l;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private ImageView iconBtn(int icon, String label, int color) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setColorFilter(Color.WHITE);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(label);
        b.setClickable(true);
        b.setOnLongClickListener(v -> { toast(label); return true; });
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), round(color, RADIUS), null));
        b.setLayoutParams(new LinearLayout.LayoutParams(px(46), px(46)));
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
