package com.tsi.launcher.modloaders;

import android.content.Context;
import com.tsi.launcher.data.MinecraftLibraryResolver;
import com.tsi.launcher.instances.GameInstance;
import com.tsi.launcher.instances.GameInstanceRepository;
import com.tsi.launcher.storage.AmethystStoragePaths;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Experimental LiteLoader installer for legacy Maven (m2) profiles.
 * Supported only for base versions advertised in the official LiteLoader
 * manifest with an m2 repository and a downloadable latest artifact.
 */
public final class LiteLoaderInstaller {
    public interface ProgressListener { void onProgress(String message); }
    private static final String MANIFEST_URL = "https://dl.liteloader.com/versions/versions.json";
    private static final long MAX_METADATA_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_LIBRARY_BYTES = 256L * 1024L * 1024L;

    private final Context context;
    private final File minecraftRoot;

    public LiteLoaderInstaller(Context context) {
        this.context = context.getApplicationContext();
        minecraftRoot = AmethystStoragePaths.minecraftHome(this.context);
    }

    public String installForSelectedInstance(ProgressListener progress) throws Exception {
        ProgressListener listener = progress == null ? message -> { } : progress;
        GameInstanceRepository instances = new GameInstanceRepository(context);
        GameInstance selected = instances.selected();
        if (selected == null) throw new IOException("Select a game instance before installing LiteLoader.");
        String baseVersion = selected.getVersionId();
        if (baseVersion == null || !baseVersion.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) {
            throw new IOException("LiteLoader can only be installed on a plain legacy Minecraft version.");
        }

        File versionFolder = new File(new File(minecraftRoot, "versions"), baseVersion);
        File baseJsonFile = new File(versionFolder, baseVersion + ".json");
        if (!baseJsonFile.isFile()) {
            throw new IOException("Install Minecraft " + baseVersion + " first, then install LiteLoader.");
        }
        JSONObject baseJson = new JSONObject(readText(baseJsonFile));
        if (!baseVersion.equals(baseJson.optString("id"))) {
            throw new IOException("The selected Minecraft version metadata is invalid.");
        }
        if (!baseJson.has("minecraftArguments")) {
            throw new IOException("This Minecraft version uses a modern argument format; LiteLoader legacy profiles are not supported.");
        }
        if (baseVersion.compareTo("1.8.9") < 0 || baseVersion.compareTo("1.12.2") > 0) {
            throw new IOException("This TSI LiteLoader installer currently supports Minecraft 1.8.9 through 1.12.2 only.");
        }

        listener.onProgress("Fetching official LiteLoader version metadata…");
        JSONObject manifest = new JSONObject(downloadText(MANIFEST_URL));
        JSONObject versions = manifest.optJSONObject("versions");
        JSONObject entry = versions == null ? null : versions.optJSONObject(baseVersion);
        if (entry == null) {
            throw new IOException("The official LiteLoader manifest has no entry for Minecraft " + baseVersion + ".");
        }
        JSONObject repo = entry.optJSONObject("repo");
        if (repo == null || !"m2".equalsIgnoreCase(repo.optString("type"))) {
            throw new IOException("LiteLoader metadata for " + baseVersion +
                    " uses a legacy Ivy repository format. TSI only installs Maven (m2) packages.");
        }
        String repositoryUrl = secureRepositoryUrl(repo.optString("url"));
        JSONObject container = entry.optJSONObject("artefacts");
        if (container == null) container = entry.optJSONObject("artifacts");
        if (container == null) container = entry.optJSONObject("snapshots");
        if (container == null) throw new IOException("LiteLoader artifact metadata is missing.");
        JSONObject loaderGroup = container.optJSONObject("com.mumfrey:liteloader");
        if (loaderGroup == null) throw new IOException("No LiteLoader artifact exists for Minecraft " + baseVersion + ".");
        JSONObject latest = loaderGroup.optJSONObject("latest");
        if (latest == null) throw new IOException("LiteLoader latest artifact is missing for " + baseVersion + ".");

        String loaderVersion = safeCoordinatePart(latest.optString("version"), "LiteLoader version");
        String loaderFile = safeFileName(latest.optString("file"));
        String tweakClass = latest.optString("tweakClass", "com.mumfrey.liteloader.launch.LiteLoaderTweaker");
        if (!"com.mumfrey.liteloader.launch.LiteLoaderTweaker".equals(tweakClass)) {
            throw new IOException("The LiteLoader manifest contains an unexpected tweak class.");
        }
        String md5 = latest.optString("md5", "").toLowerCase(Locale.ROOT);
        if (!md5.matches("[a-f0-9]{32}")) md5 = null;

        String libraryPath = "com/mumfrey/liteloader/" + loaderVersion + "/" + loaderFile;
        File libraryRoot = new File(minecraftRoot, "libraries");
        File loaderJar = safeChild(libraryRoot, libraryPath);
        String loaderUrl = repositoryUrl + libraryPath;

        JSONArray libraries = new JSONArray();
        JSONObject listedLibraries = container.optJSONObject("libraries");
        if (listedLibraries != null) appendLibraries(libraries, listedLibraries);
        JSONArray loaderLibraries = latest.optJSONArray("libraries");
        if (loaderLibraries != null) {
            for (int index = 0; index < loaderLibraries.length(); index++) {
                JSONObject item = loaderLibraries.optJSONObject(index);
                if (item != null) libraries.put(withSecureLibraryUrl(item));
            }
        }

        JSONObject loaderArtifact = new JSONObject()
                .put("path", libraryPath)
                .put("url", loaderUrl);
        JSONObject loaderDownloads = new JSONObject().put("artifact", loaderArtifact);
        JSONObject loaderLibrary = new JSONObject()
                .put("name", "com.mumfrey:liteloader:" + loaderVersion)
                .put("url", repositoryUrl)
                .put("downloads", loaderDownloads);
        libraries.put(loaderLibrary);

        listener.onProgress("Downloading LiteLoader and required libraries…");
        downloadVerified(loaderUrl, loaderJar, md5, "MD5");
        for (int index = 0; index < libraries.length(); index++) {
            JSONObject item = libraries.getJSONObject(index);
            MinecraftLibraryResolver.Artifact artifact = MinecraftLibraryResolver.resolve(item);
            if (artifact == null) continue;
            File target = safeChild(libraryRoot, artifact.getPath());
            if (target.isFile() && target.length() > 0L) {
                if (isJar(target)) continue;
                if (!target.delete()) throw new IOException("Cannot replace invalid library " + artifact.getPath());
            }
            downloadVerified(artifact.getUrl(), target, artifact.getSha1(), "SHA-1");
        }

        String rawArguments = baseJson.optString("minecraftArguments", "").trim();
        if (rawArguments.isEmpty()) throw new IOException("The base version has no legacy Minecraft arguments.");
        if (!rawArguments.contains(tweakClass)) {
            rawArguments = rawArguments + " --tweakClass " + tweakClass;
        }

        String installedVersionId = baseVersion + "-LiteLoader-" + loaderVersion.replaceAll("[^A-Za-z0-9._+-]", "_");
        File installedVersionFolder = new File(new File(minecraftRoot, "versions"), installedVersionId);
        ensureDirectory(installedVersionFolder);
        JSONObject child = new JSONObject()
                .put("id", installedVersionId)
                .put("type", "release")
                .put("inheritsFrom", baseVersion)
                .put("jar", baseVersion)
                .put("mainClass", "net.minecraft.launchwrapper.Launch")
                .put("minecraftArguments", rawArguments)
                .put("libraries", libraries)
                .put("time", baseJson.optString("time", ""))
                .put("releaseTime", baseJson.optString("releaseTime", ""));
        File childJsonFile = new File(installedVersionFolder, installedVersionId + ".json");
        writeAtomically(childJsonFile, child.toString(2).getBytes(StandardCharsets.UTF_8));

        GameInstance updated = instances.updateVersion(selected.getId(), installedVersionId);
        instances.select(updated.getId());
        listener.onProgress("LiteLoader " + loaderVersion + " installed on Minecraft " +
                baseVersion + " as " + installedVersionId + ".");
        return installedVersionId;
    }

    private JSONObject withSecureLibraryUrl(JSONObject source) throws Exception {
        JSONObject copy = new JSONObject(source.toString());
        String name = copy.optString("name", "");
        if (name.trim().isEmpty()) throw new IOException("LiteLoader dependency has no Maven coordinate.");
        if (!copy.has("url") || copy.optString("url").trim().isEmpty()) {
            String group = name.split(":", 2)[0];
            String url;
            if (group.startsWith("net.minecraft")) url = "https://libraries.minecraft.net/";
            else if (group.startsWith("org.ow2.asm")) url = "https://repo.maven.apache.org/maven2/";
            else if (group.startsWith("org.spongepowered")) url = "https://repo.spongepowered.org/maven/";
            else throw new IOException("No trusted HTTPS repository is known for LiteLoader dependency " + name + ".");
            copy.put("url", url);
        } else {
            copy.put("url", secureRepositoryUrl(copy.getString("url")));
        }
        return copy;
    }

    private void appendLibraries(JSONArray result, JSONObject source) throws Exception {
        for (java.util.Iterator<String> keys = source.keys(); keys.hasNext();) {
            String key = keys.next();
            Object value = source.get(key);
            if (value instanceof JSONObject) result.put(withSecureLibraryUrl((JSONObject) value));
        }
    }

    private String downloadText(String url) throws Exception {
        HttpURLConnection connection = openConnection(url);
        try {
            int status = connection.getResponseCode();
            validateFinalUrl(connection.getURL().toString());
            if (status < 200 || status >= 300) throw new IOException("LiteLoader metadata HTTP " + status);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_METADATA_BYTES) {
                        throw new IOException("LiteLoader metadata exceeds 8 MB.");
                    }
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void downloadVerified(String url, File destination, String checksum, String algorithm) throws Exception {
        ensureDirectory(destination.getParentFile());
        if (destination.isFile() && destination.length() > 0L) {
            if (checksum == null || verifyChecksum(destination, checksum, algorithm)) {
                if (!"jar".equalsIgnoreCase(extension(destination.getName())) || isJar(destination)) return;
            }
        }
        HttpURLConnection connection = openConnection(url);
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        MessageDigest digest = checksum == null ? null : MessageDigest.getInstance(algorithm);
        long total = 0L;
        try {
            int status = connection.getResponseCode();
            validateFinalUrl(connection.getURL().toString());
            if (status < 200 || status >= 300) throw new IOException("LiteLoader dependency HTTP " + status);
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_LIBRARY_BYTES) throw new IOException("LiteLoader library exceeds 256 MB.");
                    output.write(buffer, 0, count);
                    if (digest != null) digest.update(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (total == 0L) throw new IOException("LiteLoader library download was empty.");
            if (digest != null && !hex(digest.digest()).equalsIgnoreCase(checksum)) {
                throw new IOException("LiteLoader " + algorithm + " checksum did not match.");
            }
            if (!isJar(temporary)) throw new IOException("Downloaded LiteLoader dependency is not a valid JAR.");
            if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace existing library.");
            if (!temporary.renameTo(destination)) throw new IOException("Cannot install library " + destination.getName());
        } catch (Exception error) {
            temporary.delete();
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isJar(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            return zip.size() > 0;
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean verifyChecksum(File file, String expected, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest()).equalsIgnoreCase(expected);
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static HttpURLConnection openConnection(String url) throws Exception {
        validateFinalUrl(url);
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(90_000);
        connection.setRequestProperty("User-Agent", "TSI-Launcher/0.5");
        return connection;
    }

    private static void validateFinalUrl(String raw) throws Exception {
        URI uri = URI.create(raw);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean allowed = host.equals("dl.liteloader.com") || host.equals("repo.mumfrey.com") ||
                host.equals("libraries.minecraft.net") || host.equals("repo.maven.apache.org") ||
                host.equals("repo1.maven.org") || host.equals("repo.spongepowered.org");
        if (!"https".equals(scheme) || !allowed) {
            throw new IllegalArgumentException("Refusing non-HTTPS or untrusted LiteLoader URL: " + host);
        }
    }

    private static String secureRepositoryUrl(String raw) throws Exception {
        URI uri = URI.create(raw.trim());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("dl.liteloader.com") || host.equals("repo.mumfrey.com") ||
                host.equals("libraries.minecraft.net") || host.equals("repo.maven.apache.org") ||
                host.equals("repo1.maven.org") || host.equals("repo.spongepowered.org"))) {
            throw new IllegalArgumentException("Untrusted LiteLoader Maven repository: " + host);
        }
        return "https://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) +
                (uri.getRawPath() == null ? "/" : uri.getRawPath());
    }

    private static File safeChild(File parent, String relative) throws IOException {
        if (relative == null || relative.startsWith("/") || relative.indexOf('\\') >= 0 ||
                relative.contains("..")) throw new IOException("Unsafe LiteLoader library path.");
        File root = parent.getCanonicalFile();
        File target = new File(parent, relative).getCanonicalFile();
        if (!target.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("LiteLoader library path escapes Minecraft storage.");
        }
        return target;
    }

    private static String safeCoordinatePart(String value, String label) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._+!-]{1,100}")) {
            throw new IOException("Invalid " + label + " in LiteLoader manifest.");
        }
        return value;
    }

    private static String safeFileName(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._+-]{1,160}\\.jar")) {
            throw new IOException("Invalid LiteLoader JAR filename.");
        }
        return value;
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_METADATA_BYTES) {
                    throw new IOException("Minecraft version metadata exceeds 8 MB.");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeAtomically(File target, byte[] data) throws IOException {
        ensureDirectory(target.getParentFile());
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(data);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("Could not replace old LiteLoader profile metadata.");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Could not save LiteLoader profile metadata.");
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
    }
}
