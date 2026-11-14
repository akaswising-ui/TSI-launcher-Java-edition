package com.tsi.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/** Gallery for screen recordings created by TSI Game Recorder. */
public final class GameRecordingsGalleryActivity extends Activity {
    private LinearLayout list;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFEAF4F7);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(22));
        scroll.addView(root);
        setContentView(scroll);
        root.addView(text("TSI RECORDINGS", 22, 0xFF183B4A, true), matchWrap());
        status = text("Loading recordings…", 12, 0xFF536D78, false);
        root.addView(status, matchWrap());
        Button refresh = button("REFRESH");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh, matchWrap());
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, matchWrap());
        Button back = button("BACK");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());
    }

    private void refresh() {
        list.removeAllViews();
        File folder = recordingsDirectory();
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".mp4"));
        if (files == null) files = new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        status.setText(files.length + " MP4 recording(s) • " + folder.getAbsolutePath());
        for (File file : files) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.setBackgroundColor(0xFFFFFFFF);
            LinearLayout.LayoutParams lp = matchWrap();
            lp.bottomMargin = dp(8);
            list.addView(card, lp);
            card.addView(text(file.getName(), 14, 0xFF183B4A, true), matchWrap());
            card.addView(text(android.text.format.Formatter.formatFileSize(this, file.length())
                    + " • " + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", file.lastModified()),
                    11, 0xFF536D78, false), matchWrap());
            LinearLayout row = new LinearLayout(this);
            Button play = button("OPEN");
            play.setOnClickListener(v -> open(file));
            row.addView(play, new LinearLayout.LayoutParams(0, dp(44), 1f));
            Button share = button("SHARE");
            share.setOnClickListener(v -> share(file));
            row.addView(share, new LinearLayout.LayoutParams(0, dp(44), 1f));
            Button delete = button("DELETE");
            delete.setOnClickListener(v -> confirmDelete(file));
            row.addView(delete, new LinearLayout.LayoutParams(0, dp(44), 1f));
            card.addView(row, matchWrap());
        }
    }

    private void open(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, "com.tsi.launcher.files", file);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception error) {
            status.setText("No video player could open this recording: " + message(error));
        }
    }

    private void share(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, "com.tsi.launcher.files", file);
            Intent send = new Intent(Intent.ACTION_SEND).setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share game recording"));
        } catch (Exception error) {
            status.setText("Could not share recording: " + message(error));
        }
    }

    private void confirmDelete(File file) {
        new AlertDialog.Builder(this).setTitle("Delete recording?")
                .setMessage(file.getName() + " will be permanently removed.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (dialog, which) -> {
                    if (file.delete()) refresh();
                    else status.setText("Could not delete " + file.getName());
                }).show();
    }

    private File recordingsDirectory() {
        File movies = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        File folder = new File(movies == null ? getFilesDir() : movies, "TSI Recordings");
        if (!folder.isDirectory()) folder.mkdirs();
        return folder;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }
    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String message(Throwable error) {
        Throwable x = error;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage();
    }
}
