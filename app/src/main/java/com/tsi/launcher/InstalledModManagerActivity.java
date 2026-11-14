package com.tsi.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.tsi.launcher.instances.GameInstance;
import com.tsi.launcher.instances.GameInstanceRepository;
import com.tsi.launcher.instances.InstalledModManager;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Instance-scoped local .jar mod manager. */
public final class InstalledModManagerActivity extends Activity {
    private static final int REQUEST_PICK_MOD = 7101;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private InstalledModManager manager;
    private LinearLayout listContainer;
    private TextView status;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        try {
            GameInstance instance = new GameInstanceRepository(getApplicationContext()).selected();
            if (instance == null) {
                setStatus("Select a game instance before managing mods.");
                return;
            }
            manager = new InstalledModManager(instance.getGameDirectory());
            setStatus("Managing mods for " + instance.getName() + " • Minecraft " + instance.getVersionId());
            refresh();
        } catch (Exception error) {
            setStatus("Could not open the instance mods folder: " + message(error));
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF16191A);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(22));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("INSTALLED JAVA MODS", 21, 0xFFF0F3F0, true), matchWrap());
        root.addView(text("Import local JARs, enable or disable a mod, and remove it from the selected instance.",
                11, 0xFFAAB5AF, false), matchWrap());

        LinearLayout actions = new LinearLayout(this);
        Button add = button("IMPORT .JAR");
        add.setOnClickListener(v -> chooseMod());
        actions.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button refresh = button("REFRESH");
        refresh.setOnClickListener(v -> refresh());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(actions, matchWrap());

        status = text("Loading mods…", 12, 0xFF9FC18E, false);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status, matchWrap());

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, matchWrap());

        Button back = button("BACK");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());
    }

    private void chooseMod() {
        if (manager == null || busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/java-archive", "application/octet-stream", "application/zip"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_MOD);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_MOD || resultCode != RESULT_OK ||
                data == null || data.getData() == null) return;
        importMod(data.getData());
    }

    private void importMod(Uri uri) {
        if (busy || manager == null) return;
        busy = true;
        setStatus("Importing and validating mod JAR…");
        io.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                String name = queryDisplayName(uri);
                InstalledModManager.ModFile imported = manager.importJar(name, input);
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Installed " + imported.getName() + ".");
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Mod import failed: " + message(error));
                });
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private void refresh() {
        if (manager == null || busy) return;
        busy = true;
        setStatus("Reading installed mods…");
        io.execute(() -> {
            try {
                List<InstalledModManager.ModFile> mods = manager.listMods();
                runOnUiThread(() -> {
                    busy = false;
                    render(mods);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Could not read mods: " + message(error));
                });
            }
        });
    }

    private void render(List<InstalledModManager.ModFile> mods) {
        listContainer.removeAllViews();
        if (mods.isEmpty()) {
            setStatus("No .jar mods found in this instance.");
            listContainer.addView(text("Use IMPORT .JAR to add a mod. Mod loader compatibility still depends on the Minecraft and loader versions.",
                    12, 0xFFAAB5AF, false), matchWrap());
            return;
        }
        int enabled = 0;
        for (InstalledModManager.ModFile mod : mods) if (mod.isEnabled()) enabled++;
        setStatus(mods.size() + " mod file(s) • " + enabled + " enabled • " +
                (mods.size() - enabled) + " disabled");
        for (InstalledModManager.ModFile mod : mods) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(11), dp(11), dp(11), dp(11));
            card.setBackgroundColor(0xFF292E2F);
            LinearLayout.LayoutParams params = matchWrap();
            params.bottomMargin = dp(8);
            listContainer.addView(card, params);

            card.addView(text(mod.getName(), 14, 0xFFF0F3F0, true), matchWrap());
            card.addView(text((mod.isEnabled() ? "ENABLED" : "DISABLED") + " • " +
                            android.text.format.Formatter.formatFileSize(this, mod.getSizeBytes()),
                    11, mod.isEnabled() ? 0xFF9FC18E : 0xFFE1B36A, false), matchWrap());

            LinearLayout row = new LinearLayout(this);
            Button toggle = button(mod.isEnabled() ? "DISABLE" : "ENABLE");
            toggle.setOnClickListener(v -> toggleMod(mod));
            row.addView(toggle, new LinearLayout.LayoutParams(0, dp(44), 1f));
            Button delete = button("DELETE");
            delete.setOnClickListener(v -> confirmDelete(mod));
            row.addView(delete, new LinearLayout.LayoutParams(0, dp(44), 1f));
            card.addView(row, matchWrap());
        }
    }

    private void toggleMod(InstalledModManager.ModFile mod) {
        if (busy || manager == null) return;
        busy = true;
        setStatus("Updating " + mod.getName() + "…");
        io.execute(() -> {
            try {
                InstalledModManager.ModFile result = manager.setEnabled(mod.getFile(), !mod.isEnabled());
                runOnUiThread(() -> {
                    busy = false;
                    setStatus(result.getName() + " is now " + (result.isEnabled() ? "enabled." : "disabled."));
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Could not update mod: " + message(error));
                });
            }
        });
    }

    private void confirmDelete(InstalledModManager.ModFile mod) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + mod.getName() + "?")
                .setMessage("This removes the mod file from the selected instance. It does not delete the mod from your downloads.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (dialog, which) -> deleteMod(mod))
                .show();
    }

    private void deleteMod(InstalledModManager.ModFile mod) {
        if (busy || manager == null) return;
        busy = true;
        setStatus("Deleting " + mod.getName() + "…");
        io.execute(() -> {
            try {
                manager.delete(mod.getFile());
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Deleted " + mod.getName() + ".");
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Could not delete mod: " + message(error));
                });
            }
        });
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String value = current.getMessage();
        return value == null || value.trim().isEmpty()
                ? current.getClass().getSimpleName() : value.trim();
    }
}
