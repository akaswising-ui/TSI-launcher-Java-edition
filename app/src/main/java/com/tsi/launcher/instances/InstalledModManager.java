package com.tsi.launcher.instances;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipFile;

/**
 * Safe local .jar mod management scoped to one instance's game directory.
 * Mods are enabled as .jar files and disabled by appending ".disabled".
 */
public final class InstalledModManager {
    private static final long MAX_MOD_BYTES = 256L * 1024L * 1024L;
    private final File gameDirectory;
    private final File modsDirectory;

    public InstalledModManager(File instanceGameDirectory) throws IOException {
        if (instanceGameDirectory == null) throw new IOException("No game instance directory was selected.");
        gameDirectory = instanceGameDirectory.getCanonicalFile();
        if (!gameDirectory.isDirectory() && !gameDirectory.mkdirs()) {
            throw new IOException("Cannot create the selected instance game directory.");
        }
        modsDirectory = new File(gameDirectory, "mods").getCanonicalFile();
        if (!gameDirectory.equals(modsDirectory.getParentFile())) {
            throw new IOException("The mods folder resolves outside the selected instance.");
        }
        ensureDirectory(modsDirectory);
    }

    public File getDirectory() {
        return modsDirectory;
    }

    public synchronized List<ModFile> listMods() throws IOException {
        ensureDirectory(modsDirectory);
        File[] entries = modsDirectory.listFiles();
        if (entries == null) throw new IOException("Could not read the instance mods folder.");
        List<ModFile> result = new ArrayList<>();
        for (File entry : entries) {
            if (!entry.isFile() || !isManagedName(entry.getName())) continue;
            File canonical = entry.getCanonicalFile();
            if (!modsDirectory.equals(canonical.getParentFile())) continue;
            if (!entry.getAbsoluteFile().equals(canonical)) continue;
            result.add(new ModFile(canonical, isEnabledName(entry.getName())));
        }
        Collections.sort(result, new Comparator<ModFile>() {
            @Override public int compare(ModFile left, ModFile right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        return result;
    }

    public synchronized ModFile importJar(String sourceName, InputStream source) throws IOException {
        if (source == null) throw new IOException("Android could not open the selected mod file.");
        String safeName = sanitizeJarName(sourceName);
        ensureDirectory(modsDirectory);
        File temporary = new File(modsDirectory, ".tsi-import-" + UUID.randomUUID() + ".tmp");
        long total = 0L;
        try (InputStream input = new BufferedInputStream(source);
             FileOutputStream raw = new FileOutputStream(temporary);
             BufferedOutputStream output = new BufferedOutputStream(raw)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_MOD_BYTES) {
                    throw new IOException("Mod JAR exceeds the 256 MB import limit.");
                }
                output.write(buffer, 0, count);
            }
            output.flush();
            raw.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (total == 0L) {
            temporary.delete();
            throw new IOException("The selected mod JAR is empty.");
        }
        try (ZipFile archive = new ZipFile(temporary)) {
            if (archive.size() <= 0) throw new IOException("The selected JAR contains no files.");
        } catch (IOException error) {
            temporary.delete();
            throw new IOException("The selected file is not a valid JAR/ZIP archive.", error);
        }

        File destination = uniqueDestination(safeName);
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not install the mod into this instance.");
        }
        return new ModFile(destination, true);
    }

    public synchronized ModFile setEnabled(File source, boolean enabled) throws IOException {
        File current = requireManagedFile(source);
        boolean currentlyEnabled = isEnabledName(current.getName());
        if (currentlyEnabled == enabled) return new ModFile(current, currentlyEnabled);
        String destinationName = enabled
                ? current.getName().substring(0, current.getName().length() - ".disabled".length())
                : current.getName() + ".disabled";
        File destination = new File(modsDirectory, destinationName);
        if (destination.exists()) throw new IOException("A mod with that active/disabled name already exists.");
        if (!current.renameTo(destination)) throw new IOException("Could not change the mod's enabled state.");
        return new ModFile(destination, enabled);
    }

    public synchronized void delete(File source) throws IOException {
        File target = requireManagedFile(source);
        if (!target.delete()) throw new IOException("Could not delete " + target.getName() + ".");
    }

    private File requireManagedFile(File source) throws IOException {
        if (source == null) throw new IOException("No mod file was selected.");
        File canonical = source.getCanonicalFile();
        if (!modsDirectory.equals(canonical.getParentFile()) ||
                !source.getAbsoluteFile().equals(canonical) ||
                !canonical.isFile() || !isManagedName(canonical.getName())) {
            throw new IOException("Refusing to modify a file outside this instance's mods folder.");
        }
        return canonical;
    }

    private File uniqueDestination(String fileName) throws IOException {
        File candidate = new File(modsDirectory, fileName);
        if (!candidate.exists()) return candidate;
        String stem = fileName.substring(0, fileName.length() - 4);
        for (int suffix = 2; suffix <= 9999; suffix++) {
            candidate = new File(modsDirectory, stem + " (" + suffix + ").jar");
            if (!candidate.exists()) return candidate;
        }
        throw new IOException("Too many files with the same mod name.");
    }

    private static String sanitizeJarName(String value) throws IOException {
        if (value == null) throw new IOException("The selected file has no name.");
        String name = value.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") ||
                ".".equals(name) || "..".equals(name)) {
            throw new IOException("The selected mod file name is invalid.");
        }
        name = name.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_");
        if (name.length() > 180) name = name.substring(name.length() - 180);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Select a single .jar mod file, not a modpack or installer archive.");
        }
        return name;
    }

    private static boolean isManagedName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".jar.disabled");
    }

    private static boolean isEnabledName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create folder: " + directory.getAbsolutePath());
        }
    }

    public static final class ModFile {
        private final File file;
        private final boolean enabled;

        ModFile(File file, boolean enabled) {
            this.file = file;
            this.enabled = enabled;
        }

        public File getFile() { return file; }
        public String getName() { return file.getName(); }
        public long getSizeBytes() { return file.length(); }
        public boolean isEnabled() { return enabled; }
    }
}
