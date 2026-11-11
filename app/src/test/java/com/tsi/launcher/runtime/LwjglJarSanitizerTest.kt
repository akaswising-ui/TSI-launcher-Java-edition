package com.tsi.launcher.runtime

import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LwjglJarSanitizerTest {
    @Rule @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unsealsOnlyLwjglPackagesAndKeepsOtherPackagesSealed() {
        val gameRoot = temporaryFolder.newFolder("game")
        val directory = File(gameRoot, "libraries/org/lwjgl/lwjgl/2.9.1")
        assertTrue(directory.mkdirs())
        val jar = File(directory, "lwjgl-2.9.1.jar")
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Sealed", "true")
        }
        writeJar(jar, manifest, listOf(
            "org/lwjgl/AWTGLCanvas.class",
            "org/lwjgl/opengl/GLContext.class",
            "com/vendor/KeepSealed.class"
        ))

        val result = LwjglJarSanitizer.sanitize(gameRoot)
        assertEquals(1, result.scannedJars)
        assertEquals(1, result.modifiedJars)
        assertTrue(result.skippedSignedJars.isEmpty())

        JarFile(jar, false).use { fixed ->
            val fixedManifest = fixed.manifest
            assertEquals("true", fixedManifest.mainAttributes.getValue("Sealed"))
            assertEquals("false", fixedManifest.getAttributes("org/lwjgl/")?.getValue("Sealed"))
            assertEquals("false", fixedManifest.getAttributes("org/lwjgl/opengl/")?.getValue("Sealed"))
            assertNull(fixedManifest.getAttributes("com/vendor/"))
            assertNotNull(fixed.getEntry("org/lwjgl/AWTGLCanvas.class"))
            assertNotNull(fixed.getEntry("com/vendor/KeepSealed.class"))
        }
    }

    @Test
    fun repairsLegacyLibrariesAndExtractedLwjgl3Jars() {
        val gameRoot = temporaryFolder.newFolder("game")
        val legacyDirectory = File(gameRoot, "libraries/org/lwjgl/lwjgl/2.9.1")
        val engineDirectory = File(gameRoot, "lwjgl3/3.3.3")
        assertTrue(legacyDirectory.mkdirs())
        assertTrue(engineDirectory.mkdirs())

        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Sealed", "true")
        }
        val legacyJar = File(legacyDirectory, "lwjgl-2.9.1.jar")
        val engineJar = File(engineDirectory, "lwjgl.jar")
        writeJar(legacyJar, manifest, listOf("org/lwjgl/AWTGLCanvas.class"))
        writeJar(engineJar, manifest, listOf("org/lwjgl/Version.class"))

        val result = LwjglJarSanitizer.sanitize(gameRoot)
        assertEquals(2, result.scannedJars)
        assertEquals(2, result.modifiedJars)

        listOf(legacyJar, engineJar).forEach { jar ->
            JarFile(jar, false).use {
                assertEquals("false", it.manifest.getAttributes("org/lwjgl/")?.getValue("Sealed"))
            }
        }
    }

    @Test
    fun alreadyUnsealedJarIsNotModified() {
        val gameRoot = temporaryFolder.newFolder("game")
        val directory = File(gameRoot, "libraries/org/lwjgl/lwjgl/2.9.1")
        assertTrue(directory.mkdirs())
        val jar = File(directory, "lwjgl-2.9.1.jar")
        writeJar(jar, Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
        }, listOf("org/lwjgl/AWTGLCanvas.class"))

        val result = LwjglJarSanitizer.sanitize(gameRoot)
        assertEquals(0, result.modifiedJars)
        assertTrue(result.skippedSignedJars.isEmpty())
        JarFile(jar, false).use { assertNull(it.manifest.getAttributes("org/lwjgl/")) }
    }

    @Test
    fun signedJarIsSkippedInsteadOfInvalidatingItsSignature() {
        val gameRoot = temporaryFolder.newFolder("game")
        val directory = File(gameRoot, "libraries/org/lwjgl/lwjgl/2.9.1")
        assertTrue(directory.mkdirs())
        val jar = File(directory, "lwjgl-2.9.1.jar")
        writeJar(jar, Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Sealed", "true")
        }, listOf("org/lwjgl/AWTGLCanvas.class", "META-INF/TEST.SF"))

        val result = LwjglJarSanitizer.sanitize(gameRoot)
        assertEquals(0, result.modifiedJars)
        assertEquals(listOf(jar.absolutePath), result.skippedSignedJars)
        JarFile(jar, false).use {
            assertEquals("true", it.manifest.mainAttributes.getValue("Sealed"))
        }
    }

    private fun writeJar(file: File, manifest: Manifest, entries: List<String>) {
        JarOutputStream(FileOutputStream(file), manifest).use { output ->
            entries.forEach { name ->
                output.putNextEntry(JarEntry(name))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
            }
        }
    }
}
