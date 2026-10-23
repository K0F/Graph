package cz.kof.graph;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {
    private static final int REQ_PICK_PHOTOS = 1001;
    private static final int REQ_PERMS = 1002;

    static {
        try {
            System.loadLibrary("graph_dada");
        } catch (Throwable ignored) {
        }
    }

    public static native long[] dadaBytes64(byte[] stoneBytes, int count, long baseSeed);

    private final List<Uri> selectedUris = new ArrayList<>();
    private final List<File> musicFiles = new ArrayList<>();

    private TextView statusText;
    private VideoView videoPreview;
    private Button pickBtn;
    private Button latestBtn;
    private Button shareBtn;
    private Button postgenBtn;

    private File lastRenderedMp4 = null;
    private byte[] stoneBytes = null;
    private Bitmap stoneBitmap = null;
    private Typeface gomotorFont = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNeededPermissions();
        loadAssets();
        scanMusicPool();
        buildUi();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
            }, REQ_PERMS);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_PERMS);
        }
    }

    private void loadAssets() {
        try (InputStream is = getAssets().open("noise.png")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
            stoneBytes = bos.toByteArray();
            stoneBitmap = BitmapFactory.decodeByteArray(stoneBytes, 0, stoneBytes.length);
        } catch (Exception ignored) {
        }
        try {
            gomotorFont = Typeface.createFromAsset(getAssets(), "gomotor.ttf");
        } catch (Exception ignored) {
            gomotorFont = Typeface.MONOSPACE;
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0D0D0D"));
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        TextView header = label("GRAPH", 22, "#00FF66");
        header.setPadding(0, 0, 0, dp(8));
        root.addView(header);

        statusText = label("Select photos or share from Gallery -> auto-renders MP4.", 14, "#FFFFFF");
        statusText.setPadding(0, 0, 0, dp(14));
        root.addView(statusText);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.bottomMargin = dp(10);

        pickBtn = button("1. PICK PHOTOS & RENDER", "#004422", "#00FF66");
        pickBtn.setOnClickListener(v -> openGalleryPicker());
        root.addView(pickBtn, btnLp);

        latestBtn = button("2. LAST WEEK PHOTOS & RENDER", "#222222", "#FFFFFF");
        latestBtn.setOnClickListener(v -> {
            if (loadLatestCameraPhotos()) {
                startRender(true);
            }
        });
        root.addView(latestBtn, btnLp);

        videoPreview = new VideoView(this);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
        vlp.bottomMargin = dp(12);
        root.addView(videoPreview, vlp);

        shareBtn = button("3. SHARE VIDEO (WHATSAPP / FB)", "#143320", "#44FF88");
        shareBtn.setEnabled(false);
        shareBtn.setOnClickListener(v -> shareRenderedVideo());
        root.addView(shareBtn, btnLp);

        postgenBtn = button("4. SHARE LAST WEEK VIA POSTGEN", "#002233", "#00CCFF");
        postgenBtn.setOnClickListener(v -> shareLastWeekViaPostgen());
        root.addView(postgenBtn, btnLp);

        setContentView(root);
    }

    private void scanMusicPool() {
        musicFiles.clear();
        File[] dirs = new File[]{
                new File(Environment.getExternalStorageDirectory(), "Music/gram"),
                new File(Environment.getExternalStorageDirectory(), "Music"),
                new File(Environment.getExternalStorageDirectory(), "DCIM")
        };
        for (File d : dirs) {
            if (d.exists() && d.isDirectory()) {
                File[] list = d.listFiles();
                if (list != null) {
                    Arrays.sort(list, Comparator.comparing(File::getName));
                    for (File f : list) {
                        String n = f.getName().toLowerCase(Locale.ROOT);
                        if (f.isFile() && (n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".mp4"))) {
                            musicFiles.add(f);
                        }
                    }
                }
            }
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        if (type != null && type.startsWith("image/")) {
            selectedUris.clear();
            if (Intent.ACTION_SEND.equals(action)) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) selectedUris.add(uri);
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) selectedUris.addAll(list);
            }
            if (selectedUris.isEmpty() && intent.getClipData() != null) {
                ClipData cd = intent.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) selectedUris.add(u);
                }
            }
            List<Uri> cleanUris = new ArrayList<>();
            for (Uri u : selectedUris) {
                if (!Postgen.isDocumentPhoto(this, u)) {
                    cleanUris.add(u);
                }
            }
            selectedUris.clear();
            selectedUris.addAll(cleanUris);
            if (!selectedUris.isEmpty()) {
                startRender(true);
            } else {
                statusText.setText("Document reproduction skipped — no normal photos to render.");
            }
        }
    }

    private void openGalleryPicker() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, "Select Photos"), REQ_PICK_PHOTOS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_PHOTOS && resultCode == RESULT_OK && data != null) {
            selectedUris.clear();
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) selectedUris.add(u);
                }
            } else if (data.getData() != null) {
                selectedUris.add(data.getData());
            }
            List<Uri> cleanUris = new ArrayList<>();
            for (Uri u : selectedUris) {
                if (!Postgen.isDocumentPhoto(this, u)) {
                    cleanUris.add(u);
                }
            }
            selectedUris.clear();
            selectedUris.addAll(cleanUris);
            if (!selectedUris.isEmpty()) {
                startRender(true);
            }
        }
    }

    private boolean loadLatestCameraPhotos() {
        List<File> photos = Postgen.loadLastWeekCameraPhotos();
        if (photos.isEmpty()) {
            statusText.setText("No photos in DCIM/Camera — tap Pick Photos.");
            return false;
        }
        selectedUris.clear();
        for (File f : photos) {
            selectedUris.add(Uri.fromFile(f));
        }
        statusText.setText("Selected " + selectedUris.size() + " photos from last week.");
        return true;
    }

    private void shareLastWeekViaPostgen() {
        postgenBtn.setEnabled(false);
        pickBtn.setEnabled(false);
        latestBtn.setEnabled(false);
        statusText.setText("Loading last week photos for Postgen...");
        new Thread(() -> {
            List<File> photos = Postgen.loadLastWeekCameraPhotos();
            if (photos.isEmpty()) {
                runOnUiThread(() -> {
                    statusText.setText("No camera photos found from last week.");
                    postgenBtn.setEnabled(true);
                    pickBtn.setEnabled(true);
                    latestBtn.setEnabled(true);
                    postgenBtn.setEnabled(true);
                });
                return;
            }
            int total = photos.size();
            int posted = 0;
            for (int i = 0; i < total; i++) {
                final int idx = i;
                File f = photos.get(i);
                boolean ok = Postgen.processImageForPostgen(this, Uri.fromFile(f), (cur, tot, fn) -> {
                    runOnUiThread(() -> statusText.setText("Postgen (" + cur + "/" + tot + "): " + fn));
                }, idx + 1, total);
                if (ok) posted++;
            }
            final int finalPosted = posted;
            runOnUiThread(() -> {
                statusText.setText("Postgen ready: " + finalPosted + " posts in DCIM/postgen");
                postgenBtn.setEnabled(true);
                pickBtn.setEnabled(true);
                latestBtn.setEnabled(true);
                android.widget.Toast.makeText(this, "Postgen: " + finalPosted + " posts generated in DCIM/postgen",
                        android.widget.Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void startRender(final boolean autoShareWhenDone) {
        if (selectedUris.isEmpty()) return;
        pickBtn.setEnabled(false);
        latestBtn.setEnabled(false);
        shareBtn.setEnabled(false);
        postgenBtn.setEnabled(false);

        scanMusicPool();
        final int framesPerSlide = 11; // 0.44s per slide
        final int durationSec = 12;    // 12s short video
        final int totalFrames = durationSec * VideoRenderer.FPS;
        final List<Uri> urisCopy = new ArrayList<>(selectedUris);
        final File musicFile = musicFiles.isEmpty() ? null : musicFiles.get(new Random().nextInt(musicFiles.size()));
        final int nSlides = Math.max(1, (totalFrames / framesPerSlide) + 2);
        final int[] order = computeSlideOrder(nSlides, urisCopy.size());

        File outDir = new File(Environment.getExternalStorageDirectory(), "DCIM/Graph");
        if (!outDir.exists() && !outDir.mkdirs()) {
            outDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final File outFile = new File(outDir, "graph_" + ts + ".mp4");

        new Thread(() -> {
            try {
                VideoRenderer.renderMp4(
                        MainActivity.this,
                        urisCopy,
                        order,
                        framesPerSlide,
                        totalFrames,
                        true,
                        "kof26",
                        4,
                        stoneBitmap,
                        gomotorFont,
                        musicFile,
                        outFile,
                        (frame, total, st) -> runOnUiThread(() -> statusText.setText(st))
                );
                MediaScannerConnection.scanFile(MainActivity.this,
                        new String[]{outFile.getAbsolutePath()}, new String[]{"video/mp4"}, null);
                runOnUiThread(() -> onRenderSuccess(outFile, autoShareWhenDone));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Error: " + e.getMessage());
                    pickBtn.setEnabled(true);
                    latestBtn.setEnabled(true);
                    postgenBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private int[] computeSlideOrder(int nSlides, int poolSize) {
        int[] order = new int[nSlides];
        if (stoneBytes != null) {
            try {
                long[] words = dadaBytes64(stoneBytes, nSlides, System.currentTimeMillis() & 0xffffL);
                for (int i = 0; i < nSlides; i++) {
                    order[i] = (int) (Math.abs(words[i]) % poolSize);
                }
                return order;
            } catch (Throwable ignored) {
            }
        }
        Random rng = new Random();
        for (int i = 0; i < nSlides; i++) order[i] = rng.nextInt(Math.max(1, poolSize));
        return order;
    }

    private void onRenderSuccess(File outFile, boolean autoShare) {
        lastRenderedMp4 = outFile;
        pickBtn.setEnabled(true);
        latestBtn.setEnabled(true);
        shareBtn.setEnabled(true);
        postgenBtn.setEnabled(true);
        statusText.setText("Ready: " + outFile.getName() + " (" + (outFile.length() / 1024) + " KB)");
        try {
            videoPreview.setVideoPath(outFile.getAbsolutePath());
            videoPreview.setOnPreparedListener(mp -> mp.setLooping(true));
            videoPreview.start();
        } catch (Exception ignored) {
        }
        if (autoShare) {
            shareRenderedVideo();
        }
    }

    private void shareRenderedVideo() {
        if (lastRenderedMp4 == null || !lastRenderedMp4.exists()) return;
        Uri uri = GraphFileProvider.getUriForFile(lastRenderedMp4);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("video/mp4");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share Video"));
    }

    private TextView label(String text, int sp, String hexColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(Color.parseColor(hexColor));
        tv.setTypeface(Typeface.MONOSPACE);
        return tv;
    }

    private Button button(String text, String bgHex, String fgHex) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTypeface(Typeface.MONOSPACE);
        b.setBackgroundColor(Color.parseColor(bgHex));
        b.setTextColor(Color.parseColor(fgHex));
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
