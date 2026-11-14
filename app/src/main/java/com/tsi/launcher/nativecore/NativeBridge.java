package com.tsi.launcher.nativecore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Android compatibility adapter for the Rust launcher core and C++ JNI bridge.
 *
 * Native loading is deliberately lazy and guarded: a missing ABI library must
 * degrade to the Java fallback instead of crashing the Android process during
 * class initialization with UnsatisfiedLinkError.
 */
public final class NativeBridge {
    private static final String USER_AGENT = "TSI-Launcher/0.3";
    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_ZIP_FILE_BYTES = 1024L * 1024L * 1024L;

    private static volatile boolean nativeLoadAttempted;
    private static volatile boolean nativeAvailable;
    private static volatile String nativeLoadError = "";

    private NativeBridge() { }

    private static synchronized boolean ensureNativeLoaded() {
        if (nativeLoadAttempted) return nativeAvailable;
        nativeLoadAttempted = true;
        try {
            System.loadLibrary("tsi_core");
            System.loadLibrary("tsi_jni");
            nativeAvailable = true;
            nativeLoadError = "";
        } catch (LinkageError | SecurityException error) {
            nativeAvailable = false;
            nativeLoadError = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "native library load failed" : error.getMessage());
        }
        return nativeAvailable;
    }

    private static void disableNative(LinkageError error) {
        nativeAvailable = false;
        nativeLoadAttempted = true;
        nativeLoadError = error.getClass().getSimpleName() + ": "
                + (error.getMessage() == null ? "native JNI call failed" : error.getMessage());
    }

    /** Native status for diagnostics. False means the safe Java fallback is active. */
    public static boolean isNativeAvailable() {
        return ensureNativeLoaded();
    }

    public static String getNativeLoadError() {
        ensureNativeLoaded();
        return nativeLoadError;
    }

    public static boolean sha1Matches(File file, String expectedSha1) throws IOException {
        if (file == null) throw new IllegalArgumentException("File is required.");
        if (ensureNativeLoaded()) {
            try {
                return nativeSha1Matches(file.getAbsolutePath(), expectedSha1);
            } catch (LinkageError error) {
                disableNative(error);
            }
        }
        return expectedSha1 != null && expectedSha1.matches("(?i)[0-9a-f]{40}")
                && verifiedFileJava(file, expectedSha1, -1L);
    }

    public static boolean isVerifiedFile(File file, String expectedSha1,
                                         long expectedSize) throws IOException {
        if (file == null) throw new IllegalArgumentException("File is required.");
        if (ensureNativeLoaded()) {
            try {
                return nativeIsVerifiedFile(file.getAbsolutePath(), expectedSha1, expectedSize);
            } catch (LinkageError error) {
                disableNative(error);
            }
        }
        return verifiedFileJava(file, expectedSha1, expectedSize);
    }

    public static void downloadVerified(String url, File destination,
                                        String expectedSha1, long expectedSize) throws IOException {
        if (destination == null) throw new IllegalArgumentException("Destination is required.");
        if (ensureNativeLoaded()) {
            try {
                nativeDownloadVerified(url, destination.getAbsolutePath(), expectedSha1, expectedSize);
                return;
            } catch (LinkageError error) {
                disableNative(error);
            }
        }
        downloadVerifiedJava(url, destination, expectedSha1, expectedSize);
    }

    public static String downloadText(String url, File scratchFile) throws IOException {
        if (scratchFile == null) throw new IllegalArgumentException("Scratch file is required.");
        try {
            downloadVerified(url, scratchFile, null, -1L);
            return new String(Files.readAllBytes(scratchFile.toPath()), StandardCharsets.UTF_8);
        } finally {
            if (scratchFile.exists() && !scratchFile.delete()) {
                // The cache file is disposable. Do not hide the successful download result.
            }
        }
    }

    public static int extractZip(File archive, File destination,
                                 int maxEntries, long maxUncompressedBytes) throws IOException {
        if (archive == null || destination == null) {
            throw new IllegalArgumentException("ZIP archive and destination are required.");
        }
        if (ensureNativeLoaded()) {
            try {
                return nativeExtractZip(archive.getAbsolutePath(), destination.getAbsolutePath(),
                        maxEntries, maxUncompressedBytes);
            } catch (LinkageError error) {
                disableNative(error);
            }
        }
        return extractZipJava(archive, destination, maxEntries, maxUncompressedBytes);
    }

    public static void ensureGameFolders(File gameDirectory) throws IOException {
        if (gameDirectory == null) throw new IllegalArgumentException("Game directory is required.");
        if (ensureNativeLoaded()) {
            try {
                nativeEnsureGameFolders(gameDirectory.getAbsolutePath());
                return;
            } catch (LinkageError error) {
                disableNative(error);
            }
        }
        ensureGameFoldersJava(gameDirectory);
    }

    public static void ensureInstanceLayout(File minecraftRoot, String instanceId,
                                            String versionId) throws IOException {
        if (minecraftRoot == null) throw new IllegalArgumentException("Minecraft root is required.");
        if (ensureNativeLoaded()) {
            try {
                nativeEnsureInstanceLayout(minecraftRoot.getAbsolutePath(), instanceId, versionId);
                return;
            } catch (LinkageError error) {
                disableNative(error);
            }
        }
        ensureInstanceLayoutJava(minecraftRoot, instanceId, versionId);
    }

    public static void deleteInstance(File minecraftRoot, String instanceId) throws IOException {
        if (minecraftRoot == null) throw new IllegalArgumentException("Minecraft root is required.");
        if (ensureNativeLoaded()) {
            try {
                nativeDeleteInstance(minecraftRoot.getAbsolutePath(), instanceId);
                return;
            } catch (LinkageError error) {
                disableNative(error);
            }
        }
        deleteInstanceJava(minecraftRoot, instanceId);
    }

    public static void copyTree(File source, File destination) throws IOException {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Copy source and destination are required.");
        }
        if (ensureNativeLoaded()) {
            try {
                nativeCopyTree(source.getAbsolutePath(), destination.getAbsolutePath());
                return;
            } catch (LinkageError error) {
                disableNative(error);
            }
        }
        copyTreeJava(source, destination);
    }

    private static boolean verifiedFileJava(File file, String expectedSha1,
                                            long expectedSize) throws IOException {
        if (!file.isFile()) return false;
        if (expectedSize >= 0 && file.length() != expectedSize) return false;
        if (expectedSha1 == null || expectedSha1.isEmpty()) return expectedSize >= 0;
        if (!expectedSha1.matches("(?i)[0-9a-f]{40}")) return false;
        return sha1Java(file).equalsIgnoreCase(expectedSha1);
    }

    private static String sha1Java(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (Exception error) {
            throw new IOException("SHA-1 is unavailable on this Android runtime.", error);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(40);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void validateDownloadUrl(String rawUrl) throws IOException {
        final URL url;
        try {
            url = URI.create(rawUrl).toURL();
        } catch (Exception error) {
            throw new IOException("Invalid download URL.", error);
        }
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = host.equals("mojang.com") || host.endsWith(".mojang.com")
                || host.equals("minecraft.net") || host.endsWith(".minecraft.net")
                || host.equals("libraries.minecraft.net") || host.equals("repo.maven.apache.org")
                || host.equals("repo1.maven.org") || host.equals("resources.download.minecraft.net");
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !allowed) {
            throw new IOException("Refusing non-HTTPS or untrusted Minecraft download host: " + host);
        }
    }

    private static HttpURLConnection openDownloadJava(String rawUrl) throws IOException {
        String current = rawUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validateDownloadUrl(current);
            HttpURLConnection connection = (HttpURLConnection) URI.create(current).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(90_000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int code;
            try {
                code = connection.getResponseCode();
            } catch (IOException error) {
                connection.disconnect();
                throw error;
            }
            if (code >= 200 && code < 300) return connection;
            if (code >= 300 && code < 400 && hop < MAX_REDIRECTS) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("HTTP redirect did not include a Location header.");
                }
                try {
                    current = URI.create(current).resolve(location).toString();
                } catch (Exception error) {
                    throw new IOException("Invalid download redirect.", error);
                }
                continue;
            }
            connection.disconnect();
            throw new IOException("Download failed with HTTP " + code);
        }
        throw new IOException("Download exceeded the redirect limit.");
    }

    private static void downloadVerifiedJava(String rawUrl, File destination,
                                             String expectedSha1, long expectedSize) throws IOException {
        if (expectedSha1 != null && !expectedSha1.isEmpty()
                && !expectedSha1.matches("(?i)[0-9a-f]{40}")) {
            throw new IOException("Expected SHA-1 must contain 40 hexadecimal characters.");
        }
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null) throw new IOException("Download target has no parent directory.");
        ensureDirectoryJava(parent);
        File temporary = new File(parent, destination.getName() + ".part");
        if (temporary.exists() && !temporary.delete()) throw new IOException("Cannot clear incomplete download.");
        HttpURLConnection connection = openDownloadJava(rawUrl);
        long total = 0L;
        MessageDigest digest = null;
        try {
            if (expectedSha1 != null && !expectedSha1.isEmpty()) {
                digest = MessageDigest.getInstance("SHA-1");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (expectedSize >= 0 && total > expectedSize) {
                        throw new IOException("Download exceeded the expected file size.");
                    }
                    output.write(buffer, 0, count);
                    if (digest != null) digest.update(buffer, 0, count);
                }
                output.getFD().sync();
            } catch (Exception error) {
                if (error instanceof IOException) throw (IOException) error;
                throw new IOException("Could not verify downloaded file.", error);
            }
            if (expectedSize >= 0 && total != expectedSize) {
                throw new IOException("File size verification failed.");
            }
            if (digest != null) {
                StringBuilder actual = new StringBuilder(40);
                for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                if (!actual.toString().equalsIgnoreCase(expectedSha1)) {
                    throw new IOException("SHA-1 verification failed for " + destination.getName());
                }
            }
            replaceAtomicallyJava(temporary, destination);
        } catch (Exception error) {
            if (temporary.exists()) temporary.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Download failed.", error);
        } finally {
            connection.disconnect();
        }
    }

    private static void replaceAtomicallyJava(File temporary, File destination) throws IOException {
        File parent = destination.getParentFile();
        File backup = new File(parent, destination.getName() + ".tsi-backup");
        if (backup.exists() && !backup.delete()) throw new IOException("Cannot clear stale backup.");
        boolean hadDestination = destination.exists();
        if (hadDestination && !destination.renameTo(backup)) {
            throw new IOException("Cannot stage the previous downloaded file.");
        }
        if (!temporary.renameTo(destination)) {
            if (hadDestination) backup.renameTo(destination);
            throw new IOException("Cannot move the verified download into place.");
        }
        if (backup.exists()) backup.delete();
    }

    private static int extractZipJava(File archive, File destination, int maxEntries,
                                      long maxUncompressedBytes) throws IOException {
        if (!archive.isFile() || archive.length() <= 0) throw new IOException("The selected ZIP is empty.");
        if (maxEntries <= 0 || maxUncompressedBytes <= 0) throw new IOException("ZIP safety limits must be positive.");
        ensureDirectoryJava(destination);
        File root = destination.getCanonicalFile();
        int entries = 0;
        long total = 0L;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > maxEntries) throw new IOException("ZIP contains too many entries.");
                File target = safeZipTargetJava(root, entry.getName());
                if (entry.isDirectory()) {
                    ensureDirectoryJava(target);
                } else {
                    if (entry.getSize() > MAX_ZIP_FILE_BYTES) {
                        throw new IOException("ZIP entry exceeds the 1 GB per-file limit.");
                    }
                    ensureDirectoryJava(target.getParentFile());
                    long perFile = 0L;
                    try (FileOutputStream output = new FileOutputStream(target)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            perFile += count;
                            total += count;
                            if (perFile > MAX_ZIP_FILE_BYTES || total > maxUncompressedBytes) {
                                throw new IOException("ZIP expanded beyond its safety limit.");
                            }
                            output.write(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                }
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static File safeZipTargetJava(File root, String name) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            throw new IOException("ZIP contains an unsafe entry path.");
        }
        String normalized = name.startsWith("/") ? "" : name;
        if (normalized.isEmpty()) throw new IOException("ZIP contains an absolute entry path.");
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("ZIP path traversal was blocked.");
            }
        }
        File target = new File(root, normalized).getCanonicalFile();
        if (!target.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("ZIP entry escapes the import directory.");
        }
        return target;
    }

    private static void ensureGameFoldersJava(File game) throws IOException {
        ensureDirectoryJava(game);
        for (String name : new String[]{"mods", "config", "saves", "resourcepacks",
                "shaderpacks", "screenshots", "logs"}) {
            ensureDirectoryJava(new File(game, name));
        }
    }

    private static void ensureInstanceLayoutJava(File minecraftRoot, String id,
                                                 String version) throws IOException {
        requireSafeSegment(id, "instance ID");
        requireSafeSegment(version, "Minecraft version ID");
        ensureDirectoryJava(minecraftRoot);
        File root = minecraftRoot.getCanonicalFile();
        File versions = safeChildJava(root, "versions");
        File instances = safeChildJava(root, "instances");
        File assets = safeChildJava(root, "assets");
        File libraries = safeChildJava(root, "libraries");
        ensureDirectoryJava(versions);
        ensureDirectoryJava(instances);
        ensureDirectoryJava(assets);
        ensureDirectoryJava(libraries);
        ensureDirectoryJava(safeChildJava(versions, version));
        File instance = safeChildJava(instances, id);
        ensureGameFoldersJava(safeChildJava(instance, "game"));
    }

    private static void deleteInstanceJava(File minecraftRoot, String id) throws IOException {
        requireSafeSegment(id, "instance ID");
        File root = minecraftRoot.getCanonicalFile();
        File instances = new File(root, "instances").getCanonicalFile();
        if (!instances.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("Instances directory escapes Minecraft storage.");
        }
        File selected = safeChildJava(instances, id).getCanonicalFile();
        if (!selected.isDirectory() || Files.isSymbolicLink(selected.toPath())) {
            throw new IOException("Selected instance is not a normal directory.");
        }
        File[] folders = instances.listFiles();
        int valid = 0;
        if (folders != null) {
            for (File folder : folders) {
                if (folder.isDirectory() && new File(folder, "tsi-instance.json").isFile()) valid++;
            }
        }
        if (valid <= 1) throw new IOException("The last remaining instance cannot be deleted.");
        deleteTreeJava(selected);
    }

    private static void copyTreeJava(File source, File destination) throws IOException {
        if (Files.isSymbolicLink(source.toPath())) throw new IOException("Symbolic-link copies are not allowed.");
        File canonicalSource = source.getCanonicalFile();
        if (canonicalSource.isDirectory()) {
            if (destination.exists() && Files.isSymbolicLink(destination.toPath())) {
                throw new IOException("Copy destination cannot be a symbolic link.");
            }
            ensureDirectoryJava(destination);
            File root = destination.getCanonicalFile();
            File[] children = canonicalSource.listFiles();
            if (children == null) throw new IOException("Cannot read source folder.");
            for (File child : children) {
                copyNodeJava(child, new File(root, child.getName()), root);
            }
        } else {
            File parent = destination.getAbsoluteFile().getParentFile();
            ensureDirectoryJava(parent);
            copyNodeJava(canonicalSource, destination, parent.getCanonicalFile());
        }
    }

    private static void copyNodeJava(File source, File destination, File root) throws IOException {
        if (Files.isSymbolicLink(source.toPath())) throw new IOException("Refusing to follow a symbolic link.");
        File canonical = source.getCanonicalFile();
        if (canonical.isDirectory()) {
            ensureDirectoryJava(destination);
            File targetRoot = destination.getCanonicalFile();
            if (!targetRoot.getPath().startsWith(root.getPath())) throw new IOException("Copy destination escapes root.");
            File[] children = canonical.listFiles();
            if (children == null) throw new IOException("Cannot read source folder.");
            for (File child : children) copyNodeJava(child, new File(destination, child.getName()), targetRoot);
        } else if (canonical.isFile() && !destination.exists()) {
            File parent = destination.getAbsoluteFile().getParentFile();
            ensureDirectoryJava(parent);
            if (!parent.getCanonicalPath().startsWith(root.getCanonicalPath())) {
                throw new IOException("Copy destination escapes root.");
            }
            try (FileInputStream input = new FileInputStream(canonical);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
        }
    }

    private static void deleteTreeJava(File root) throws IOException {
        if (!root.exists()) return;
        if (Files.isSymbolicLink(root.toPath()) || root.isFile()) {
            if (!root.delete()) throw new IOException("Could not delete " + root.getName());
            return;
        }
        File[] children = root.listFiles();
        if (children == null) throw new IOException("Cannot read " + root.getAbsolutePath());
        for (File child : children) deleteTreeJava(child);
        if (!root.delete()) throw new IOException("Could not delete " + root.getAbsolutePath());
    }

    private static File safeChildJava(File parent, String segment) throws IOException {
        requireSafeSegment(segment, "path component");
        File root = parent.getCanonicalFile();
        File child = new File(root, segment).getCanonicalFile();
        if (!child.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("Path escapes its parent folder.");
        }
        return child;
    }

    private static void requireSafeSegment(String value, String label) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 100 || ".".equals(value)
                || "..".equals(value) || !value.matches("[A-Za-z0-9._+-]+")) {
            throw new IOException("Invalid " + label + ".");
        }
    }

    private static void ensureDirectoryJava(File directory) throws IOException {
        if (directory == null) throw new IOException("Invalid directory path.");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create directory: " + directory.getAbsolutePath());
        }
        if (!directory.isDirectory()) throw new IOException("Not a directory: " + directory.getAbsolutePath());
    }

    private static native boolean nativeSha1Matches(String path, String expectedSha1)
            throws IOException;
    private static native boolean nativeIsVerifiedFile(String path, String expectedSha1,
                                                        long expectedSize) throws IOException;
    private static native void nativeDownloadVerified(String url, String destination,
                                                        String expectedSha1, long expectedSize)
            throws IOException;
    private static native int nativeExtractZip(String archive, String destination,
                                                int maxEntries, long maxBytes) throws IOException;
    private static native void nativeEnsureGameFolders(String gameDirectory) throws IOException;
    private static native void nativeEnsureInstanceLayout(String minecraftRoot, String instanceId,
                                                            String versionId) throws IOException;
    private static native void nativeDeleteInstance(String minecraftRoot, String instanceId)
            throws IOException;
    private static native void nativeCopyTree(String source, String destination) throws IOException;
}
