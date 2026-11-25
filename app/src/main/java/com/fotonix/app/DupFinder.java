package com.fotonix.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pencari gambar duplikat menggunakan "difference hash" (dHash) 64-bit.
 * Gambar yang sama walaupun berbeza resolusi, mampatan JPEG atau nama fail
 * menghasilkan cap jari hampir sama (jarak Hamming kecil).
 */
public class DupFinder {

    /** Jarak Hamming maksimum (daripada 64 bit) untuk dianggap duplikat. */
    private static final int THRESHOLD = 5;
    /** Beza nisbah aspek maksimum (5%). */
    private static final float ASPECT_TOL = 0.05f;

    public static class Item {
        public long id, size, taken, mod, hash;
        public int w, h;
        public String name;
        public boolean hashed, del;
        public long pixels() { return (long) w * h; }
    }

    public interface Progress {
        void onProgress(int done, int total);
        boolean cancelled();
    }

    private final ContentResolver cr;
    private final File cacheFile;

    public DupFinder(Context c) {
        cr = c.getContentResolver();
        cacheFile = new File(c.getFilesDir(), "dhash.txt");
    }

    /** @return senarai kumpulan duplikat (item pertama = terbaik, disimpan), atau null jika dibatalkan. */
    public List<List<Item>> find(final Progress pr) throws InterruptedException {
        final ArrayList<Item> all = query();
        HashMap<Long, long[]> cache = loadCache();
        final AtomicInteger done = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(4);
        for (final Item it : all) {
            long[] c = cache.get(it.id);
            if (c != null && c[0] == it.mod) { it.hash = c[1]; it.hashed = true; done.incrementAndGet(); continue; }
            ex.execute(() -> {
                if (pr.cancelled()) return;
                Bitmap t = thumb(it.id);
                if (t != null) { it.hash = dhash(t); it.hashed = true; t.recycle(); }
                int d = done.incrementAndGet();
                if (d % 10 == 0) pr.onProgress(d, all.size());
            });
        }
        ex.shutdown();
        while (!ex.awaitTermination(200, TimeUnit.MILLISECONDS)) {
            if (pr.cancelled()) { ex.shutdownNow(); return null; }
        }
        if (pr.cancelled()) return null;
        pr.onProgress(all.size(), all.size());
        saveCache(all);

        // Kumpulkan dengan union-find
        ArrayList<Item> list = new ArrayList<>();
        for (Item it : all) if (it.hashed) list.add(it);
        int n = list.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        long[] hs = new long[n];
        float[] ar = new float[n];
        for (int i = 0; i < n; i++) { hs[i] = list.get(i).hash; ar[i] = aspect(list.get(i)); }
        for (int i = 0; i < n; i++) {
            if (pr.cancelled()) return null;
            for (int j = i + 1; j < n; j++) {
                if (Long.bitCount(hs[i] ^ hs[j]) > THRESHOLD) continue;
                if (ar[i] > 0 && ar[j] > 0 && Math.abs(ar[i] - ar[j]) / Math.max(ar[i], ar[j]) > ASPECT_TOL) continue;
                union(parent, i, j);
            }
        }
        HashMap<Integer, List<Item>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int r = root(parent, i);
            List<Item> g = groups.get(r);
            if (g == null) { g = new ArrayList<>(); groups.put(r, g); }
            g.add(list.get(i));
        }
        ArrayList<List<Item>> out = new ArrayList<>();
        for (List<Item> g : groups.values()) {
            if (g.size() < 2) continue;
            // Terbaik: resolusi tertinggi → fail terbesar (kurang mampatan) → paling awal (asal)
            Collections.sort(g, (a, b) -> {
                if (a.pixels() != b.pixels()) return Long.compare(b.pixels(), a.pixels());
                if (a.size != b.size) return Long.compare(b.size, a.size);
                return Long.compare(a.taken, b.taken);
            });
            for (int i = 0; i < g.size(); i++) g.get(i).del = i > 0;
            out.add(g);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.get(0).taken, a.get(0).taken));
        return out;
    }

    private ArrayList<Item> query() {
        ArrayList<Item> l = new ArrayList<>();
        String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DISPLAY_NAME};
        try (Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0);
                it.w = c.isNull(1) ? 0 : c.getInt(1);
                it.h = c.isNull(2) ? 0 : c.getInt(2);
                it.size = c.isNull(3) ? 0 : c.getLong(3);
                it.taken = c.isNull(4) ? 0 : c.getLong(4);
                it.mod = c.isNull(5) ? 0 : c.getLong(5);
                it.name = c.getString(6);
                l.add(it);
            }
        } catch (Exception ignored) {}
        return l;
    }

    private Bitmap thumb(long id) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                return cr.loadThumbnail(u, new Size(96, 96), null);
            }
            return MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MICRO_KIND, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** dHash: kecilkan ke 9×8 kelabu, bandingkan setiap piksel dengan jiran kanannya. */
    static long dhash(Bitmap src) {
        Bitmap s = Bitmap.createScaledBitmap(src, 9, 8, true);
        int[] px = new int[72];
        s.getPixels(px, 0, 9, 0, 0, 9, 8);
        if (s != src) s.recycle();
        long h = 0;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (lum(px[y * 9 + x]) > lum(px[y * 9 + x + 1])) h |= 1L << bit;
                bit++;
            }
        }
        return h;
    }

    private static int lum(int c) {
        return (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
    }

    private static float aspect(Item it) {
        if (it.w <= 0 || it.h <= 0) return 0;
        return (float) Math.max(it.w, it.h) / Math.min(it.w, it.h);   // tidak terjejas oleh orientasi
    }

    private static int root(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        int ra = root(p, a), rb = root(p, b);
        if (ra != rb) p[rb] = ra;
    }

    // Cache cap jari supaya imbasan seterusnya jauh lebih pantas
    private HashMap<Long, long[]> loadCache() {
        HashMap<Long, long[]> m = new HashMap<>();
        if (!cacheFile.exists()) return m;
        try (BufferedReader r = new BufferedReader(new FileReader(cacheFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length != 3) continue;
                m.put(Long.parseLong(p[0]), new long[]{Long.parseLong(p[1]), new java.math.BigInteger(p[2], 16).longValue()});
            }
        } catch (Exception ignored) {}
        return m;
    }

    private void saveCache(List<Item> all) {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(cacheFile))) {
            for (Item it : all) {
                if (!it.hashed) continue;
                w.write(it.id + "," + it.mod + "," + Long.toHexString(it.hash));
                w.newLine();
            }
        } catch (Exception ignored) {}
    }
}
