package com.tsi.launcher.vulkan;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * AdrenoTools-compatible Vulkan driver package store.
 *
 * The selected .so may be loaded by the Amethyst Turnip namespace hook. The
 * pinned engine does not expose Hyper's qgl_config override hook, so this class
 * intentionally does not claim or apply qgl_config.txt tuning overrides.
 */
public final class AdrenoDriverPackageManager {
    private static final String PREFS = "tsi_vulkan_drivers";
    private static final String SELECTED_KEY = "selected_driver_hash";
    private static final int MAX_ENTRIES = 50_000;
    private static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 160L * 1024L * 1024L;
    private final Context context;
    private final File packagesDirectory;
    private final SharedPreferences preferences;

    public AdrenoDriverPackageManager(Context context) {
        this.context = context.getApplicationContext();
        packagesDirectory = new File(this.context.getFilesDir(), "vulkan-drivers");
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized DriverPackage install(File archive, boolean overwrite) throws IOException {
        if (archive == null || !archive.isFile() || archive.length() <= 0L) {
            throw new IOException("The selected driver archive is empty.");
        }
        ensureDirectory(packagesDirectory);
        File staging = new File(packagesDirectory, ".staging-" + UUID.randomUUID());
        ensureDirectory(staging);
        try {
            extractBounded(archive, staging);
            File metadata = new File(staging, "meta.json");
            if (!metadata.isFile() || metadata.length() > 1024L * 1024L) {
                throw new IOException("Driver ZIP must contain a root meta.json file no larger than 1 MB.");
            }
            JSONObject json = new JSONObject(readText(metadata));
            String name = requiredString(json, "name", 80);
            String author = requiredString(json, "author", 80);
            String version = requiredString(json, "driverVersion", 80);
            String library = safeLibraryName(requiredString(json, "libraryName", 120));
            int schemaVersion = json.optInt("schemaVersion", 0);
            int minApi = json.optInt("minApi", 0);
            if (schemaVersion <= 0) throw new IOException("Driver package schemaVersion is missing or invalid.");
            if (minApi > Build.VERSION.SDK_INT) {
                throw new IOException("This driver needs Android API " + minApi +
                        "; this phone provides API " + Build.VERSION.SDK_INT + ".");
            }
            File mainLibrary = safeChild(staging, library);
            if (!mainLibrary.isFile() || mainLibrary.length() == 0L) {
                throw new IOException("Driver package does not contain its declared native library: " + library);
            }
            verifyActiveAbi(mainLibrary);
            String hash = UUID.nameUUIDFromBytes((name + "::" + author)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            File destination = new File(packagesDirectory, hash);
            if (destination.exists()) {
                if (!overwrite) throw new IOException("This driver package is already installed.");
                deleteTree(destination);
            }
            if (!staging.renameTo(destination)) throw new IOException("Could not activate the driver package folder.");
            DriverPackage installed = readPackage(destination);
            if (installed == null) throw new IOException("Installed driver metadata could not be revalidated.");
            return installed;
        } catch (org.json.JSONException error) {
            throw new IOException("Driver metadata is not valid JSON.", error);
        } finally {
            if (staging.exists()) deleteTree(staging);
        }
    }

    public synchronized List<DriverPackage> list() throws IOException {
        ensureDirectory(packagesDirectory);
        File[] directories = packagesDirectory.listFiles(File::isDirectory);
        if (directories == null) throw new IOException("Cannot list Vulkan driver packages.");
        List<DriverPackage> result = new ArrayList<>();
        for (File directory : directories) {
            if (directory.getName().startsWith(".staging-")) continue;
            DriverPackage driver = readPackage(directory);
            if (driver != null) result.add(driver);
        }
        Collections.sort(result, Comparator.comparing(a -> a.name.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    public synchronized void select(String hash) throws IOException {
        if (hash == null || hash.trim().isEmpty()) {
            if (!preferences.edit().remove(SELECTED_KEY).commit()) {
                throw new IOException("Could not reset selected Vulkan driver.");
            }
            return;
        }
        if (!hash.matches("[a-fA-F0-9-]{36}")) throw new IOException("Invalid driver identifier.");
        DriverPackage driver = readPackage(new File(packagesDirectory, hash));
        if (driver == null) throw new IOException("Selected driver package is not installed or failed validation.");
        if (!preferences.edit().putString(SELECTED_KEY, hash).commit()) {
            throw new IOException("Could not save selected Vulkan driver.");
        }
    }

    public synchronized String getSelectedHash() {
        return preferences.getString(SELECTED_KEY, null);
    }

    public synchronized DriverPackage getSelected() throws IOException {
        String hash = getSelectedHash();
        if (hash == null) return null;
        DriverPackage result = readPackage(new File(packagesDirectory, hash));
        if (result == null) {
            preferences.edit().remove(SELECTED_KEY).apply();
            return null;
        }
        return result;
    }

    public synchronized void remove(String hash) throws IOException {
        if (hash == null || !hash.matches("[a-fA-F0-9-]{36}")) {
            throw new IOException("Invalid driver identifier.");
        }
        File directory = new File(packagesDirectory, hash).getCanonicalFile();
        if (!packagesDirectory.getCanonicalFile().equals(directory.getParentFile())) {
            throw new IOException("Refusing to delete outside the driver package folder.");
        }
        if (!directory.isDirectory()) throw new IOException("Driver package is not installed.");
        deleteTree(directory);
        if (hash.equals(getSelectedHash())) {
            preferences.edit().remove(SELECTED_KEY).apply();
        }
    }

    public static DriverPackageInfo selectedForNativeLoader(Context context) {
        try {
            DriverPackage driver = new AdrenoDriverPackageManager(context).getSelected();
            if (driver == null) return null;
            File directory = driver.directory.getCanonicalFile();
            File library = safeChild(directory, driver.libraryName);
            if (!library.isFile() || library.length() <= 0L) return null;
            return new DriverPackageInfo(directory.getAbsolutePath(), driver.libraryName);
        } catch (Exception error) {
            return null;
        }
    }

    private DriverPackage readPackage(File directory) throws IOException {
        File metadata = new File(directory, "meta.json");
        if (!metadata.isFile() || metadata.length() > 1024L * 1024L) return null;
        try {
            JSONObject json = new JSONObject(readText(metadata));
            String name = requiredString(json, "name", 80);
            String author = requiredString(json, "author", 80);
            String version = requiredString(json, "driverVersion", 80);
            String library = safeLibraryName(requiredString(json, "libraryName", 120));
            int minApi = json.optInt("minApi", 0);
            String hash = UUID.nameUUIDFromBytes((name + "::" + author)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            if (!hash.equals(directory.getName()) || minApi > Build.VERSION.SDK_INT) return null;
            File main = safeChild(directory, library);
            if (!main.isFile() || main.length() == 0L) return null;
            verifyActiveAbi(main);
            File config = new File(directory, "qgl_config.txt");
            return new DriverPackage(hash, name, author, version, library,
                    minApi, directory, config.isFile());
        } catch (org.json.JSONException | IllegalArgumentException | IOException error) {
            return null;
        }
    }

    private static void extractBounded(File archive, File destination) throws IOException {
        int count = 0;
        long total = 0L;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) throw new IOException("Driver archive contains too many entries.");
                String name = safeArchivePath(entry.getName());
                File target = safeChild(destination, name);
                if (entry.isDirectory()) {
                    ensureDirectory(target);
                    continue;
                }
                if (entry.getSize() > MAX_FILE_BYTES) throw new IOException("Driver archive contains an oversized file.");
                File parent = target.getParentFile();
                ensureDirectory(parent);
                try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
                     FileOutputStream raw = new FileOutputStream(target);
                     BufferedOutputStream output = new BufferedOutputStream(raw)) {
                    byte[] buffer = new byte[64 * 1024];
                    long fileBytes = 0L;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        fileBytes += read;
                        total += read;
                        if (fileBytes > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                            throw new IOException("Driver archive exceeds its extraction size limit.");
                        }
                        output.write(buffer, 0, read);
                    }
                    output.flush();
                    raw.getFD().sync();
                }
            }
        }
    }

    private static String safeArchivePath(String path) throws IOException {
        if (path == null || path.trim().isEmpty() || path.startsWith("/") ||
                path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            throw new IOException("Driver archive contains an unsafe path.");
        }
        String normalized = path;
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.endsWith("/") && !normalized.isEmpty()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) throw new IOException("Driver archive contains an empty entry.");
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Driver archive contains a path traversal entry.");
            }
        }
        return normalized;
    }

    private static File safeChild(File root, String relative) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File child = new File(canonicalRoot, relative).getCanonicalFile();
        if (!child.equals(canonicalRoot) &&
                !child.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new IOException("Refusing to access a path outside the driver package folder.");
        }
        return child;
    }

    private static void verifyActiveAbi(File library) throws IOException {
        byte[] header = new byte[20];
        try (InputStream input = new FileInputStream(library)) {
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) throw new IOException("Driver library has a truncated ELF header.");
                offset += count;
            }
        }
        if ((header[0] & 0xff) != 0x7f || header[1] != 'E' ||
                header[2] != 'L' || header[3] != 'F') {
            throw new IOException("Declared Vulkan driver library is not an ELF shared object.");
        }
        String[] processAbis = Process.is64Bit()
                ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        String abi = processAbis.length == 0 ? "" : processAbis[0];
        int expectedClass;
        int expectedMachine;
        if ("arm64-v8a".equals(abi)) { expectedClass = 2; expectedMachine = 183; }
        else if ("armeabi-v7a".equals(abi) || "armeabi".equals(abi)) { expectedClass = 1; expectedMachine = 40; }
        else if ("x86_64".equals(abi)) { expectedClass = 2; expectedMachine = 62; }
        else if ("x86".equals(abi)) { expectedClass = 1; expectedMachine = 3; }
        else throw new IOException("Unsupported Android ABI for custom Vulkan driver: " + abi);
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        if ((header[4] & 0xff) != expectedClass || machine != expectedMachine) {
            throw new IOException("Driver library ABI does not match this app process (" + abi + ").");
        }
    }

    private static String safeLibraryName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_.+-]{1,120}\\.so") ||
                name.contains("..")) throw new IOException("Invalid driver library filename.");
        return name;
    }

    private static String requiredString(JSONObject json, String key, int maxLength) throws IOException {
        String value = json.optString(key, "").trim();
        if (value.isEmpty() || value.length() > maxLength ||
                value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("Driver metadata field " + key + " is missing or invalid.");
        }
        return value;
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 1024L * 1024L) throw new IOException("Driver metadata is too large.");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory == null) throw new IOException("Invalid driver directory.");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        if (!directory.isDirectory()) throw new IOException("Expected directory: " + directory);
    }

    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        File[] children = root.listFiles();
        if (root.isDirectory() && children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!root.delete()) throw new IOException("Could not delete " + root);
    }

    public static final class DriverPackage {
        public final String hash;
        public final String name;
        public final String author;
        public final String version;
        public final String libraryName;
        public final int minimumAndroidApi;
        public final File directory;
        public final boolean containsQglConfig;

        DriverPackage(String hash, String name, String author, String version,
                      String libraryName, int minimumAndroidApi, File directory,
                      boolean containsQglConfig) {
            this.hash = hash;
            this.name = name;
            this.author = author;
            this.version = version;
            this.libraryName = libraryName;
            this.minimumAndroidApi = minimumAndroidApi;
            this.directory = directory;
            this.containsQglConfig = containsQglConfig;
        }
    }

    public static final class DriverPackageInfo {
        public final String directory;
        public final String libraryName;
        DriverPackageInfo(String directory, String libraryName) {
            this.directory = directory;
            this.libraryName = libraryName;
        }
    }
}
