package cz.kof.graph;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private TextView photoCountText;
    private Spinner modeSpinner;
    private Spinner durationSpinner;
    private Spinner musicSpinner;
    private CheckBox grayscaleCheck;
    private EditText titleInput;
    private VideoView videoPreview;
    private Button renderBtn;
    private Button shareAllBtn;
    private Button shareWaBtn;
    private Button shareFbBtn;

    private File lastRenderedMp4 = null;
    private byte[] stoneBytes = null;
    private Bitmap stoneBitmap = null;
    private Typeface gomotorFont = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNeededPermissions();
        loadAssets();
        buildUi();
        scanMusicPool();
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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0D0D0D"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView header = label("GRAPH // K0F AV ENGINE", 20, "#00FF66");
        header.setPadding(0, 0, 0, dp(4));
        root.addView(header);

        TextView sub = label("Share-To & Local Photo Short Video Generator (932x576@25 PAL SD)", 12, "#888888");
        sub.setPadding(0, 0, 0, dp(12));
        root.addView(sub);

        photoCountText = label("Selected photos: 0", 14, "#FFFFFF");
        photoCountText.setPadding(0, 0, 0, dp(8));
        root.addView(photoCountText);

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);

        Button pickGalleryBtn = button("Pick Photos", "#222222", "#00FF66");
        pickGalleryBtn.setOnClickListener(v -> openGalleryPicker());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = dp(6);
        pickRow.addView(pickGalleryBtn, lp1);

        Button pickLatestBtn = button("Latest 24 (DCIM/Camera)", "#222222", "#FFFFFF");
        pickLatestBtn.setOnClickListener(v -> loadLatestCameraPhotos(24));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pickRow.addView(pickLatestBtn, lp2);

        root.addView(pickRow);

        root.addView(sectionLabel("MODE / CUT TIMING"));
        modeSpinner = new Spinner(this);
        modeSpinner.setBackgroundColor(Color.parseColor("#1A1A1A"));
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Dada Stone Oracle (0.20s / 5 frames per slide)",
                "Standard Slides (0.44s / 11 frames per slide)",
                "Slow Montage (1.00s / 25 frames per slide)"
        });
        modeSpinner.setAdapter(modeAdapter);
        root.addView(modeSpinner);

        root.addView(sectionLabel("VIDEO DURATION"));
        durationSpinner = new Spinner(this);
        durationSpinner.setBackgroundColor(Color.parseColor("#1A1A1A"));
        ArrayAdapter<String> durAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "8 seconds (short clip)",
                "15 seconds (story / status)",
                "30 seconds",
                "60 seconds (full dada film)"
        });
        durationSpinner.setAdapter(durAdapter);
        durationSpinner.setSelection(1);
        root.addView(durationSpinner);

        root.addView(sectionLabel("AUDIO / MUSIC POOL (/sdcard/Music/gram)"));
        musicSpinner = new Spinner(this);
        musicSpinner.setBackgroundColor(Color.parseColor("#1A1A1A"));
        root.addView(musicSpinner);

        root.addView(sectionLabel("OPENING TITLE (OVER STONE)"));
        titleInput = new EditText(this);
        titleInput.setText("kof26");
        titleInput.setTextColor(Color.WHITE);
        titleInput.setHintTextColor(Color.GRAY);
        titleInput.setTypeface(Typeface.MONOSPACE);
        titleInput.setBackgroundColor(Color.parseColor("#1A1A1A"));
        titleInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(titleInput);

        grayscaleCheck = new CheckBox(this);
        grayscaleCheck.setText("BT.601 Grayscale (gram signature monochrome)");
        grayscaleCheck.setTextColor(Color.WHITE);
        grayscaleCheck.setChecked(true);
        grayscaleCheck.setPadding(0, dp(8), 0, dp(8));
        root.addView(grayscaleCheck);

        renderBtn = button("RENDER SHORT VIDEO (.MP4)", "#005522", "#00FF66");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        rlp.bottomMargin = dp(10);
        renderBtn.setOnClickListener(v -> startRender());
        root.addView(renderBtn, rlp);

        statusText = label("Ready.", 13, "#00FF66");
        statusText.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusText);

        videoPreview = new VideoView(this);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(210));
        vlp.bottomMargin = dp(10);
        root.addView(videoPreview, vlp);

        shareAllBtn = button("SHARE VIDEO (SYSTEM SHEET)", "#1F1F1F", "#FFFFFF");
        shareAllBtn.setEnabled(false);
        shareAllBtn.setOnClickListener(v -> shareRenderedVideo(null));
        root.addView(shareAllBtn, rlp);

        LinearLayout socialRow = new LinearLayout(this);
        socialRow.setOrientation(LinearLayout.HORIZONTAL);

        shareWaBtn = button("WhatsApp / Channel", "#143320", "#44FF88");
        shareWaBtn.setEnabled(false);
        shareWaBtn.setOnClickListener(v -> shareRenderedVideo("com.whatsapp"));
        socialRow.addView(shareWaBtn, lp1);

        shareFbBtn = button("Facebook (Personal)", "#142238", "#66B2FF");
        shareFbBtn.setEnabled(false);
        shareFbBtn.setOnClickListener(v -> shareRenderedVideo("com.facebook.katana"));
        socialRow.addView(shareFbBtn, lp2);

        root.addView(socialRow);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void scanMusicPool() {
        musicFiles.clear();
        List<String> names = new ArrayList<>();
        names.add("Random Track from /sdcard/Music/gram");
        names.add("Mute (Silent Video)");

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
                        if (f.isFile() && (n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".mp4") || n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac"))) {
                            musicFiles.add(f);
                            names.add(f.getName());
                        }
                    }
                }
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
        musicSpinner.setAdapter(adapter);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        if (type != null && type.startsWith("image/")) {
            if (Intent.ACTION_SEND.equals(action)) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) {
                    selectedUris.clear();
                    selectedUris.add(uri);
                }
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null && !list.isEmpty()) {
                    selectedUris.clear();
                    selectedUris.addAll(list);
                }
            } else if (intent.getClipData() != null) {
                ClipData cd = intent.getClipData();
                selectedUris.clear();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) selectedUris.add(u);
                }
            }
        }
        if (selectedUris.isEmpty()) {
            loadLatestCameraPhotos(16);
        } else {
            updatePhotoCount("Received " + selectedUris.size() + " shared photo(s)");
        }
    }

    private void openGalleryPicker() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, "Select Photos for Graph"), REQ_PICK_PHOTOS);
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
            updatePhotoCount("Selected " + selectedUris.size() + " photo(s)");
        }
    }

    private void loadLatestCameraPhotos(int maxCount) {
        File camDir = new File(Environment.getExternalStorageDirectory(), "DCIM/Camera");
        if (!camDir.exists() || !camDir.isDirectory()) {
            updatePhotoCount("DCIM/Camera not accessible yet — tap Pick Photos");
            return;
        }
        File[] files = camDir.listFiles((dir, name) -> {
            String low = name.toLowerCase(Locale.ROOT);
            return !name.startsWith(".") && (low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png"));
        });
        if (files == null || files.length == 0) {
            updatePhotoCount("No photos in DCIM/Camera — tap Pick Photos");
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        selectedUris.clear();
        int lim = Math.min(maxCount, files.length);
        for (int i = 0; i < lim; i++) {
            selectedUris.add(Uri.fromFile(files[i]));
        }
        updatePhotoCount("Loaded latest " + selectedUris.size() + " photo(s) from DCIM/Camera");
    }

    private void updatePhotoCount(String msg) {
        photoCountText.setText("Photos: " + selectedUris.size() + "  (" + msg + ")");
    }

    private void startRender() {
        if (selectedUris.isEmpty()) {
            statusText.setText("Please pick at least 1 photo first.");
            return;
        }
        renderBtn.setEnabled(false);
        shareAllBtn.setEnabled(false);
        shareWaBtn.setEnabled(false);
        shareFbBtn.setEnabled(false);

        final int modeIdx = modeSpinner.getSelectedItemPosition();
        final int framesPerSlide = (modeIdx == 0) ? 5 : (modeIdx == 1 ? 11 : 25);
        final int durIdx = durationSpinner.getSelectedItemPosition();
        final int durationSec = (durIdx == 0) ? 8 : (durIdx == 1 ? 15 : (durIdx == 2 ? 30 : 60));
        final int totalFrames = durationSec * VideoRenderer.FPS;
        final boolean grayscale = grayscaleCheck.isChecked();
        final String title = titleInput.getText().toString();
        final List<Uri> urisCopy = new ArrayList<>(selectedUris);

        File chosenMusic = null;
        int mPos = musicSpinner.getSelectedItemPosition();
        if (mPos == 0 && !musicFiles.isEmpty()) {
            chosenMusic = musicFiles.get(new Random().nextInt(musicFiles.size()));
        } else if (mPos >= 2 && (mPos - 2) < musicFiles.size()) {
            chosenMusic = musicFiles.get(mPos - 2);
        }
        final File musicFile = chosenMusic;

        final int nSlides = Math.max(1, (totalFrames / framesPerSlide) + 2);
        final int[] order = computeSlideOrder(nSlides, urisCopy.size(), modeIdx == 0);

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
                        grayscale,
                        title,
                        (title != null && !title.trim().isEmpty()) ? 6 : 0,
                        stoneBitmap,
                        gomotorFont,
                        musicFile,
                        outFile,
                        (frame, total, st) -> runOnUiThread(() -> statusText.setText(st))
                );
                MediaScannerConnection.scanFile(MainActivity.this,
                        new String[]{outFile.getAbsolutePath()}, new String[]{"video/mp4"}, null);
                runOnUiThread(() -> onRenderSuccess(outFile));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Render error: " + e.getMessage());
                    renderBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private int[] computeSlideOrder(int nSlides, int poolSize, boolean useDadaOracle) {
        int[] order = new int[nSlides];
        if (useDadaOracle && stoneBytes != null) {
            try {
                long[] words = dadaBytes64(stoneBytes, nSlides, 0L);
                for (int i = 0; i < nSlides; i++) {
                    order[i] = (int) (Math.abs(words[i]) % poolSize);
                }
                return order;
            } catch (Throwable ignored) {
            }
        }
        Random rng = new Random(System.currentTimeMillis());
        for (int i = 0; i < nSlides; i++) {
            order[i] = rng.nextInt(Math.max(1, poolSize));
        }
        return order;
    }

    private void onRenderSuccess(File outFile) {
        lastRenderedMp4 = outFile;
        renderBtn.setEnabled(true);
        shareAllBtn.setEnabled(true);
        shareWaBtn.setEnabled(true);
        shareFbBtn.setEnabled(true);
        statusText.setText("Saved: " + outFile.getAbsolutePath() + " (" + (outFile.length() / 1024) + " KB)");
        try {
            videoPreview.setVideoPath(outFile.getAbsolutePath());
            videoPreview.setOnPreparedListener(mp -> mp.setLooping(true));
            videoPreview.start();
        } catch (Exception ignored) {
        }
    }

    private void shareRenderedVideo(String targetPackage) {
        if (lastRenderedMp4 == null || !lastRenderedMp4.exists()) return;
        Uri uri = GraphFileProvider.getUriForFile(lastRenderedMp4);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("video/mp4");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (targetPackage != null) {
            PackageManager pm = getPackageManager();
            Intent pkgIntent = new Intent(send);
            pkgIntent.setPackage(targetPackage);
            if (pkgIntent.resolveActivity(pm) != null) {
                startActivity(pkgIntent);
                return;
            } else if ("com.facebook.katana".equals(targetPackage)) {
                pkgIntent.setPackage("com.facebook.lite");
                if (pkgIntent.resolveActivity(pm) != null) {
                    startActivity(pkgIntent);
                    return;
                }
            }
        }
        startActivity(Intent.createChooser(send, "Share Graph Video"));
    }

    private TextView label(String text, int sp, String hexColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(Color.parseColor(hexColor));
        tv.setTypeface(Typeface.MONOSPACE);
        return tv;
    }

    private TextView sectionLabel(String text) {
        TextView tv = label(text, 12, "#00FF66");
        tv.setPadding(0, dp(12), 0, dp(4));
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
