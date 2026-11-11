package com.tsi.launcher.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import static org.junit.Assert.*;

public class LwjglJarSanitizerTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void unsealsOnlyLwjglPackagesAndKeepsOtherPackagesSealed() throws Exception {
        File gameRoot = temporaryFolder.newFolder("game");
        File directory = new File(gameRoot, "libraries/org/lwjgl/lwjgl/2.9.1");
        assertTrue(directory.mkdirs());
        File jar = new File(directory, "lwjgl-2.9.1.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Sealed", "true");
        writeJar(jar, manifest, Arrays.asList(
                "org/lwjgl/AWTGLCanvas.class",
                "org/lwjgl/opengl/GLContext.class",
                "com/vendor/KeepSealed.class"));

        LwjglJarSanitizer.Result result = LwjglJarSanitizer.sanitize(gameRoot);
        assertEquals(1, result.getScannedJars());
        assertEquals(1, result.getModifiedJars());
        assertTrue(result.getSkippedSignedJars().isEmpty());

        try (JarFile fixed = new JarFile(jar, false)) {
            Manifest fixedManifest = fixed.getManifest();
            assertEquals("true", fixedManifest.getMainAttributes().getValue("Sealed"));
            assertEquals("false", fixedManifest.getAttributes("org/lwjgl/").getValue("Sealed"));
            assertEquals("false", fixedManifest.getAttributes("org/lwjgl/opengl/").getValue("Sealed"));
            assertNull(fixedManifest.getAttributes("com/vendor/"));
            assertNotNull(fixed.getEntry("org/lwjgl/AWTGLCanvas.class"));
            assertNotNull(fixed.getEntry("com/vendor/KeepSealed.class"));
        }
    }

    @Test public void repairsLegacyLibrariesAndExtractedLwjgl3Jars() throws Exception {
        File gameRoot = temporaryFolder.newFolder("game");
        File legacyDirectory = new File(gameRoot, "libraries/org/lwjgl/lwjgl/2.9.1");
        File engineDirectory = new File(gameRoot, "lwjgl3/3.3.3");
        assertTrue(legacyDirectory.mkdirs());
        assertTrue(engineDirectory.mkdirs());

        Manifest unsealed = manifest(false);
        Manifest sealed = manifest(true);
        File legacyJar = new File(legacyDirectory, "lwjgl-2.9.1.jar");
        File engineJar = new File(engineDirectory, "lwjgl.jar");
        writeJar(legacyJar, unsealed, Arrays.asList("org/lwjgl/AWTGLCanvas.class"));
        writeJar(engineJar, sealed, Arrays.asList("org/lwjgl/Version.class"));

        LwjglJarSanitizer.Result result = LwjglJarSanitizer.sanitize(gameRoot);
        assertEquals(2, result.getScannedJars());
        assertEquals(1, result.getModifiedJars());
        try (JarFile fixed = new JarFile(engineJar, false)) {
            assertEquals("false", fixed.getManifest().getAttributes("org/lwjgl/").getValue("Sealed"));
        }
    }

    @Test public void alreadyUnsealedJarIsNotModified() throws Exception {
        File gameRoot = temporaryFolder.newFolder("game");
        File directory = new File(gameRoot, "libraries/org/lwjgl/lwjgl/2.9.1");
        assertTrue(directory.mkdirs());
        File jar = new File(directory, "lwjgl-2.9.1.jar");
        writeJar(jar, manifest(false), Arrays.asList("org/lwjgl/AWTGLCanvas.class"));
        LwjglJarSanitizer.Result result = LwjglJarSanitizer.sanitize(gameRoot);
        assertEquals(0, result.getModifiedJars());
        try (JarFile fixed = new JarFile(jar, false)) {
            assertNull(fixed.getManifest().getAttributes("org/lwjgl/"));
        }
    }

    @Test public void signedJarIsSkippedInsteadOfInvalidatingItsSignature() throws Exception {
        File gameRoot = temporaryFolder.newFolder("game");
        File directory = new File(gameRoot, "libraries/org/lwjgl/lwjgl/2.9.1");
        assertTrue(directory.mkdirs());
        File jar = new File(directory, "lwjgl-2.9.1.jar");
        writeJar(jar, manifest(true), Arrays.asList(
                "org/lwjgl/AWTGLCanvas.class", "META-INF/TEST.SF"));
        LwjglJarSanitizer.Result result = LwjglJarSanitizer.sanitize(gameRoot);
        assertEquals(0, result.getModifiedJars());
        assertEquals(1, result.getSkippedSignedJars().size());
        try (JarFile unchanged = new JarFile(jar, false)) {
            assertEquals("true", unchanged.getManifest().getMainAttributes().getValue("Sealed"));
        }
    }

    private static Manifest manifest(boolean sealed) {
        Manifest result = new Manifest();
        result.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (sealed) result.getMainAttributes().putValue("Sealed", "true");
        return result;
    }

    private static void writeJar(File file, Manifest manifest, Iterable<String> entries) throws Exception {
        try (JarOutputStream output = new JarOutputStream(new FileOutputStream(file), manifest)) {
            for (String name : entries) {
                output.putNextEntry(new JarEntry(name));
                output.write(new byte[]{1, 2, 3});
                output.closeEntry();
            }
        }
    }
}
