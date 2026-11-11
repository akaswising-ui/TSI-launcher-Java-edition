package com.tsi.launcher.runtime

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Repairs a URLClassLoader failure caused by a sealed org.lwjgl package
 * being split across multiple LWJGL JARs. Unrelated package sealing is kept.
 */
object LwjglJarSanitizer {
    data class Result(
        val scannedJars: Int,
        val modifiedJars: Int,
        val skippedSignedJars: List<String>
    )

    fun sanitize(gameRoot: File): Result {
        if (!gameRoot.isDirectory) {
            throw IOException("Minecraft game directory is unavailable: ${gameRoot.absolutePath}")
        }

        // Legacy Minecraft libraries and Amethyst's extracted LWJGL 3 assets
        // live in different directories under the same game root.
        val roots = listOf(
            File(gameRoot, "libraries/org/lwjgl"),
            File(gameRoot, "lwjgl3"),
            File(gameRoot, "components")
        ).filter { it.isDirectory }

        val jars = linkedSetOf<File>()
        roots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                .forEach { jars += it.canonicalFile }
        }

        var scanned = 0
        var modified = 0
        val skippedSigned = mutableListOf<String>()
        jars.forEach { jar ->
            scanned++
            when (sanitizeJar(jar)) {
                JarResult.UNCHANGED -> Unit
                JarResult.MODIFIED -> modified++
                JarResult.SIGNED -> skippedSigned += jar.absolutePath
            }
        }
        return Result(scanned, modified, skippedSigned)
    }

    private enum class JarResult { UNCHANGED, MODIFIED, SIGNED }

    private fun sanitizeJar(jar: File): JarResult {
        val manifest: Manifest
        val packagesToUnseal: Set<String>

        JarFile(jar, false).use { source ->
            val packageNames = linkedSetOf<String>()
            val entries = source.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (!entry.isDirectory && name.startsWith("org/lwjgl/") &&
                    name.endsWith(".class", ignoreCase = true)
                ) {
                    val packagePath = name.substringBeforeLast('/', "")
                    if (packagePath.isNotBlank()) packageNames += "$packagePath/"
                }
            }
            if (packageNames.isEmpty()) return JarResult.UNCHANGED

            val manifestEntry = source.getJarEntry(JarFile.MANIFEST_NAME)
                ?: return JarResult.UNCHANGED
            manifest = source.getInputStream(manifestEntry).use { Manifest(it) }

            val mainSealed = manifest.mainAttributes
                .getValue(Attributes.Name.SEALED)
                ?.equals("true", ignoreCase = true) == true
            packagesToUnseal = packageNames.filterTo(linkedSetOf()) { packagePath ->
                val packageSealed = manifest.entries[packagePath]
                    ?.getValue(Attributes.Name.SEALED)
                    ?.equals("true", ignoreCase = true) == true
                mainSealed || packageSealed
            }
            if (packagesToUnseal.isEmpty()) return JarResult.UNCHANGED

            if (source.entries().asSequence().any { isSignatureFile(it.name) }) {
                return JarResult.SIGNED
            }
        }

        packagesToUnseal.forEach { packagePath ->
            manifest.entries.getOrPut(packagePath) { Attributes() }
                .putValue(Attributes.Name.SEALED.toString(), "false")
        }
        rewriteJar(jar, manifest)
        return JarResult.MODIFIED
    }

    private fun isSignatureFile(entryName: String): Boolean {
        if (!entryName.startsWith("META-INF/", ignoreCase = true)) return false
        val name = entryName.substringAfterLast('/').uppercase(Locale.ROOT)
        return name.endsWith(".SF") || name.endsWith(".RSA") ||
            name.endsWith(".DSA") || name.endsWith(".EC") || name.startsWith("SIG-")
    }

    private fun rewriteJar(jar: File, manifest: Manifest) {
        val parent = jar.parentFile
            ?: throw IOException("LWJGL JAR has no parent directory: ${jar.absolutePath}")
        val temporary = File(parent, jar.name + ".tsi-unsealed.tmp")
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Could not clear temporary LWJGL JAR: ${temporary.absolutePath}")
        }

        try {
            JarFile(jar, false).use { source ->
                JarOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                    output.putNextEntry(JarEntry(JarFile.MANIFEST_NAME))
                    manifest.write(output)
                    output.closeEntry()

                    val entries = source.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.equals(JarFile.MANIFEST_NAME, ignoreCase = true)) continue

                        val copied = JarEntry(entry.name).apply {
                            if (entry.time >= 0L) time = entry.time
                            entry.comment?.let { comment = it }
                            entry.extra?.let { extra = it }
                        }
                        output.putNextEntry(copied)
                        if (!entry.isDirectory) {
                            source.getInputStream(entry).use { input ->
                                input.copyTo(output, 64 * 1024)
                            }
                        }
                        output.closeEntry()
                    }
                }
            }

            if (!temporary.isFile || temporary.length() == 0L) {
                throw IOException("Rewritten LWJGL JAR is empty: ${jar.absolutePath}")
            }
            try {
                Files.move(
                    temporary.toPath(), jar.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temporary.delete()
            if (error is IOException) throw error
            throw IOException("Could not normalize LWJGL package sealing in ${jar.absolutePath}", error)
        }
    }
}
