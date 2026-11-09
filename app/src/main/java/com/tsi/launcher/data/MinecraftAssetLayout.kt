package com.tsi.launcher.data

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Creates the paths required by older Minecraft asset loaders.
 *
 * Modern clients read hash-addressed objects from assets/objects. Older index
 * formats can additionally request assets to be materialized directly under
 * assets/ or resources/. The Amethyst offline launch path does not download
 * those legacy paths after TSI has installed the object cache.
 */
object MinecraftAssetLayout {
    fun materialize(
        assetIndex: JSONObject,
        assetsRoot: File,
        resourcesRoot: File
    ): Int {
        val virtual = assetIndex.optBoolean("virtual", false)
        val mapToResources = assetIndex.optBoolean("map_to_resources", false)
        if (!virtual && !mapToResources) return 0

        val objects = assetIndex.optJSONObject("objects")
            ?: throw IOException("Legacy asset index has no objects map.")
        val objectRoot = File(assetsRoot, "objects").canonicalFile
        val targetRoot = (if (mapToResources) resourcesRoot else assetsRoot).canonicalFile
        ensureDirectory(targetRoot)

        var materialized = 0
        val keys = objects.keys()
        while (keys.hasNext()) {
            val assetName = keys.next()
            val descriptor = objects.optJSONObject(assetName)
                ?: throw IOException("Invalid legacy asset entry: " + assetName)
            val hash = descriptor.optString("hash").lowercase()
            if (!hash.matches(Regex("[a-f0-9]{40}"))) {
                throw IOException("Invalid SHA-1 for legacy asset: " + assetName)
            }
            val expectedSize = descriptor.optLong("size", -1L)
            if (expectedSize < 0L) {
                throw IOException("Missing size for legacy asset: " + assetName)
            }

            val source = File(File(objectRoot, hash.substring(0, 2)), hash).canonicalFile
            requireInside(objectRoot, source, "Asset object path escapes the object cache.")
            if (!source.isFile || source.length() != expectedSize || !matchesSha1(source, hash)) {
                throw IOException("Asset object is missing or corrupt: " + assetName)
            }

            val target = safeChild(targetRoot, assetName)
            if (!target.isFile || target.length() != expectedSize || !matchesSha1(target, hash)) {
                val parent = target.parentFile ?: throw IOException("Legacy asset has no parent folder.")
                ensureDirectory(parent)
                if (target.exists() && !target.delete()) {
                    throw IOException("Cannot replace corrupt legacy asset: " + assetName)
                }

                val temporary = File(parent, target.name + "." + java.util.UUID.randomUUID() + ".part")
                try {
                    // A hard link avoids doubling storage when both paths are on
                    // the same filesystem. Android shared storage may reject it,
                    // so copy to a temporary file as the portable fallback.
                    try {
                        Files.createLink(temporary.toPath(), source.toPath())
                    } catch (_: Exception) {
                        FileInputStream(source).use { input ->
                            FileOutputStream(temporary).use { output ->
                                input.copyTo(output, 64 * 1024)
                                output.fd.sync()
                            }
                        }
                    }

                    if (!temporary.isFile || temporary.length() != expectedSize ||
                        !matchesSha1(temporary, hash)
                    ) {
                        throw IOException("Could not verify materialized legacy asset: " + assetName)
                    }
                    try {
                        Files.move(
                            temporary.toPath(), target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(
                            temporary.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
            materialized++
        }
        return materialized
    }

    private fun safeChild(root: File, relativePath: String): File {
        if (relativePath.isBlank() || relativePath.startsWith("/") ||
            relativePath.contains('\\') || relativePath.contains('\u0000')
        ) {
            throw IOException("Invalid legacy asset path: " + relativePath)
        }
        val target = File(root, relativePath).canonicalFile
        requireInside(root, target, "Legacy asset path escapes its destination folder.")
        return target
    }

    private fun requireInside(root: File, child: File, message: String) {
        val prefix = root.canonicalPath + File.separator
        if (!child.canonicalPath.startsWith(prefix)) throw IOException(message)
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create legacy asset directory: " + directory.absolutePath)
        }
        if (!directory.isDirectory) {
            throw IOException("Legacy asset destination is not a directory: " + directory.absolutePath)
        }
    }

    private fun matchesSha1(file: File, expected: String): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }.getOrDefault(false)
}
