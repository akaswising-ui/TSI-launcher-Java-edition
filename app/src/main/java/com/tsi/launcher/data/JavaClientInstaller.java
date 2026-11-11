package com.tsi.launcher.data;

import com.tsi.launcher.storage.VersionDirectoryManager;
import com.tsi.launcher.storage.VersionPaths;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Locale;

/** Downloads and SHA-1 verifies a Minecraft client JAR and its version JSON. */
public final class JavaClientInstaller {
    private final VersionDirectoryManager directoryManager;

    public JavaClientInstaller(VersionDirectoryManager directoryManager) {
        this.directoryManager = directoryManager;
    }

    /** Call from an IO executor, not the Android main thread. */
    public ClientDownloadResult downloadClientJar(MinecraftVersion version) throws Exception {
        VersionPaths paths = directoryManager.ensureVersionLayout(version.getId());
        String metadataText = downloadText(version.getUrl());
        JSONObject metadata = new JSONObject(metadataText);
        if (!version.getId().equals(metadata.optString("id"))) {
            throw new IOException("Version metadata ID did not match the selected version");
        }

        Integer javaMajor = null;
        JSONObject javaVersion = metadata.optJSONObject("javaVersion");
        if (javaVersion != null) {
            int major = javaVersion.optInt("majorVersion", 0);
            if (major > 0) javaMajor = major;
        }

        JSONObject client = metadata.getJSONObject("downloads").getJSONObject("client");
        String clientUrl = client.getString("url");
        String expectedSha1 = client.optString("sha1").toLowerCase(Locale.ROOT);
        long expectedSize = client.optLong("size", -1L);
        if (!expectedSha1.matches("[a-f0-9]{40}")) {
            throw new IOException("Mojang client metadata did not contain a valid SHA-1 checksum");
        }

        File target = new File(paths.getVersionDirectory(), version.getId() + ".jar");
        File temporary = new File(paths.getVersionDirectory(), version.getId() + ".jar.part");
        downloadAndVerify(clientUrl, temporary, expectedSha1, expectedSize);
        replaceAtomically(temporary, target);

        File metadataFile = new File(paths.getVersionDirectory(), version.getId() + ".json");
        File metadataTemp = new File(paths.getVersionDirectory(), version.getId() + ".json.part");
        try (FileOutputStream output = new FileOutputStream(metadataTemp)) {
            output.write(metadataText.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        replaceAtomically(metadataTemp, metadataFile);
        return new ClientDownloadResult(version.getId(), target.getAbsolutePath(),
                metadataFile.getAbsolutePath(), javaMajor);
    }

    private String downloadText(String rawUrl) throws IOException {
        HttpURLConnection connection = openMojangConnection(rawUrl);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("Version metadata download failed: HTTP " + code);
            }
            StringBuilder body = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    private void downloadAndVerify(String rawUrl, File destination,
                                   String expectedSha1, long expectedSize) throws Exception {
        HttpURLConnection connection = openMojangConnection(rawUrl);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        long bytesWritten = 0L;
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("Minecraft client download failed: HTTP " + code);
            }
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fileOutput = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    fileOutput.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    bytesWritten += count;
                }
                fileOutput.getFD().sync();
            }
        } catch (Exception error) {
            destination.delete();
            throw error;
        } finally {
            connection.disconnect();
        }

        String actualSha1 = toHex(digest.digest());
        if (expectedSize >= 0 && bytesWritten != expectedSize) {
            destination.delete();
            throw new IOException("Client JAR size check failed");
        }
        if (!actualSha1.equalsIgnoreCase(expectedSha1)) {
            destination.delete();
            throw new IOException("Client JAR SHA-1 verification failed");
        }
    }

    private HttpURLConnection openMojangConnection(String rawUrl) throws IOException {
        URI uri = URI.create(rawUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean mojangHost = host.equals("mojang.com") || host.endsWith(".mojang.com") ||
                host.equals("minecraft.net") || host.endsWith(".minecraft.net") ||
                (host.equals("s3.amazonaws.com") &&
                        uri.getPath() != null && uri.getPath().startsWith("/Minecraft.Download/"));
        if (!"https".equals(scheme) || !mojangHost) {
            throw new IllegalArgumentException("Refusing a non-official Minecraft download URL");
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "TSI-Launcher/0.1");
        return connection;
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
            throw new IOException("Cannot install downloaded file: " + target);
        }
        if (backup.exists()) backup.delete();
    }

    private static String toHex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }
}
