package com.tsi.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.tsi.launcher.instances.GameInstance;
import com.tsi.launcher.instances.GameInstanceRepository;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Java Views instance manager with safe MultiMC/Prism ZIP transfer. */
public final class GameInstanceManagerActivity extends Activity {
    private static final int REQUEST_IMPORT_ZIP = 5101;
    private static final int REQUEST_EXPORT_ZIP = 5102;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private GameInstanceRepository repository;
    private LinearLayout listContainer;
    private TextView status;
    private String selectedVersionId;
    private String pendingExportId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = new GameInstanceRepository(getApplicationContext());
        selectedVersionId = getIntent().getStringExtra("version_id");
        if (selectedVersionId == null || selectedVersionId.trim().isEmpty()) {
            selectedVersionId = "1.20.1";
        }
        buildUi();
        refresh();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFEAF4F7);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("GAME INSTANCES", 22, 0xFF183B4A, true);
        root.addView(title, matchWrap());
        root.addView(text("Separate saves, mods, configs and settings for each profile.", 12,
                0xFF71838B, false), matchWrap());

        LinearLayout actions = new LinearLayout(this);
        Button create = button("NEW INSTANCE");
        create.setOnClickListener(v -> showCreateDialog());
        Button importZip = button("IMPORT ZIP");
        importZip.setOnClickListener(v -> chooseImport());
        actions.addView(create, new LinearLayout.LayoutParams(0, dp(48), 1f));
        actions.addView(importZip, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(actions, matchWrap());

        status = text("Loading instances…", 12, 0xFF087F9C, false);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status, matchWrap());
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, matchWrap());

        Button done = button("DONE");
        done.setOnClickListener(v -> finish());
        root.addView(done, matchWrap());
    }

    private void refresh() {
        status.setText("Reading instance list…");
        io.execute(() -> {
            try {
                List<GameInstance> instances = repository.list();
                runOnUiThread(() -> render(instances));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Could not load instances: " + message(error)));
            }
        });
    }

    private void render(List<GameInstance> instances) {
        listContainer.removeAllViews();
        if (instances.isEmpty()) {
            status.setText("No game instances yet. Create one or import a MultiMC / Prism ZIP.");
            return;
        }
        GameInstance selected = null;
        try { selected = repository.selected(); } catch (Exception ignored) { }
        final String selectedId = selected == null ? "" : selected.getId();
        status.setText(instances.size() + " instance(s) • selected profile is highlighted");
        for (GameInstance instance : instances) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(instance.getId().equals(selectedId) ? 0xFFD9EEF2 : 0xFFFFFFFF);
            LinearLayout.LayoutParams cardParams = matchWrap();
            cardParams.bottomMargin = dp(10);
            listContainer.addView(card, cardParams);

            String type = instance.getLoaderId() == null ? "" :
                    " • " + instance.getLoaderId().substring(instance.getLoaderId().lastIndexOf('.') + 1);
            card.addView(text(instance.getName() + (instance.isPinned() ? "  ★" : ""),
                    16, 0xFF183B4A, true), matchWrap());
            card.addView(text("Minecraft " + instance.getVersionId() + type,
                    11, 0xFF71838B, false), matchWrap());
            card.addView(text(instance.getGameDirectory().getAbsolutePath(),
                    10, 0xFF71838B, false), matchWrap());

            LinearLayout row = new LinearLayout(this);
            Button select = button(instance.getId().equals(selectedId) ? "SELECTED" : "SELECT");
            select.setEnabled(!instance.getId().equals(selectedId));
            select.setOnClickListener(v -> runOperation("Selecting instance…", () -> {
                repository.select(instance.getId());
                return "Selected " + instance.getName() + ".";
            }));
            Button pin = button(instance.isPinned() ? "UNPIN" : "PIN");
            pin.setOnClickListener(v -> runOperation("Updating pin…", () -> {
                repository.setPinned(instance.getId(), !instance.isPinned());
                return "Updated " + instance.getName() + ".";
            }));
            Button rename = button("RENAME");
            rename.setOnClickListener(v -> showRenameDialog(instance));
            row.addView(select, new LinearLayout.LayoutParams(0, dp(44), 1f));
            row.addView(pin, new LinearLayout.LayoutParams(0, dp(44), 1f));
            row.addView(rename, new LinearLayout.LayoutParams(0, dp(44), 1f));
            card.addView(row, matchWrap());

            LinearLayout row2 = new LinearLayout(this);
            Button export = button("EXPORT ZIP");
            export.setOnClickListener(v -> chooseExport(instance.getId()));
            Button delete = button("DELETE");
            delete.setOnClickListener(v -> confirmDelete(instance));
            row2.addView(export, new LinearLayout.LayoutParams(0, dp(44), 1f));
            row2.addView(delete, new LinearLayout.LayoutParams(0, dp(44), 1f));
            card.addView(row2, matchWrap());
        }
    }

    private void showCreateDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setPadding(dp(8), dp(4), dp(8), dp(4));
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Instance name");
        fields.addView(name, matchWrap());
        EditText version = new EditText(this);
        version.setSingleLine(true);
        version.setHint("Minecraft version");
        version.setText(selectedVersionId);
        fields.addView(version, matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("Create game instance")
                .setView(fields)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CREATE", (dialog, which) -> {
                    String n = name.getText().toString();
                    String v = version.getText().toString().trim();
                    runOperation("Creating instance…", () -> {
                        GameInstance created = repository.create(n, v, false, false);
                        return "Created " + created.getName() + ".";
                    });
                }).show();
    }

    private void showRenameDialog(GameInstance instance) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(instance.getName());
        new AlertDialog.Builder(this)
                .setTitle("Rename instance")
                .setView(name)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", (dialog, which) ->
                        runOperation("Renaming instance…", () -> {
                            repository.rename(instance.getId(), name.getText().toString());
                            return "Instance renamed.";
                        })).show();
    }

    private void confirmDelete(GameInstance instance) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + instance.getName() + "?")
                .setMessage("This deletes the instance's own folder. Shared versions, libraries and assets remain.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (dialog, which) ->
                        runOperation("Deleting instance…", () -> {
                            repository.delete(instance.getId());
                            return "Instance deleted.";
                        })).show();
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-7z-compressed", "application/x-7z"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMPORT_ZIP);
    }

    private void chooseExport(String instanceId) {
        pendingExportId = instanceId;
        GameInstance instance = null;
        try { for (GameInstance item : repository.list()) if (instanceId.equals(item.getId())) instance = item; }
        catch (Exception ignored) { }
        String name = instance == null ? "tsi-instance" : instance.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, name + ".zip");
        startActivityForResult(intent, REQUEST_EXPORT_ZIP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT_ZIP) importArchive(uri);
        else if (requestCode == REQUEST_EXPORT_ZIP && pendingExportId != null) {
            String id = pendingExportId;
            pendingExportId = null;
            exportArchive(uri, id);
        }
    }

    private void importArchive(Uri uri) {
        runOperation("Importing ZIP / 7z / MultiMC / Prism / Hyper archive…", () -> {
            File temporary = new File(getCacheDir(), "tsi-instance-" + System.currentTimeMillis() + ".archive");
            try {
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    if (input == null) throw new IOException("Android could not open the selected archive.");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.getFD().sync();
                }
                GameInstance imported = repository.importArchive(temporary);
                return "Imported and selected " + imported.getName() + ".";
            } finally {
                temporary.delete();
            }
        });
    }

    private void exportArchive(Uri uri, String id) {
        runOperation("Exporting instance…", () -> {
            File temporary = new File(getCacheDir(), "tsi-instance-export-" + System.currentTimeMillis() + ".zip");
            try {
                repository.exportMultiMc(id, temporary);
                try (InputStream input = new java.io.FileInputStream(temporary);
                     OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IOException("Android could not create the destination file.");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.flush();
                }
                return "Instance exported as a MultiMC-compatible ZIP.";
            } finally {
                temporary.delete();
            }
        });
    }

    private interface Operation { String run() throws Exception; }

    private void runOperation(String message, Operation operation) {
        status.setText(message);
        io.execute(() -> {
            try {
                String result = operation.run();
                runOnUiThread(() -> {
                    status.setText(result);
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText(message(error)));
            }
        });
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, 1);
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
    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
