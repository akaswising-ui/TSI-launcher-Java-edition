package com.tsi.launcher.runtime;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Repairs URLClassLoader sealing conflicts when multiple LWJGL JARs contribute
 * classes to org.lwjgl. Other package sealing metadata is left untouched.
 */
public final class LwjglJarSanitizer {
    private LwjglJarSanitizer() { }

    public static final class Result {
        private final int scannedJars;
        private final int modifiedJars;
        private final List<String> skippedSignedJars;

        Result(int scannedJars, int modifiedJars, List<String> skippedSignedJars) {
            this.scannedJars = scannedJars;
            this.modifiedJars = modifiedJars;
            this.skippedSignedJars = new ArrayList<>(skippedSignedJars);
        }
        public int getScannedJars() { return scannedJars; }
        public int getModifiedJars() { return modifiedJars; }
        public List<String> getSkippedSignedJars() {
            return new ArrayList<>(skippedSignedJars);
        }
    }

    private enum JarResult { UNCHANGED, MODIFIED, SIGNED }

    public static Result sanitize(File gameRoot) throws IOException {
        if (gameRoot == null || !gameRoot.isDirectory()) {
            throw new IOException("Minecraft game directory is unavailable: " +
                    (gameRoot == null ? "null" : gameRoot.getAbsolutePath()));
        }

        List<File> roots = new ArrayList<>();
        addDirectory(roots, new File(gameRoot, "libraries/org/lwjgl"));
        addDirectory(roots, new File(gameRoot, "lwjgl3"));
        addDirectory(roots, new File(gameRoot, "components"));

        Set<String> seen = new LinkedHashSet<>();
        List<File> jars = new ArrayList<>();
        for (File root : roots) {
            collectJars(root, jars, seen);
        }

        int scanned = 0;
        int modified = 0;
        List<String> signed = new ArrayList<>();
        for (File jar : jars) {
            scanned++;
            JarResult result = sanitizeJar(jar);
            if (result == JarResult.MODIFIED) modified++;
            else if (result == JarResult.SIGNED) signed.add(jar.getAbsolutePath());
        }
        return new Result(scanned, modified, signed);
    }

    private static void addDirectory(List<File> roots, File candidate) {
        if (candidate.isDirectory()) roots.add(candidate);
    }

    private static void collectJars(File directory, List<File> jars, Set<String> seen) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectJars(child, jars, seen);
            } else if (child.isFile() && child.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                try {
                    String canonical = child.getCanonicalPath();
                    if (seen.add(canonical)) jars.add(child.getCanonicalFile());
                } catch (IOException ignored) {
                    // An inaccessible JAR is not rewritten; later class loading will report it.
                }
            }
        }
    }

    private static JarResult sanitizeJar(File jar) throws IOException {
        Manifest manifest;
        Set<String> packagesToUnseal = new LinkedHashSet<>();

        try (JarFile source = new JarFile(jar, false)) {
            Enumeration<JarEntry> entries = source.entries();
            Set<String> packageNames = new LinkedHashSet<>();
            boolean signed = false;

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith("org/lwjgl/") &&
                        name.endsWith(".class")) {
                    int separator = name.lastIndexOf('/');
                    if (separator > 0) packageNames.add(name.substring(0, separator + 1));
                }
                if (isSignatureFile(name)) signed = true;
            }
            if (packageNames.isEmpty()) return JarResult.UNCHANGED;

            java.util.jar.JarEntry manifestEntry = source.getJarEntry(JarFile.MANIFEST_NAME);
            if (manifestEntry == null) return JarResult.UNCHANGED;
            try (java.io.InputStream input = source.getInputStream(manifestEntry)) {
                manifest = new Manifest(input);
            }

            boolean mainSealed = "true".equalsIgnoreCase(
                    manifest.getMainAttributes().getValue(Attributes.Name.SEALED));
            for (String packageName : packageNames) {
                Attributes packageAttributes = manifest.getAttributes(packageName);
                boolean packageSealed = packageAttributes != null &&
                        "true".equalsIgnoreCase(packageAttributes.getValue(Attributes.Name.SEALED));
                if (mainSealed || packageSealed) packagesToUnseal.add(packageName);
            }
            if (packagesToUnseal.isEmpty()) return JarResult.UNCHANGED;
            if (signed) return JarResult.SIGNED;
        }

        for (String packageName : packagesToUnseal) {
            Attributes attributes = manifest.getEntries().get(packageName);
            if (attributes == null) {
                attributes = new Attributes();
                manifest.getEntries().put(packageName, attributes);
            }
            attributes.putValue(Attributes.Name.SEALED.toString(), "false");
        }
        rewriteJar(jar, manifest);
        return JarResult.MODIFIED;
    }

    private static boolean isSignatureFile(String entryName) {
        if (!entryName.regionMatches(true, 0, "META-INF/", 0, "META-INF/".length())) return false;
        String name = entryName.substring(entryName.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
        return name.endsWith(".SF") || name.endsWith(".RSA") ||
                name.endsWith(".DSA") || name.endsWith(".EC") || name.startsWith("SIG-");
    }

    private static void rewriteJar(File jar, Manifest manifest) throws IOException {
        File parent = jar.getParentFile();
        if (parent == null) throw new IOException("LWJGL JAR has no parent directory: " + jar);
        File temporary = new File(parent, jar.getName() + ".tsi-unsealed.tmp");
        File backup = new File(parent, jar.getName() + ".tsi-original.bak");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Could not clear temporary LWJGL JAR: " + temporary);
        }

        try {
            try (JarFile source = new JarFile(jar, false);
                 JarOutputStream output = new JarOutputStream(
                         new BufferedOutputStream(new FileOutputStream(temporary)))) {
                output.putNextEntry(new JarEntry(JarFile.MANIFEST_NAME));
                manifest.write(output);
                output.closeEntry();

                Enumeration<JarEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.getName())) continue;
                    JarEntry copied = new JarEntry(entry.getName());
                    if (entry.getTime() >= 0L) copied.setTime(entry.getTime());
                    if (entry.getComment() != null) copied.setComment(entry.getComment());
                    if (entry.getExtra() != null) copied.setExtra(entry.getExtra());
                    output.putNextEntry(copied);
                    if (!entry.isDirectory()) {
                        try (java.io.InputStream input = source.getInputStream(entry)) {
                            byte[] buffer = new byte[64 * 1024];
                            int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        }
                    }
                    output.closeEntry();
                }
            }

            if (!temporary.isFile() || temporary.length() == 0L) {
                throw new IOException("Rewritten LWJGL JAR is empty: " + jar);
            }
            if (backup.exists()) {
                if (!jar.exists()) {
                    if (!backup.renameTo(jar)) {
                        throw new IOException("Could not restore interrupted LWJGL backup: " + backup);
                    }
                } else if (!backup.delete()) {
                    throw new IOException("Could not clear stale LWJGL backup: " + backup);
                }
            }
            if (!jar.renameTo(backup)) {
                throw new IOException("Could not stage original LWJGL JAR safely: " + jar);
            }
            if (!temporary.renameTo(jar)) {
                if (!backup.renameTo(jar)) {
                    throw new IOException("Could not install fixed JAR or restore original. Backup: " + backup);
                }
                throw new IOException("Could not install repaired LWJGL JAR: " + jar);
            }
            backup.delete();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        } catch (Exception error) {
            temporary.delete();
            throw new IOException("Could not normalize LWJGL package sealing in " + jar, error);
        }
    }
}
