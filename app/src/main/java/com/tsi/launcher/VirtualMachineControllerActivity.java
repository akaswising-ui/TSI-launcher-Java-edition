package com.tsi.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.tsi.launcher.instances.GameInstance;
import com.tsi.launcher.instances.GameInstanceRepository;
import com.tsi.launcher.runtime.AmethystEngineBridge;
import com.tsi.launcher.runtime.AndroidJreInstaller;
import com.tsi.launcher.runtime.DetectedJavaRuntime;
import com.tsi.launcher.runtime.DevicePerformanceProfile;
import com.tsi.launcher.runtime.DevicePerformanceProfileSnapshot;
import com.tsi.launcher.runtime.JavaRuntimeManager;
import com.tsi.launcher.runtime.VersionVmInspection;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Java Android Views screen for runtime preflight and launching Minecraft. */
public final class VirtualMachineControllerActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private JavaRuntimeManager manager;
    private AndroidJreInstaller jreInstaller;
    private VersionVmInspection inspection;
    private GameInstance instanceConfig;
    private String versionId;
    private String instanceId;
    private String playerName;
    private int heapMb;
    private String runtimePreference;
    private TextView status;
    private TextView requirements;
    private TextView runtimeSummary;
    private Spinner runtimeSpinner;
    private Button refreshButton;
    private Button installButton;
    private Button startButton;
    private Button optimizeButton;
    private boolean busy;
    private List<DetectedJavaRuntime> runtimes = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manager = new JavaRuntimeManager(getApplicationContext());
        jreInstaller = new AndroidJreInstaller(getApplicationContext());
        Intent intent = getIntent();
        versionId = intent.getStringExtra("version_id");
        instanceId = intent.getStringExtra("instance_id");
        if (instanceId == null || instanceId.trim().isEmpty()) instanceId = versionId;
        playerName = intent.getStringExtra("player_name");
        if (playerName == null) playerName = "Steve";
        SharedPreferences preferences = getSharedPreferences("tsi_launcher_preferences", MODE_PRIVATE);
        heapMb = preferences.getInt("ram_mb", com.tsi.launcher.runtime.DevicePerformanceProfile.recommendedHeapMb(this));
        try {
            instanceConfig = new GameInstanceRepository(getApplicationContext()).get(instanceId);
            if (instanceConfig != null) versionId = instanceConfig.getVersionId();
        } catch (Exception ignored) {
            instanceConfig = null;
        }
        runtimePreference = normalizeRuntimePreference(
                instanceConfig == null ? null : instanceConfig.getSelectedRuntime(),
                preferences.getString("java_runtime", "Auto"));
        buildUi();
        refreshInspection();
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

        addText(root, "VIRTUAL MACHINE CONTROLLER", 22, 0xFF183B4A, true);
        addText(root, "Minecraft Java " + (versionId == null ? "not selected" : versionId), 15, 0xFF183B4A, true);
        addText(root, "Android Java runtime and Amethyst game engine preflight", 12, 0xFF71838B, false);

        root.addView(section("JAVA RUNTIME"));
        runtimeSummary = addText(root, "Scanning installed Android JREs…", 13, 0xFF183B4A, false);
        runtimeSpinner = new Spinner(this);
        root.addView(runtimeSpinner, matchWrap());
        Button saveRuntime = button("USE SELECTED RUNTIME");
        saveRuntime.setOnClickListener(v -> saveRuntimePreference());
        root.addView(saveRuntime, matchWrap());

        installButton = button("DOWNLOAD REQUIRED JAVA");
        installButton.setOnClickListener(v -> installRuntime());
        root.addView(installButton, matchWrap());

        root.addView(section("DEVICE MEMORY"));
        addText(root, "Heap request: " + heapMb + " MB", 13, 0xFF183B4A, false);
        LinearLayout memoryRow = new LinearLayout(this);
        Button decrease = button("− 128 MB");
        Button increase = button("+ 128 MB");
        decrease.setOnClickListener(v -> changeHeap(-128));
        increase.setOnClickListener(v -> changeHeap(128));
        memoryRow.addView(decrease, new LinearLayout.LayoutParams(0, dp(48), 1f));
        memoryRow.addView(increase, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(memoryRow, matchWrap());

        root.addView(section("FPS OPTIMIZATION"));
        DevicePerformanceProfileSnapshot fpsProfile = DevicePerformanceProfile.detect(this);
        addText(root, "Auto-detected " + fpsProfile.getTierLabel()
                + " profile • " + fpsProfile.getRenderDistance() + " chunks"
                + " • " + fpsProfile.getSimulationDistance() + " simulation"
                + " • " + fpsProfile.getMaxFps() + " FPS cap", 12, 0xFF183B4A, false);
        optimizeButton = button("APPLY FPS OPTIMIZATION");
        optimizeButton.setOnClickListener(v -> applyFpsOptimization());
        root.addView(optimizeButton, matchWrap());

        root.addView(section("LAUNCH PIPELINE"));
        requirements = addText(root, "Waiting for inspection…", 12, 0xFF183B4A, false);
        refreshButton = button("REFRESH VM STATUS");
        refreshButton.setOnClickListener(v -> refreshInspection());
        root.addView(refreshButton, matchWrap());

        startButton = button("START VIRTUAL MACHINE");
        startButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF25845B));
        startButton.setTextColor(0xFFFFFFFF);
        startButton.setOnClickListener(v -> startGame());
        root.addView(startButton, matchWrap());

        root.addView(section("TOOLS"));
        Button clearCache = button("CLEAR LAUNCHER CACHE");
        clearCache.setOnClickListener(v -> clearLauncherCache());
        root.addView(clearCache, matchWrap());

        status = addText(root, "VM is stopped.", 12, 0xFF71838B, false);
        Button back = button("BACK TO DASHBOARD");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());
    }

    private void refreshInspection() {
        if (busy) return;
        busy = true;
        refreshButton.setEnabled(false);
        startButton.setEnabled(false);
        setStatus("Checking version files, JREs, libraries, assets and engine…");
        io.execute(() -> {
            try {
                VersionVmInspection result = manager.inspect(versionId, runtimePreference, instanceId);
                main.post(() -> {
                    inspection = result;
                    runtimes = result.getDetectedRuntimes();
                    updateRuntimeList();
                    updateRequirements();
                    busy = false;
                    refreshButton.setEnabled(true);
                    startButton.setEnabled(result.isReadyToLaunch());
                    setStatus(result.isReadyToLaunch()
                            ? "All launch checks passed. VM engine is ready."
                            : "VM is stopped. Resolve the missing launch requirements.");
                });
            } catch (Exception error) {
                main.post(() -> {
                    busy = false;
                    refreshButton.setEnabled(true);
                    startButton.setEnabled(false);
                    setStatus("VM inspection failed: " + message(error));
                    requirements.setText(message(error));
                });
            }
        });
    }

    private void updateRuntimeList() {
        List<String> labels = new ArrayList<>();
        labels.add("Auto");
        for (DetectedJavaRuntime runtime : runtimes) {
            labels.add(runtime.getName() + " (Java " + runtime.getMajorVersion() + ")");
        }
        runtimeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        if (runtimePreference != null && runtimePreference.startsWith("Name:")) {
            String wanted = runtimePreference.substring(5);
            for (int i = 0; i < runtimes.size(); i++) {
                if (wanted.equals(runtimes.get(i).getName())) runtimeSpinner.setSelection(i + 1);
            }
        }
        StringBuilder summary = new StringBuilder();
        summary.append(runtimes.size()).append(" compatible Android runtime(s) detected.");
        for (DetectedJavaRuntime runtime : runtimes) {
            summary.append("\nJava ").append(runtime.getMajorVersion())
                    .append(" • ").append(runtime.getName()).append(" • ")
                    .append(runtime.getArchitecture());
        }
        runtimeSummary.setText(summary.toString());
        Integer required = inspection == null ? null : inspection.getRequiredJavaMajor();
        Integer installable = required == null ? null : jreInstaller.recommendedRuntimeMajor(required);
        boolean suitable = required != null && hasCompatibleRuntime(required);
        installButton.setVisibility(required != null && installable != null && !suitable ? View.VISIBLE : View.GONE);
        if (installable != null) installButton.setText("DOWNLOAD & INSTALL JAVA " + installable);
    }

    private boolean hasCompatibleRuntime(int required) {
        for (DetectedJavaRuntime runtime : runtimes) if (runtime.getMajorVersion() >= required) return true;
        return false;
    }

    private void updateRequirements() {
        if (inspection == null) {
            requirements.setText("Inspection unavailable.");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("Client JAR: ").append(inspection.isClientJarExists() ? "READY" : "MISSING").append('\n');
        text.append("Metadata: ").append(inspection.isMetadataExists() ? "READY" : "MISSING").append('\n');
        text.append("Required Java: ").append(inspection.getRequiredJavaMajor() == null
                ? "unknown" : inspection.getRequiredJavaMajor()).append('\n');
        text.append("Libraries: ").append(inspection.getInstalledLibraryCount())
                .append("/").append(inspection.getLibraryCount()).append('\n');
        text.append("Asset index: ").append(inspection.isAssetIndexExists() ? "READY" : "MISSING").append('\n');
        text.append("Asset objects: ").append(inspection.getAssetObjectCount())
                .append("/").append(inspection.getAssetObjectTotal()).append('\n');
        text.append("Amethyst engine: ").append(inspection.isEngineBridgeAvailable() ? "READY" : "MISSING");
        if (!inspection.getBlockers().isEmpty()) {
            text.append("\n\nWHY THE VM CANNOT START");
            for (String blocker : inspection.getBlockers()) text.append("\n• ").append(blocker);
        }
        requirements.setText(text.toString());
    }

    private void saveRuntimePreference() {
        int selected = runtimeSpinner.getSelectedItemPosition();
        if (selected <= 0) runtimePreference = "Auto";
        else runtimePreference = "Name:" + runtimes.get(Math.min(selected - 1, runtimes.size() - 1)).getName();
        getSharedPreferences("tsi_launcher_preferences", MODE_PRIVATE).edit()
                .putString("java_runtime", runtimePreference).apply();

        if (instanceConfig == null) {
            setStatus("Default runtime preference saved.");
            refreshInspection();
            return;
        }
        if (busy) {
            setStatus("Wait for the current operation to finish before saving the runtime.");
            return;
        }
        busy = true;
        setStatus("Saving runtime selection for this instance…");
        final String savedRuntime = "Auto".equals(runtimePreference) ? null : runtimePreference;
        io.execute(() -> {
            try {
                GameInstanceRepository repository = new GameInstanceRepository(getApplicationContext());
                instanceConfig = repository.updateLaunchSettings(instanceId,
                        instanceConfig.getRendererId(), instanceConfig.getJavaArgs(),
                        savedRuntime, instanceConfig.getControlLayout(), instanceConfig.getArgsMode(),
                        instanceConfig.isSharedData());
                main.post(() -> {
                    busy = false;
                    setStatus("Runtime preference saved for " + instanceConfig.getName() + ".");
                    refreshInspection();
                });
            } catch (Exception error) {
                main.post(() -> {
                    busy = false;
                    setStatus("Could not save instance runtime: " + message(error));
                });
            }
        });
    }

    private static String normalizeRuntimePreference(String value, String fallback) {
        String saved = value == null ? "" : value.trim();
        if (saved.isEmpty()) return fallback == null ? "Auto" : fallback;
        if ("Auto".equalsIgnoreCase(saved)) return "Auto";
        if (saved.startsWith("Name:")) return saved;
        if (saved.matches("(?i)Java\\s*[0-9]{1,2}") || saved.matches("[0-9]{1,2}")) {
            return saved.replaceAll("(?i)^Java\\s*", "Java");
        }
        return "Name:" + saved;
    }

    private void installRuntime() {
        if (inspection == null || inspection.getRequiredJavaMajor() == null || busy) return;
        Integer major = jreInstaller.recommendedRuntimeMajor(inspection.getRequiredJavaMajor());
        if (major == null) {
            setStatus("No downloadable Android JRE satisfies Java " + inspection.getRequiredJavaMajor() + ".");
            return;
        }
        busy = true;
        installButton.setEnabled(false);
        setStatus("Preparing Java " + major + " download…");
        io.execute(() -> {
            try {
                jreInstaller.downloadAndInstall(major, update ->
                        main.post(() -> setStatus(update)));
                main.post(() -> {
                    busy = false;
                    setStatus("Java " + major + " installed. Rechecking selected version.");
                    refreshInspection();
                });
            } catch (Exception error) {
                main.post(() -> {
                    busy = false;
                    installButton.setEnabled(true);
                    setStatus("Runtime install failed: " + message(error));
                });
            }
        });
    }

    private void changeHeap(int delta) {
        int maximum = com.tsi.launcher.runtime.DevicePerformanceProfile.maxHeapMb(this);
        int minimum = com.tsi.launcher.runtime.DevicePerformanceProfile.minHeapMb(this);
        heapMb = Math.max(minimum, Math.min(maximum, heapMb + delta));
        getSharedPreferences("tsi_launcher_preferences", MODE_PRIVATE)
                .edit().putInt("ram_mb", heapMb).apply();
        setStatus("Heap request saved: " + heapMb + " MB (device limit " + maximum + " MB).");
    }

    private void applyFpsOptimization() {
        if (busy) return;
        busy = true;
        optimizeButton.setEnabled(false);
        setStatus("Applying FPS settings to this Minecraft instance…");
        io.execute(() -> {
            try {
                String result = AmethystEngineBridge.applyPerformanceProfile(
                        getApplicationContext(), instanceId, true);
                main.post(() -> {
                    busy = false;
                    optimizeButton.setEnabled(true);
                    setStatus(result);
                });
            } catch (Exception error) {
                main.post(() -> {
                    busy = false;
                    optimizeButton.setEnabled(true);
                    setStatus("FPS optimization failed: " + message(error));
                });
            }
        });
    }

    private void startGame() {
        if (busy || inspection == null || !inspection.isReadyToLaunch()) {
            setStatus("Launch blocked by preflight. Resolve every missing requirement first.");
            return;
        }
        DetectedJavaRuntime runtime = inspection.getCompatibleRuntime();
        if (runtime == null) {
            setStatus("No compatible Java runtime is selected.");
            return;
        }
        busy = true;
        startButton.setEnabled(false);
        refreshButton.setEnabled(false);
        setStatus("Preparing Amethyst engine components…");
        io.execute(() -> {
            try {
                AmethystEngineBridge.prepare(this, update -> main.post(() -> setStatus(update)));
                main.post(() -> {
                    try {
                        AmethystEngineBridge.launch(this, versionId, heapMb, runtime, playerName,
                                instanceId,
                                instanceConfig == null ? null : instanceConfig.getRendererId(),
                                instanceConfig == null ? null : instanceConfig.getJavaArgs(),
                                instanceConfig == null ? null : instanceConfig.getControlLayout(),
                                instanceConfig == null ? 0 : instanceConfig.getArgsMode(),
                                instanceConfig != null && instanceConfig.isSharedData());
                        setStatus("Starting Minecraft " + versionId + "…");
                    } catch (Exception error) {
                        busy = false;
                        startButton.setEnabled(true);
                        refreshButton.setEnabled(true);
                        setStatus("Engine launch failed: " + message(error));
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    busy = false;
                    startButton.setEnabled(true);
                    refreshButton.setEnabled(true);
                    setStatus("Engine preparation failed: " + message(error));
                });
            }
        });
    }

    private void clearLauncherCache() {
        if (busy) {
            setStatus("Wait until the current launcher operation finishes before clearing cache.");
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Clear launcher cache?")
                .setMessage("This removes temporary cache files only. Minecraft worlds, mods, configs, installed versions and Java runtimes are kept.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CLEAR", (dialog, which) -> {
                    setStatus("Clearing temporary cache files…");
                    io.execute(() -> {
                        try {
                            long removed = clearCacheChildren(getCacheDir());
                            File external = getExternalCacheDir();
                            if (external != null && !external.getCanonicalFile()
                                    .equals(getCacheDir().getCanonicalFile())) {
                                removed += clearCacheChildren(external);
                            }
                            long total = removed;
                            main.post(() -> setStatus("Launcher cache cleared • "
                                    + android.text.format.Formatter.formatShortFileSize(this, total)
                                    + " released. Worlds and game files were preserved."));
                        } catch (Exception error) {
                            main.post(() -> setStatus("Cache clear failed: " + message(error)));
                        }
                    });
                })
                .show();
    }

    private static long clearCacheChildren(File directory) throws IOException {
        if (!directory.exists()) return 0L;
        if (!directory.isDirectory()) throw new IOException("Cache location is not a directory.");
        File root = directory.getCanonicalFile();
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Could not read the cache directory.");
        long removed = 0L;
        for (File child : children) removed += deleteCacheEntry(root, child);
        return removed;
    }

    private static long deleteCacheEntry(File root, File entry) throws IOException {
        File canonical = entry.getCanonicalFile();
        if (!canonical.equals(root)
                && !canonical.getPath().startsWith(root.getPath() + File.separator)) {
            long length = entry.length();
            if (!entry.delete()) throw new IOException("Could not remove cache link: " + entry.getName());
            return Math.max(0L, length);
        }
        long removed = 0L;
        if (entry.isDirectory()) {
            File[] children = entry.listFiles();
            if (children == null) throw new IOException("Could not read cache folder: " + entry.getName());
            for (File child : children) removed += deleteCacheEntry(root, child);
        } else {
            removed = Math.max(0L, entry.length());
        }
        if (!entry.delete()) throw new IOException("Could not remove cache file: " + entry.getName());
        return removed;
    }

    private TextView section(String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(14);
        view.setTextColor(0xFF183B4A);
        view.setTypeface(null, 1);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private TextView addText(LinearLayout root, String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, 1);
        root.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private void setStatus(String value) { if (status != null) status.setText(value); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
