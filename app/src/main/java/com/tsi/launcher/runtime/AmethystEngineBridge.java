package com.tsi.launcher.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import com.tsi.launcher.instances.GameInstance;
import com.tsi.launcher.instances.GameInstanceRepository;
import com.tsi.launcher.storage.AmethystStoragePaths;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter from TSI's installed game/runtime layout to Amethyst's Android game host.
 * All calls that scan or prepare files must run on an IO executor.
 */
public final class AmethystEngineBridge {
    private static final String LAUNCHER_ACTIVITY = "net.kdt.pojavlaunch.LauncherActivity";
    private static final String PROFILE_PREFS = "pojav_profile";
    private static final String PROFILE_PREF_FILE = "file";
    private static final String PROFILE_PREFIX = "amethyst://";

    private static final List<String> REQUIRED_JAVA_CLASSES = Arrays.asList(
            LAUNCHER_ACTIVITY,
            "org.libsdl.app.SDL",
            "org.lwjgl.glfw.CallbackBridge",
            "net.kdt.pojavlaunch.AWTInputBridge",
            "net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode",
            "net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput");

    private static final List<String> REQUIRED_LWJGL_FILES = Arrays.asList(
            "lwjgl3/3.3.3/lwjgl.jar",
            "lwjgl3/3.3.3/lwjgl-3.3.3-merged-modules.jar",
            "lwjgl3/3.3.3/lwjgl-lwjglx.jar",
            "lwjgl3/3.3.3/lwjgl-openal.jar",
            "lwjgl3/3.4.1/lwjgl.jar",
            "lwjgl3/3.4.1/lwjgl-3.4.1-merged-modules.jar",
            "lwjgl3/3.4.1/lwjgl-lwjglx.jar",
            "lwjgl3/3.4.1/lwjgl-openal.jar");

    private static final List<String> REQUIRED_NATIVE_LIBRARIES = Arrays.asList(
            "libpojavexec.so", "libpojavexec_awt.so", "libexithook.so");

    private AmethystEngineBridge() { }

    public interface ProgressListener {
        void onProgress(String message);
    }

    public static boolean isAvailable(Context context) {
        return missingEmbeddedComponents(context).isEmpty();
    }

    private static List<String> missingEmbeddedComponents(Context context) {
        List<String> missing = new ArrayList<>();
        for (String className : REQUIRED_JAVA_CLASSES) {
            try {
                Class.forName(className, false, context.getClassLoader());
            } catch (Throwable error) {
                missing.add("Amethyst class: " + className);
            }
        }

        File nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        for (String library : REQUIRED_NATIVE_LIBRARIES) {
            if (!new File(nativeDirectory, library).isFile()) {
                missing.add("Amethyst native library: " + library);
            }
        }

        for (String relativePath : REQUIRED_LWJGL_FILES) {
            String assetPath = "components/" + relativePath;
            try (java.io.InputStream input = context.getAssets().open(assetPath)) {
                if (input.read() < 0) missing.add("Bundled LWJGL component: " + assetPath);
            } catch (IOException error) {
                missing.add("Bundled LWJGL component: " + assetPath);
            }
        }
        return missing;
    }

    public static List<String> missingComponents(Context context) {
        List<String> missing = missingEmbeddedComponents(context);
        File gameRoot = AmethystStoragePaths.gameHome(context);
        if (!gameRoot.exists() || !gameRoot.isDirectory()) {
            missing.add("Amethyst game storage is unavailable");
        } else {
            for (String relativePath : REQUIRED_LWJGL_FILES) {
                if (!new File(gameRoot, relativePath).isFile()) {
                    missing.add("LWJGL component not extracted: " + relativePath);
                }
            }
        }
        return missing;
    }

    /**
     * Extracts packaged Amethyst components and runs the LWJGL sealing repair.
     * Must be called on a background thread.
     */
    public static void prepare(Context context, ProgressListener progress) throws Exception {
        ProgressListener listener = progress == null ? message -> { } : progress;
        ensureDefaultControlMap(context);
        listener.onProgress("Preparing Amethyst SDL, LWJGL, graphics, audio and input components…");

        Class<?> assetManager = Class.forName("net.kdt.pojavlaunch.tasks.AsyncAssetManager");
        assetManager.getMethod("unpackComponents", Context.class).invoke(null, context);

        File gameRoot = AmethystStoragePaths.gameHome(context);
        long deadline = SystemClock.elapsedRealtime() + 90_000L;
        boolean ready = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            ready = true;
            for (String relativePath : REQUIRED_LWJGL_FILES) {
                if (!new File(gameRoot, relativePath).isFile()) {
                    ready = false;
                    break;
                }
            }
            if (ready) break;
            Thread.sleep(300L);
        }

        LwjglJarSanitizer.Result sealing = LwjglJarSanitizer.sanitize(gameRoot);
        if (!sealing.getSkippedSignedJars().isEmpty()) {
            throw new IllegalStateException(
                    "LWJGL package sealing is present in signed JAR(s) that cannot be safely rewritten: " +
                            join(sealing.getSkippedSignedJars(), ", "));
        }
        if (sealing.getModifiedJars() > 0) {
            listener.onProgress("Repaired org.lwjgl package sealing in " +
                    sealing.getModifiedJars() + " LWJGL JAR(s).");
        }

        List<String> missing = missingComponents(context);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The Amethyst engine bundle is incomplete: " + join(missing, ", "));
        }
        listener.onProgress("Amethyst JVM host, SDL, LWJGL OpenGL/OpenAL JARs, renderer and input components are ready.");
    }

    /** Selects a durable Amethyst offline profile unless an authenticated account is already active. */
    public static String ensureOfflineAccountSelected(Context context) throws IOException {
        android.content.SharedPreferences preferences =
                context.getSharedPreferences("tsi_launcher_preferences", Context.MODE_PRIVATE);
        String currentName = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
                .getString(PROFILE_PREF_FILE, null);
        currentName = currentName == null ? null : currentName.trim();

        if (currentName != null && !currentName.isEmpty() &&
                (isLocalAccountFile(context, currentName) || selectedAccountIsMicrosoft(context))) {
            String username = readAccountUsername(context, currentName);
            if (username == null) username = currentName;
            preferences.edit().putString("profile_name", username).apply();
            return username;
        }

        String requested = preferences.getString("profile_name", "Steve001");
        if (requested == null) requested = "Steve001";
        requested = requested.trim();
        if (!requested.matches("[A-Za-z0-9_]{3,16}")) requested = "Steve001";

        File dataRoot = dataRoot(context);
        File accountsDirectory = new File(dataRoot, "accounts");
        String candidate = requested;
        if (new File(accountsDirectory, candidate + ".json").exists() &&
                !isLocalAccountFile(context, candidate)) {
            candidate = null;
            for (int suffix = 1; suffix <= 99; suffix++) {
                String tail = "_" + suffix;
                String proposed = requested.substring(0, Math.min(requested.length(), 16 - tail.length())) + tail;
                File file = new File(accountsDirectory, proposed + ".json");
                if (!file.exists() || isLocalAccountFile(context, proposed)) {
                    candidate = proposed;
                    break;
                }
            }
            if (candidate == null) throw new IOException("Could not select an unused offline username.");
        }

        addOfflineAccount(context, candidate);
        if (!preferences.edit().putString("profile_name", candidate).commit()) {
            throw new IOException("Could not save the offline username.");
        }
        return candidate;
    }

    private static String readAccountUsername(Context context, String accountName) {
        try {
            File accountFile = new File(new File(dataRoot(context), "accounts"), accountName + ".json");
            if (!accountFile.isFile()) return null;
            String value = new JSONObject(readText(accountFile)).optString("username").trim();
            return value.isEmpty() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isLocalAccountFile(Context context, String accountName) {
        if (accountName == null || !accountName.matches("[A-Za-z0-9_]{1,64}")) return false;
        try {
            File accountFile = new File(new File(dataRoot(context), "accounts"), accountName + ".json");
            if (!accountFile.isFile()) return false;
            JSONObject json = new JSONObject(readText(accountFile));
            return "0".equals(json.optString("accessToken", "0")) &&
                    !json.optString("username", accountName).startsWith("Demo.") &&
                    !json.optBoolean("isMicrosoft", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String selectedAccountMode(Context context) {
        if (selectedAccountUsername(context) == null) return "No account";
        if (selectedAccountIsMicrosoft(context)) return "Microsoft";
        if (selectedAccountIsLocal(context)) return "Offline";
        return "No account";
    }

    public static String selectedAccountUsername(Context context) {
        String accountName = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
                .getString(PROFILE_PREF_FILE, null);
        if (accountName == null) return null;
        accountName = accountName.trim();
        if (!accountName.matches("[A-Za-z0-9_]{1,64}")) return null;
        return readAccountUsername(context, accountName);
    }

    private static boolean selectedAccountIsMicrosoft(Context context) {
        String accountName = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
                .getString(PROFILE_PREF_FILE, null);
        if (accountName == null || !accountName.trim().matches("[A-Za-z0-9_]{1,64}")) return false;
        try {
            File accountFile = new File(new File(dataRoot(context), "accounts"), accountName.trim() + ".json");
            return accountFile.isFile() && new JSONObject(readText(accountFile)).optBoolean("isMicrosoft", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean selectedAccountIsLocal(Context context) {
        String name = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
                .getString(PROFILE_PREF_FILE, null);
        return name != null && isLocalAccountFile(context, name.trim());
    }

    public static File addOfflineAccount(Context context, String username) throws IOException {
        String playerName = username == null ? "" : username.trim();
        if (!playerName.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Offline username must be 3–16 characters using letters, numbers or underscore.");
        }

        File accountsDirectory = new File(dataRoot(context), "accounts");
        ensureDirectory(accountsDirectory);
        File accountFile = new File(accountsDirectory, playerName + ".json");
        if (!accountFile.isFile()) {
            try {
                JSONObject account = new JSONObject()
                        .put("accessToken", "0")
                        .put("clientToken", "0")
                        .put("profileId", "00000000-0000-0000-0000-000000000000")
                        .put("username", playerName)
                        .put("isMicrosoft", false);
                writeAtomically(accountFile, account.toString(2));
            } catch (org.json.JSONException error) {
                throw new IOException("Could not encode the Amethyst offline account.", error);
            }
        } else {
            try {
                JSONObject existing = new JSONObject(readText(accountFile));
                if (existing.optString("username").trim().isEmpty()) {
                    throw new IOException("The existing offline account file is invalid.");
                }
            } catch (Exception error) {
                throw new IOException("Could not read the existing Amethyst account for " + playerName, error);
            }
        }

        boolean selected = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(PROFILE_PREF_FILE, playerName).commit();
        if (!selected) throw new IOException("Could not save the selected offline account.");
        verifyAmethystOfflineAccount(context, playerName);
        return accountFile;
    }

    private static void verifyAmethystOfflineAccount(Context context, String username) throws IOException {
        try {
            Class<?> accountClass = Class.forName("net.kdt.pojavlaunch.value.MinecraftAccount");
            Object account = accountClass.getMethod("load", String.class).invoke(null, username);
            if (account == null) throw new IOException("Amethyst could not load the local account file.");
            Object isLocal = accountClass.getMethod("isLocal").invoke(account);
            if (!(isLocal instanceof Boolean) || !((Boolean) isLocal)) {
                throw new IOException("Amethyst did not classify " + username + " as a local offline account.");
            }

            Class<?> profileClass = Class.forName("net.kdt.pojavlaunch.PojavProfile");
            Object selectedName = profileClass.getMethod("getCurrentProfileName", Context.class)
                    .invoke(null, context);
            if (!username.equals(selectedName)) {
                throw new IOException("Amethyst did not select the local account " + username + ".");
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Amethyst could not validate the selected local account.", error);
        }
    }

    private static boolean hasClientJarInInheritanceChain(File minecraftRoot, String versionId) {
        File versionsRoot = new File(minecraftRoot, "versions");
        Set<String> visited = new java.util.HashSet<>();
        String currentId = versionId;
        for (int depth = 0; depth < 16; depth++) {
            if (!currentId.matches("[A-Za-z0-9._+\\-]{1,100}") || !visited.add(currentId)) return false;
            File directory = new File(versionsRoot, currentId);
            File metadataFile = new File(directory, currentId + ".json");
            JSONObject metadata;
            try {
                if (!metadataFile.isFile()) return false;
                metadata = new JSONObject(readText(metadataFile));
            } catch (Exception ignored) {
                return false;
            }

            File clientJar = new File(directory, currentId + ".jar");
            if (clientJar.isFile() && clientJar.length() > 0L) {
                JSONObject downloads = metadata.optJSONObject("downloads");
                JSONObject client = downloads == null ? null : downloads.optJSONObject("client");
                long expectedSize = client == null ? -1L : client.optLong("size", -1L);
                if (expectedSize <= 0L || clientJar.length() == expectedSize) return true;
            }
            String parent = metadata.optString("inheritsFrom").trim();
            if (parent.isEmpty()) return false;
            currentId = parent;
        }
        return false;
    }

    public static void launch(Context context, String versionId, float heapMb,
                              DetectedJavaRuntime runtime, String playerName, String instanceId,
                              String rendererId, String javaArgs, String controlLayout,
                              int argsMode, boolean sharedData) throws Exception {
        ensureDefaultControlMap(context);
        String safeVersion = versionId == null ? "" : versionId.trim();
        if (!safeVersion.matches("[A-Za-z0-9._+\\-]{1,100}")) {
            throw new IllegalArgumentException("Invalid Minecraft version identifier.");
        }
        if (runtime == null || !runtime.getName().matches("[A-Za-z0-9._+\\-]{1,80}")) {
            throw new IllegalArgumentException("Invalid Android runtime name.");
        }

        File dataParent = context.getFilesDir().getParentFile();
        File amethystRuntimeRoot = new File(dataParent == null ? context.getFilesDir() : dataParent, "runtimes");
        File runtimeHome = new File(amethystRuntimeRoot, runtime.getName());
        if (!runtimeHome.getCanonicalFile().equals(runtime.getHome().getCanonicalFile()) ||
                !new File(runtimeHome, "release").isFile() || !runtime.getJvmLibrary().isFile()) {
            throw new IOException("Java " + runtime.getMajorVersion() +
                    " is not installed in Amethyst's MultiRT folder. Return to VM Controller and install a compatible Android JRE.");
        }

        String safeInstanceId = instanceId == null ? "" : instanceId.trim();
        if (!safeInstanceId.matches("[A-Za-z0-9._+\\-]{1,100}")) {
            throw new IllegalArgumentException("Invalid game instance identifier.");
        }
        File minecraftRoot = AmethystStoragePaths.minecraftHome(context);
        File versionDirectory = new File(new File(minecraftRoot, "versions"), safeVersion);
        if (!new File(versionDirectory, safeVersion + ".json").isFile()) {
            throw new IOException("Minecraft " + safeVersion + " version metadata is missing.");
        }
        if (!hasClientJarInInheritanceChain(minecraftRoot, safeVersion)) {
            throw new IOException("Minecraft " + safeVersion +
                    " and its inherited vanilla parent do not contain a usable client JAR.");
        }

        File instancesRoot = new File(minecraftRoot, "instances").getCanonicalFile();
        File selectedInstanceRoot = new File(instancesRoot, safeInstanceId).getCanonicalFile();
        if (!selectedInstanceRoot.getPath().startsWith(instancesRoot.getPath() + File.separator)) {
            throw new IOException("Selected game instance points outside the instances folder.");
        }
        File gameDirectory = sharedData
                ? new File(minecraftRoot.getParentFile() == null ? minecraftRoot : minecraftRoot.getParentFile(), "shared_dir")
                : new File(selectedInstanceRoot, "game");
        ensureDirectory(gameDirectory);
        applyPerformanceProfile(context, safeInstanceId, gameDirectory, false);

        String safePlayer = sanitizePlayerName(playerName);
        String safeRenderer = optionalIdentifier(rendererId, "renderer identifier", 80);
        if (safeRenderer != null) {
            Class<?> toolsClass = Class.forName("net.kdt.pojavlaunch.Tools");
            Object compatible = toolsClass.getMethod("checkRendererCompatible", Context.class, String.class)
                    .invoke(null, context, safeRenderer);
            if (!(compatible instanceof Boolean) || !((Boolean) compatible)) {
                throw new IllegalArgumentException("Renderer " + safeRenderer + " is not available or incompatible.");
            }
        }
        if (argsMode < 0 || argsMode > 2) throw new IllegalArgumentException("Unsupported JVM argument merge mode.");
        String rawArgs = javaArgs == null || javaArgs.trim().isEmpty() ? null : javaArgs.trim();
        if (rawArgs != null && (rawArgs.length() > 8192 || rawArgs.indexOf('\u0000') >= 0)) {
            throw new IllegalArgumentException("Per-instance JVM arguments exceed the 8192-character limit or contain an invalid character.");
        }
        String defaultArgs = context.getSharedPreferences(context.getPackageName() + "_preferences",
                Context.MODE_PRIVATE).getString("javaArgs", "");
        if (defaultArgs == null) defaultArgs = "";
        defaultArgs = defaultArgs.trim();
        String safeJavaArgs;
        if (argsMode == 0) safeJavaArgs = rawArgs;
        else if (argsMode == 1) safeJavaArgs = combine(defaultArgs, rawArgs);
        else safeJavaArgs = combine(rawArgs, defaultArgs);

        String safeControl = optionalIdentifier(stripJson(controlLayout), "control layout name", 100);
        if (safeControl != null) {
            File controlFile = new File(new File(AmethystStoragePaths.gameHome(context), "controlmap"),
                    safeControl + ".json");
            if (!controlFile.isFile() || controlFile.length() <= 0L) {
                throw new IOException("Control layout " + safeControl +
                        " is not installed in Amethyst's controlmap folder.");
            }
        }

        int safeHeapMb = DevicePerformanceProfile.clampHeap(context, (int) heapMb);
        writeAmethystProfile(context, minecraftRoot, safeVersion, runtime.getName(), safePlayer,
                safeHeapMb, safeInstanceId, safeRenderer, safeJavaArgs, safeControl, sharedData);
        activateAmethystRuntime(context, runtime.getName(), runtime.getMajorVersion(), safeHeapMb);

        Intent intent = new Intent().setClassName(context, LAUNCHER_ACTIVITY)
                .putExtra("tsi_auto_launch", true);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * Applies a device-aware FPS preset once per game instance. The manual action
     * can force a re-apply; normal launches never keep overwriting player changes.
     */
    public static String applyPerformanceProfile(Context context, String instanceId,
                                                  boolean force) throws IOException {
        String safeInstanceId = validateInstanceId(instanceId);
        GameInstance selected = null;
        for (GameInstance candidate : new GameInstanceRepository(context).list()) {
            if (safeInstanceId.equals(candidate.getId())) {
                selected = candidate;
                break;
            }
        }
        if (selected == null) throw new IOException("Selected Minecraft instance no longer exists.");
        return applyPerformanceProfile(context, safeInstanceId, selected.getGameDirectory(), force);
    }

    private static String applyPerformanceProfile(Context context, String instanceId,
                                                   File selectedGameDirectory,
                                                   boolean force) throws IOException {
        String safeInstanceId = validateInstanceId(instanceId);
        if (selectedGameDirectory == null) {
            throw new IOException("Selected Minecraft instance has no game directory.");
        }

        DevicePerformanceProfileSnapshot device = DevicePerformanceProfile.detect(context);
        android.content.SharedPreferences preferences =
                context.getSharedPreferences("tsi_launcher_preferences", Context.MODE_PRIVATE);
        String preferenceKey = "fps_profile_" + safeInstanceId;
        String profileSignature = "fps-v1|" + DevicePerformanceProfile.profileKey(context);
        if (!force && profileSignature.equals(preferences.getString(preferenceKey, ""))) {
            return "FPS profile is already current for this device (" + device.getTierLabel() + ").";
        }

        File minecraftRoot = AmethystStoragePaths.minecraftHome(context).getCanonicalFile();
        File instancesRoot = new File(minecraftRoot, "instances").getCanonicalFile();
        File instanceRoot = new File(instancesRoot, safeInstanceId).getCanonicalFile();
        if (!instanceRoot.getPath().startsWith(instancesRoot.getPath() + File.separator)) {
            throw new IOException("Selected Minecraft instance points outside the instances folder.");
        }

        File gameDirectory = selectedGameDirectory.getCanonicalFile();
        File instanceGameDirectory = new File(instanceRoot, "game").getCanonicalFile();
        File sharedDataDirectory = new File(
                minecraftRoot.getParentFile() == null ? minecraftRoot : minecraftRoot.getParentFile(),
                "shared_dir").getCanonicalFile();
        if (!gameDirectory.equals(instanceGameDirectory)
                && !gameDirectory.equals(sharedDataDirectory)) {
            throw new IOException("Selected game folder is not owned by this Minecraft instance.");
        }
        ensureDirectory(gameDirectory);

        File optionsFile = new File(gameDirectory, "options.txt");
        String existingOptions = optionsFile.isFile() ? readText(optionsFile) : "";
        String optimizedOptions = MinecraftPerformanceOptions.merge(existingOptions, device);
        writeAtomically(optionsFile, optimizedOptions);

        if (!preferences.edit().putString(preferenceKey, profileSignature).commit()) {
            throw new IOException("Could not save the applied FPS profile.");
        }

        return "FPS profile applied: " + device.getTierLabel()
                + " • render distance " + device.getRenderDistance()
                + " • simulation distance " + device.getSimulationDistance()
                + " • FPS cap " + device.getMaxFps() + ".";
    }

    private static String validateInstanceId(String instanceId) {
        String safeInstanceId = instanceId == null ? "" : instanceId.trim();
        if (!safeInstanceId.matches("[A-Za-z0-9._+\\-]{1,100}")) {
            throw new IllegalArgumentException("Invalid game instance identifier for FPS tuning.");
        }
        return safeInstanceId;
    }

    private static void ensureDefaultControlMap(Context context) throws IOException {
        File gameRoot = AmethystStoragePaths.gameHome(context);
        File controlDirectory = new File(gameRoot, "controlmap");
        File destination = new File(controlDirectory, "default.json");
        if (destination.isFile() && destination.length() > 0L) return;
        ensureDirectory(controlDirectory);

        File temporary = new File(controlDirectory, "default.json.tmp");
        try (java.io.InputStream input = context.getAssets().open("default.json");
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            throw new IOException("Failed to initialize Amethyst's default touch controls.", error);
        }
        if (!temporary.isFile() || temporary.length() == 0L) {
            temporary.delete();
            throw new IOException("Amethyst's bundled default control layout is empty.");
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Could not replace the empty default control layout.");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not install Amethyst's default control layout.");
        }
    }

    private static void writeAmethystProfile(Context context, File minecraftRoot, String versionId,
            String runtimeName, String playerName, int heapMb, String instanceId,
            String rendererId, String javaArgs, String controlLayout, boolean sharedData) throws IOException {
        File profilesFile = new File(minecraftRoot, "launcher_profiles.json");
        JSONObject launcherProfiles;
        if (profilesFile.isFile()) {
            try {
                launcherProfiles = new JSONObject(readText(profilesFile));
            } catch (Exception error) {
                throw new IOException("Amethyst launcher_profiles.json is invalid; existing data was not overwritten.", error);
            }
        } else launcherProfiles = new JSONObject();

        JSONObject profiles = launcherProfiles.optJSONObject("profiles");
        if (profiles == null) profiles = new JSONObject();
        String profileKey = UUID.nameUUIDFromBytes(
                ("tsi|" + versionId + "|" + playerName + "|" + instanceId).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();

        final String encodedProfiles;
        try {
            JSONObject profile = new JSONObject()
                    .put("name", "TSI " + versionId + " (" +
                            instanceId.substring(0, Math.min(8, instanceId.length())) + ")")
                    .put("type", "custom")
                    .put("lastVersionId", versionId)
                    .put("gameDir", sharedData ? PROFILE_PREFIX + "shared_dir" :
                            PROFILE_PREFIX + ".minecraft/instances/" + instanceId + "/game")
                    .put("javaDir", PROFILE_PREFIX + runtimeName);

            if (rendererId != null) profile.put("pojavRendererName", rendererId);
            if (javaArgs != null) profile.put("javaArgs", javaArgs);
            if (controlLayout != null) {
                profile.put("controlFile", new File(new File(AmethystStoragePaths.gameHome(context), "controlmap"),
                        controlLayout + ".json").getAbsolutePath());
            }

            profiles.put(profileKey, profile);
            launcherProfiles.put("profiles", profiles);
            encodedProfiles = launcherProfiles.toString(2);
        } catch (org.json.JSONException error) {
            throw new IOException("Could not encode the Amethyst game profile.", error);
        }
        writeAtomically(profilesFile, encodedProfiles);

        if (!selectedAccountIsMicrosoft(context)) addOfflineAccount(context, playerName);

        boolean saved = context.getSharedPreferences(context.getPackageName() + "_preferences",
                Context.MODE_PRIVATE).edit()
                .putString("currentProfile", profileKey)
                .putString("defaultRuntime", runtimeName)
                .putInt("allocation", heapMb)
                .commit();
        if (!saved) throw new IOException("Could not persist Amethyst's selected runtime and profile preferences.");
    }

    private static void activateAmethystRuntime(Context context, String runtimeName,
                                                int expectedMajor, int heapMb) throws IOException {
        try {
            File dataParent = context.getFilesDir().getParentFile();
            File runtimeRoot = new File(dataParent == null ? context.getFilesDir() : dataParent, "runtimes");
            File expectedHome = new File(runtimeRoot, runtimeName);
            Class<?> toolsClass = Class.forName("net.kdt.pojavlaunch.Tools");
            Object configured = toolsClass.getField("MULTIRT_HOME").get(null);
            String configuredRoot = configured == null ? null : configured.toString();
            if (configuredRoot != null && !configuredRoot.trim().isEmpty() &&
                    !new File(configuredRoot).getCanonicalFile().equals(runtimeRoot.getCanonicalFile())) {
                throw new IOException("Amethyst MultiRT path does not match TSI's installed runtime folder.");
            }

            Class<?> multiRtClass = Class.forName("net.kdt.pojavlaunch.multirt.MultiRTUtils");
            Object selectedRuntime = multiRtClass.getMethod("forceReread", String.class)
                    .invoke(null, runtimeName);
            if (selectedRuntime == null) throw new IOException("Amethyst could not read the selected Java runtime.");

            int actualMajor = selectedRuntime.getClass().getField("javaVersion").getInt(selectedRuntime);
            Object versionValue = selectedRuntime.getClass().getField("versionString").get(selectedRuntime);
            String versionString = versionValue == null ? null : versionValue.toString();
            if (actualMajor != expectedMajor || versionString == null || versionString.trim().isEmpty() ||
                    !new File(expectedHome, "release").isFile()) {
                throw new IOException("Amethyst MultiRT cannot recognize Java " + expectedMajor + " in " +
                        expectedHome.getAbsolutePath() + ". Runtime metadata is invalid or incomplete.");
            }

            Class<?> preferencesClass = Class.forName("net.kdt.pojavlaunch.prefs.LauncherPreferences");
            preferencesClass.getField("PREF_DEFAULT_RUNTIME").set(null, runtimeName);
            preferencesClass.getField("PREF_RAM_ALLOCATION").setInt(null, heapMb);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Could not activate Java " + expectedMajor + " in Amethyst's runtime selector.", error);
        }
    }

    private static String sanitizePlayerName(String input) {
        StringBuilder result = new StringBuilder();
        if (input != null) {
            for (int i = 0; i < input.length() && result.length() < 16; i++) {
                char c = input.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                        (c >= '0' && c <= '9') || c == '_') result.append(c);
            }
        }
        return result.length() >= 3 ? result.toString() : "Steve";
    }

    private static String optionalIdentifier(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > maxLength || !trimmed.matches("[A-Za-z0-9._+-]+") ||
                ".".equals(trimmed) || "..".equals(trimmed)) {
            throw new IllegalArgumentException("Invalid " + label + ".");
        }
        return trimmed;
    }

    private static String stripJson(String value) {
        return value != null && value.endsWith(".json") ? value.substring(0, value.length() - 5) : value;
    }

    private static String combine(String first, String second) {
        String a = first == null ? "" : first.trim();
        String b = second == null ? "" : second.trim();
        String combined = (a + " " + b).trim();
        return combined.isEmpty() ? null : combined;
    }

    private static File dataRoot(Context context) {
        File parent = context.getFilesDir().getParentFile();
        return parent == null ? context.getFilesDir() : parent;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create " + directory);
        if (!directory.isDirectory()) throw new IOException("Not a directory: " + directory);
    }

    private static void writeAtomically(File destination, String content) throws IOException {
        File parent = destination.getParentFile();
        if (parent == null) throw new IOException("Destination has no parent directory.");
        ensureDirectory(parent);
        File temporary = new File(parent, destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not atomically update destination file.");
        }
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(delimiter);
            result.append(value);
        }
        return result.toString();
    }
}
