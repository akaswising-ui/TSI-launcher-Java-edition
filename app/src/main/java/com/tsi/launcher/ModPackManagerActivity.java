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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.tsi.launcher.instances.GameInstance;
import com.tsi.launcher.instances.GameInstanceRepository;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Java Views screen for live Modrinth/CurseForge modpack search and installation.
 * Amethyst modpack API types are accessed reflectively to keep this Java module
 * decoupled from their Kotlin implementation details.
 */
public final class ModPackManagerActivity extends Activity {
    private static final int REQUEST_IMPORT_PACK = 6101;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private GameInstanceRepository instanceRepository;
    private Object api;
    private EditText queryInput;
    private LinearLayout results;
    private TextView status;
    private Button searchButton;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instanceRepository = new GameInstanceRepository(getApplicationContext());
        buildUi();
        initializeApi();
        search("");
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

        TextView title = text("JAVA EDITION MOD MANAGER", 21, 0xFFF0F3F0, true);
        root.addView(title, matchWrap());
        root.addView(text("Live Modrinth and CurseForge modpack catalogue", 11, 0xFFAAB5AF, false), matchWrap());

        LinearLayout queryRow = new LinearLayout(this);
        queryInput = new EditText(this);
        queryInput.setSingleLine(true);
        queryInput.setHint("Search modpacks");
        queryInput.setTextColor(0xFFFFFFFF);
        queryInput.setHintTextColor(0xFFAAB5AF);
        queryRow.addView(queryInput, new LinearLayout.LayoutParams(0, dp(52), 1f));
        searchButton = button("SEARCH");
        searchButton.setOnClickListener(v -> search(queryInput.getText().toString()));
        queryRow.addView(searchButton, new LinearLayout.LayoutParams(dp(110), dp(52)));
        root.addView(queryRow, matchWrap());

        Button importButton = button("IMPORT .ZIP / .MRPACK");
        importButton.setOnClickListener(v -> chooseImport());
        root.addView(importButton, matchWrap());

        Button loadersButton = button("OPEN FORGE / FABRIC / QUILT / NEOFORGE / OPTIFINE INSTALLERS");
        loadersButton.setOnClickListener(v -> openAmethyst("tsi_open_modloader_manager"));
        root.addView(loadersButton, matchWrap());

        status = text("Connecting to modpack providers…", 12, 0xFF9FC18E, false);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status, matchWrap());

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results, matchWrap());

        Button back = button("BACK TO DASHBOARD");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());
    }

    private void initializeApi() {
        try {
            String key = "";
            int keyId = getResources().getIdentifier("curseforge_api_key", "string", getPackageName());
            if (keyId != 0) key = getString(keyId);
            Class<?> commonApi = Class.forName("net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi");
            api = commonApi.getConstructor(String.class).newInstance(key);
        } catch (Exception error) {
            setStatus("Modrinth/CurseForge API unavailable: " + message(error));
        }
    }

    private void search(String query) {
        if (busy) return;
        if (api == null) initializeApi();
        if (api == null) return;
        busy = true;
        searchButton.setEnabled(false);
        setStatus("Searching Modrinth and CurseForge…");
        io.execute(() -> {
            try {
                Object filters = makeFilters(query);
                Object result = call(api, "searchMod", filters);
                List<?> items = asList(property(result, "results"));
                int total = number(property(result, "totalResultCount"));
                runOnUiThread(() -> renderItems(items, total));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    results.removeAllViews();
                    setStatus("Modpack search failed: " + message(error));
                    busy = false;
                    searchButton.setEnabled(true);
                });
            }
        });
    }

    private Object makeFilters(String query) throws Exception {
        Class<?> type = Class.forName("net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters");
        Object filters = type.getDeclaredConstructor().newInstance();
        setProperty(filters, "isModpack", true);
        setProperty(filters, "name", query == null ? "" : query.trim());
        return filters;
    }

    private void renderItems(List<?> items, int total) {
        results.removeAllViews();
        for (Object item : items) {
            String title = stringProperty(item, "title", "Untitled modpack");
            String description = stringProperty(item, "description", "No description supplied.");
            int source = number(property(item, "apiSource"));
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(0xFF292E2F);
            LinearLayout.LayoutParams params = matchWrap();
            params.bottomMargin = dp(9);
            results.addView(card, params);
            card.addView(text(title + " • " + (source == 0 ? "MODRINTH" : "CURSEFORGE"),
                    14, 0xFFF0F3F0, true), matchWrap());
            TextView summary = text(description, 10, 0xFFAAB5AF, false);
            summary.setMaxLines(3);
            card.addView(summary, matchWrap());
            Button versions = button("VIEW VERSIONS");
            versions.setOnClickListener(v -> openDetails(item));
            card.addView(versions, matchWrap());
        }
        setStatus(items.size() + " shown • " + total + " results");
        busy = false;
        searchButton.setEnabled(true);
        if (items.isEmpty()) results.addView(text("No modpacks matched this search.", 12, 0xFFAAB5AF, false));
    }

    private void openDetails(Object item) {
        if (busy) return;
        busy = true;
        setStatus("Loading available modpack versions…");
        io.execute(() -> {
            try {
                Object detail = call(api, "getModDetails", item);
                List<?> versions = asList(property(detail, "versionNames"));
                runOnUiThread(() -> {
                    busy = false;
                    if (versions.isEmpty()) {
                        setStatus("No installable versions were returned for " +
                                stringProperty(item, "title", "this modpack") + ".");
                    } else {
                        showVersions(item, detail, versions);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Could not load modpack versions: " + message(error));
                });
            }
        });
    }

    private void showVersions(Object item, Object detail, List<?> versions) {
        String[] labels = new String[versions.size()];
        for (int i = 0; i < versions.size(); i++) labels[i] = String.valueOf(versions.get(i));
        final int[] selected = {0};
        new AlertDialog.Builder(this)
                .setTitle(stringProperty(item, "title", "Select modpack version"))
                .setSingleChoiceItems(labels, 0, (dialog, which) -> selected[0] = which)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("INSTALL", (dialog, which) -> installModpack(detail, selected[0]))
                .show();
    }

    private void installModpack(Object detail, int selectedVersion) {
        if (busy) return;
        busy = true;
        setStatus("Downloading and installing modpack…");
        io.execute(() -> {
            try {
                ProfileSnapshot previous = readProfiles();
                Object loader = call(api, "installMod", detail, selectedVersion);
                runLoaderTask(loader);
                ProfileSnapshot now = readProfiles();
                ImportedProfile profile = findNewProfile(previous.keys, now);
                GameInstance instance = instanceRepository.importInstalledModpack(
                        profile.name, profile.version, profile.gameDirectory);
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Modpack installed as " + instance.getName() + ".");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Modpack install failed: " + message(error));
                });
            }
        });
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed",
                "application/octet-stream", "application/vnd.modrinth.mrpack"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMPORT_PACK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_PACK && resultCode == RESULT_OK && data != null &&
                data.getData() != null) {
            importFile(data.getData());
        }
    }

    private void importFile(Uri uri) {
        if (busy) return;
        busy = true;
        setStatus("Importing modpack archive…");
        io.execute(() -> {
            File archive = null;
            try {
                String displayName = queryDisplayName(uri);
                String extension = displayName.toLowerCase().endsWith(".mrpack") ? ".mrpack" : ".zip";
                archive = new File(getCacheDir(), "tsi-modpack-" + System.currentTimeMillis() + extension);
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(archive)) {
                    if (input == null) throw new java.io.IOException("Android could not open the selected archive.");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.getFD().sync();
                }
                if (!archive.isFile() || archive.length() == 0L) {
                    throw new java.io.IOException("The selected modpack archive is empty.");
                }
                ProfileSnapshot previous = readProfiles();
                Object loader = call(api, "importModpack", archive);
                runLoaderTask(loader);
                ProfileSnapshot now = readProfiles();
                ImportedProfile profile = findNewProfile(previous.keys, now);
                GameInstance instance = instanceRepository.importInstalledModpack(
                        profile.name, profile.version, profile.gameDirectory);
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Modpack imported as " + instance.getName() + ".");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    setStatus("Modpack import failed: " + message(error));
                });
            } finally {
                if (archive != null) archive.delete();
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return "modpack.zip";
    }

    private void runLoaderTask(Object loader) throws Exception {
        if (loader == null) return;
        Class<?> listenerClass = Class.forName(
                "net.kdt.pojavlaunch.modloaders.modpacks.api.NotificationDownloadListener");
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : listenerClass.getConstructors()) {
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length == 2 && types[0].isAssignableFrom(android.content.Context.class) &&
                    types[1].isAssignableFrom(loader.getClass())) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) {
            throw new NoSuchMethodException("NotificationDownloadListener(Context, ModLoader)");
        }
        Object listener = constructor.newInstance(getApplicationContext(), loader);
        Object task = call(loader, "getDownloadTask", listener);
        if (task != null) call(task, "run");
    }

    private ProfileSnapshot readProfiles() throws Exception {
        Class<?> profilesClass = Class.forName("net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles");
        Object singleton = null;
        try { singleton = profilesClass.getField("INSTANCE").get(null); }
        catch (Exception ignored) { }
        invokeStaticOrInstance(profilesClass, singleton, "load");
        Object json = getStaticOrInstanceProperty(profilesClass, singleton, "mainProfileJson");
        Object profiles = property(json, "profiles");
        if (!(profiles instanceof Map)) throw new java.io.IOException("Amethyst profile registry is unavailable.");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) profiles;
        return new ProfileSnapshot(map);
    }

    private ImportedProfile findNewProfile(Set<String> previous, ProfileSnapshot now) throws Exception {
        for (Map.Entry<String, Object> entry : now.profiles.entrySet()) {
            Object profile = entry.getValue();
            if (previous.contains(entry.getKey())) continue;
            String gameDir = stringProperty(profile, "gameDir", "");
            if (!gameDir.replace('\\', '/').contains("custom_instances/")) continue;
            String name = stringProperty(profile, "name", entry.getKey());
            String version = stringProperty(profile, "lastVersionId", "");
            if (version.trim().isEmpty()) throw new java.io.IOException("The imported modpack profile has no Minecraft version.");
            if (gameDir.trim().isEmpty()) throw new java.io.IOException("The imported modpack profile has no game directory.");
            return new ImportedProfile(name, version, gameDir);
        }
        throw new java.io.IOException("The modpack installer did not create a new selectable Java profile.");
    }

    private Object getStaticOrInstanceProperty(Class<?> type, Object instance, String name) throws Exception {
        try { return type.getField(name).get(null); }
        catch (Exception ignored) { }
        try { return type.getMethod("get" + capitalize(name)).invoke(instance); }
        catch (Exception ignored) { }
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private void invokeStaticOrInstance(Class<?> type, Object instance, String name) throws Exception {
        try { type.getMethod(name).invoke(null); }
        catch (Exception ignored) { type.getMethod(name).invoke(instance); }
    }

    private void setProperty(Object target, String property, Object value) throws Exception {
        String cap = capitalize(property);
        String[] setters = {"set" + cap, "set" + (property.startsWith("is") ? property.substring(2) : cap)};
        for (String setter : setters) {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(setter) && method.getParameterTypes().length == 1) {
                    method.invoke(target, value);
                    return;
                }
            }
        }
        try {
            Field field = target.getClass().getDeclaredField(property);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception error) {
            throw error;
        }
    }

    private Object call(Object receiver, String name, Object... arguments) throws Exception {
        for (Method method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != arguments.length) continue;
            try { return method.invoke(receiver, arguments); }
            catch (IllegalArgumentException ignored) { }
        }
        throw new NoSuchMethodException(receiver.getClass().getName() + "." + name);
    }

    private Object property(Object object, String name) {
        if (object == null) return null;
        String cap = capitalize(name);
        try {
            for (String method : new String[]{"get" + cap, "is" + cap}) {
                try { return object.getClass().getMethod(method).invoke(object); }
                catch (NoSuchMethodException ignored) { }
            }
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stringProperty(Object object, String name, String fallback) {
        try {
            Object value = property(object, name);
            return value == null || value.toString().trim().isEmpty() ? fallback : value.toString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<?> asList(Object value) {
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    private int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static String capitalize(String value) {
        return value == null || value.isEmpty() ? value :
                Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void openAmethyst(String extra) {
        try {
            Intent intent = new Intent().setClassName(this, "net.kdt.pojavlaunch.LauncherActivity");
            intent.putExtra(extra, true);
            startActivity(intent);
        } catch (Exception error) {
            setStatus("Amethyst screen unavailable: " + message(error));
        }
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

    private void setStatus(String value) { if (status != null) status.setText(value); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String message(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static final class ProfileSnapshot {
        final Map<String, Object> profiles;
        final Set<String> keys;
        ProfileSnapshot(Map<String, Object> profiles) {
            this.profiles = profiles;
            this.keys = new java.util.HashSet<>(profiles.keySet());
        }
    }

    private static final class ImportedProfile {
        final String name;
        final String version;
        final String gameDirectory;
        ImportedProfile(String name, String version, String gameDirectory) {
            this.name = name;
            this.version = version;
            this.gameDirectory = gameDirectory;
        }
    }
}
