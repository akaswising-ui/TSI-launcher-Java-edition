package com.tsi.launcher.runtime

import android.content.Context
import android.os.Build
import com.tsi.launcher.data.MinecraftLibraryResolver
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
        val clientJar = File(versionFolder, "$safeId.jar")
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

        if (metadataFile.isFile) {
            try {
                val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
                requiredMajor = metadata.optJSONObject("javaVersion")
                    ?.optInt("majorVersion")
                    ?.takeIf { it in 8..99 }

                // Legacy metadata before javaVersion used Java 8.
                if (requiredMajor == null && isLegacyVersion(safeId)) {
                    requiredMajor = 8
                }

                val clientDownload = metadata.optJSONObject("downloads")
                    ?.optJSONObject("client")
                val expectedClientSize = clientDownload?.optLong("size", -1L) ?: -1L
                clientJarValid = clientJar.isFile &&
                    (expectedClientSize <= 0L || clientJar.length() == expectedClientSize)

                val libraries = metadata.optJSONArray("libraries")
                if (libraries != null) {
                    for (index in 0 until libraries.length()) {
                        val library = libraries.optJSONObject(index) ?: continue
                        if (!libraryAllowed(library)) continue
                        // Use the same resolver as installation so legacy
                        // pre-1.13 libraries are included in the VM preflight.
                        val artifact = MinecraftLibraryResolver.resolve(library) ?: continue
                        val relativePath = artifact.path
                        if (relativePath.isBlank() || relativePath.startsWith("/") ||
                            relativePath.contains('\\')
                        ) continue

                        val libraryRoot = librariesRoot.canonicalFile
                        val local = File(librariesRoot, relativePath).canonicalFile
                        if (!local.path.startsWith(libraryRoot.path + File.separator)) continue

                        libraryCount++
                        val expectedSize = artifact.size
                        if (local.isFile && local.length() > 0L &&
                            (expectedSize <= 0L || local.length() == expectedSize)
                        ) {
                            installedLibraryCount++
                        }
                    }
                }

                assetIndexId = metadata.optJSONObject("assetIndex")
                    ?.optString("id")
                    ?.takeIf { !it.isNullOrBlank() }

                if (assetIndexId != null) {
                    val assetIndexFile = File(File(assetsRoot, "indexes"), "$assetIndexId.json")
                    val assetIndexMetadata = metadata.optJSONObject("assetIndex")
                    val expectedIndexSize = assetIndexMetadata?.optLong("size", -1L) ?: -1L
                    assetIndexExists = assetIndexFile.isFile &&
                        assetIndexFile.length() > 0L &&
                        (expectedIndexSize <= 0L || assetIndexFile.length() == expectedIndexSize)

                    if (assetIndexExists) {
                        val objectMap = JSONObject(assetIndexFile.readText(Charsets.UTF_8))
                            .optJSONObject("objects")
                            ?: throw IOException("Asset index has no objects map.")
                        assetObjectTotal = objectMap.length()
                        val objectKeys = objectMap.keys()
                        val objectDirectory = File(assetsRoot, "objects")
                        while (objectKeys.hasNext()) {
                            val item = objectMap.optJSONObject(objectKeys.next()) ?: continue
                            val hash = item.optString("hash").lowercase(Locale.ROOT)
                            if (!hash.matches(Regex("[a-f0-9]{40}"))) continue
                            val expectedSize = item.optLong("size", -1L)
                            val objectFile = File(File(objectDirectory, hash.substring(0, 2)), hash)
                            if (objectFile.isFile &&
                                (expectedSize <= 0L || objectFile.length() == expectedSize)
                            ) {
                                assetObjectCount++
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Missing or corrupt metadata is reported in blockers below.
            }
        }

        val detected = scanRuntimes()
        val requestedMajor = runtimePreference
            .removePrefix("Java")
            .trim()
            .toIntOrNull()
        val minimumMajor = requiredMajor ?: requestedMajor
        val preferredMajor = requestedMajor?.takeIf {
            requiredMajor == null || it >= requiredMajor
        }
        // Honor the selected runtime only when it meets the Minecraft version's
        // minimum Java requirement. Otherwise use the lowest compatible runtime.
        val compatible = preferredMajor?.let { preferred ->
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

    private fun isLegacyVersion(versionId: String): Boolean {
        val match = Regex("^(?:1\\.(\\d+))(?:\\.|$)").find(versionId) ?: return false
        val minor = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return false
        return minor <= 16
    }

    private fun safeSegment(value: String): String {
        val safe = value.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        require(safe.isNotBlank() && safe != "." && safe != "..") {
            "Invalid Minecraft version ID"
        }
        return safe
    }
}
