package com.tsi.launcher.storage;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Resolves game-instance directories and performs one-time legacy storage migration. */
public final class VersionDirectoryManager {
    private static final Object MIGRATION_LOCK = new Object();
    private final Context appContext;
    private final File rootDirectory;
    private final File legacyTsiRoot;
    private final File migrationMarker;

    public VersionDirectoryManager(Context context) {
        appContext = context.getApplicationContext();
        rootDirectory = AmethystStoragePaths.minecraftHome(appContext);
        File external = appContext.getExternalFilesDir(null);
        legacyTsiRoot = new File(external != null ? external : appContext.getFilesDir(), ".minecraft");
        migrationMarker = new File(rootDirectory, ".tsi-legacy-storage-import-v1");
    }

    /**
     * Copy only files missing from Amethyst's active .minecraft folder.
     * Source files are never deleted or overwritten.
     */
    public int migrateLegacyStorage() throws IOException {
        synchronized (MIGRATION_LOCK) {
            try {
                if (rootDirectory.getCanonicalFile().equals(legacyTsiRoot.getCanonicalFile())) return 0;
            } catch (IOException ignored) {
                return 0;
            }
            if (!legacyTsiRoot.isDirectory() || migrationMarker.isFile() ||
                    !AmethystStoragePaths.hasLegacyStoragePermission(appContext)) return 0;

            File sourceRoot = legacyTsiRoot.getCanonicalFile();
            File destinationRoot = rootDirectory.getCanonicalFile();
            File[] children = legacyTsiRoot.listFiles();
            if (children == null) return 0;
            ensureDirectory(rootDirectory);

            int copied = 0;
            for (File child : children) {
                copied += copyMissingEntry(child, new File(rootDirectory, child.getName()),
                        sourceRoot, destinationRoot);
            }

            File temporaryMarker = new File(rootDirectory, migrationMarker.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporaryMarker)) {
                output.write("completed".getBytes("UTF-8"));
                output.getFD().sync();
            }
            if (!temporaryMarker.renameTo(migrationMarker)) {
                temporaryMarker.delete();
                throw new IOException("Could not save the legacy Minecraft storage migration marker.");
            }
            return copied;
        }
    }

    private int copyMissingEntry(File source, File destination,
                                 File sourceRoot, File destinationRoot) throws IOException {
        File canonicalSource;
        File canonicalDestination;
        try {
            canonicalSource = source.getCanonicalFile();
            canonicalDestination = destination.getCanonicalFile();
        } catch (IOException error) {
            return 0;
        }

        if (!inside(sourceRoot, canonicalSource) || !inside(destinationRoot, canonicalDestination)) {
            return 0; // Do not follow symbolic links outside either root.
        }

        if (source.isDirectory()) {
            if (destination.exists() && !destination.isDirectory()) return 0;
            File[] children = source.listFiles();
            if (children == null) {
                throw new IOException("Cannot read legacy Minecraft folder: " + source.getAbsolutePath());
            }
            ensureDirectory(destination);
            int copied = 0;
            for (File child : children) {
                copied += copyMissingEntry(child, new File(destination, child.getName()),
                        sourceRoot, destinationRoot);
            }
            return copied;
        }

        if (!source.isFile() || destination.exists()) return 0;
        String name = source.getName();
        if (name.endsWith(".part") || name.endsWith(".tmp") ||
                name.endsWith(".tsi-migration.part")) return 0;

        File parent = destination.getParentFile();
        ensureDirectory(parent == null ? rootDirectory : parent);
        File temporary = new File(parent, destination.getName() + ".tsi-migration.part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Cannot clear temporary migration file: " + temporary.getAbsolutePath());
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }

        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Cannot finish copying legacy Minecraft file: " +
                    destination.getAbsolutePath());
        }
        return 1;
    }

    private static boolean inside(File root, File child) throws IOException {
        String rootPath = root.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
    }

    public VersionPaths ensureVersionLayout(String versionId) throws IOException {
        return ensureVersionLayout(versionId, versionId);
    }

    public VersionPaths ensureVersionLayout(String versionId, String instanceId) throws IOException {
        String safeId = safeSegment(versionId);
        String safeInstanceId = safeSegment(instanceId);
        migrateLegacyStorage();
        ensureDirectory(rootDirectory);

        File versionsRoot = child(rootDirectory, "versions");
        File instancesRoot = child(rootDirectory, "instances");
        File versionDirectory = child(versionsRoot, safeId);
        File gameDirectory = child(child(instancesRoot, safeInstanceId), "game");
        File assetsDirectory = child(rootDirectory, "assets");
        File librariesDirectory = child(rootDirectory, "libraries");

        File[] directories = {
                versionsRoot, instancesRoot, versionDirectory, gameDirectory,
                child(gameDirectory, "saves"), child(gameDirectory, "resourcepacks"),
                child(gameDirectory, "screenshots"), child(gameDirectory, "mods"),
                child(gameDirectory, "config"), child(gameDirectory, "logs"),
                assetsDirectory, librariesDirectory
        };
        for (File directory : directories) ensureDirectory(directory);

        return new VersionPaths(versionDirectory, gameDirectory, assetsDirectory, librariesDirectory);
    }

    public File root() { return rootDirectory; }

    private static String safeSegment(String value) {
        if (value == null) throw new IllegalArgumentException("Invalid Minecraft version ID");
        String safe = value.replaceAll("[^A-Za-z0-9._+-]", "_");
        if (safe.trim().isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            throw new IllegalArgumentException("Invalid Minecraft version ID");
        }
        return safe;
    }

    private static File child(File parent, String name) throws IOException {
        File candidate = new File(parent, name).getCanonicalFile();
        File safeParent = parent.getCanonicalFile();
        if (!candidate.getPath().startsWith(safeParent.getPath() + File.separator)) {
            throw new IOException("Refusing to create a directory outside the TSI Minecraft folder");
        }
        return candidate;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create directory: " + directory.getAbsolutePath());
        }
        if (!directory.isDirectory()) {
            throw new IOException("Expected a directory: " + directory.getAbsolutePath());
        }
    }
}
