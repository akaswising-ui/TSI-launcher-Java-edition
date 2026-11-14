package com.tsi.launcher.recording;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.content.pm.ServiceInfo;
import android.widget.Toast;
import android.net.Uri;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * User-consented screen recording service. Captures the visible game surface
 * through Android MediaProjection and writes an H.264 MP4 to app-specific Movies.
 * Audio capture is intentionally omitted: this avoids silently recording the
 * microphone or claiming internal game-audio capture that Android does not grant.
 */
public final class GameRecordingService extends Service {
    public static final String ACTION_START = "com.tsi.launcher.recording.START";
    public static final String ACTION_STOP = "com.tsi.launcher.recording.STOP";
    public static final String ACTION_STATE = "com.tsi.launcher.recording.STATE";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    private static final String CHANNEL_ID = "tsi_game_recording";
    private static final int NOTIFICATION_ID = 8310;

    private static volatile boolean recording;
    private final HandlerThread workerThread = new HandlerThread("TSI-Recorder");
    private Handler worker;
    private MediaProjection projection;
    private MediaRecorder recorder;
    private VirtualDisplay virtualDisplay;
    private File outputFile;
    private boolean stopping;

    public static boolean isRecording() { return recording; }

    @Override public void onCreate() {
        super.onCreate();
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            worker.post(() -> finishRecording(true));
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startAsForeground();
        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        final Intent resultData = getResultData(intent);
        if (resultData == null) {
            finishRecording(true);
            Toast.makeText(this, "Screen capture permission data is missing.", Toast.LENGTH_LONG).show();
            return START_NOT_STICKY;
        }
        worker.post(() -> startRecording(resultCode, resultData));
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getResultData(Intent source) {
        if (Build.VERSION.SDK_INT >= 33) {
            return source.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        return source.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private void startAsForeground() {
        Notification notification = buildNotification("Preparing screen recording");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startRecording(int resultCode, Intent resultData) {
        if (recording || stopping) return;
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("Android did not grant a screen-capture session.");

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            if (width <= 0 || height <= 0) throw new IllegalStateException("Display dimensions are unavailable.");

            float scale = Math.min(1.0f, 1280.0f / Math.max(width, height));
            width = Math.max(2, ((int) (width * scale)) & ~1);
            height = Math.max(2, ((int) (height * scale)) & ~1);

            File movies = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (movies == null) throw new IllegalStateException("App Movies storage is unavailable.");
            File folder = new File(movies, "TSI Recordings");
            if (!folder.isDirectory() && !folder.mkdirs()) {
                throw new IllegalStateException("Cannot create the recordings folder.");
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            outputFile = new File(folder, "TSI_Game_" + stamp + ".mp4");

            recorder = Build.VERSION.SDK_INT >= 31
                    ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(outputFile.getAbsolutePath());
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoEncodingBitRate(5_000_000);
            recorder.setVideoFrameRate(30);
            recorder.setVideoSize(width, height);
            recorder.prepare();

            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    worker.post(() -> finishRecording(false));
                }
            }, worker);

            virtualDisplay = projection.createVirtualDisplay(
                    "TSI-Game-Recording",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(),
                    null,
                    worker);
            recorder.start();
            recording = true;
            stopping = false;
            updateNotification("Recording game screen • 30 FPS max • 720p max");
            broadcastState(true, outputFile);
        } catch (Throwable error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            Toast.makeText(this, "Recording could not start: " + message, Toast.LENGTH_LONG).show();
            finishRecording(true);
        }
    }

    private void finishRecording(boolean stopProjection) {
        if (stopping) return;
        stopping = true;
        recording = false;
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) { }
            virtualDisplay = null;
        }
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
            try { recorder.reset(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
        if (projection != null) {
            MediaProjection old = projection;
            projection = null;
            try {
                if (stopProjection) old.stop();
            } catch (Exception ignored) { }
        }

        File completed = outputFile;
        outputFile = null;
        boolean saved = completed != null && completed.isFile() && completed.length() > 1024;
        broadcastState(false, saved ? completed : null);
        updateNotification(saved ? "Recording saved: " + completed.getName() : "Recording stopped");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();

        if (!saved && completed != null && completed.exists()) completed.delete();
        stopping = false;
    }

    private void broadcastState(boolean active, File file) {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra("recording", active);
        if (file != null) state.putExtra("path", file.getAbsolutePath());
        sendBroadcast(state);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, com.tsi.launcher.GameRecorderActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 8311, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, GameRecordingService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 8312, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("TSI Game Recorder")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(recording || !stopping)
                .addAction(android.R.drawable.ic_media_pause, "STOP", stopPending)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "TSI Game Recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows the active TSI game screen recording session.");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        if (recording || recorder != null || projection != null) {
            if (worker != null) worker.post(() -> finishRecording(true));
        }
        workerThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
