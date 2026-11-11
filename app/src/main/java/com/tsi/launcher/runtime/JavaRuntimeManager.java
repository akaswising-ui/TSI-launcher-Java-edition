package com.tsi.launcher.runtime;

import android.content.Context;
import android.os.Build;
import com.tsi.launcher.data.MinecraftAssetLayout;
import com.tsi.launcher.data.MinecraftJavaCompatibility;
import com.tsi.launcher.data.MinecraftLibraryResolver;
import com.tsi.launcher.storage.AmethystStoragePaths;
import com.tsi.launcher.storage.VersionDirectoryManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Inspects Minecraft metadata, installed assets, Android JREs and the game engine. */
public final class JavaRuntimeManager {
    private final Context context;
    private final VersionDirectoryManager directoryManager;
    private final File root;
    private final File amethystRuntimeRoot;
    private final File privateRuntimeRoot;
    private final File legacyRuntimeRoot;

    public JavaRuntimeManager(Context context) {
        this.context = context.getApplicationContext();
        directoryManager = new VersionDirectoryManager(this.context);
        root = directoryManager.root();
        File dataParent = this.context.getFilesDir().getParentFile();
        amethystRuntimeRoot = new File(dataParent == null ? this.context.getFilesDir() : dataParent, "runtimes");
        privateRuntimeRoot = new File(this.context.getNoBackupFilesDir(), "tsi/.minecraft/runtimes");
        legacyRuntimeRoot = new File(root, "runtimes");
    }

    /** Call from an IO executor because this checks files and SHA-1 hashes. */
    public VersionVmInspection inspect(String versionId, String runtimePreference) throws IOException {
        return inspect(versionId, runtimePreference, versionId);
    }

    public VersionVmInspection inspect(String versionId, String runtimePreference, String instanceId)
            throws IOException {
        String safeId = safeSegment(versionId);
        directoryManager.migrateLegacyStorage();
        directoryManager.ensureVersionLayout(safeId, instanceId);
        File versionFolder = new File(new File(root, "versions"), safeId);
        File metadataFile = new File(versionFolder, safeId + ".json");
        File librariesRoot = new File(root, "libraries");
        File assetsRoot = new File(root, "assets");

        Integer requiredMajor = null;
        boolean clientJarValid = false;
        int libraryCount = 0;
        int installedLibraryCount = 0;
        boolean assetIndexDeclared = false;
        String assetIndexId = null;
        boolean assetIndexExists = false;
        int assetObjectCount = 0;
        int assetObjectTotal = 0;
        boolean legacyAssetFilesReady = true;

        if (metadataFile.isFile()) {
            try {
                List<VersionMetadataEntry> chain = readMetadataChain(safeId);
                for (VersionMetadataEntry entry : chain) {
                    JSONObject java = entry.metadata.optJSONObject("javaVersion");
                    int major = java == null ? 0 : java.optInt("majorVersion", 0);
                    if (major >= 8 && major <= 99) {
                        requiredMajor = major;
                        break;
                    }
                }
                if (requiredMajor == null) {
                    for (VersionMetadataEntry entry : chain) {
                        Integer fallback = MinecraftJavaCompatibility.fallbackJavaMajorFor(
                                entry.id, entry.metadata.has("minecraftArguments"));
                        if (fallback != null) {
                            requiredMajor = fallback;
                            break;
                        }
                    }
                }

                VersionMetadataEntry clientOwner = null;
                for (VersionMetadataEntry entry : chain) {
                    JSONObject downloads = entry.metadata.optJSONObject("downloads");
                    if (downloads != null && downloads.optJSONObject("client") != null) {
                        clientOwner = entry;
                        break;
                    }
                }
                if (clientOwner != null) {
                    JSONObject download = clientOwner.metadata.getJSONObject("downloads").getJSONObject("client");
                    long expectedSize = download.optLong("size", -1L);
                    File clientJar = new File(new File(new File(root, "versions"), clientOwner.id),
                            clientOwner.id + ".jar");
                    clientJarValid = clientJar.isFile() &&
                            (expectedSize <= 0L || clientJar.length() == expectedSize);
                }

                Map<String, LibraryEntry> libraryArtifacts = new LinkedHashMap<>();
                for (VersionMetadataEntry entry : chain) {
                    JSONArray libraries = entry.metadata.optJSONArray("libraries");
                    if (libraries == null) continue;
                    for (int index = 0; index < libraries.length(); index++) {
                        JSONObject library = libraries.optJSONObject(index);
                        if (library == null || !libraryAllowed(library)) continue;
                        MinecraftLibraryResolver.Artifact artifact = MinecraftLibraryResolver.resolve(library);
                        if (artifact == null) continue;
                        String relativePath = artifact.getPath();
                        if (relativePath.trim().isEmpty() || relativePath.startsWith("/") ||
                                relativePath.indexOf('\\') >= 0) continue;
                        File libraryRoot = librariesRoot.getCanonicalFile();
                        File local = new File(librariesRoot, relativePath).getCanonicalFile();
                        if (!local.getPath().startsWith(libraryRoot.getPath() + File.separator)) continue;
                        libraryArtifacts.put(relativePath, new LibraryEntry(local, artifact.getSize()));
                    }
                }
                libraryCount = libraryArtifacts.size();
                for (LibraryEntry entry : libraryArtifacts.values()) {
                    if (entry.file.isFile() && entry.file.length() > 0L &&
                            (entry.expectedSize <= 0L || entry.file.length() == entry.expectedSize)) {
                        installedLibraryCount++;
                    }
                }

                VersionMetadataEntry assetOwner = null;
                for (VersionMetadataEntry entry : chain) {
                    if (entry.metadata.optJSONObject("assetIndex") != null) {
                        assetOwner = entry;
                        break;
                    }
                }
                assetIndexDeclared = assetOwner != null;
                JSONObject assetIndexMetadata = assetOwner == null ? null :
                        assetOwner.metadata.optJSONObject("assetIndex");
                if (assetIndexMetadata != null) {
                    String id = assetIndexMetadata.optString("id");
                    if (!id.trim().isEmpty()) assetIndexId = id;
                }

                if (assetIndexId != null) {
                    File assetIndexFile = new File(new File(assetsRoot, "indexes"), assetIndexId + ".json");
                    long expectedIndexSize = assetIndexMetadata.optLong("size", -1L);
                    assetIndexExists = assetIndexFile.isFile() && assetIndexFile.length() > 0L &&
                            (expectedIndexSize <= 0L || assetIndexFile.length() == expectedIndexSize);

                    if (assetIndexExists) {
                        JSONObject indexJson = new JSONObject(readText(assetIndexFile));
                        JSONObject objectMap = indexJson.optJSONObject("objects");
                        if (objectMap == null) throw new IOException("Asset index has no objects map.");
                        assetObjectTotal = objectMap.length();
                        File objectDirectory = new File(assetsRoot, "objects");
                        boolean mapToResources = indexJson.optBoolean("map_to_resources", false);
                        boolean virtualAssets = indexJson.optBoolean("virtual", false);
                        File legacyRoot = mapToResources ? new File(root, "resources") :
                                (virtualAssets ? new File(assetsRoot, "virtual/legacy") : assetsRoot);
                        java.util.Iterator<String> objectKeys = objectMap.keys();
                        while (objectKeys.hasNext()) {
                            String assetName = objectKeys.next();
                            JSONObject item = objectMap.optJSONObject(assetName);
                            if (item == null) continue;
                            String hash = item.optString("hash").toLowerCase(Locale.ROOT);
                            if (!hash.matches("[a-f0-9]{40}")) continue;
                            long expectedSize = item.optLong("size", -1L);
                            File objectFile = new File(new File(objectDirectory, hash.substring(0, 2)), hash);
                            if (objectFile.isFile() &&
                                    (expectedSize <= 0L || objectFile.length() == expectedSize) &&
                                    MinecraftAssetLayout.hasExpectedSha1(objectFile, hash)) {
                                assetObjectCount++;
                            }
                            if (virtualAssets || mapToResources) {
                                File legacyFile = null;
                                try {
                                    File candidate = new File(legacyRoot, assetName).getCanonicalFile();
                                    if (candidate.getPath().startsWith(legacyRoot.getCanonicalPath() + File.separator)) {
                                        legacyFile = candidate;
                                    }
                                } catch (IOException ignored) { }
                                if (legacyFile == null || !legacyFile.isFile() ||
                                        (expectedSize >= 0L && legacyFile.length() != expectedSize) ||
                                        !MinecraftAssetLayout.hasExpectedSha1(legacyFile, hash)) {
                                    legacyAssetFilesReady = false;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Incomplete or corrupt metadata is surfaced in the preflight blockers below.
            }
        }

        List<DetectedJavaRuntime> detected = scanRuntimes();
        String explicitRuntimeName = null;
        if (runtimePreference != null && runtimePreference.startsWith("Name:")) {
            String value = runtimePreference.substring("Name:".length()).trim();
            if (!value.isEmpty()) explicitRuntimeName = value;
        }
        DetectedJavaRuntime namedRuntime = null;
        if (explicitRuntimeName != null) {
            for (DetectedJavaRuntime item : detected) {
                if (item.getName().equals(explicitRuntimeName) &&
                        item.getMajorVersion() >= (requiredMajor == null ? 1 : requiredMajor)) {
                    namedRuntime = item;
                    break;
                }
            }
        }

        Integer requestedMajor = null;
        if (explicitRuntimeName == null && runtimePreference != null) {
            String value = runtimePreference.startsWith("Java")
                    ? runtimePreference.substring(4).trim() : runtimePreference.trim();
            try { requestedMajor = Integer.valueOf(value); }
            catch (NumberFormatException ignored) { }
        }
        Integer minimumMajor = requiredMajor != null ? requiredMajor : requestedMajor;
        Integer preferredMajor = requestedMajor != null &&
                (requiredMajor == null || requestedMajor >= requiredMajor) ? requestedMajor : null;

        DetectedJavaRuntime compatible = namedRuntime;
        if (compatible == null && preferredMajor != null) {
            for (DetectedJavaRuntime item : detected) {
                if (item.getMajorVersion() == preferredMajor &&
                        item.getMajorVersion() >= (requiredMajor == null ? preferredMajor : requiredMajor)) {
                    compatible = item;
                    break;
                }
            }
        }
        if (compatible == null && minimumMajor != null) {
            for (DetectedJavaRuntime item : detected) {
                if (item.getMajorVersion() >= minimumMajor) {
                    compatible = item;
                    break;
                }
            }
        }

        boolean engineAvailable = AmethystEngineBridge.isAvailable(context);
        List<String> blockers = new ArrayList<>();
        if (!AmethystStoragePaths.hasLegacyStoragePermission(context)) {
            blockers.add("Grant READ and WRITE storage permission in Android settings before using Amethyst's shared Minecraft folder.");
        }
        if (!clientJarValid) blockers.add("Client JAR is missing, incomplete or has the wrong size for " + safeId + ".");
        if (!metadataFile.isFile()) blockers.add("Version metadata JSON is missing.");
        if (requiredMajor == null) blockers.add("Java major version could not be resolved from version metadata.");
        if (minimumMajor != null && compatible == null) {
            blockers.add("No Amethyst-compatible Android JRE for Java " + minimumMajor +
                    " or newer is installed in the MultiRT runtimes folder.");
        }
        if (libraryCount == 0 || installedLibraryCount < libraryCount) {
            blockers.add("Minecraft libraries are incomplete (" + installedLibraryCount + "/" + libraryCount + ").");
        }
        if (assetIndexDeclared && (assetIndexId == null || !assetIndexExists)) {
            blockers.add("The Minecraft asset index is missing.");
        }
        if (assetIndexDeclared && assetObjectTotal == 0) {
            blockers.add("The asset index contains no readable object entries.");
        } else if (assetIndexDeclared && assetObjectCount < assetObjectTotal) {
            blockers.add("Minecraft assets are incomplete (" + assetObjectCount + "/" + assetObjectTotal +
                    " objects are present and SHA-1 verified).");
        }
        if (!legacyAssetFilesReady) {
            blockers.add("Legacy asset paths are incomplete. Reinstall this version to rebuild the virtual/resource asset files.");
        }
        if (!engineAvailable) {
            blockers.add("The Amethyst Android engine is not included in this build (JVM, LWJGL, SDL and native renderer bridge missing).");
        }

        return new VersionVmInspection(safeId, clientJarValid, metadataFile.isFile(), requiredMajor,
                libraryCount, installedLibraryCount, assetIndexId, assetIndexExists,
                assetObjectCount, assetObjectTotal, compatible, detected, engineAvailable, blockers);
    }

    public File runtimeDirectory() { return amethystRuntimeRoot; }

    private List<VersionMetadataEntry> readMetadataChain(String versionId) throws Exception {
        List<VersionMetadataEntry> entries = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String currentId = versionId;
        for (int depth = 0; depth < 16; depth++) {
            if (!visited.add(currentId)) {
                throw new IOException("Minecraft version inheritance contains a cycle at " + currentId + ".");
            }
            File currentFolder = new File(new File(root, "versions"), currentId);
            File currentJson = new File(currentFolder, currentId + ".json");
            if (!currentJson.isFile()) {
                if (entries.isEmpty()) return entries;
                throw new IOException("Required inherited Minecraft metadata is missing: " + currentId + ".");
            }
            JSONObject metadata = new JSONObject(readText(currentJson));
            entries.add(new VersionMetadataEntry(currentId, metadata));
            String parentId = metadata.optString("inheritsFrom").trim();
            if (parentId.isEmpty()) return entries;
            if (!parentId.matches("[A-Za-z0-9._+\\-]{1,100}")) {
                throw new IOException("Invalid inherited Minecraft version ID.");
            }
            currentId = parentId;
        }
        throw new IOException("Minecraft version inheritance exceeds the 16-level safety limit.");
    }

    private List<DetectedJavaRuntime> scanRuntimes() {
        try { migrateLegacyRuntimes(); }
        catch (Exception ignored) { }
        File canonicalRoot;
        try { canonicalRoot = amethystRuntimeRoot.getCanonicalFile(); }
        catch (IOException error) { return Collections.emptyList(); }
        String rootPrefix = canonicalRoot.getPath() + File.separator;
        File[] directories = amethystRuntimeRoot.listFiles();
        if (directories == null) return Collections.emptyList();
        List<DetectedJavaRuntime> result = new ArrayList<>();
        for (File directory : directories) {
            if (!directory.isDirectory()) continue;
            try {
                File home = directory.getCanonicalFile();
                if (!home.getPath().startsWith(rootPrefix)) continue;
                File release = new File(home, "release");
                if (!release.isFile()) continue;
                Map<String, String> properties = parseRelease(release);
                String versionText = trimQuotes(properties.get("JAVA_VERSION"));
                Integer major = parseJavaMajor(versionText);
                if (major == null) continue;
                String arch = trimQuotes(properties.get("OS_ARCH"));
                File jvm = findJvmLibrary(home, arch);
                if (jvm == null || !isDeviceCompatible(arch)) continue;
                result.add(new DetectedJavaRuntime(directory.getName(), major, versionText, arch, home, jvm));
            } catch (Exception ignored) { }
        }
        Collections.sort(result, Comparator.comparingInt(DetectedJavaRuntime::getMajorVersion));
        return result;
    }

    private boolean libraryAllowed(JSONObject library) {
        JSONArray rules = library.optJSONArray("rules");
        if (rules == null) return true;
        boolean allowed = false;
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0];
        String architecture;
        if (abi.contains("arm64") || abi.contains("aarch64")) architecture = "aarch64";
        else if (abi.contains("armeabi") || "arm".equals(abi)) architecture = "arm";
        else if (abi.contains("x86_64") || abi.contains("amd64")) architecture = "x86_64";
        else if ("x86".equals(abi) || abi.contains("i386") || abi.contains("i686")) architecture = "x86";
        else architecture = "unsupported";

        for (int index = 0; index < rules.length(); index++) {
            JSONObject rule = rules.optJSONObject(index);
            if (rule == null) continue;
            JSONObject os = rule.optJSONObject("os");
            boolean osMatches = true;
            if (os != null) {
                String osName = os.optString("name");
                boolean nameMatches = osName.trim().isEmpty() || "linux".equals(osName);
                String archPattern = os.optString("arch");
                boolean archMatches = archPattern.trim().isEmpty() || regexMatches(archPattern, architecture);
                String versionPattern = os.optString("version");
                boolean versionMatches = versionPattern.trim().isEmpty() ||
                        regexMatches(versionPattern, System.getProperty("os.version", ""));
                osMatches = nameMatches && archMatches && versionMatches;
            }
            JSONObject features = rule.optJSONObject("features");
            boolean featuresMatch = features == null || featureRulesMatch(features);
            if (osMatches && featuresMatch) allowed = "allow".equals(rule.optString("action"));
        }
        return allowed;
    }

    private boolean featureRulesMatch(JSONObject features) {
        java.util.Iterator<String> keys = features.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (features.optBoolean(key, false)) return false;
        }
        return true;
    }

    private void migrateLegacyRuntimes() throws IOException {
        List<File> oldRoots = new ArrayList<>();
        oldRoots.add(privateRuntimeRoot);
        oldRoots.add(legacyRuntimeRoot);
        for (File oldRoot : oldRoots) {
            File[] candidates = oldRoot.listFiles();
            if (candidates == null) continue;
            for (File oldRuntime : candidates) {
                if (!oldRuntime.isDirectory()) continue;
                File nested = new File(oldRuntime, "jre");
                File sourceHome = new File(oldRuntime, "release").isFile() ? oldRuntime :
                        (new File(nested, "release").isFile() ? nested : null);
                if (sourceHome == null) continue;
                String destinationName = sourceHome.equals(oldRuntime)
                        ? oldRuntime.getName() : oldRuntime.getName() + "-flat";
                File destination = new File(amethystRuntimeRoot, destinationName);
                if (new File(destination, "release").isFile()) continue;
                ensureDirectory(amethystRuntimeRoot);
                try { copyRecursively(sourceHome, destination, false); }
                catch (IOException ignored) { }
            }
        }

        File[] runtimeFolders = amethystRuntimeRoot.listFiles();
        if (runtimeFolders == null) return;
        for (File nestedRuntime : runtimeFolders) {
            File nested = new File(nestedRuntime, "jre");
            if (nestedRuntime.isDirectory() && !new File(nestedRuntime, "release").isFile() &&
                    new File(nested, "release").isFile()) {
                File destination = new File(amethystRuntimeRoot, nestedRuntime.getName() + "-flat");
                if (!new File(destination, "release").isFile()) {
                    try { copyRecursively(nested, destination, false); }
                    catch (IOException ignored) { }
                }
            }
        }
    }

    private static Map<String, String> parseRelease(File file) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf('=');
                if (separator > 0) result.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return result;
    }

    private static Integer parseJavaMajor(String version) {
        if (version == null || version.isEmpty()) return null;
        String normalized = version.startsWith("1.") ? version.substring(2) : version;
        String first = normalized.split("[.-]", 2)[0];
        try { return Integer.valueOf(first); }
        catch (NumberFormatException ignored) { return null; }
    }

    private boolean isDeviceCompatible(String runtimeArch) {
        String arch = runtimeArch == null ? "" : runtimeArch.toLowerCase(Locale.ROOT);
        List<String> abis = new ArrayList<>();
        for (String item : Build.SUPPORTED_ABIS) abis.add(item.toLowerCase(Locale.ROOT));
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            for (String item : abis) if (item.contains("arm64")) return true;
        } else if ("arm".equals(arch) || arch.contains("armv7")) {
            for (String item : abis) if (item.contains("armeabi")) return true;
        } else if (arch.contains("x86_64") || arch.contains("amd64")) {
            for (String item : abis) if (item.contains("x86_64")) return true;
        } else if ("x86".equals(arch) || arch.contains("i386") || arch.contains("i686")) {
            return abis.contains("x86");
        }
        return false;
    }

    private static File findJvmLibrary(File home, String arch) {
        List<String> candidates = new ArrayList<>();
        candidates.add("lib/server/libjvm.so");
        candidates.add("lib/client/libjvm.so");
        if (arch != null && !arch.isEmpty()) {
            candidates.add("lib/" + arch + "/server/libjvm.so");
            candidates.add("lib/" + arch + "/client/libjvm.so");
        }
        Collections.addAll(candidates,
                "lib/amd64/server/libjvm.so", "lib/amd64/client/libjvm.so",
                "lib/aarch64/server/libjvm.so", "lib/aarch64/client/libjvm.so",
                "lib/arm/server/libjvm.so", "lib/arm/client/libjvm.so",
                "lib/x86_64/server/libjvm.so", "lib/x86/server/libjvm.so");
        for (String path : candidates) {
            File candidate = new File(home, path);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    private static boolean regexMatches(String pattern, String value) {
        try { return Pattern.compile(pattern).matcher(value).find(); }
        catch (Exception ignored) { return false; }
    }

    private static boolean inside(File root, File child) throws IOException {
        return child.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
    }

    private static void copyRecursively(File source, File destination, boolean overwrite) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) throw new IOException("Cannot create " + destination);
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Cannot list " + source);
            for (File child : children) copyRecursively(child, new File(destination, child.getName()), overwrite);
        } else if (source.isFile() && (overwrite || !destination.exists())) {
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
            try (FileInputStream input = new FileInputStream(source);
                 java.io.FileOutputStream output = new java.io.FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
        }
    }

    private static String readText(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            return text.toString();
        }
    }

    private static String safeSegment(String value) {
        if (value == null) throw new IllegalArgumentException("Invalid Minecraft version ID");
        String safe = value.replaceAll("[^A-Za-z0-9._+-]", "_");
        if (safe.trim().isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            throw new IllegalArgumentException("Invalid Minecraft version ID");
        }
        return safe;
    }

    private static final class VersionMetadataEntry {
        final String id;
        final JSONObject metadata;
        VersionMetadataEntry(String id, JSONObject metadata) {
            this.id = id;
            this.metadata = metadata;
        }
    }

    private static final class LibraryEntry {
        final File file;
        final long expectedSize;
        LibraryEntry(File file, long expectedSize) {
            this.file = file;
            this.expectedSize = expectedSize;
        }
    }
}
