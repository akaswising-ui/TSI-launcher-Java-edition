package com.tsi.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.tsi.launcher.data.JavaGameInstallResult;
import com.tsi.launcher.data.JavaGameInstaller;
import com.tsi.launcher.data.MinecraftVersion;
import com.tsi.launcher.data.VersionFilter;
import com.tsi.launcher.data.VersionManifestRepository;
import com.tsi.launcher.instances.GameInstance;
import com.tsi.launcher.instances.GameInstanceRepository;
import com.tsi.launcher.runtime.AmethystEngineBridge;
import com.tsi.launcher.runtime.DevicePerformanceProfile;
import com.tsi.launcher.storage.AmethystStoragePaths;
import com.tsi.launcher.storage.VersionDirectoryManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Java-only launcher dashboard implemented with Android Views. */
public final class MainActivity extends Activity {
    private static final int REQUEST_LEGACY_STORAGE = 4101;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences preferences;
    private VersionManifestRepository manifestRepository;
    private VersionDirectoryManager directoryManager;
    private JavaGameInstaller gameInstaller;
    private GameInstanceRepository instanceRepository;

    private final List<MinecraftVersion> allVersions = new ArrayList<>();
    private final List<MinecraftVersion> visibleVersions = new ArrayList<>();
    private Spinner filterSpinner;
    private Spinner versionSpinner;
    private TextView accountText;
    private TextView statusText;
    private TextView deviceText;
    private Button installButton;
    private Button refreshButton;
    private boolean loadingVersions;
    private String profileName = "Steve";
    private GameInstance activeInstance;
    private VersionFilter selectedFilter = VersionFilter.RELEASE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("tsi_launcher_preferences", MODE_PRIVATE);
        manifestRepository = new VersionManifestRepository();
        directoryManager = new VersionDirectoryManager(getApplicationContext());
        gameInstaller = new JavaGameInstaller(getApplicationContext(), directoryManager);
        instanceRepository = new GameInstanceRepository(getApplicationContext());
        buildDashboard();
        loadAccountAndInstance();
        refreshVersions();
        requestLegacyStorageIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccount();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void buildDashboard() {
        int page = 0xFFF3F7F8;
        int ink = 0xFF183B4A;
        int teal = 0xFF087F9C;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(page);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(22));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("TSI LAUNCHER", 26, ink, true);
        root.addView(title, matchWrap());
        TextView subtitle = text("MINECRAFT JAVA EDITION", 12, teal, true);
        subtitle.setPadding(0, dp(3), 0, dp(12));
        root.addView(subtitle, matchWrap());

        accountText = text("Account: checking…", 13, ink, true);
        root.addView(accountText, matchWrap());
        Button accountButton = button("MANAGE ACCOUNT");
        accountButton.setOnClickListener(v -> openAmethyst("tsi_open_account_manager"));
        root.addView(accountButton, matchWrap());

        deviceText = text("", 12, 0xFF71838B, false);
        deviceText.setPadding(0, dp(7), 0, dp(7));
        root.addView(deviceText, matchWrap());

        root.addView(sectionTitle("SELECT MINECRAFT VERSION"));
        filterSpinner = new Spinner(this);
        List<String> filters = new ArrayList<>();
        for (VersionFilter filter : VersionFilter.values()) filters.add(filter.getLabel());
        filterSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, filters));
        filterSpinner.setSelection(1); // Release
        filterSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                VersionFilter[] values = VersionFilter.values();
                if (position < values.length) {
                    selectedFilter = values[position];
                    applyVersionFilter();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        root.addView(filterSpinner, matchWrap());

        versionSpinner = new Spinner(this);
        root.addView(versionSpinner, matchWrap());

        LinearLayout installRow = new LinearLayout(this);
        installRow.setOrientation(LinearLayout.HORIZONTAL);
        installButton = button("INSTALL & PREPARE");
        installButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(teal));
        installButton.setTextColor(0xFFFFFFFF);
        installButton.setOnClickListener(v -> installSelectedVersion());
        installRow.addView(installButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        refreshButton = button("REFRESH");
        refreshButton.setOnClickListener(v -> refreshVersions());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(112), dp(52));
        refreshParams.leftMargin = dp(8);
        installRow.addView(refreshButton, refreshParams);
        root.addView(installRow, matchWrap());

        root.addView(sectionTitle("GAME MANAGEMENT"));
        addAction(root, "VIRTUAL MACHINE CONTROLLER", v -> openVmController());
        addAction(root, "GAME INSTANCES", v -> openInstances());
        addAction(root, "MODS & MODPACKS", v -> openModpacks());
        addAction(root, "ENGINE SETTINGS", v -> openAmethyst("tsi_open_engine_settings"));

        root.addView(sectionTitle("GAME TOOLS"));
        addAction(root, "SCREEN RECORDER", v ->
                startActivity(new Intent(this, GameRecorderActivity.class)));
        addAction(root, "RECORDINGS GALLERY", v ->
                startActivity(new Intent(this, GameRecordingsGalleryActivity.class)));
        addAction(root, "FILE MANAGER", v ->
                startActivity(new Intent(this, StorageFileManagerActivity.class)));
        addAction(root, "GEMINI CRASH ANALYZER", v ->
                startActivity(new Intent(this, CrashAnalyzerActivity.class)));
        addAction(root, "EXTERNAL RENDERER PLUGINS", v ->
                startActivity(new Intent(this, ExternalRendererManagerActivity.class)));
        addAction(root, "VULKAN / ADRENO DRIVER PACKAGES", v ->
                startActivity(new Intent(this, VulkanDriverManagerActivity.class)));
        addAction(root, GameQuickToolsService.isRunning()
                ? "DISABLE GAME QUICK PANEL" : "ENABLE GAME QUICK PANEL", v -> toggleQuickPanel());

        root.addView(sectionTitle("STATUS"));
        statusText = text("Connecting to Mojang version catalogue…", 12, 0xFF71838B, false);
        statusText.setPadding(0, dp(7), 0, dp(12));
        root.addView(statusText, matchWrap());
    }

    private void loadAccountAndInstance() {
        refreshAccount();
        String defaultVersion = preferences.getString("selected_version_id", "");
        io.execute(() -> {
            try {
                directoryManager.migrateLegacyStorage();
                GameInstance selected = instanceRepository.selected();
                if (selected == null && !defaultVersion.isEmpty()) {
                    selected = instanceRepository.ensureDefault(defaultVersion);
                }
                final GameInstance instance = selected;
                main.post(() -> {
                    activeInstance = instance;
                    if (instance != null) {
                        statusText.setText("Selected instance: " + instance.getName() +
                                " • Minecraft " + instance.getVersionId());
                    }
                });
            } catch (Exception error) {
                main.post(() -> statusText.setText("Instance setup: " + message(error)));
            }
        });
    }

    private void refreshAccount() {
        String account = AmethystEngineBridge.selectedAccountUsername(this);
        if (account == null || account.trim().isEmpty()) account = preferences.getString("profile_name", "Steve");
        profileName = account == null ? "Steve" : account;
        if (accountText != null) {
            accountText.setText("PLAYER: " + profileName + " • " +
                    AmethystEngineBridge.selectedAccountMode(this));
        }
        if (deviceText != null) {
            deviceText.setText(DevicePerformanceProfile.detect(this).getTierLabel() +
                    " • " + DevicePerformanceProfile.detect(this).getTotalPhysicalRamMb() + " MB physical RAM");
        }
    }

    private void refreshVersions() {
        if (loadingVersions) return;
        loadingVersions = true;
        if (refreshButton != null) refreshButton.setEnabled(false);
        if (installButton != null) installButton.setEnabled(false);
        setStatus("Fetching Mojang version manifest…");
        io.execute(() -> {
            try {
                List<MinecraftVersion> result = manifestRepository.fetchVersions();
                main.post(() -> {
                    allVersions.clear();
                    allVersions.addAll(result);
                    loadingVersions = false;
                    if (refreshButton != null) refreshButton.setEnabled(true);
                    applyVersionFilter();
                    setStatus("Loaded " + result.size() + " Java version entries from Mojang.");
                });
            } catch (Exception error) {
                main.post(() -> {
                    loadingVersions = false;
                    if (refreshButton != null) refreshButton.setEnabled(true);
                    if (installButton != null) installButton.setEnabled(false);
                    setStatus("Could not load versions: " + message(error));
                });
            }
        });
    }

    private void applyVersionFilter() {
        if (versionSpinner == null) return;
        visibleVersions.clear();
        List<String> labels = new ArrayList<>();
        for (MinecraftVersion version : allVersions) {
            if (selectedFilter.accepts(version.getType())) {
                visibleVersions.add(version);
                labels.add(version.getId() + " • " + version.getType());
            }
        }
        versionSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        String saved = preferences.getString("selected_version_id", "");
        for (int i = 0; i < visibleVersions.size(); i++) {
            if (visibleVersions.get(i).getId().equals(saved)) {
                versionSpinner.setSelection(i);
                break;
            }
        }
        if (visibleVersions.isEmpty()) {
            setStatus("No versions in this filter.");
            if (installButton != null) installButton.setEnabled(false);
        } else if (installButton != null && !loadingVersions) {
            installButton.setEnabled(true);
        }
    }

    private MinecraftVersion selectedVersion() {
        if (versionSpinner == null || visibleVersions.isEmpty()) return null;
        int selected = versionSpinner.getSelectedItemPosition();
        if (selected < 0 || selected >= visibleVersions.size()) return null;
        return visibleVersions.get(selected);
    }

    private void installSelectedVersion() {
        final MinecraftVersion version = selectedVersion();
        if (version == null) {
            setStatus("Select a Minecraft version first.");
            return;
        }
        if (!hasStorageAccess()) {
            requestLegacyStorageIfNeeded();
            setStatus("Storage permission is required before installing Minecraft files.");
            return;
        }
        installButton.setEnabled(false);
        refreshButton.setEnabled(false);
        setStatus("Preparing Minecraft " + version.getId() + "…");
        io.execute(() -> {
            try {
                GameInstance instance = activeInstance;
                if (instance == null) instance = instanceRepository.ensureDefault(version.getId());
                if (!instance.getVersionId().equals(version.getId())) {
                    instance = instanceRepository.updateVersion(instance.getId(), version.getId());
                }
                final GameInstance selectedInstance = instance;
                JavaGameInstallResult result = gameInstaller.install(version, selectedInstance.getId(),
                        progress -> main.post(() -> setStatus(progress)));
                preferences.edit().putString("selected_version_id", version.getId()).apply();
                activeInstance = selectedInstance;
                main.post(() -> {
                    installButton.setEnabled(true);
                    refreshButton.setEnabled(true);
                    setStatus(result.getVersionId() + " installed: " + result.getLibraryCount() +
                            " libraries, " + result.getAssetCount() + " assets. Required Java: " +
                            (result.getRequiredJavaMajor() == null ? "not resolved" :
                                    result.getRequiredJavaMajor()) + ".");
                    openVmController();
                });
            } catch (Exception error) {
                main.post(() -> {
                    installButton.setEnabled(true);
                    refreshButton.setEnabled(true);
                    setStatus("Install failed: " + message(error));
                });
            }
        });
    }

    private void openVmController() {
        GameInstance selectedInstance = null;
        try {
            selectedInstance = instanceRepository.selected();
            if (selectedInstance != null) activeInstance = selectedInstance;
        } catch (Exception error) {
            setStatus("Could not read the selected game instance: " + message(error));
            return;
        }
        MinecraftVersion version = selectedVersion();
        String versionId = selectedInstance != null ? selectedInstance.getVersionId()
                : version == null ? preferences.getString("selected_version_id", "") : version.getId();
        if (versionId == null || versionId.trim().isEmpty()) {
            setStatus("Select and install a Minecraft version first.");
            return;
        }
        Intent intent = new Intent(this, VirtualMachineControllerActivity.class);
        intent.putExtra("version_id", versionId);
        intent.putExtra("instance_id", selectedInstance == null ? versionId : selectedInstance.getId());
        intent.putExtra("player_name", profileName);
        startActivity(intent);
    }

    private void openInstances() {
        Intent intent = new Intent(this, GameInstanceManagerActivity.class);
        MinecraftVersion version = selectedVersion();
        intent.putExtra("version_id", version == null ? "" : version.getId());
        startActivity(intent);
    }

    private void openModpacks() {
        startActivity(new Intent(this, ModPackManagerActivity.class));
    }

    private void toggleQuickPanel() {
        if (GameQuickToolsService.isRunning()) {
            stopService(new Intent(this, GameQuickToolsService.class));
            setStatus("TSI Game Quick Panel disabled.");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Allow Display over other apps, then tap ENABLE GAME QUICK PANEL again.");
            Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(permission);
            return;
        }
        Intent service = new Intent(this, GameQuickToolsService.class)
                .setAction(GameQuickToolsService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
        setStatus("TSI Game Quick Panel is starting.");
    }

    private void openAmethyst(String extra) {
        try {
            Intent intent = new Intent().setClassName(this, "net.kdt.pojavlaunch.LauncherActivity");
            intent.putExtra(extra, true);
            startActivity(intent);
        } catch (Exception error) {
            setStatus("Amethyst engine screen is unavailable: " + message(error));
        }
    }

    private void requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasStorageAccess()) return;
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_LEGACY_STORAGE);
        }
    }

    private boolean hasStorageAccess() {
        return AmethystStoragePaths.hasLegacyStoragePermission(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LEGACY_STORAGE) {
            boolean granted = true;
            for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) granted = false;
            setStatus(granted ? "Storage access granted." :
                    "Read and write storage permissions are required on Android 9 and older.");
        }
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText(message);
    }

    private void addAction(LinearLayout root, String label, View.OnClickListener listener) {
        Button action = button(label);
        action.setOnClickListener(listener);
        root.addView(action, matchWrap());
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 14, 0xFF183B4A, true);
        view.setPadding(0, dp(19), 0, dp(6));
        return view;
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
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private static String message(Throwable error) {
        if (error == null) return "unknown error";
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
