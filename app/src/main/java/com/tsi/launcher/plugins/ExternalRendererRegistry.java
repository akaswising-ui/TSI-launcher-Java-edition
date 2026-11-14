package com.tsi.launcher.plugins;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy FCL / Zalith renderer APK discovery for the pinned Amethyst renderer bridge.
 * Plugins must already be installed as separate Android apps. Only the active
 * ABI's nativeLibraryDir is used; no code or library is extracted from arbitrary ZIPs.
 */
public final class ExternalRendererRegistry {
    private static final String TAG = "TSI-RendererPlugins";
    private static final String ID_PREFIX = "tsi.fcl.";
    private static final Map<String, RendererPluginInfo> CACHE = new ConcurrentHashMap<>();

    private ExternalRendererRegistry() { }

    public static List<String> getRendererIds(Context context) {
        refresh(context);
        List<String> ids = new ArrayList<>(CACHE.keySet());
        Collections.sort(ids);
        return ids;
    }

    public static String getRendererDisplayName(Context context, String rendererId) {
        RendererPluginInfo info = get(context, rendererId);
        return info == null ? rendererId : info.displayName;
    }

    public static boolean isExternalRenderer(Context context, String rendererId) {
        return rendererId != null && rendererId.startsWith(ID_PREFIX) && get(context, rendererId) != null;
    }

    public static RendererPluginInfo get(Context context, String rendererId) {
        if (rendererId == null || !rendererId.startsWith(ID_PREFIX)) return null;
        refresh(context);
        return CACHE.get(rendererId);
    }

    public static RendererPluginInfo prepare(Context context, String rendererId) throws Exception {
        RendererPluginInfo info = get(context, rendererId);
        if (info == null) throw new IllegalArgumentException("External renderer plugin is no longer installed or compatible.");
        // Revalidate the package at the point of use. A removed or updated plugin
        // must not leave a stale path in the native renderer launch.
        ApplicationInfo current = getApplicationInfo(context, info.packageName);
        if (current == null || current.nativeLibraryDir == null) {
            throw new IllegalStateException("Renderer plugin app is unavailable.");
        }
        File nativeRoot = new File(current.nativeLibraryDir).getCanonicalFile();
        File library = new File(nativeRoot, info.glLibraryName).getCanonicalFile();
        if (!nativeRoot.equals(library.getParentFile()) || !library.isFile()) {
            throw new IllegalStateException("Renderer native library is missing for the active Android ABI.");
        }
        List<String> dlopen = new ArrayList<>();
        for (String name : info.dlopenLibraries) {
            File extra = new File(nativeRoot, name).getCanonicalFile();
            if (!nativeRoot.equals(extra.getParentFile()) || !extra.isFile()) {
                throw new IllegalStateException("Renderer dependency is missing: " + name);
            }
            dlopen.add(extra.getAbsolutePath());
        }
        return new RendererPluginInfo(info.packageName, info.rendererId, info.displayName,
                info.minMinecraftVersion, info.maxMinecraftVersion, info.glLibraryName,
                info.eglLibraryName, nativeRoot.getAbsolutePath(), info.environment, dlopen);
    }

    public static void loadDependencies(RendererPluginInfo plugin) {
        for (String library : plugin.dlopenLibraries) {
            try {
                System.load(library);
            } catch (LinkageError error) {
                throw new IllegalStateException("Could not load renderer dependency " +
                        new File(library).getName() + ": " + error.getMessage(), error);
            }
        }
    }

    private static void refresh(Context context) {
        CACHE.clear();
        PackageManager manager = context.getPackageManager();
        List<ApplicationInfo> applications;
        try {
            // QUERY_ALL_PACKAGES is declared because FCL renderer packages are
            // arbitrary installed APKs. On restricted stores this permission
            // needs policy approval; sideload builds can use it directly.
            applications = manager.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot enumerate installed renderer plugins", error);
            return;
        }

        for (ApplicationInfo app : applications) {
            if (app == null || app.packageName == null || app.nativeLibraryDir == null) continue;
            Bundle metadata = app.metaData;
            if (metadata == null) continue;
            if (!metadata.getBoolean("fclPlugin", false) &&
                    !metadata.getBoolean("zalithRendererPlugin", false)) continue;
            try {
                RendererPluginInfo plugin = parse(context, app, metadata);
                if (plugin != null) CACHE.put(plugin.rendererId, plugin);
            } catch (Exception error) {
                Log.w(TAG, "Skipped incompatible renderer APK " + app.packageName, error);
            }
        }
    }

    private static RendererPluginInfo parse(Context context, ApplicationInfo app, Bundle metadata) throws Exception {
        String declaration = metadata.getString("renderer");
        String display = metadata.getString("des");
        String envDeclaration = metadata.getString("pojavEnv");
        if (declaration == null || display == null || display.trim().isEmpty()) return null;
        String[] renderer = declaration.split(":", -1);
        if (renderer.length < 2 || renderer.length > 3) return null;

        String glLibrary = safeLibraryName(renderer[1]);
        String eglLibrary = renderer.length > 2 && !renderer[2].trim().isEmpty()
                ? safeLibraryName(renderer[2]) : "libEGL.so";
        File nativeRoot = new File(app.nativeLibraryDir).getCanonicalFile();
        File mainLibrary = new File(nativeRoot, glLibrary).getCanonicalFile();
        if (!nativeRoot.equals(mainLibrary.getParentFile()) || !mainLibrary.isFile()) return null;

        String packageName = app.packageName;
        if (!packageName.matches("[A-Za-z0-9_.]{1,68}")) return null;
        String id = ID_PREFIX + packageName;
        String min = readVersion(metadata, "minMCVer");
        String max = readVersion(metadata, "maxMCVer");

        Map<String, String> environment = new LinkedHashMap<>();
        List<String> dlopen = new ArrayList<>();
        if (envDeclaration != null) {
            for (String item : envDeclaration.split(":")) {
                int equals = item.indexOf('=');
                if (equals <= 0) continue;
                String key = item.substring(0, equals).trim();
                String value = item.substring(equals + 1).trim();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") ||
                        value.length() > 1024 || value.contains("\n") || value.contains("\r")) continue;
                if ("DLOPEN".equals(key)) {
                    for (String extra : value.split(",")) {
                        String safe = safeLibraryName(extra.trim());
                        if (!dlopen.contains(safe)) dlopen.add(safe);
                    }
                } else if ("LIB_MESA_NAME".equals(key) || "MESA_LIBRARY".equals(key)) {
                    String safe = safeLibraryName(value);
                    environment.put(key, new File(nativeRoot, safe).getCanonicalPath());
                } else if (!"LD_LIBRARY_PATH".equals(key) && !"LD_PRELOAD".equals(key)
                        && !"PATH".equals(key) && !"HOME".equals(key)) {
                    environment.put(key, value);
                }
            }
        }

        // Hyper/FCL conventions permit the EGL library to be supplied either by
        // the plugin package or by the system. Don't reject a system EGL name.
        if (!eglLibrary.startsWith("/")) {
            File egl = new File(nativeRoot, eglLibrary).getCanonicalFile();
            if (egl.getParentFile().equals(nativeRoot) && egl.isFile()) {
                eglLibrary = egl.getAbsolutePath();
            }
        }
        return new RendererPluginInfo(packageName, id, display.trim(), min, max,
                glLibrary, eglLibrary, nativeRoot.getAbsolutePath(), environment, dlopen);
    }

    private static String safeLibraryName(String value) throws Exception {
        if (value == null) throw new IllegalArgumentException("Renderer library name is missing.");
        String name = value.trim();
        if (!name.matches("[A-Za-z0-9_.+-]{1,120}\\.so") ||
                name.equals(".so") || name.contains("..")) {
            throw new IllegalArgumentException("Invalid renderer .so library name.");
        }
        return name;
    }

    private static String readVersion(Bundle metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof String) return (String) value;
        if (value instanceof Number) return String.valueOf(value);
        return null;
    }

    private static ApplicationInfo getApplicationInfo(Context context, String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return context.getPackageManager().getApplicationInfo(packageName,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA));
            }
            //noinspection deprecation
            return context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    public static final class RendererPluginInfo {
        public final String packageName;
        public final String rendererId;
        public final String displayName;
        public final String minMinecraftVersion;
        public final String maxMinecraftVersion;
        public final String glLibraryName;
        public final String eglLibraryName;
        public final String nativeLibraryDirectory;
        public final Map<String, String> environment;
        public final List<String> dlopenLibraries;

        RendererPluginInfo(String packageName, String rendererId, String displayName,
                           String minMinecraftVersion, String maxMinecraftVersion,
                           String glLibraryName, String eglLibraryName, String nativeLibraryDirectory,
                           Map<String, String> environment, List<String> dlopenLibraries) {
            this.packageName = packageName;
            this.rendererId = rendererId;
            this.displayName = displayName;
            this.minMinecraftVersion = minMinecraftVersion;
            this.maxMinecraftVersion = maxMinecraftVersion;
            this.glLibraryName = glLibraryName;
            this.eglLibraryName = eglLibraryName;
            this.nativeLibraryDirectory = nativeLibraryDirectory;
            this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
            this.dlopenLibraries = Collections.unmodifiableList(new ArrayList<>(dlopenLibraries));
        }

        public String getGraphicsLibraryAbsolutePath() {
            return new File(nativeLibraryDirectory, glLibraryName).getAbsolutePath();
        }

        public String getLibraryPath() {
            return nativeLibraryDirectory;
        }
    }
}
