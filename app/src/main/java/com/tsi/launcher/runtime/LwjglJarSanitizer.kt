package com.tsi.launcher.runtime

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
            // Use same-directory renames instead of java.nio.file.Files so
            // this remains compatible with the engine's minSdk 21.
            val backup = File(parent, jar.name + ".tsi-original.bak")
            if (backup.exists()) {
                if (!jar.exists()) {
                    if (!backup.renameTo(jar)) {
                        throw IOException("Could not restore interrupted LWJGL backup: ${backup.absolutePath}")
                    }
                } else if (!backup.delete()) {
                    throw IOException("Could not clear stale LWJGL backup: ${backup.absolutePath}")
                }
            }
            if (!jar.renameTo(backup)) {
                throw IOException("Could not stage original LWJGL JAR safely: ${jar.absolutePath}")
            }
            if (!temporary.renameTo(jar)) {
                if (!backup.renameTo(jar)) {
                    throw IOException(
                        "Could not install repaired LWJGL JAR or restore its backup. " +
                            "Original backup remains at ${backup.absolutePath}"
                    )
                }
                throw IOException("Could not install repaired LWJGL JAR: ${jar.absolutePath}")
            }
            // The replacement is now in place; a leftover backup is harmless
            // and will be cleaned on the next launch if deletion fails.
            backup.delete()
        } catch (error: Exception) {
            temporary.delete()
            if (error is IOException) throw error
            throw IOException("Could not normalize LWJGL package sealing in ${jar.absolutePath}", error)
        }
    }
}
