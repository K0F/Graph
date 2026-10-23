package cz.kof.graph;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Postgen {
    private static final String TAG = "Postgen";
    public static final int TARGET_WIDTH = 512;
    private static final byte[] DOC_PHOTO_MARKER = "docPhoto".getBytes(StandardCharsets.US_ASCII);

    private static final Pattern RE_YYYY = Pattern.compile("(20\\d{2})[_-]?(0[1-9]|1[0-2])[_-]?(0[1-9]|[12]\\d|3[01])");
    private static final Pattern RE_YY = Pattern.compile("(?:IMG[_-]?)(\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])");

    public interface ProgressListener {
        void onProgress(int current, int total, String filename);
    }

    public static File getPostgenDir() {
        File base = new File(Environment.getExternalStorageDirectory(), "DCIM/postgen");
        if (!base.exists()) {
            base.mkdirs();
        }
        return base;
    }

    public static boolean isDocumentPhoto(File file) {
        if (file == null || !file.exists()) return false;
        String name = file.getName().toUpperCase(Locale.ROOT);
        if (name.startsWith("DOC_") || name.startsWith("SCAN_") || name.contains("DOCUMENT")) {
            return true;
        }
        try (InputStream is = new FileInputStream(file)) {
            return containsDocPhotoMarker(is);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isDocumentPhoto(Context ctx, Uri uri) {
        if (uri == null) return false;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return isDocumentPhoto(new File(uri.getPath()));
        }
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            return containsDocPhotoMarker(is);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean containsDocPhotoMarker(InputStream is) {
        if (is == null) return false;
        try {
            byte[] buf = new byte[65536];
            int n = is.read(buf);
            if (n <= 0) return false;
            for (int i = 0; i <= n - DOC_PHOTO_MARKER.length; i++) {
                boolean match = true;
                for (int j = 0; j < DOC_PHOTO_MARKER.length; j++) {
                    if (buf[i + j] != DOC_PHOTO_MARKER[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static List<File> loadLastWeekCameraPhotos() {
        File camDir = new File(Environment.getExternalStorageDirectory(), "DCIM/Camera");
        File[] all = camDir.listFiles((dir, name) -> {
            String low = name.toLowerCase(Locale.ROOT);
            return !name.startsWith(".") && (low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png"));
        });
        if (all == null || all.length == 0) return new ArrayList<>();

        Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        long oneWeekAgo = System.currentTimeMillis() - (7L * 24L * 3600L * 1000L);
        List<File> weekPhotos = new ArrayList<>();
        List<File> fallbackPhotos = new ArrayList<>();

        for (File f : all) {
            if (isDocumentPhoto(f)) {
                Log.d(TAG, "Skipping document reproduction: " + f.getName());
                continue;
            }
            if (f.lastModified() >= oneWeekAgo) {
                weekPhotos.add(f);
            }
            if (fallbackPhotos.size() < 24) {
                fallbackPhotos.add(f);
            }
        }

        return weekPhotos.isEmpty() ? fallbackPhotos : weekPhotos;
    }

    public static String extractDate(File file, String fallbackFilename) {
        String name = file != null ? file.getName() : fallbackFilename;
        if (name != null) {
            Matcher mYYYY = RE_YYYY.matcher(name);
            if (mYYYY.find()) {
                return mYYYY.group(1) + "-" + mYYYY.group(2) + "-" + mYYYY.group(3);
            }
            Matcher mYY = RE_YY.matcher(name);
            if (mYY.find()) {
                return "20" + mYY.group(1) + "-" + mYY.group(2) + "-" + mYY.group(3);
            }
        }
        if (file != null && file.exists()) {
            try {
                ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                String dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                if (dt != null && dt.length() >= 10) {
                    return dt.substring(0, 10).replace(':', '-');
                }
            } catch (Exception ignored) {
            }
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(file.lastModified()));
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static boolean processImageForPostgen(Context ctx, Uri uri, ProgressListener listener, int cur, int total) {
        try {
            File postgenDir = getPostgenDir();
            File assetsDir = new File(postgenDir, "assets");
            File postsDir = new File(postgenDir, "_posts");
            if (!assetsDir.exists()) assetsDir.mkdirs();
            if (!postsDir.exists()) postsDir.mkdirs();

            String displayName = resolveFilename(ctx, uri);
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = "IMG_" + System.currentTimeMillis() + ".jpg";
            }

            File srcFile = ("file".equals(uri.getScheme()) && uri.getPath() != null) ? new File(uri.getPath()) : null;
            if (srcFile != null && isDocumentPhoto(srcFile)) {
                Log.i(TAG, "Skipping document photo: " + displayName);
                return false;
            }
            if (srcFile == null && isDocumentPhoto(ctx, uri)) {
                Log.i(TAG, "Skipping document photo: " + displayName);
                return false;
            }

            String postDate = extractDate(srcFile, displayName);
            String title = displayName;
            int dot = title.lastIndexOf('.');
            if (dot > 0) title = title.substring(0, dot);
            title = title.replace(':', '-');

            if (listener != null) {
                listener.onProgress(cur, total, displayName);
            }

            // Decode image bounds
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, opts);
            }

            int origW = opts.outWidth;
            int origH = opts.outHeight;
            if (origW <= 0 || origH <= 0) return false;

            // Compute sample size
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = Math.max(1, origW / (TARGET_WIDTH * 2));
            Bitmap decoded;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                decoded = BitmapFactory.decodeStream(is, null, opts);
            }
            if (decoded == null) return false;

            // Scale precisely to TARGET_WIDTH
            int finalH = Math.max(1, Math.round((float) decoded.getHeight() * TARGET_WIDTH / decoded.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(decoded, TARGET_WIDTH, finalH, true);
            if (scaled != decoded) {
                decoded.recycle();
            }

            // Write asset to assets/<displayName>
            String safeFile = displayName.replace(':', '-');
            File destAsset = new File(assetsDir, safeFile);
            try (FileOutputStream fos = new FileOutputStream(destAsset)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            }
            scaled.recycle();

            // Write markdown post to _posts/<postDate>-<safeFile>.markdown
            String mdContent = "---\n" +
                    "layout: post\n" +
                    "title: \"" + title + "\"\n" +
                    "date: " + postDate + "\n" +
                    "categories: kof archive\n" +
                    "---\n\n" +
                    "![Image Alt](https://k0f.github.io/assets/" + safeFile + ")\n";

            File destPost = new File(postsDir, postDate + "-" + safeFile + ".markdown");
            try (FileOutputStream fos = new FileOutputStream(destPost)) {
                fos.write(mdContent.getBytes(StandardCharsets.UTF_8));
            }

            Log.i(TAG, "Created postgen post: " + destPost.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in processImageForPostgen", e);
            return false;
        }
    }

    private static String resolveFilename(Context ctx, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).getName();
        }
        try (android.database.Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0) {
                    return cursor.getString(nameIdx);
                }
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }
}
