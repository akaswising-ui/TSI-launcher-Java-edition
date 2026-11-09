package com.tsi.launcher.storage

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class VersionPaths(
    val versionDirectory: File,
    val gameDirectory: File,
    val sharedAssetsDirectory: File,
    val sharedLibrariesDirectory: File
)

class VersionDirectoryManager(context: Context) {
    private val appContext = context.applicationContext
    private val rootDirectory: File = AmethystStoragePaths.minecraftHome(appContext)
    // Previous TSI builds used this app-specific path on every Android release.
    private val legacyTsiRoot = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        ".minecraft"
    )
    private val migrationMarker = File(rootDirectory, ".tsi-legacy-storage-import-v1")

    companion object {
        private val migrationLock = Any()
    }

    /**
     * Import old TSI game data into Amethyst's active .minecraft folder.
     * Copies only missing files. Legacy originals are never deleted or overwritten.
     */
    fun migrateLegacyStorage(): Int = synchronized(migrationLock) {
        if (runCatching { rootDirectory.canonicalFile == legacyTsiRoot.canonicalFile }.getOrDefault(false) ||
            !legacyTsiRoot.isDirectory || migrationMarker.isFile ||
            !AmethystStoragePaths.hasLegacyStoragePermission(appContext)
        ) return@synchronized 0

        val sourceRoot = legacyTsiRoot.canonicalFile
        val destinationRoot = rootDirectory.canonicalFile
        val children = legacyTsiRoot.listFiles() ?: return@synchronized 0
        ensureDirectory(rootDirectory)

        var copied = 0
        children.forEach { child ->
            copied += copyMissingEntry(child, File(rootDirectory, child.name), sourceRoot, destinationRoot)
        }

        val temporaryMarker = File(rootDirectory, migrationMarker.name + ".tmp")
        FileOutputStream(temporaryMarker).use { output ->
            output.write("completed".toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporaryMarker.renameTo(migrationMarker)) {
            temporaryMarker.delete()
            throw IOException("Could not save the legacy Minecraft storage migration marker.")
        }
        copied
    }

    private fun copyMissingEntry(
        source: File,
        destination: File,
        sourceRoot: File,
        destinationRoot: File
    ): Int {
        val canonicalSource = runCatching { source.canonicalFile }.getOrNull() ?: return 0
        val canonicalDestination = runCatching { destination.canonicalFile }.getOrNull() ?: return 0
        val sourceRootPath = sourceRoot.path
        val destinationRootPath = destinationRoot.path
        val sourceInside = canonicalSource.path == sourceRootPath ||
            canonicalSource.path.startsWith(sourceRootPath + File.separator)
        val destinationInside = canonicalDestination.path == destinationRootPath ||
            canonicalDestination.path.startsWith(destinationRootPath + File.separator)
        // Ignore symbolic links that escape either storage root.
        if (!sourceInside || !destinationInside) return 0

        if (source.isDirectory) {
            if (destination.exists() && !destination.isDirectory) return 0
            val children = source.listFiles()
                ?: throw IOException("Cannot read legacy Minecraft folder: ${source.absolutePath}")
            ensureDirectory(destination)
            var copied = 0
            children.forEach { child ->
                copied += copyMissingEntry(child, File(destination, child.name), sourceRoot, destinationRoot)
            }
            return copied
        }

        if (!source.isFile || destination.exists()) return 0
        // Do not promote incomplete downloads or interrupted copy temporaries.
        if (source.name.endsWith(".part") || source.name.endsWith(".tmp") ||
            source.name.endsWith(".tsi-migration.part")
        ) return 0

        ensureDirectory(destination.parentFile ?: rootDirectory)
        val temporary = File(destination.parentFile, destination.name + ".tsi-migration.part")
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Cannot clear temporary migration file: ${temporary.absolutePath}")
        }
        source.copyTo(temporary, overwrite = false)
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("Cannot finish copying legacy Minecraft file: ${destination.absolutePath}")
        }
        return 1
    }

    fun ensureVersionLayout(versionId: String, instanceId: String = versionId): VersionPaths {
        val safeId = safeSegment(versionId)
        val safeInstanceId = safeSegment(instanceId)
        migrateLegacyStorage()
        ensureDirectory(rootDirectory)

        val versionsRoot = child(rootDirectory, "versions")
        val instancesRoot = child(rootDirectory, "instances")
        val versionDirectory = child(versionsRoot, safeId)
        val gameDirectory = child(child(instancesRoot, safeInstanceId), "game")
        val assetsDirectory = child(rootDirectory, "assets")
        val librariesDirectory = child(rootDirectory, "libraries")

        listOf(
            versionsRoot,
            instancesRoot,
            versionDirectory,
            gameDirectory,
            child(gameDirectory, "saves"),
            child(gameDirectory, "resourcepacks"),
            child(gameDirectory, "screenshots"),
            child(gameDirectory, "mods"),
            child(gameDirectory, "config"),
            child(gameDirectory, "logs"),
            assetsDirectory,
            librariesDirectory
        ).forEach(::ensureDirectory)

        return VersionPaths(
            versionDirectory = versionDirectory,
            gameDirectory = gameDirectory,
            sharedAssetsDirectory = assetsDirectory,
            sharedLibrariesDirectory = librariesDirectory
        )
    }

    fun root(): File = rootDirectory

    private fun safeSegment(value: String): String {
        val safe = value.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        require(safe.isNotBlank() && safe != "." && safe != "..") {
            "Invalid Minecraft version ID"
        }
        return safe
    }

    private fun child(parent: File, name: String): File {
        val candidate = File(parent, name).canonicalFile
        val safeParent = parent.canonicalFile
        require(candidate.path.startsWith(safeParent.path + File.separator)) {
            "Refusing to create a directory outside the TSI Minecraft folder"
        }
        return candidate
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Cannot create directory: ${directory.absolutePath}")
        }
        if (!directory.isDirectory) {
            throw IllegalStateException("Expected a directory: ${directory.absolutePath}")
        }
    }
}
