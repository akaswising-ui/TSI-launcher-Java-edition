package com.tsi.launcher.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import kotlin.Pair;

/** Builds compatibility paths needed by legacy Minecraft asset loaders. */
public final class MinecraftAssetLayout {
    private MinecraftAssetLayout() { }

    public static boolean hasExpectedSha1(File file, String expected) {
        return file.isFile() && matchesSha1(file, expected);
    }

    public static List<String> uniqueObjectHashes(List<? extends Pair<String, String>> assetEntries) {
        Set<String> unique = new LinkedHashSet<>();
        for (Pair<String, String> entry : assetEntries) {
            if (entry != null && entry.getSecond() != null) unique.add(entry.getSecond());
        }
        return new ArrayList<>(unique);
    }

    public static int materialize(
            boolean virtualAssets,
            boolean mapToResources,
            Iterable<MinecraftLegacyAsset> assetObjects,
            File assetsRoot,
            File resourcesRoot) throws IOException {
        if (!virtualAssets && !mapToResources) return 0;

        File objectRoot = new File(assetsRoot, "objects").getCanonicalFile();
        File targetRoot;
        if (mapToResources) targetRoot = resourcesRoot.getCanonicalFile();
        else if (virtualAssets) targetRoot = new File(assetsRoot, "virtual/legacy").getCanonicalFile();
        else targetRoot = assetsRoot.getCanonicalFile();
        ensureDirectory(targetRoot);

        int materialized = 0;
        for (MinecraftLegacyAsset asset : assetObjects) {
            String assetName = asset.getName();
            String hash = asset.getHash().toLowerCase(Locale.ROOT);
            if (!hash.matches("[a-f0-9]{40}")) {
                throw new IOException("Invalid SHA-1 for legacy asset: " + assetName);
            }
            long expectedSize = asset.getSize();
            if (expectedSize < 0L) throw new IOException("Missing size for legacy asset: " + assetName);

            File source = new File(new File(objectRoot, hash.substring(0, 2)), hash).getCanonicalFile();
            requireInside(objectRoot, source, "Asset object path escapes the object cache.");
            if (!source.isFile() || source.length() != expectedSize || !matchesSha1(source, hash)) {
                throw new IOException("Asset object is missing or corrupt: " + assetName);
            }

            File target = safeChild(targetRoot, assetName);
            if (!target.isFile() || target.length() != expectedSize || !matchesSha1(target, hash)) {
                File parent = target.getParentFile();
                if (parent == null) throw new IOException("Legacy asset has no parent folder.");
                ensureDirectory(parent);
                File temporary = new File(parent, target.getName() + "." + UUID.randomUUID() + ".part");
                try {
                    copySynced(source, temporary);
                    if (!temporary.isFile() || temporary.length() != expectedSize || !matchesSha1(temporary, hash)) {
                        throw new IOException("Could not verify materialized legacy asset: " + assetName);
                    }
                    replaceAtomically(temporary, target);
                } finally {
                    if (temporary.exists()) temporary.delete();
                }
            }
            materialized++;
        }
        return materialized;
    }

    private static File safeChild(File root, String relativePath) throws IOException {
        if (relativePath == null || relativePath.trim().isEmpty() ||
                relativePath.startsWith("/") || relativePath.indexOf('\\') >= 0 ||
                relativePath.indexOf('\u0000') >= 0) {
            throw new IOException("Invalid legacy asset path: " + relativePath);
        }
        File target = new File(root, relativePath).getCanonicalFile();
        requireInside(root, target, "Legacy asset path escapes its destination folder.");
        return target;
    }

    private static void requireInside(File root, File child, String message) throws IOException {
        String prefix = root.getCanonicalPath() + File.separator;
        if (!child.getCanonicalPath().startsWith(prefix)) throw new IOException(message);
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create legacy asset directory: " + directory.getAbsolutePath());
        }
        if (!directory.isDirectory()) {
            throw new IOException("Legacy asset destination is not a directory: " + directory.getAbsolutePath());
        }
    }

    private static boolean matchesSha1(File file, String expected) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return toHex(digest.digest()).equalsIgnoreCase(expected);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder result = new StringBuilder(data.length * 2);
        for (byte value : data) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void copySynced(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static void replaceAtomically(File source, File target) throws IOException {
        File parent = target.getParentFile();
        File backup = new File(parent, target.getName() + ".tsi-backup");
        if (backup.exists() && !backup.delete()) {
            throw new IOException("Cannot clear stale asset backup: " + backup);
        }
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) {
            throw new IOException("Cannot stage existing legacy asset: " + target);
        }
        if (!source.renameTo(target)) {
            if (hadTarget && !backup.renameTo(target)) {
                throw new IOException("Asset replacement failed; original remains at " + backup);
            }
            throw new IOException("Cannot install legacy asset: " + target);
        }
        if (backup.exists()) backup.delete();
    }
}
