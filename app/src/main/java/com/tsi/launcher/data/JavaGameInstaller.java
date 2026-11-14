package com.tsi.launcher.data;

import android.content.Context;
import android.os.Build;
import com.tsi.launcher.storage.VersionDirectoryManager;
import com.tsi.launcher.storage.VersionPaths;
import com.tsi.launcher.nativecore.NativeBridge;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Installs the Java Edition data files described by Mojang's version JSON.
 * Run install() from an IO executor. The engine provides Android-native LWJGL
 * and renderer libraries; desktop native classifier downloads are excluded.
 */
public final class JavaGameInstaller {
    public interface ProgressListener {
        void onProgress(String message);
    }

    private final Context context;
    private final VersionDirectoryManager directoryManager;

    public JavaGameInstaller(Context context, VersionDirectoryManager directoryManager) {
        this.context = context.getApplicationContext();
        this.directoryManager = directoryManager;
    }

    public JavaGameInstallResult install(MinecraftVersion version, String instanceId,
                                         ProgressListener progress) throws Exception {
        final ProgressListener listener = progress == null ? message -> { } : progress;
        VersionPaths paths = directoryManager.ensureVersionLayout(version.getId(), instanceId);
        listener.onProgress("Fetching Minecraft " + version.getId() + " version metadata…");

        String metadataText = downloadText(version.getUrl());
        JSONObject metadata = new JSONObject(metadataText);
        if (!version.getId().equals(metadata.optString("id"))) {
            throw new IOException("Mojang metadata ID does not match " + version.getId());
        }

        Integer requiredJava = null;
        JSONObject javaVersion = metadata.optJSONObject("javaVersion");
        if (javaVersion != null) {
            int major = javaVersion.optInt("majorVersion", 0);
            if (major >= 8 && major <= 99) requiredJava = major;
        }
        if (requiredJava == null) {
            requiredJava = MinecraftJavaCompatibility.fallbackJavaMajorFor(
                    version.getId(), metadata.has("minecraftArguments"));
        }

        ensureAsmCoreLibrary(metadata);
        File metadataFile = new File(paths.getVersionDirectory(), version.getId() + ".json");
        writeAtomically(metadataFile, metadata.toString(2).getBytes(StandardCharsets.UTF_8));

        JSONObject client = metadata.optJSONObject("downloads") == null ? null
                : metadata.optJSONObject("downloads").optJSONObject("client");
        if (client == null) {
            throw new IOException("This version has no downloadable client JAR in its metadata");
        }
        String clientUrl = client.optString("url");
        String clientSha1 = client.optString("sha1");
        if (!clientSha1.matches("[a-fA-F0-9]{40}")) clientSha1 = null;
        long clientSize = client.optLong("size", -1L);
        File clientJar = new File(paths.getVersionDirectory(), version.getId() + ".jar");
        if (!isVerifiedFile(clientJar, clientSha1, clientSize)) {
            listener.onProgress("Downloading Minecraft " + version.getId() + " client JAR…");
            downloadVerified(clientUrl, clientJar, clientSha1, clientSize);
        } else {
            listener.onProgress("Client JAR already verified.");
        }

        JSONArray libraries = metadata.optJSONArray("libraries");
        int totalLibraries = 0;
        int installedLibraries = 0;
        if (libraries != null) {
            for (int index = 0; index < libraries.length(); index++) {
                JSONObject library = libraries.optJSONObject(index);
                if (library == null || !libraryAllowed(library)) continue;

                JSONObject downloads = library.optJSONObject("downloads");
                JSONObject artifact = downloads == null ? null : downloads.optJSONObject("artifact");
                if (library.optString("name").startsWith("net.java.dev.jna:jna:") &&
                        classExists("net.kdt.pojavlaunch.tasks.NativesExtractor")) {
                    String aarPath = jnaNativeArchivePath(library, artifact);
                    File aarTarget = safeChild(paths.getSharedLibrariesDirectory(), aarPath);
                    if (!aarTarget.isFile()) {
                        listener.onProgress("Downloading Android JNA native library…");
                        downloadVerified("https://repo1.maven.org/maven2/" + aarPath,
                                aarTarget, null, -1L);
                    }
                    extractAmethystNativeArchive(version.getId(), aarTarget);
                }

                MinecraftLibraryResolver.Artifact resolved = MinecraftLibraryResolver.resolve(library);
                if (resolved == null) continue;
                File target = safeChild(paths.getSharedLibrariesDirectory(), resolved.getPath());
                totalLibraries++;
                if (!isVerifiedFile(target, resolved.getSha1(), resolved.getSize())) {
                    listener.onProgress("Downloading library " + (installedLibraries + 1) +
                            "/" + totalLibraries + "…");
                    downloadVerified(resolved.getUrl(), target, resolved.getSha1(), resolved.getSize());
                }
                installedLibraries++;
            }
        }

        String assetIndexId = "";
        int totalAssets = 0;
        JSONObject assetIndex = metadata.optJSONObject("assetIndex");
        if (assetIndex != null) {
            assetIndexId = assetIndex.optString("id");
            if (assetIndexId.trim().isEmpty()) throw new IOException("Asset index metadata has no ID");
            String assetIndexUrl = assetIndex.optString("url");
            if (assetIndexUrl.trim().isEmpty()) {
                throw new IOException("Asset index metadata has no download URL");
            }

            File assetIndexesDirectory = new File(paths.getSharedAssetsDirectory(), "indexes");
            ensureDirectory(assetIndexesDirectory);
            File assetIndexFile = new File(assetIndexesDirectory, assetIndexId + ".json");
            String assetIndexSha = assetIndex.optString("sha1");
            if (!assetIndexSha.matches("[a-fA-F0-9]{40}")) assetIndexSha = null;
            long assetIndexSize = assetIndex.optLong("size", -1L);
            if (!isVerifiedFile(assetIndexFile, assetIndexSha, assetIndexSize)) {
                listener.onProgress("Downloading asset index " + assetIndexId + "…");
                downloadVerified(assetIndexUrl, assetIndexFile, assetIndexSha, assetIndexSize);
            }

            JSONObject assetIndexJson = new JSONObject(readText(assetIndexFile));
            JSONObject objects = assetIndexJson.optJSONObject("objects");
            if (objects == null) throw new IOException("Asset index has no objects map");
            totalAssets = objects.length();

            List<MinecraftAssetEntry> assetEntries = new ArrayList<>();
            java.util.Iterator<String> names = objects.keys();
            while (names.hasNext()) {
                String name = names.next();
                JSONObject descriptor = objects.optJSONObject(name);
                String hash = descriptor == null ? "" :
                        descriptor.optString("hash").toLowerCase(Locale.ROOT);
                if (!hash.matches("[a-f0-9]{40}")) {
                    throw new IOException("Invalid asset hash in asset index: " + name);
                }
                assetEntries.add(new MinecraftAssetEntry(name, hash));
            }

            File assetObjectsDirectory = new File(paths.getSharedAssetsDirectory(), "objects");
            ensureDirectory(assetObjectsDirectory);
            List<String> uniqueHashes = MinecraftAssetLayout.uniqueObjectHashes(assetEntries);
            int uniqueCount = uniqueHashes.size();
            AtomicInteger finished = new AtomicInteger(0);
            listener.onProgress("Checking " + totalAssets + " Minecraft assets (" +
                    uniqueCount + " unique objects)…");

            ExecutorService executor = Executors.newFixedThreadPool(5);
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (final String hash : uniqueHashes) {
                    futures.add(executor.submit(() -> {
                        String prefix = hash.substring(0, 2);
                        File folder = new File(assetObjectsDirectory, prefix);
                        ensureDirectory(folder);
                        File destination = new File(folder, hash);
                        if (!isVerifiedFile(destination, hash, -1L)) {
                            String assetUrl = "https://resources.download.minecraft.net/" + prefix + "/" + hash;
                            downloadVerified(assetUrl, destination, hash, -1L);
                        }
                        int done = finished.incrementAndGet();
                        if (done % 50 == 0 || done == uniqueCount) {
                            listener.onProgress("Minecraft asset objects: " + done + " / " + uniqueCount);
                        }
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (ExecutionException error) {
                        Throwable cause = error.getCause();
                        if (cause instanceof Exception) throw (Exception) cause;
                        throw new IOException("Minecraft asset download failed.", cause);
                    }
                }
            } finally {
                executor.shutdownNow();
            }

            boolean mapToResources = assetIndexJson.optBoolean("map_to_resources", false);
            boolean virtualAssets = assetIndexJson.optBoolean("virtual", false);
            if (mapToResources || virtualAssets) {
                List<MinecraftLegacyAsset> legacyEntries = new ArrayList<>();
                java.util.Iterator<String> assetNames = objects.keys();
                while (assetNames.hasNext()) {
                    String name = assetNames.next();
                    JSONObject descriptor = objects.optJSONObject(name);
                    if (descriptor == null) throw new IOException("Invalid legacy asset entry: " + name);
                    legacyEntries.add(new MinecraftLegacyAsset(name,
                            descriptor.optString("hash"), descriptor.optLong("size", -1L)));
                }
                int materialized = MinecraftAssetLayout.materialize(
                        virtualAssets, mapToResources, legacyEntries,
                        paths.getSharedAssetsDirectory(),
                        new File(paths.getSharedAssetsDirectory().getParentFile() == null
                                ? paths.getSharedAssetsDirectory()
                                : paths.getSharedAssetsDirectory().getParentFile(), "resources"));
                if (materialized > 0) {
                    listener.onProgress("Prepared " + materialized +
                            " legacy asset paths for older Minecraft versions.");
                }
            }
        }

        return new JavaGameInstallResult(version.getId(), clientJar, metadataFile,
                installedLibraries, totalAssets, requiredJava, assetIndexId);
    }

    private void ensureAsmCoreLibrary(JSONObject metadata) throws Exception {
        JSONArray libraries = metadata.optJSONArray("libraries");
        if (libraries == null) {
            libraries = new JSONArray();
            metadata.put("libraries", libraries);
        }
        boolean hasAsmCore = false;
        boolean hasAsmUber = false;
        for (int index = 0; index < libraries.length(); index++) {
            String name = libraries.optJSONObject(index) == null ? "" :
                    libraries.optJSONObject(index).optString("name");
            if (name.startsWith("org.ow2.asm:asm:")) hasAsmCore = true;
            if (name.startsWith("org.ow2.asm:asm-all:")) hasAsmUber = true;
        }
        if (hasAsmCore || hasAsmUber) return;
        String path = "org/ow2/asm/asm/9.7.1/asm-9.7.1.jar";
        JSONObject artifact = new JSONObject()
                .put("path", path)
                .put("size", 126093)
                .put("url", "https://repo.maven.apache.org/maven2/" + path);
        JSONObject downloads = new JSONObject().put("artifact", artifact);
        libraries.put(new JSONObject()
                .put("name", "org.ow2.asm:asm:9.7.1")
                .put("downloads", downloads));
    }

    private String jnaNativeArchivePath(JSONObject library, JSONObject artifact) throws IOException {
        String artifactPath = artifact == null ? "" : artifact.optString("path");
        if (!artifactPath.trim().isEmpty()) {
            int dot = artifactPath.lastIndexOf('.');
            String withoutExtension = dot >= 0 ? artifactPath.substring(0, dot) : artifactPath;
            return withoutExtension + ".aar";
        }
        String[] coordinates = library.optString("name").split(":");
        if (coordinates.length < 3) throw new IOException("Invalid JNA Maven coordinates");
        String groupPath = coordinates[0].replace('.', '/');
        String artifactName = coordinates[1];
        String versionName = coordinates[2];
        String classifier = coordinates.length > 3 && !coordinates[3].trim().isEmpty()
                ? "-" + coordinates[3] : "";
        return groupPath + "/" + artifactName + "/" + versionName + "/" +
                artifactName + "-" + versionName + classifier + ".aar";
    }

    private void extractAmethystNativeArchive(String versionId, File archive) throws IOException {
        try {
            Class<?> extractorClass = Class.forName("net.kdt.pojavlaunch.tasks.NativesExtractor");
            File destination = new File(context.getCacheDir(), "natives/" + versionId);
            ensureDirectory(destination);
            Object extractor = extractorClass.getConstructor(File.class).newInstance(destination);
            extractorClass.getMethod("extractFromAar", File.class).invoke(extractor, archive);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable target = error.getTargetException() == null ? error : error.getTargetException();
            throw new IOException("Amethyst could not extract Android JNA native files.", target);
        } catch (Exception error) {
            throw new IOException("Amethyst Android native extractor is unavailable.", error);
        }
    }

    private boolean libraryAllowed(JSONObject library) {
        JSONArray rules = library.optJSONArray("rules");
        if (rules == null) return true;
        boolean allowed = false;
        String deviceArch = Build.SUPPORTED_ABIS.length == 0 ? "" :
                Build.SUPPORTED_ABIS[0].toLowerCase(Locale.ROOT);
        String architecture;
        if (deviceArch.contains("arm64") || deviceArch.contains("aarch64")) architecture = "aarch64";
        else if (deviceArch.contains("armeabi") || deviceArch.equals("arm")) architecture = "arm";
        else if (deviceArch.contains("x86_64") || deviceArch.contains("amd64")) architecture = "x86_64";
        else if (deviceArch.equals("x86") || deviceArch.contains("i386") || deviceArch.contains("i686")) architecture = "x86";
        else architecture = "unsupported";

        for (int index = 0; index < rules.length(); index++) {
            JSONObject rule = rules.optJSONObject(index);
            if (rule == null) continue;
            JSONObject os = rule.optJSONObject("os");
            boolean osMatches = true;
            if (os != null) {
                String name = os.optString("name");
                boolean nameMatches = name.trim().isEmpty() || "linux".equals(name);
                String archPattern = os.optString("arch");
                boolean archMatches = archPattern.trim().isEmpty() ||
                        regexMatches(archPattern, architecture);
                String versionPattern = os.optString("version");
                String osVersion = System.getProperty("os.version", "");
                boolean versionMatches = versionPattern.trim().isEmpty() ||
                        regexMatches(versionPattern, osVersion);
                osMatches = nameMatches && archMatches && versionMatches;
            }
            JSONObject features = rule.optJSONObject("features");
            boolean featuresMatch = features == null || featureRulesMatch(features);
            if (osMatches && featuresMatch) {
                allowed = "allow".equals(rule.optString("action"));
            }
        }
        return allowed;
    }

    private boolean featureRulesMatch(JSONObject features) {
        java.util.Iterator<String> keys = features.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            boolean expected = features.optBoolean(key, false);
            // The TSI profile does not request demo, quick-play or custom-resolution features.
            if (expected) return false;
        }
        return true;
    }

    private boolean classExists(String className) {
        try {
            Class.forName(className, false, context.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean regexMatches(String pattern, String value) {
        try { return java.util.regex.Pattern.compile(pattern).matcher(value).find(); }
        catch (Exception ignored) { return false; }
    }

    private String downloadText(String rawUrl) throws Exception {
        HttpURLConnection connection = openConnection(rawUrl);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("Metadata request failed: HTTP " + code);
            validateUrl(connection.getURL().toURI().toString());
            return readStream(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private void downloadVerified(String rawUrl, File target,
                                  String expectedSha1, long expectedSize) throws Exception {

        NativeBridge.downloadVerified(rawUrl, target, expectedSha1, expectedSize);

    }

    private boolean isVerifiedFile(File file, String sha1, long expectedSize) throws IOException {

        return NativeBridge.isVerifiedFile(file, sha1, expectedSize);

    }

    private HttpURLConnection openConnection(String rawUrl) throws Exception {
        validateUrl(rawUrl);
        HttpURLConnection connection = (HttpURLConnection) URI.create(rawUrl).toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(90_000);
        connection.setRequestProperty("User-Agent", "TSI-Launcher/0.2");
        return connection;
    }

    private void validateUrl(String rawUrl) throws Exception {
        URI uri = URI.create(rawUrl);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean allowed = host.equals("mojang.com") || host.endsWith(".mojang.com") ||
                host.equals("minecraft.net") || host.endsWith(".minecraft.net") ||
                host.equals("libraries.minecraft.net") || host.equals("repo.maven.apache.org") ||
                host.equals("repo1.maven.org") || host.equals("resources.download.minecraft.net");
        if (!"https".equals(scheme) || !allowed) {
            throw new IllegalArgumentException("Refusing non-HTTPS or non-Minecraft download URL: " + host);
        }
    }

    private File safeChild(File parent, String relativePath) throws IOException {
        if (relativePath == null || relativePath.startsWith("/") || relativePath.indexOf('\\') >= 0) {
            throw new IOException("Invalid library path");
        }
        File root = parent.getCanonicalFile();
        File target = new File(parent, relativePath).getCanonicalFile();
        if (!target.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("Library path escapes its installation folder");
        }
        return target;
    }

    private void writeAtomically(File target, byte[] bytes) throws IOException {
        File parent = target.getParentFile();
        if (parent == null) throw new IOException("Metadata target has no parent");
        ensureDirectory(parent);
        File temporary = new File(parent, target.getName() + ".part");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        replaceAtomically(temporary, target);
    }

    private void replaceAtomically(File source, File target) throws IOException {
        File parent = target.getParentFile();
        File backup = new File(parent, target.getName() + ".tsi-backup");
        if (backup.exists() && !backup.delete()) throw new IOException("Cannot clear stale backup: " + backup);
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) throw new IOException("Cannot stage existing file: " + target);
        if (!source.renameTo(target)) {
            if (hadTarget && !backup.renameTo(target)) {
                throw new IOException("Replacement failed; original remains at " + backup);
            }
            throw new IOException("Cannot install file: " + target);
        }
        if (backup.exists()) backup.delete();
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create directory: " + directory.getAbsolutePath());
        }
        if (!directory.isDirectory()) throw new IOException("Not a directory: " + directory.getAbsolutePath());
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return readStream(input);
        }
    }

    private static String readStream(java.io.InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            return result.toString();
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
