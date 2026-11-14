package com.tsi.launcher.nativecore;

import java.io.File;
import java.io.IOException;

/**
 * Minimal Android-facing adapter for the Rust launcher core and C++ JNI bridge.
 * Do not put installer, downloader, archive or instance business logic here.
 */
public final class NativeBridge {
    static {
        System.loadLibrary("tsi_core");
        System.loadLibrary("tsi_jni");
    }

    private NativeBridge() { }

    public static boolean sha1Matches(File file, String expectedSha1) throws IOException {
        if (file == null) throw new IllegalArgumentException("File is required.");
        return nativeSha1Matches(file.getAbsolutePath(), expectedSha1);
    }

    public static boolean isVerifiedFile(File file, String expectedSha1,
                                         long expectedSize) throws IOException {
        if (file == null) throw new IllegalArgumentException("File is required.");
        return nativeIsVerifiedFile(file.getAbsolutePath(), expectedSha1, expectedSize);
    }

    public static void downloadVerified(String url, File destination,
                                        String expectedSha1, long expectedSize) throws IOException {
        if (destination == null) throw new IllegalArgumentException("Destination is required.");
        nativeDownloadVerified(url, destination.getAbsolutePath(), expectedSha1, expectedSize);
    }

    public static int extractZip(File archive, File destination,
                                 int maxEntries, long maxUncompressedBytes) throws IOException {
        if (archive == null || destination == null) {
            throw new IllegalArgumentException("ZIP archive and destination are required.");
        }
        return nativeExtractZip(archive.getAbsolutePath(), destination.getAbsolutePath(),
                maxEntries, maxUncompressedBytes);
    }

    public static void ensureGameFolders(File gameDirectory) throws IOException {
        if (gameDirectory == null) throw new IllegalArgumentException("Game directory is required.");
        nativeEnsureGameFolders(gameDirectory.getAbsolutePath());
    }

    public static void ensureInstanceLayout(File minecraftRoot, String instanceId,
                                            String versionId) throws IOException {
        if (minecraftRoot == null) throw new IllegalArgumentException("Minecraft root is required.");
        nativeEnsureInstanceLayout(minecraftRoot.getAbsolutePath(), instanceId, versionId);
    }

    public static void deleteInstance(File minecraftRoot, String instanceId) throws IOException {
        if (minecraftRoot == null) throw new IllegalArgumentException("Minecraft root is required.");
        nativeDeleteInstance(minecraftRoot.getAbsolutePath(), instanceId);
    }

    public static void copyTree(File source, File destination) throws IOException {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Copy source and destination are required.");
        }
        nativeCopyTree(source.getAbsolutePath(), destination.getAbsolutePath());
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
