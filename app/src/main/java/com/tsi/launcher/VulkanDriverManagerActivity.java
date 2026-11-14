package com.tsi.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.tsi.launcher.vulkan.AdrenoDriverPackageManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Safe AdrenoTools-style Vulkan driver package store for the embedded Amethyst loader. */
public final class VulkanDriverManagerActivity extends Activity {
    private static final int REQUEST_DRIVER_ZIP = 9410;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AdrenoDriverPackageManager manager;
    private LinearLayout list;
    private TextView status;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        manager = new AdrenoDriverPackageManager(getApplicationContext());
        buildUi();
        refresh();
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
        root.addView(text("VULKAN / ADRENO DRIVER PACKAGES", 21, 0xFF183B4A, true), matchWrap());
        root.addView(text("Imports AdrenoTools-style ZIP packages into TSI private storage. Custom Turnip loading is enabled only on Adreno + Vulkan devices. Hyper's qgl_config.txt override is not available in the pinned Amethyst engine.",
                12, 0xFF536D78, false), matchWrap());
        Button importButton = button("IMPORT DRIVER ZIP");
        importButton.setOnClickListener(v -> chooseImport());
        root.addView(importButton, matchWrap());
        Button refresh = button("REFRESH");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh, matchWrap());
        status = text("Loading driver packages…", 12, 0xFF536D78, false);
        root.addView(status, matchWrap());
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, matchWrap());
        Button back = button("BACK");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());
    }

    private void chooseImport() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_DRIVER_ZIP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DRIVER_ZIP || resultCode != RESULT_OK ||
                data == null || data.getData() == null) return;
        importZip(data.getData());
    }

    private void importZip(Uri uri) {
        if (busy) return;
        busy = true;
        status.setText("Validating Adreno driver package…");
        io.execute(() -> {
            File temporary = new File(getCacheDir(), "tsi-driver-" + System.currentTimeMillis() + ".zip");
            try {
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    if (input == null) throw new java.io.IOException("Android could not open the selected ZIP.");
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0L;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > 512L * 1024L * 1024L) {
                            throw new java.io.IOException("Driver ZIP is larger than the 512 MB import limit.");
                        }
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                AdrenoDriverPackageManager.DriverPackage installed = manager.install(temporary, false);
                runOnUiThread(() -> {
                    busy = false;
                    status.setText("Installed " + installed.name + " " + installed.version + ".");
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    status.setText("Driver import failed: " + message(error));
                });
            } finally {
                temporary.delete();
            }
        });
    }

    private void refresh() {
        if (busy) return;
        io.execute(() -> {
            try {
                List<AdrenoDriverPackageManager.DriverPackage> packages = manager.list();
                AdrenoDriverPackageManager.DriverPackage selected = manager.getSelected();
                runOnUiThread(() -> render(packages, selected == null ? null : selected.hash));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Could not read drivers: " + message(error)));
            }
        });
    }

    private void render(List<AdrenoDriverPackageManager.DriverPackage> packages, String selectedHash) {
        list.removeAllViews();
        status.setText(packages.size() + " package(s) installed. " +
                (selectedHash == null ? "Built-in driver selected." : "A custom package is selected."));
        Button builtIn = button("USE BUILT-IN / SYSTEM TURNIP");
        builtIn.setOnClickListener(v -> runManagerAction("Selecting built-in driver…", () -> {
            manager.select(null);
            return "Built-in / system driver selected.";
        }));
        list.addView(builtIn, matchWrap());
        for (AdrenoDriverPackageManager.DriverPackage pkg : packages) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.setBackgroundColor(0xFFFFFFFF);
            LinearLayout.LayoutParams lp = matchWrap();
            lp.bottomMargin = dp(8);
            list.addView(card, lp);
            card.addView(text(pkg.name + " • " + pkg.version, 15, 0xFF183B4A, true), matchWrap());
            card.addView(text("Author: " + pkg.author + "\nLibrary: " + pkg.libraryName +
                    "\nMinimum Android API: " + pkg.minimumAndroidApi +
                    (pkg.containsQglConfig ? "\nqgl_config.txt present (not applied by Amethyst)" : ""),
                    11, 0xFF536D78, false), matchWrap());
            LinearLayout row = new LinearLayout(this);
            Button select = button(pkg.hash.equals(selectedHash) ? "SELECTED" : "SELECT");
            select.setEnabled(!pkg.hash.equals(selectedHash));
            select.setOnClickListener(v -> runManagerAction("Selecting driver…", () -> {
                manager.select(pkg.hash);
                return "Selected " + pkg.name + ". Custom Turnip loads only on a supported Adreno/Vulkan device.";
            }));
            row.addView(select, new LinearLayout.LayoutParams(0, dp(44), 1f));
            Button remove = button("REMOVE");
            remove.setOnClickListener(v -> confirmRemove(pkg));
            row.addView(remove, new LinearLayout.LayoutParams(0, dp(44), 1f));
            card.addView(row, matchWrap());
        }
    }

    private void confirmRemove(AdrenoDriverPackageManager.DriverPackage pkg) {
        new AlertDialog.Builder(this).setTitle("Remove driver package?")
                .setMessage("Remove " + pkg.name + " from TSI's private storage?")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("REMOVE", (dialog, which) -> runManagerAction("Removing driver…", () -> {
                    manager.remove(pkg.hash);
                    return "Removed " + pkg.name + ".";
                })).show();
    }

    private void runManagerAction(String pending, Operation operation) {
        if (busy) return;
        busy = true;
        status.setText(pending);
        io.execute(() -> {
            try {
                String result = operation.run();
                runOnUiThread(() -> {
                    busy = false;
                    status.setText(result);
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    status.setText("Driver operation failed: " + message(error));
                });
            }
        });
    }

    private interface Operation { String run() throws Exception; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
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
