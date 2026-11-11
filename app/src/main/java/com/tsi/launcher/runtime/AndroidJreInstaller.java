package com.tsi.launcher.runtime;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import com.tsi.launcher.storage.VersionDirectoryManager;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/** Installs Android-built OpenJDK runtime packages for the device ABI. */
public final class AndroidJreInstaller {
    public interface ProgressListener {
        void onProgress(String message);
    }

    private final File gameRoot;
    private final File runtimeRoot;
    private final List<File> legacyRuntimeRoots;
    private final String runtimeNamePrefix = "TSI-External-";

    public AndroidJreInstaller(Context context) {
        Context app = context.getApplicationContext();
        gameRoot = new VersionDirectoryManager(app).root();
        File parent = app.getFilesDir().getParentFile();
        runtimeRoot = new File(parent == null ? app.getFilesDir() : parent, "runtimes");
        legacyRuntimeRoots = Arrays.asList(
                new File(app.getNoBackupFilesDir(), "tsi/.minecraft/runtimes"),
                new File(gameRoot, "runtimes"));
    }

    public List<Integer> supportedRuntimeMajors() {
        return Arrays.asList(8, 17, 21, 25);
    }

    /** Returns the lowest bundled Android JRE satisfying the requested Java major. */
    public Integer recommendedRuntimeMajor(int requiredMajor) {
        for (Integer major : supportedRuntimeMajors()) if (major >= requiredMajor) return major;
        return null;
    }

    public String deviceArchitecture() {
        if (Build.SUPPORTED_ABIS.length == 0) return null;
        String abi = Build.SUPPORTED_ABIS[0].toLowerCase(Locale.ROOT);
        if (abi.contains("arm64") || abi.contains("aarch64")) return "arm64";
        if (abi.contains("armeabi") || "arm".equals(abi)) return "arm";
        if (abi.contains("x86_64") || abi.contains("amd64")) return "x86_64";
        if ("x86".equals(abi)) return "x86";
        return null;
    }

    public boolean isInstalled(int javaMajor) throws IOException {
        return findInstalledRuntime(javaMajor) != null;
    }

    private File findInstalledRuntime(int javaMajor) throws IOException {
        File preferred = new File(runtimeRoot, runtimeNamePrefix + javaMajor);
        if (isValidRuntime(preferred, javaMajor)) return preferred;

        List<File> legacyCandidates = new ArrayList<>();
        legacyCandidates.add(new File(runtimeRoot, "External-" + javaMajor));
        for (File root : legacyRuntimeRoots) {
            legacyCandidates.add(new File(root, runtimeNamePrefix + javaMajor));
            legacyCandidates.add(new File(root, "External-" + javaMajor));
        }
        for (File legacy : legacyCandidates) {
            if (!isValidRuntime(legacy, javaMajor)) continue;
            ensureDirectory(runtimeRoot);
            if (!preferred.exists()) copyRecursively(legacy, preferred);
            if (isValidRuntime(preferred, javaMajor)) return preferred;
        }
        return null;
    }

    private boolean isValidRuntime(File directory, int javaMajor) {
        File release = new File(directory, "release");
        if (!release.isFile()) return false;
        try {
            Map<String, String> properties = parseRelease(release);
            String version = trimQuotes(properties.get("JAVA_VERSION"));
            String arch = trimQuotes(properties.get("OS_ARCH"));
            return Integer.valueOf(javaMajor).equals(parseJavaMajor(version)) &&
                    isDeviceCompatible(arch) && findJvmLibrary(directory) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Call from an IO executor, not the Android main thread. */
    public File downloadAndInstall(int javaMajor, ProgressListener progress) throws Exception {
        if (progress == null) progress = message -> { };
        String arch = deviceArchitecture();
        if (arch == null) {
            throw new IOException("This Android CPU architecture is not supported by the available JRE builds.");
        }
        RuntimePackage pkg = packageFor(javaMajor, arch);
        if (pkg == null) {
            throw new IOException("Java " + javaMajor + " is not available for Android architecture " + arch + ".");
        }

        File installed = findInstalledRuntime(javaMajor);
        if (installed != null) return installed;

        ensureDirectory(runtimeRoot);
        File archive = new File(runtimeRoot, "jre" + javaMajor + "-android-" + arch + ".tar.xz.part");
        File temporaryDirectory = new File(runtimeRoot, runtimeNamePrefix + javaMajor + ".installing");
        File finalDirectory = new File(runtimeRoot, runtimeNamePrefix + javaMajor);
        deleteRecursively(temporaryDirectory);

        progress.onProgress("Downloading Android OpenJDK " + javaMajor + " for " + arch + "…");
        downloadAndVerify(pkg, archive, progress);
        progress.onProgress("Extracting Java " + javaMajor + " runtime…");
        extractTarXz(archive, temporaryDirectory);
        archive.delete();

        File release = new File(temporaryDirectory, "release");
        if (!release.isFile()) {
            deleteRecursively(temporaryDirectory);
            throw new IOException("The downloaded JRE package has no release metadata.");
        }

        Map<String, String> properties = parseRelease(release);
        Integer parsedMajor = parseJavaMajor(trimQuotes(properties.get("JAVA_VERSION")));
        String runtimeArch = trimQuotes(properties.get("OS_ARCH"));
        if (!Integer.valueOf(javaMajor).equals(parsedMajor) ||
                !isDeviceCompatible(runtimeArch) || findJvmLibrary(temporaryDirectory) == null) {
            deleteRecursively(temporaryDirectory);
            throw new IOException("The extracted Java runtime failed version, ABI or libjvm validation.");
        }

        File backup = new File(runtimeRoot, runtimeNamePrefix + javaMajor + ".previous");
        deleteRecursively(backup);
        if (finalDirectory.exists() && !finalDirectory.renameTo(backup)) {
            deleteRecursively(temporaryDirectory);
            throw new IOException("Cannot move the existing Java " + javaMajor + " runtime aside.");
        }
        if (!temporaryDirectory.renameTo(finalDirectory)) {
            if (backup.exists()) backup.renameTo(finalDirectory);
            deleteRecursively(temporaryDirectory);
            throw new IOException("Cannot activate the Java " + javaMajor + " runtime.");
        }
        deleteRecursively(backup);

        try {
            Class<?> multiRt = Class.forName("net.kdt.pojavlaunch.multirt.MultiRTUtils");
            multiRt.getMethod("forceReread", String.class)
                    .invoke(null, runtimeNamePrefix + javaMajor);
        } catch (Exception ignored) {
            // Runtime installation remains valid; Amethyst will reread it on next startup.
        }
        progress.onProgress("Java " + javaMajor + " runtime installed and verified.");
        return finalDirectory;
    }

    private RuntimePackage packageFor(int major, String arch) {
        Map<String, String[]> digests = new HashMap<>();
        if (major == 8) {
            digests.put("arm", new String[]{"9dbee3b09af5f170e2ed9dd596bc81d0e573c88f8bf760c59c8fadbb9073d1e7", "27035664"});
            digests.put("arm64", new String[]{"9a59124d9791957d55c68be664ab76831f336cf2e1e1cd4414220c6fdbf0e06d", "28026736"});
            digests.put("x86", new String[]{"b96ce49fab52b28688dccc1a7d85dfc6d0f4048636aac826e1967524b28f75af", "27473340"});
            digests.put("x86_64", new String[]{"b1fbcef4965c17925894febe8216d089c6dd47b37950b5f945a89616443c1d0e", "29132884"});
        } else if (major == 17) {
            digests.put("arm", new String[]{"4a9134f1ebf6340dd855805d351712462cddab6f3c2684da7e7da10ccf06648d", "25001168"});
            digests.put("arm64", new String[]{"e162c860fe05ee4a4e4af7606437419879f6c748386a7b09fa77d10db6a64091", "26900804"});
            digests.put("x86", new String[]{"223a2d54606a9eb853c8451cf1d6bda1a8f09cb357f40b790140e57d45731ed7", "26951040"});
            digests.put("x86_64", new String[]{"893e27d2aed8b40407f29fe939e2a0f193e5d55f72892a303db634b0808a2b61", "27780012"});
        } else if (major == 21) {
            digests.put("arm", new String[]{"96c297487def64666e379a9a363d9955c05b1a0b091b0cf24af88359a66f394a", "26586292"});
            digests.put("arm64", new String[]{"8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe", "28675516"});
            digests.put("x86", new String[]{"9b8c7d10c5f751acb3b33506593da44ece52a0fd03e0b3c283ba08a7f285a40f", "28689112"});
            digests.put("x86_64", new String[]{"cb88723961f5f9ad63afa1f212eb199816c27cabfd7dc66567bde1d8fb69713b", "29662988"});
        } else if (major == 25) {
            digests.put("arm", new String[]{"9ae13aee9cba7b2d2d8f40965061667e876d7380d866f37a992db3eff296ffb5", "28252088"});
            digests.put("arm64", new String[]{"d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37", "38031580"});
            digests.put("x86_64", new String[]{"7fca862ee1b2d5fe23cd9c9c3d9b7ad3c241947ad1a6cc9464ef2e674867105d", "39061384"});
        } else {
            return null;
        }
        String[] asset = digests.get(arch);
        if (asset == null) return null;
        String name = "jre" + major + "-android-" + arch + ".tar.xz";
        String url = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre" +
                major + "/" + name;
        return new RuntimePackage(major, arch, url, Long.parseLong(asset[1]), asset[0]);
    }

    private void downloadAndVerify(RuntimePackage pkg, File target, ProgressListener progress) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(pkg.getUrl()).toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("User-Agent", "TSI-Launcher/0.2");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("JRE download failed: HTTP " + code);
            URI finalUri = connection.getURL().toURI();
            String host = finalUri.getHost() == null ? "" : finalUri.getHost();
            if (!"https".equalsIgnoreCase(finalUri.getScheme()) ||
                    !(host.equalsIgnoreCase("release-assets.githubusercontent.com") ||
                            host.equalsIgnoreCase("github.com"))) {
                throw new IOException("JRE download redirected to an unexpected host.");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0L;
            File parent = target.getParentFile();
            if (parent == null) throw new IOException("Runtime folder missing");
            ensureDirectory(parent);
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long lastReport = 0L;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    total += count;
                    if (total - lastReport >= 1024 * 1024) {
                        lastReport = total;
                        progress.onProgress("Java " + pkg.getJavaMajor() + ": downloaded " +
                                (total / (1024 * 1024)) + " MB");
                    }
                }
                output.getFD().sync();
            }
            String hash = toHex(digest.digest());
            if (total != pkg.getSize() || !hash.equalsIgnoreCase(pkg.getSha256())) {
                target.delete();
                throw new IOException("JRE archive size or SHA-256 verification failed.");
            }
        } catch (Exception error) {
            target.delete();
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    private void extractTarXz(File archive, File destination) throws Exception {
        ensureDirectory(destination);
        File root = destination.getCanonicalFile();
        try (BufferedInputStream source = new BufferedInputStream(new FileInputStream(archive));
             XZCompressorInputStream xz = new XZCompressorInputStream(source);
             TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                File output = new File(destination, entry.getName()).getCanonicalFile();
                if (!inside(root, output)) {
                    throw new IOException("JRE archive contains a path outside its install folder.");
                }

                if (entry.isDirectory()) {
                    ensureDirectory(output);
                } else if (entry.isSymbolicLink()) {
                    ensureDirectory(output.getParentFile() == null ? destination : output.getParentFile());
                    String linkName = entry.getLinkName();
                    if (new File(linkName).isAbsolute()) {
                        throw new IOException("Absolute JRE symlink is not allowed.");
                    }
                    File resolvedLink = new File(output.getParentFile(), linkName).getCanonicalFile();
                    if (!inside(root, resolvedLink)) throw new IOException("JRE symlink escapes its install folder.");
                    Files.deleteIfExists(output.toPath());
                    Os.symlink(linkName, output.getAbsolutePath());
                } else if (entry.isLink()) {
                    File sourcePath = new File(destination, entry.getLinkName()).getCanonicalFile();
                    if (!inside(root, sourcePath) || !sourcePath.isFile()) {
                        throw new IOException("JRE archive contains an invalid hard link.");
                    }
                    ensureDirectory(output.getParentFile() == null ? destination : output.getParentFile());
                    copyFile(sourcePath, output);
                } else if (entry.isFile()) {
                    ensureDirectory(output.getParentFile() == null ? destination : output.getParentFile());
                    try (FileOutputStream fileOut = new FileOutputStream(output)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = tar.read(buffer)) != -1) fileOut.write(buffer, 0, count);
                        fileOut.getFD().sync();
                    }
                    if ((entry.getMode() & 73) != 0) output.setExecutable(true, false);
                }
            }
        }
    }

    private static boolean inside(File root, File child) throws IOException {
        String rootPath = root.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
    }

    private static Map<String, String> parseRelease(File file) throws IOException {
        Map<String, String> values = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf('=');
                if (split > 0) values.put(line.substring(0, split), line.substring(split + 1));
            }
        }
        return values;
    }

    private static String trimQuotes(String value) {
        if (value == null) return "";
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static Integer parseJavaMajor(String version) {
        if (version == null) return null;
        String normalized = version.startsWith("1.") ? version.substring(2) : version;
        String first = normalized.split("[.-]", 2)[0];
        try { return Integer.parseInt(first); }
        catch (NumberFormatException ignored) { return null; }
    }

    private boolean isDeviceCompatible(String architecture) {
        String normalized = architecture == null ? "" : architecture.toLowerCase(Locale.ROOT);
        List<String> abis = new ArrayList<>();
        for (String abi : Build.SUPPORTED_ABIS) abis.add(abi.toLowerCase(Locale.ROOT));
        if (normalized.contains("aarch64") || normalized.contains("arm64")) {
            for (String abi : abis) if (abi.contains("arm64")) return true;
        } else if (normalized.equals("arm") || normalized.contains("armv7")) {
            for (String abi : abis) if (abi.contains("armeabi")) return true;
        } else if (normalized.contains("x86_64") || normalized.contains("amd64")) {
            for (String abi : abis) if (abi.contains("x86_64")) return true;
        } else if (normalized.equals("x86") || normalized.contains("i386") || normalized.contains("i686")) {
            return abis.contains("x86");
        }
        return false;
    }

    private static File findJvmLibrary(File directory) {
        String[] paths = {
                "lib/server/libjvm.so", "lib/client/libjvm.so",
                "lib/amd64/server/libjvm.so", "lib/amd64/client/libjvm.so",
                "lib/aarch64/server/libjvm.so", "lib/aarch64/client/libjvm.so",
                "lib/arm/server/libjvm.so", "lib/arm/client/libjvm.so",
                "lib/x86_64/server/libjvm.so", "lib/x86/server/libjvm.so"
        };
        for (String path : paths) {
            File candidate = new File(directory, path);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        if (!directory.isDirectory()) throw new IOException("Not a directory: " + directory);
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static void copyRecursively(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            ensureDirectory(destination);
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Cannot read runtime folder: " + source);
            for (File child : children) {
                copyRecursively(child, new File(destination, child.getName()));
            }
        } else if (source.isFile() && !destination.exists()) {
            File parent = destination.getParentFile();
            ensureDirectory(parent);
            copyFile(source, destination);
        }
    }

    private void deleteRecursively(File file) throws IOException {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return;
        if (Files.isSymbolicLink(file.toPath())) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("Cannot remove previous install path: " + file);
    }

    private static String toHex(byte[] digest) {
        StringBuilder text = new StringBuilder(digest.length * 2);
        for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }
}
