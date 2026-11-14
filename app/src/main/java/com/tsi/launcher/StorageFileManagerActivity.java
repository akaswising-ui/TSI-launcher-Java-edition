package com.tsi.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.documentfile.provider.DocumentFile;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * SAF-based file manager. The user explicitly chooses a folder; all operations
 * remain below that granted tree URI and do not request unrestricted file access.
 */
public final class StorageFileManagerActivity extends Activity {
    private static final int REQUEST_TREE = 9201;
    private static final long MAX_COPY_BYTES = 1024L * 1024L * 1024L;
    private static final String PREFS = "tsi_file_manager";
    private static final String ROOT_URI = "root_tree_uri";

    private LinearLayout list;
    private TextView status;
    private TextView pathLabel;
    private DocumentFile root;
    private DocumentFile current;
    private final Deque<DocumentFile> parents = new ArrayDeque<>();
    private Uri pendingCopy;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        loadSavedRoot();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFEAF4F7);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(14), dp(14), dp(22));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("TSI FILE MANAGER", 22, 0xFF183B4A, true), matchWrap());
        page.addView(text("Browse only the folder you grant through Android's document picker. No unrestricted storage permission is needed.",
                12, 0xFF536D78, false), matchWrap());

        LinearLayout toolbar = new LinearLayout(this);
        Button choose = button("CHOOSE FOLDER");
        choose.setOnClickListener(v -> chooseRoot());
        toolbar.addView(choose, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button up = button("UP");
        up.setOnClickListener(v -> goParent());
        toolbar.addView(up, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button refresh = button("REFRESH");
        refresh.setOnClickListener(v -> refreshDirectory());
        toolbar.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1f));
        page.addView(toolbar, matchWrap());

        pathLabel = text("No folder selected", 11, 0xFF087F9C, false);
        pathLabel.setPadding(0, dp(8), 0, dp(4));
        page.addView(pathLabel, matchWrap());
        status = text("Choose a folder to start.", 12, 0xFF536D78, false);
        page.addView(status, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        Button newFolder = button("NEW FOLDER");
        newFolder.setOnClickListener(v -> createFolder());
        actions.addView(newFolder, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button paste = button("PASTE FILE");
        paste.setOnClickListener(v -> pasteFile());
        actions.addView(paste, new LinearLayout.LayoutParams(0, dp(44), 1f));
        page.addView(actions, matchWrap());

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, matchWrap());

        Button back = button("BACK");
        back.setOnClickListener(v -> finish());
        page.addView(back, matchWrap());
    }

    private void loadSavedRoot() {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE).getString(ROOT_URI, null);
        if (value == null) return;
        try {
            Uri uri = Uri.parse(value);
            root = DocumentFile.fromTreeUri(this, uri);
            if (root == null || !root.canRead()) {
                root = null;
                status.setText("Saved folder permission is unavailable. Choose the folder again.");
                return;
            }
            current = root;
            refreshDirectory();
        } catch (Exception error) {
            root = null;
            status.setText("Saved folder could not be opened. Choose it again.");
        }
    }

    private void chooseRoot() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
            root = DocumentFile.fromTreeUri(this, uri);
            if (root == null || !root.canRead()) throw new IllegalStateException("Android did not grant folder read access.");
            current = root;
            parents.clear();
            pendingCopy = null;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(ROOT_URI, uri.toString()).apply();
            refreshDirectory();
        } catch (Exception error) {
            status.setText("Could not use the selected folder: " + message(error));
        }
    }

    private void refreshDirectory() {
        if (current == null) {
            status.setText("Choose a folder first.");
            list.removeAllViews();
            return;
        }
        if (busy) return;
        busy = true;
        list.removeAllViews();
        pathLabel.setText(current.getUri().toString());
        status.setText("Reading folder…");
        list.post(() -> {
            try {
                DocumentFile[] items = current.listFiles();
                List<DocumentFile> sorted = new ArrayList<>(Arrays.asList(items));
                sorted.sort(Comparator
                        .comparing((DocumentFile file) -> !file.isDirectory())
                        .thenComparing(file -> safeName(file).toLowerCase()));
                list.removeAllViews();
                for (DocumentFile item : sorted) addItem(item);
                busy = false;
                status.setText(sorted.size() + " item(s) • " +
                        (pendingCopy == null ? "clipboard empty" : "one file ready to paste"));
            } catch (Exception error) {
                busy = false;
                status.setText("Could not read folder: " + message(error));
            }
        });
    }

    private void addItem(DocumentFile item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(5);
        list.addView(row, lp);

        String label = (item.isDirectory() ? "📁 " : "📄 ") + safeName(item);
        Button open = button(label);
        open.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        open.setOnClickListener(v -> {
            if (item.isDirectory()) {
                parents.push(current);
                current = item;
                refreshDirectory();
            } else showFileActions(item);
        });
        row.addView(open, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button more = button("⋮");
        more.setOnClickListener(v -> showFileActions(item));
        row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private void showFileActions(DocumentFile item) {
        List<String> actions = item.isDirectory()
                ? Arrays.asList("OPEN", "RENAME", "DELETE")
                : Arrays.asList("OPEN", "COPY", "RENAME", "SHARE", "DELETE");
        new AlertDialog.Builder(this).setTitle(safeName(item))
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    String action = actions.get(which);
                    switch (action) {
                        case "OPEN": if (item.isDirectory()) { parents.push(current); current = item; refreshDirectory(); } else openFile(item); break;
                        case "COPY": pendingCopy = item.getUri(); status.setText("Copied " + safeName(item) + " to the file manager clipboard."); break;
                        case "RENAME": renameItem(item); break;
                        case "SHARE": shareFile(item); break;
                        case "DELETE": confirmDelete(item); break;
                    }
                }).show();
    }

    private void createFolder() {
        if (!canWrite()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Folder name");
        new AlertDialog.Builder(this).setTitle("Create folder").setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CREATE", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!validName(name)) { status.setText("Folder name is invalid."); return; }
                    if (current.findFile(name) != null) { status.setText("An item with that name already exists."); return; }
                    if (current.createDirectory(name) == null) status.setText("Could not create folder.");
                    refreshDirectory();
                }).show();
    }

    private void renameItem(DocumentFile item) {
        if (!canWrite()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(safeName(item));
        new AlertDialog.Builder(this).setTitle("Rename item").setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!validName(name)) { status.setText("New name is invalid."); return; }
                    if (!item.renameTo(name)) status.setText("Android could not rename this item.");
                    refreshDirectory();
                }).show();
    }

    private void confirmDelete(DocumentFile item) {
        if (!canWrite()) return;
        new AlertDialog.Builder(this).setTitle("Delete item?")
                .setMessage("Delete " + safeName(item) + " from the selected folder?")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (dialog, which) -> {
                    if (!item.delete()) status.setText("Android could not delete this item.");
                    refreshDirectory();
                }).show();
    }

    private void pasteFile() {
        if (!canWrite()) return;
        if (pendingCopy == null) { status.setText("Copy a file first."); return; }
        if (busy) return;
        busy = true;
        status.setText("Copying file…");
        new Thread(() -> {
            String outcome;
            try {
                DocumentFile source = DocumentFile.fromSingleUri(this, pendingCopy);
                if (source == null || !source.isFile()) throw new IllegalStateException("Clipboard file is unavailable.");
                String name = safeName(source);
                String mime = source.getType() == null ? "application/octet-stream" : source.getType();
                String destinationName = uniqueName(current, name);
                DocumentFile destination = current.createFile(mime, destinationName);
                if (destination == null) throw new IllegalStateException("Could not create destination file.");
                try (InputStream input = getContentResolver().openInputStream(source.getUri());
                     OutputStream output = getContentResolver().openOutputStream(destination.getUri(), "w")) {
                    if (input == null || output == null) throw new IllegalStateException("Could not open source or destination stream.");
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0L;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_COPY_BYTES) throw new IllegalStateException("Copy is limited to 1 GB per file.");
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                } catch (Exception error) {
                    destination.delete();
                    throw error;
                }
                outcome = "Copied " + name + " as " + destinationName + ".";
            } catch (Exception error) {
                outcome = "Copy failed: " + message(error);
            }
            final String finalOutcome = outcome;
            runOnUiThread(() -> {
                busy = false;
                status.setText(finalOutcome);
                refreshDirectory();
            });
        }, "TSI-File-Copy").start();
    }

    private void openFile(DocumentFile file) {
        try {
            String mime = file.getType();
            if (mime == null) mime = "*/*";
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(file.getUri(), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
        } catch (Exception error) {
            status.setText("No app could open this file: " + message(error));
        }
    }

    private void shareFile(DocumentFile file) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(file.getType() == null ? "*/*" : file.getType())
                    .putExtra(Intent.EXTRA_STREAM, file.getUri())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share file"));
        } catch (Exception error) {
            status.setText("Could not share this file: " + message(error));
        }
    }

    private void goParent() {
        if (!parents.isEmpty()) {
            current = parents.pop();
            refreshDirectory();
        } else {
            status.setText("You are at the selected folder root.");
        }
    }

    private boolean canWrite() {
        if (current == null || !current.canWrite()) {
            status.setText("This folder is read-only. Choose a folder with write access.");
            return false;
        }
        return true;
    }

    private static String uniqueName(DocumentFile folder, String original) {
        if (folder.findFile(original) == null) return original;
        int dot = original.lastIndexOf('.');
        String stem = dot > 0 ? original.substring(0, dot) : original;
        String suffix = dot > 0 ? original.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            String candidate = stem + " (" + i + ")" + suffix;
            if (folder.findFile(candidate) == null) return candidate;
        }
        throw new IllegalStateException("Too many duplicate file names.");
    }

    private static String safeName(DocumentFile file) {
        String name = file.getName();
        return name == null || name.trim().isEmpty() ? "(unnamed)" : name;
    }

    private static boolean validName(String name) {
        return name != null && !name.trim().isEmpty() && name.length() <= 120
                && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && name.indexOf('\u0000') < 0;
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
