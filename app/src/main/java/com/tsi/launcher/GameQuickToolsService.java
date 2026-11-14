package com.tsi.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.content.pm.ServiceInfo;

/** User-enabled floating shortcuts for the active Minecraft game session. */
public final class GameQuickToolsService extends Service {
    public static final String ACTION_START = "com.tsi.launcher.quickpanel.START";
    public static final String ACTION_STOP = "com.tsi.launcher.quickpanel.STOP";
    private static final String CHANNEL_ID = "tsi_game_quick_panel";
    private static final int NOTIFICATION_ID = 9320;
    private static volatile boolean running;

    private WindowManager windowManager;
    private LinearLayout panelRoot;
    private LinearLayout expandedPanel;
    private WindowManager.LayoutParams windowParams;
    private boolean expanded;

    public static boolean isRunning() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable Display over other apps for TSI Quick Panel.", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        if (panelRoot == null) showOverlay();
        running = true;
        return START_STICKY;
    }

    private void startAsForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 9321, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("TSI Game Quick Panel")
                .setContentText("Floating game shortcuts are enabled.")
                .setContentIntent(openPending)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void showOverlay() {
        panelRoot = new LinearLayout(this);
        panelRoot.setOrientation(LinearLayout.VERTICAL);
        panelRoot.setPadding(dp(6), dp(6), dp(6), dp(6));
        panelRoot.setBackgroundColor(0xEE102631);

        Button bubble = new Button(this);
        bubble.setText("TSI");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setAllCaps(false);
        bubble.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF087F9C));
        panelRoot.addView(bubble, new LinearLayout.LayoutParams(dp(58), dp(52)));
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startX;
            private int startY;
            private boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = windowParams.x;
                        startY = windowParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        if (moved) {
                            windowParams.x = Math.max(0, startX - (int) dx);
                            windowParams.y = Math.max(0, startY + (int) dy);
                            windowManager.updateViewLayout(panelRoot, windowParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) toggleExpanded();
                        return true;
                }
                return true;
            }
        });

        expandedPanel = new LinearLayout(this);
        expandedPanel.setOrientation(LinearLayout.VERTICAL);
        expandedPanel.setVisibility(View.GONE);
        panelRoot.addView(expandedPanel, new LinearLayout.LayoutParams(dp(188), -2));

        windowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.END;
        windowParams.x = dp(10);
        windowParams.y = dp(110);
        try {
            windowManager.addView(panelRoot, windowParams);
        } catch (Exception error) {
            panelRoot = null;
            stopSelf();
            Toast.makeText(this, "Could not create TSI game overlay: " + message(error), Toast.LENGTH_LONG).show();
        }
        addAction("GAME RECORDER", GameRecorderActivity.class);
        addAction("RECORDINGS GALLERY", GameRecordingsGalleryActivity.class);
        addAction("FILE MANAGER", StorageFileManagerActivity.class);
        addAction("CRASH ANALYZER", CrashAnalyzerActivity.class);
        addAction("VULKAN DRIVER PACKAGES", VulkanDriverManagerActivity.class);
        addEngineSettingsAction();
        Button hide = button("HIDE PANEL");
        hide.setOnClickListener(v -> toggleExpanded());
        expandedPanel.addView(hide);
        Button stop = button("DISABLE QUICK PANEL");
        stop.setOnClickListener(v -> stopSelf());
        expandedPanel.addView(stop);
    }

    private void addAction(String label, Class<?> activity) {
        Button action = button(label);
        action.setOnClickListener(v -> {
            Intent intent = new Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(intent); }
            catch (Exception error) {
                Toast.makeText(this, "Could not open " + label + ": " + message(error), Toast.LENGTH_LONG).show();
            }
        });
        expandedPanel.addView(action);
    }

    private void addEngineSettingsAction() {
        Button action = button("ENGINE SETTINGS");
        action.setOnClickListener(v -> {
            Intent intent = new Intent().setClassName(this, "net.kdt.pojavlaunch.LauncherActivity")
                    .putExtra("tsi_open_engine_settings", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(intent); }
            catch (Exception error) {
                Toast.makeText(this, "Amethyst engine settings are unavailable: " + message(error), Toast.LENGTH_LONG).show();
            }
        });
        expandedPanel.addView(action);
    }

    private void toggleExpanded() {
        expanded = !expanded;
        if (expandedPanel != null) expandedPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF244A58));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
        lp.topMargin = dp(3);
        lp.bottomMargin = dp(3);
        button.setLayoutParams(lp);
        return button;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "TSI Game Quick Panel", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Persistent notification for the user-enabled floating game controls.");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        running = false;
        if (panelRoot != null) {
            try { windowManager.removeView(panelRoot); } catch (Exception ignored) { }
            panelRoot = null;
        }
        expandedPanel = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String message(Throwable error) {
        Throwable x = error;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage();
    }
}
