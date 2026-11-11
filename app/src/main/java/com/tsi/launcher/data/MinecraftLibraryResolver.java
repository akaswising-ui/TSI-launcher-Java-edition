package com.tsi.launcher.data;

import org.json.JSONObject;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resolves libraries from current Mojang metadata and legacy Maven coordinates.
 * Desktop native classifier artifacts are skipped; Amethyst supplies Android natives.
 */
public final class MinecraftLibraryResolver {
    private static final Pattern SAFE_PART = Pattern.compile("[A-Za-z0-9_.-]+");

    private MinecraftLibraryResolver() { }

    public static final class Artifact {
        private final String path;
        private final String url;
        private final String sha1;
        private final long size;

        public Artifact(String path, String url, String sha1, long size) {
            this.path = path;
            this.url = url;
            this.sha1 = sha1;
            this.size = size;
        }
        public String getPath() { return path; }
        public String getUrl() { return url; }
        public String getSha1() { return sha1; }
        public long getSize() { return size; }
    }

    public static Artifact resolve(JSONObject library) throws IOException {
        JSONObject downloads = library.optJSONObject("downloads");
        JSONObject artifact = downloads == null ? null : downloads.optJSONObject("artifact");
        if (artifact != null) {
            String path = artifact.optString("path").trim();
            if (path.isEmpty()) {
                throw new IOException("Library metadata has no artifact path: " + library.optString("name"));
            }
            String sha1 = artifact.optString("sha1").trim();
            if (!sha1.matches("[a-fA-F0-9]{40}")) sha1 = null;
            long size = artifact.optLong("size", -1L);
            String directUrl = artifact.optString("url").trim();
            String baseUrl = library.optString("url").trim();
            if (baseUrl.isEmpty()) baseUrl = "https://libraries.minecraft.net/";
            String url = directUrl.isEmpty() ? trimSlash(baseUrl) + "/" + path : directUrl;
            return new Artifact(path, url, sha1, size);
        }

        // Classifier-only modern entries are native payloads, not classpath JARs.
        if (downloads != null) return null;

        String rawName = library.optString("name").trim();
        if (rawName.isEmpty()) return null;
        String[] extensionParts = rawName.split("@", 2);
        String coordinate = extensionParts[0];
        String extension = extensionParts.length > 1 && !extensionParts[1].trim().isEmpty()
                ? extensionParts[1].trim() : "jar";
        if (!extension.matches("[A-Za-z0-9]{1,10}")) return null;

        String[] parts = coordinate.split(":", -1);
        if (parts.length < 3 || parts.length > 4) return null;
        String group = parts[0];
        String module = parts[1];
        String version = parts[2];
        String classifier = parts.length == 4 && !parts[3].trim().isEmpty() ? parts[3] : null;

        if (!SAFE_PART.matcher(group).matches() ||
                !SAFE_PART.matcher(module).matches() ||
                !version.matches("[A-Za-z0-9_.+!-]+") ||
                (classifier != null && !SAFE_PART.matcher(classifier).matches())) {
            throw new IOException("Unsafe legacy Maven coordinate: " + rawName);
        }

        boolean hasNativeRules = library.optJSONObject("natives") != null;
        if ((classifier != null && classifier.toLowerCase(Locale.ROOT).startsWith("natives-")) ||
                (hasNativeRules && module.toLowerCase(Locale.ROOT).endsWith("-platform"))) {
            return null;
        }

        StringBuilder fileName = new StringBuilder(module).append('-').append(version);
        if (classifier != null) fileName.append('-').append(classifier);
        fileName.append('.').append(extension);
        String path = group.replace('.', '/') + "/" + module + "/" + version + "/" + fileName;
        String baseUrl = library.optString("url").trim();
        if (baseUrl.isEmpty()) baseUrl = "https://libraries.minecraft.net/";
        return new Artifact(path, trimSlash(baseUrl) + "/" + path, null, -1L);
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
