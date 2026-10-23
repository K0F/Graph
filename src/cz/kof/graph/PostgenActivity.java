package cz.kof.graph;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class PostgenActivity extends Activity {
    private TextView statusText;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0D0D0D"));
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("POSTGEN");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#00FF66"));
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(16);
        root.addView(title, tlp);

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(false);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.bottomMargin = dp(16);
        root.addView(progressBar, plp);

        statusText = new TextView(this);
        statusText.setText("Preparing queue...");
        statusText.setTextSize(14);
        statusText.setTextColor(Color.parseColor("#FFFFFF"));
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText);

        setContentView(root);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }

        String action = intent.getAction();
        String type = intent.getType();
        final List<Uri> uris = new ArrayList<>();

        if (type != null && type.startsWith("image/")) {
            if (Intent.ACTION_SEND.equals(action)) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) uris.add(uri);
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) uris.addAll(list);
            }
        }

        if (uris.isEmpty() && intent.getClipData() != null) {
            ClipData cd = intent.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No images received for Postgen", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        progressBar.setMax(uris.size());
        progressBar.setProgress(0);

        new Thread(() -> {
            int posted = 0;
            int skippedDocs = 0;
            int total = uris.size();

            for (int i = 0; i < total; i++) {
                final int idx = i;
                Uri u = uris.get(i);
                boolean isDoc = Postgen.isDocumentPhoto(PostgenActivity.this, u);
                if (isDoc) {
                    skippedDocs++;
                    runOnUiThread(() -> {
                        progressBar.setProgress(idx + 1);
                        statusText.setText("Skipping document reproduction " + (idx + 1) + "/" + total);
                    });
                    continue;
                }

                boolean ok = Postgen.processImageForPostgen(PostgenActivity.this, u, (cur, tot, fn) -> {
                    runOnUiThread(() -> {
                        progressBar.setProgress(cur);
                        statusText.setText("Postgen (" + cur + "/" + tot + "): " + fn);
                    });
                }, idx + 1, total);

                if (ok) posted++;
            }

            final int finalPosted = posted;
            final int finalSkipped = skippedDocs;
            runOnUiThread(() -> {
                String msg = "Postgen: " + finalPosted + " posts generated in DCIM/postgen"
                        + (finalSkipped > 0 ? " (" + finalSkipped + " doc scans skipped)" : "");
                Toast.makeText(PostgenActivity.this, msg, Toast.LENGTH_LONG).show();
                finish();
            });
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
