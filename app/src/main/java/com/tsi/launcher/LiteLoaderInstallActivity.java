package com.tsi.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.tsi.launcher.instances.GameInstance;
import com.tsi.launcher.instances.GameInstanceRepository;
import com.tsi.launcher.modloaders.LiteLoaderInstaller;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Experimental LiteLoader installer for supported legacy Maven-format profiles. */
public final class LiteLoaderInstallActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button install;
    private GameInstance selected;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { selected = new GameInstanceRepository(getApplicationContext()).selected(); }
        catch (Exception ignored) { selected = null; }
        buildUi();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
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

        root.addView(text("LITELOADER (EXPERIMENTAL)", 21, 0xFF183B4A, true), matchWrap());
        root.addView(text("Legacy Java mod loader for Minecraft 1.8.9–1.12.2 only. This installer uses the official LiteLoader manifest and only supports HTTPS Maven (m2) entries. Older Ivy-only entries are rejected.",
                12, 0xFF536D78, false), matchWrap());
        root.addView(text("Selected instance: " + (selected == null ? "none" : selected.getName())
                + "\nMinecraft: " + (selected == null ? "none" : selected.getVersionId()),
                13, 0xFF183B4A, true), matchWrap());

        install = button("INSTALL LITELOADER ON SELECTED INSTANCE");
        install.setEnabled(selected != null);
        install.setOnClickListener(v -> install());
        root.addView(install, matchWrap());

        status = text("Install the vanilla Minecraft version first. Worlds and other instance data will remain in the same game folder.",
                12, 0xFF536D78, false);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status, matchWrap());

        Button back = button("BACK");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());
    }

    private void install() {
        if (selected == null || !install.isEnabled()) return;
        install.setEnabled(false);
        status.setText("Preparing LiteLoader…");
        io.execute(() -> {
            try {
                LiteLoaderInstaller installer = new LiteLoaderInstaller(getApplicationContext());
                String installedId = installer.installForSelectedInstance(
                        message -> runOnUiThread(() -> status.setText(message)));
                runOnUiThread(() -> {
                    status.setText("Installed " + installedId + ". Open the VM Controller to verify the profile before launching.");
                    install.setEnabled(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    status.setText("LiteLoader install failed: " + message(error));
                    install.setEnabled(true);
                });
            }
        });
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }
    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String message(Throwable error) {
        Throwable x = error;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage();
    }
}
