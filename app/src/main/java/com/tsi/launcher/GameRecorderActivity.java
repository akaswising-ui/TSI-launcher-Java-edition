package com.tsi.launcher;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.tsi.launcher.recording.GameRecordingService;

/** Activity that requests Android's explicit screen-capture consent before recording. */
public final class GameRecorderActivity extends Activity {
    private static final int REQUEST_CAPTURE = 8310;
    private TextView status;
    private Button recordButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        refreshState();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFEAF4F7);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(22));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("TSI GAME RECORDER", 22, 0xFF183B4A, true);
        root.addView(title, matchWrap());
        root.addView(text("Records the visible screen to MP4. Android will show a screen-capture permission prompt for every new recording.",
                13, 0xFF536D78, false), matchWrap());
        root.addView(text("Video only • H.264 • 30 FPS maximum • 720p maximum. Internal game audio is not captured by this build.",
                12, 0xFF536D78, false), matchWrap());

        status = text("Recorder is stopped.", 13, 0xFF183B4A, true);
        status.setPadding(0, dp(14), 0, dp(14));
        root.addView(status, matchWrap());

        recordButton = button("START SCREEN RECORDING");
        recordButton.setOnClickListener(v -> toggleRecording());
        root.addView(recordButton, matchWrap());

        Button gallery = button("OPEN RECORDINGS GALLERY");
        gallery.setOnClickListener(v -> startActivity(new Intent(this, GameRecordingsGalleryActivity.class)));
        root.addView(gallery, matchWrap());

        Button back = button("BACK");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());
    }

    private void toggleRecording() {
        if (GameRecordingService.isRecording()) {
            Intent stop = new Intent(this, GameRecordingService.class).setAction(GameRecordingService.ACTION_STOP);
            startService(stop);
            status.setText("Stopping recording…");
            recordButton.setEnabled(false);
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen recording permission was not granted.", Toast.LENGTH_SHORT).show();
            refreshState();
            return;
        }
        Intent start = new Intent(this, GameRecordingService.class)
                .setAction(GameRecordingService.ACTION_START)
                .putExtra(GameRecordingService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(GameRecordingService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(start);
        else startService(start);
        status.setText("Starting recorder…");
        recordButton.setEnabled(false);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void refreshState() {
        boolean active = GameRecordingService.isRecording();
        if (status != null) status.setText(active ? "Recording is active. Stop it here or from the persistent notification."
                : "Recorder is stopped.");
        if (recordButton != null) {
            recordButton.setEnabled(true);
            recordButton.setText(active ? "STOP RECORDING" : "START SCREEN RECORDING");
        }
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
