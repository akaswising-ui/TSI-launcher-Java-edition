package com.tsi.launcher.runtime

import android.content.Context
import android.os.Build
import com.tsi.launcher.data.MinecraftLibraryResolver
import com.tsi.launcher.data.MinecraftJavaCompatibility
import com.tsi.launcher.storage.AmethystStoragePaths
import com.tsi.launcher.storage.VersionDirectoryManager
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

data class DetectedJavaRuntime(
    val name: String,
    val majorVersion: Int,
    val version: String,
    val architecture: String,
    val home: File,
    val jvmLibrary: File
)

data class VersionVmInspection(
    val versionId: String,
    val clientJarExists: Boolean,
    val metadataExists: Boolean,
    val requiredJavaMajor: Int?,
    val libraryCount: Int,
    val installedLibraryCount: Int,
    val assetIndexId: String?,
    val assetIndexExists: Boolean,
    val assetObjectCount: Int,
    val assetObjectTotal: Int,
    val compatibleRuntime: DetectedJavaRuntime?,
    val detectedRuntimes: List<DetectedJavaRuntime>,
    val engineBridgeAvailable: Boolean,
    val blockers: List<String>
) {
    val readyToLaunch: Boolean get() = blockers.isEmpty()
}

/**
 * Inspects the app-specific Java runtime layout used by TSI.
 *
 * Runtime directory contract used by Amethyst MultiRT:
 * <app-data>/runtimes/<runtime-name>/release
 * <app-data>/runtimes/<runtime-name>/lib/server/libjvm.so
 *
 * Minecraft files remain under <external-files>/.minecraft; JRE files are
 * kept separately in Amethyst's app-private MultiRT directory.
 *
 * A detected JRE is not by itself an Android Minecraft engine. The JNI/JVM,
 * LWJGL, renderer, surface and input bridge must also be linked.
 */
class JavaRuntimeManager(private val context: Context) {
    private val directoryManager = VersionDirectoryManager(context.applicationContext)
    private val root = directoryManager.root()
    // Amethyst's MultiRTUtils reads runtimes from Tools.MULTIRT_HOME,
    // which is <app-data>/runtimes. Install once in that exact shared location.
    private val amethystRuntimeRoot = File(context.filesDir.parentFile ?: context.filesDir, "runtimes")
    private val privateRuntimeRoot = File(context.noBackupFilesDir, "tsi/.minecraft/runtimes")
    private val legacyRuntimeRoot = File(root, "runtimes")

    fun inspect(versionId: String, runtimePreference: String, instanceId: String = versionId): VersionVmInspection {
        val safeId = safeSegment(versionId)
        directoryManager.migrateLegacyStorage()
        directoryManager.ensureVersionLayout(safeId, instanceId)
        val versionFolder = File(File(root, "versions"), safeId)
        val metadataFile = File(versionFolder, "$safeId.json")
        val librariesRoot = File(root, "libraries")
        val assetsRoot = File(root, "assets")

        var requiredMajor: Int? = null
        var clientJarValid = false
        var libraryCount = 0
        var installedLibraryCount = 0
        var assetIndexId: String? = null
        var assetIndexExists = false
        var assetObjectCount = 0
        var assetObjectTotal = 0
        var legacyAssetFilesReady = true

        if (metadataFile.isFile) {
            try {
                // Fabric/Quilt/Forge profiles commonly inherit the vanilla version.
                // Resolve the complete metadata chain, bounded against cycles, so
                // preflight checks the parent client JAR, libraries and asset index.
                val metadataChain = readMetadataChain(safeId)
                requiredMajor = metadataChain.firstNotNullOfOrNull { entry ->
                    entry.metadata.optJSONObject("javaVersion")
                        ?.optInt("majorVersion")
                        ?.takeIf { it in 8..99 }
                }

                // Old releases, Alpha/Beta builds and older snapshots may omit
                // javaVersion. Their legacy minecraftArguments format requires Java 8.
                if (requiredMajor == null) {
                    requiredMajor = metadataChain.firstNotNullOfOrNull { entry ->
                        MinecraftJavaCompatibility.fallbackJavaMajorFor(
                            entry.id,
                            entry.metadata.has("minecraftArguments")
                        )
                    }
                }

                val clientOwner = metadataChain.firstOrNull { entry ->
                    entry.metadata.optJSONObject("downloads")?.optJSONObject("client") != null
                }
                if (clientOwner != null) {
                    val clientDownload = clientOwner.metadata
                        .optJSONObject("downloads")?.optJSONObject("client")
                    val expectedClientSize = clientDownload?.optLong("size", -1L) ?: -1L
                    val clientJar = File(
                        File(File(root, "versions"), clientOwner.id),
                        clientOwner.id + ".jar"
                    )
                    clientJarValid = clientJar.isFile &&
                        (expectedClientSize <= 0L || clientJar.length() == expectedClientSize)
                }

                // Merge the artifacts declared by the child and its parents, while
                // counting identical Maven paths only once.
                val libraryArtifacts = linkedMapOf<String, Pair<File, Long>>()
                metadataChain.forEach { entry ->
                    val libraries = entry.metadata.optJSONArray("libraries") ?: return@forEach
                    for (index in 0 until libraries.length()) {
                        val library = libraries.optJSONObject(index) ?: continue
                        if (!libraryAllowed(library)) continue
                        val artifact = MinecraftLibraryResolver.resolve(library) ?: continue
                        val relativePath = artifact.path
                        if (relativePath.isBlank() || relativePath.startsWith("/") ||
                            relativePath.contains('\\')
                        ) continue

                        val libraryRoot = librariesRoot.canonicalFile
                        val local = File(librariesRoot, relativePath).canonicalFile
                        if (!local.path.startsWith(libraryRoot.path + File.separator)) continue
                        libraryArtifacts[relativePath] = local to artifact.size
                    }
                }
                libraryCount = libraryArtifacts.size
                installedLibraryCount = libraryArtifacts.values.count { (local, expectedSize) ->
                    local.isFile && local.length() > 0L &&
                        (expectedSize <= 0L || local.length() == expectedSize)
                }

                val assetOwner = metadataChain.firstOrNull { it.metadata.optJSONObject("assetIndex") != null }
                val assetIndexMetadata = assetOwner?.metadata?.optJSONObject("assetIndex")
                assetIndexId = assetIndexMetadata?.optString("id")?.takeIf { !it.isNullOrBlank() }

                if (assetIndexId != null) {
                    val assetIndexFile = File(File(assetsRoot, "indexes"), "$assetIndexId.json")
                    val expectedIndexSize = assetIndexMetadata?.optLong("size", -1L) ?: -1L
                    assetIndexExists = assetIndexFile.isFile &&
                        assetIndexFile.length() > 0L &&
                        (expectedIndexSize <= 0L || assetIndexFile.length() == expectedIndexSize)

                    if (assetIndexExists) {
                        val assetIndexJson = JSONObject(assetIndexFile.readText(Charsets.UTF_8))
                        val objectMap = assetIndexJson.optJSONObject("objects")
                            ?: throw IOException("Asset index has no objects map.")
                        assetObjectTotal = objectMap.length()
                        val objectKeys = objectMap.keys()
                        val objectDirectory = File(assetsRoot, "objects")
                        val mapToResources = assetIndexJson.optBoolean("map_to_resources", false)
                        val virtualAssets = assetIndexJson.optBoolean("virtual", false)
                        val legacyRoot = when {
                            mapToResources -> File(root, "resources")
                            virtualAssets -> File(assetsRoot, "virtual/legacy")
                            else -> assetsRoot
                        }
                        while (objectKeys.hasNext()) {
                            val assetName = objectKeys.next()
                            val item = objectMap.optJSONObject(assetName) ?: continue
                            val hash = item.optString("hash").lowercase(Locale.ROOT)
                            if (!hash.matches(Regex("[a-f0-9]{40}"))) continue
                            val expectedSize = item.optLong("size", -1L)
                            val objectFile = File(File(objectDirectory, hash.substring(0, 2)), hash)
                            if (objectFile.isFile &&
                                (expectedSize <= 0L || objectFile.length() == expectedSize)
                            ) {
                                assetObjectCount++
                            }
                            if (virtualAssets || mapToResources) {
                                val legacyFile = runCatching {
                                    val candidate = File(legacyRoot, assetName).canonicalFile
                                    require(candidate.path.startsWith(legacyRoot.canonicalPath + File.separator))
                                    candidate
                                }.getOrNull()
                                if (legacyFile == null || !legacyFile.isFile ||
                                    (expectedSize >= 0L && legacyFile.length() != expectedSize)
                                ) {
                                    legacyAssetFilesReady = false
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Missing/cyclic/corrupt parent metadata is reported in blockers below.
            }
        }

        val detected = scanRuntimes()
        val explicitRuntimeName = runtimePreference
            .takeIf { it.startsWith("Name:") }
            ?.removePrefix("Name:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val namedRuntime = explicitRuntimeName?.let { name ->
            detected.firstOrNull {
                it.name == name && it.majorVersion >= (requiredMajor ?: 1)
            }
        }
        val requestedMajor = if (explicitRuntimeName != null) null else runtimePreference
            .removePrefix("Java")
            .trim()
            .toIntOrNull()
        val minimumMajor = requiredMajor ?: requestedMajor
        val preferredMajor = requestedMajor?.takeIf {
            requiredMajor == null || it >= requiredMajor
        }
        // Honor the selected runtime only when it meets the Minecraft version's
        // minimum Java requirement. Otherwise use the lowest compatible runtime.
        val compatible = namedRuntime ?: preferredMajor?.let { preferred ->
            detected.firstOrNull { it.majorVersion == preferred && it.majorVersion >= (requiredMajor ?: preferred) }
        } ?: minimumMajor?.let { minimum ->
            detected.firstOrNull { it.majorVersion >= minimum }
        }

        val engineBridgeAvailable = AmethystEngineBridge.isAvailable(context)

        val blockers = buildList {
            if (!AmethystStoragePaths.hasLegacyStoragePermission(context)) {
                add("Grant READ and WRITE storage permission in Android settings before using Amethyst's shared Minecraft folder.")
            }
            if (!clientJarValid) add("Client JAR is missing, incomplete or has the wrong size for $safeId.")
            if (!metadataFile.isFile) add("Version metadata JSON is missing.")
            if (requiredMajor == null) add("Java major version could not be resolved from version metadata.")
            if (minimumMajor != null && compatible == null) {
                add("No Amethyst-compatible Android JRE for Java $minimumMajor or newer is installed in the MultiRT runtimes folder.")
            }
            if (libraryCount == 0 || installedLibraryCount < libraryCount) {
                add("Minecraft libraries are incomplete ($installedLibraryCount/$libraryCount).")
            }
            if (assetIndexId == null || !assetIndexExists) {
                add("The Minecraft asset index is missing.")
            }
            if (assetObjectTotal == 0) {
                add("The asset index contains no readable object entries.")
            } else if (assetObjectCount < assetObjectTotal) {
                add("Minecraft assets are incomplete ($assetObjectCount/$assetObjectTotal objects are present with expected sizes).")
            }
            if (!legacyAssetFilesReady) {
                add("Legacy asset paths are incomplete. Reinstall this version to rebuild the virtual/resource asset files.")
            }
            if (!engineBridgeAvailable) {
                add("The Amethyst Android engine is not included in this build (JVM, LWJGL, SDL and native renderer bridge missing).")
            }
        }

        return VersionVmInspection(
            versionId = safeId,
            clientJarExists = clientJarValid,
            metadataExists = metadataFile.isFile,
            requiredJavaMajor = requiredMajor,
            libraryCount = libraryCount,
            installedLibraryCount = installedLibraryCount,
            assetIndexId = assetIndexId,
            assetIndexExists = assetIndexExists,
            assetObjectCount = assetObjectCount,
            assetObjectTotal = assetObjectTotal,
            compatibleRuntime = compatible,
            detectedRuntimes = detected,
            engineBridgeAvailable = engineBridgeAvailable,
            blockers = blockers
        )
    }

    private data class VersionMetadataEntry(
        val id: String,
        val metadata: JSONObject
    )

    /**
     * Returns metadata from the selected profile through its vanilla parent.
     * A missing parent or cyclic inheritance is an installation blocker, not
     * a successful partial preflight.
     */
    private fun readMetadataChain(versionId: String): List<VersionMetadataEntry> {
        val entries = mutableListOf<VersionMetadataEntry>()
        val visited = mutableSetOf<String>()
        var currentId = versionId

        repeat(16) {
            if (!visited.add(currentId)) {
                throw IOException("Minecraft version inheritance contains a cycle at $currentId.")
            }
            val currentFolder = File(File(root, "versions"), currentId)
            val currentJson = File(currentFolder, "$currentId.json")
            if (!currentJson.isFile) {
                if (entries.isEmpty()) return emptyList()
                throw IOException("Required inherited Minecraft metadata is missing: $currentId.")
            }
            val metadata = JSONObject(currentJson.readText(Charsets.UTF_8))
            entries += VersionMetadataEntry(currentId, metadata)
            val parentId = metadata.optString("inheritsFrom").trim()
            if (parentId.isBlank()) return entries
            require(parentId.matches(Regex("[A-Za-z0-9._+\\-]{1,100}"))) {
                "Invalid inherited Minecraft version ID."
            }
            currentId = parentId
        }

        throw IOException("Minecraft version inheritance exceeds the 16-level safety limit.")
    }

    fun runtimeDirectory(): File = amethystRuntimeRoot

    private fun scanRuntimes(): List<DetectedJavaRuntime> {
        migrateLegacyRuntimes()
        val canonicalRoot = runCatching { amethystRuntimeRoot.canonicalFile }.getOrNull()
            ?: return emptyList()
        val rootPrefix = canonicalRoot.path + File.separator
        val entries = amethystRuntimeRoot.listFiles()
            ?.filter { it.isDirectory && runCatching { it.canonicalPath.startsWith(rootPrefix) }.getOrDefault(false) }
            .orEmpty()

        // One corrupt or partially extracted JRE must not abort discovery of all
        // other installed runtimes. Validate every folder independently.
        return entries.mapNotNull { directory ->
            runCatching {
                // Amethyst MultiRT needs runtimes/<name>/release at the top level.
                val home = directory.canonicalFile
                if (!home.path.startsWith(rootPrefix)) return@runCatching null
                val releaseFile = File(home, "release")
                if (!releaseFile.isFile) return@runCatching null
                val properties = parseRelease(releaseFile)
                val versionText = properties["JAVA_VERSION"].orEmpty().trim('"')
                val major = parseJavaMajor(versionText) ?: return@runCatching null
                val arch = properties["OS_ARCH"].orEmpty().trim('"')
                val jvmCandidates = listOf(
                    File(home, "lib/server/libjvm.so"),
                    File(home, "lib/client/libjvm.so"),
                    File(home, "lib/$arch/server/libjvm.so"),
                    File(home, "lib/$arch/client/libjvm.so"),
                    File(home, "lib/amd64/server/libjvm.so"),
                    File(home, "lib/amd64/client/libjvm.so"),
                    File(home, "lib/aarch64/server/libjvm.so"),
                    File(home, "lib/aarch64/client/libjvm.so"),
                    File(home, "lib/arm/server/libjvm.so"),
                    File(home, "lib/arm/client/libjvm.so"),
                    File(home, "lib/x86_64/server/libjvm.so"),
                    File(home, "lib/x86/server/libjvm.so")
                )
                val jvm = jvmCandidates.firstOrNull { it.isFile } ?: return@runCatching null
                if (!isDeviceCompatible(arch)) return@runCatching null

                DetectedJavaRuntime(
                    name = directory.name,
                    majorVersion = major,
                    version = versionText,
                    architecture = arch,
                    home = home,
                    jvmLibrary = jvm
                )
            }.getOrNull()
        }.sortedBy { it.majorVersion }
    }

    private fun libraryAllowed(library: JSONObject): Boolean {
        val rules = library.optJSONArray("rules") ?: return true
        var allowed = false
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val architecture = when {
            abi.contains("arm64") || abi.contains("aarch64") -> "aarch64"
            abi.contains("armeabi") || abi == "arm" -> "arm"
            abi.contains("x86_64") || abi.contains("amd64") -> "x86_64"
            abi == "x86" || abi.contains("i386") || abi.contains("i686") -> "x86"
            else -> "unsupported"
        }

        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            val os = rule.optJSONObject("os")
            val osMatches = if (os == null) true else {
                val nameMatches = os.optString("name").let { it.isBlank() || it == "linux" }
                val archPattern = os.optString("arch")
                val archMatches = archPattern.isBlank() || runCatching {
                    Regex(archPattern).containsMatchIn(architecture)
                }.getOrDefault(false)
                val versionPattern = os.optString("version")
                val versionMatches = versionPattern.isBlank() || runCatching {
                    Regex(versionPattern).containsMatchIn(System.getProperty("os.version").orEmpty())
                }.getOrDefault(false)
                nameMatches && archMatches && versionMatches
            }
            val features = rule.optJSONObject("features")
            val featuresMatch = if (features == null) true else {
                val keys = features.keys()
                var matches = true
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (features.optBoolean(key, false)) matches = false
                }
                matches
            }
            if (osMatches && featuresMatch) {
                allowed = rule.optString("action") == "allow"
            }
        }
        return allowed
    }

    private fun migrateLegacyRuntimes() {
        val oldRoots = listOf(privateRuntimeRoot, legacyRuntimeRoot)
        oldRoots.forEach { oldRoot ->
            oldRoot.listFiles()?.filter { it.isDirectory }?.forEach runtimeLoop@ { oldRuntime ->
                val sourceHome = listOf(oldRuntime, File(oldRuntime, "jre"))
                    .firstOrNull { File(it, "release").isFile }
                    ?: return@runtimeLoop

                // Flatten legacy nested JRE layouts into the exact structure
                // Amethyst MultiRT expects, while leaving the original untouched.
                val destinationName = if (sourceHome == oldRuntime) {
                    oldRuntime.name
                } else {
                    "${oldRuntime.name}-flat"
                }
                val destination = File(amethystRuntimeRoot, destinationName)
                if (File(destination, "release").isFile) return@runtimeLoop

                runCatching {
                    if (!amethystRuntimeRoot.exists() && !amethystRuntimeRoot.mkdirs()) {
                        throw IOException("Cannot create Amethyst runtime directory.")
                    }
                    sourceHome.copyRecursively(destination, overwrite = false)
                }
            }
        }

        // Also recover nested installs already present inside Amethyst's runtime
        // root by copying their JRE contents to a sibling flat directory.
        amethystRuntimeRoot.listFiles()?.filter {
            it.isDirectory && !File(it, "release").isFile && File(File(it, "jre"), "release").isFile
        }?.forEach { nestedRuntime ->
            val destination = File(amethystRuntimeRoot, "${nestedRuntime.name}-flat")
            if (!File(destination, "release").isFile) {
                runCatching {
                    File(nestedRuntime, "jre").copyRecursively(destination, overwrite = false)
                }
            }
        }
    }

    private fun parseRelease(file: File): Map<String, String> {
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null
            else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
    }

    private fun parseJavaMajor(version: String): Int? {
        val normalized = version.removePrefix("1.")
        return normalized.substringBefore('.').substringBefore('-').toIntOrNull()
    }

    private fun isDeviceCompatible(runtimeArch: String): Boolean {
        val arch = runtimeArch.lowercase(Locale.ROOT)
        val deviceAbis = Build.SUPPORTED_ABIS.map { it.lowercase(Locale.ROOT) }
        return when {
            arch.contains("aarch64") || arch.contains("arm64") ->
                deviceAbis.any { it.contains("arm64") }
            arch == "arm" || arch.contains("armv7") ->
                deviceAbis.any { it.contains("armeabi") }
            arch.contains("x86_64") || arch.contains("amd64") ->
                deviceAbis.any { it.contains("x86_64") }
            arch == "x86" || arch.contains("i386") || arch.contains("i686") ->
                deviceAbis.any { it == "x86" || it.contains("x86") }
            else -> false
        }
    }

    private fun safeSegment(value: String): String {
        val safe = value.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        require(safe.isNotBlank() && safe != "." && safe != "..") {
            "Invalid Minecraft version ID"
        }
        return safe
    }
}
