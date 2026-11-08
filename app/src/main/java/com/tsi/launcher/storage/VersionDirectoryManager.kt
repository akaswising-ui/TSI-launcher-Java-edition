package com.tsi.launcher.storage

import android.content.Context
import java.io.File

data class VersionPaths(
    val versionDirectory: File,
    val gameDirectory: File,
    val sharedAssetsDirectory: File,
    val sharedLibrariesDirectory: File
)

class VersionDirectoryManager(context: Context) {
    private val rootDirectory: File =
        AmethystStoragePaths.minecraftHome(context.applicationContext)

    fun ensureVersionLayout(versionId: String): VersionPaths {
        val safeId = safeSegment(versionId)
        ensureDirectory(rootDirectory)

        val versionsRoot = child(rootDirectory, "versions")
        val instancesRoot = child(rootDirectory, "instances")
        val versionDirectory = child(versionsRoot, safeId)
        val gameDirectory = child(child(instancesRoot, safeId), "game")
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
