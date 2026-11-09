package com.tsi.launcher.data

import android.content.Context
import android.os.Build
import com.tsi.launcher.storage.VersionDirectoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class JavaGameInstallResult(
    val versionId: String,
    val clientJar: File,
    val metadataFile: File,
    val libraryCount: Int,
    val assetCount: Int,
    val requiredJavaMajor: Int?,
    val assetIndexId: String
)

/**
 * Installs the Java Edition data files described by Mojang's version JSON.
 *
 * This resolves client, library artifacts and asset objects. Android-specific
 * LWJGL natives and the actual JVM/renderer bridge are supplied by the engine
 * integration and are deliberately not treated as ordinary desktop natives.
 */
class JavaGameInstaller(
    private val context: Context,
    private val directoryManager: VersionDirectoryManager
) {
    suspend fun install(
        version: MinecraftVersion,
        instanceId: String = version.id,
        progress: suspend (String) -> Unit = {}
    ): JavaGameInstallResult = withContext(Dispatchers.IO) {
        val paths = directoryManager.ensureVersionLayout(version.id, instanceId)
        progress("Fetching Minecraft ${version.id} version metadata…")

        val metadataText = downloadText(version.url)
        val metadata = JSONObject(metadataText)
        require(metadata.optString("id") == version.id) {
            "Mojang metadata ID does not match ${version.id}"
        }

        val requiredJava = metadata.optJSONObject("javaVersion")
            ?.optInt("majorVersion")
            ?.takeIf { it in 8..99 }

        // Some older Mojang manifests omit ASM's standalone core artifact even
        // though LaunchWrapper's VanillaTweaker loads org.objectweb.asm classes.
        // Add the official Maven Central core JAR only when no ASM core/uber JAR
        // is already declared. This keeps the game classpath metadata-driven.
        ensureAsmCoreLibrary(metadata)

        val metadataFile = File(paths.versionDirectory, "${version.id}.json")
        // Persist the normalized metadata, not the original response text.
        // ensureAsmCoreLibrary() may have added a missing classpath dependency;
        // Amethyst reads this on-disk JSON later when it constructs the JVM classpath.
        writeAtomically(metadataFile, metadata.toString(2).toByteArray(Charsets.UTF_8))

        val client = metadata.optJSONObject("downloads")
            ?.optJSONObject("client")
            ?: throw IOException("This version has no downloadable client JAR in its metadata")

        val clientUrl = client.optString("url")
        val clientSha1 = client.optString("sha1").takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
        val clientSize = client.optLong("size", -1L)
        val clientJar = File(paths.versionDirectory, "${version.id}.jar")
        if (!isVerifiedFile(clientJar, clientSha1, clientSize)) {
            progress("Downloading Minecraft ${version.id} client JAR…")
            downloadVerified(clientUrl, clientJar, clientSha1, clientSize)
        } else {
            progress("Client JAR already verified.")
        }

        val libraries = metadata.optJSONArray("libraries")
        var totalLibraries = 0
        var installedLibraries = 0
        if (libraries != null) {
            for (index in 0 until libraries.length()) {
                val library = libraries.optJSONObject(index) ?: continue
                if (!libraryAllowed(library)) continue

                val downloads = library.optJSONObject("downloads")
                val artifact = downloads?.optJSONObject("artifact")

                // Amethyst uses an Android JNA AAR. Keep the existing TSI library
                // installer as the single download path, then use Amethyst's own
                // native extractor when the engine is embedded in this APK.
                if (library.optString("name").startsWith("net.java.dev.jna:jna:") &&
                    runCatching {
                        Class.forName("net.kdt.pojavlaunch.tasks.NativesExtractor")
                    }.isSuccess
                ) {
                    val aarPath = jnaNativeArchivePath(library, artifact)
                    val aarTarget = safeChild(paths.sharedLibrariesDirectory, aarPath)
                    if (!aarTarget.isFile) {
                        progress("Downloading Android JNA native library…")
                        downloadVerified(
                            "https://repo1.maven.org/maven2/$aarPath",
                            aarTarget,
                            expectedSha1 = null,
                            expectedSize = -1L
                        )
                    }
                    extractAmethystNativeArchive(version.id, aarTarget)
                }

                // Before 1.13, Mojang's version JSON uses Maven coordinates in
                // "name" and a repository URL instead of downloads.artifact.
                // Skipping those entries silently left ASM/LaunchWrapper jars
                // absent, causing VanillaTweaker to fail with ClassNotFoundError.
                val resolvedArtifact = MinecraftLibraryResolver.resolve(library) ?: continue
                val target = safeChild(paths.sharedLibrariesDirectory, resolvedArtifact.path)
                totalLibraries++
                if (!isVerifiedFile(target, resolvedArtifact.sha1, resolvedArtifact.size)) {
                    progress("Downloading library ${installedLibraries + 1}/$totalLibraries…")
                    downloadVerified(
                        resolvedArtifact.url,
                        target,
                        resolvedArtifact.sha1,
                        resolvedArtifact.size
                    )
                }
                installedLibraries++
            }
        }

        val assetIndex = metadata.optJSONObject("assetIndex")
            ?: throw IOException("Version metadata does not contain an asset index")
        val assetIndexId = assetIndex.optString("id")
        val assetIndexUrl = assetIndex.optString("url")
        require(assetIndexId.isNotBlank() && assetIndexUrl.isNotBlank()) {
            "Asset index metadata is incomplete"
        }

        val assetIndexesDir = File(paths.sharedAssetsDirectory, "indexes")
        ensureDirectory(assetIndexesDir)
        val assetIndexFile = File(assetIndexesDir, "$assetIndexId.json")
        val assetIndexSha = assetIndex.optString("sha1")
            .takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
        val assetIndexSize = assetIndex.optLong("size", -1L)
        if (!isVerifiedFile(assetIndexFile, assetIndexSha, assetIndexSize)) {
            progress("Downloading asset index $assetIndexId…")
            downloadVerified(assetIndexUrl, assetIndexFile, assetIndexSha, assetIndexSize)
        }

        val assetIndexJson = JSONObject(assetIndexFile.readText(Charsets.UTF_8))
        val objects = assetIndexJson.optJSONObject("objects")
            ?: throw IOException("Asset index has no objects map")
        val assetEntries = ArrayList<Pair<String, String>>(objects.length())
        val iterator = objects.keys()
        while (iterator.hasNext()) {
            val name = iterator.next()
            val hash = objects.optJSONObject(name)?.optString("hash").orEmpty().lowercase(Locale.ROOT)
            if (!hash.matches(Regex("[a-f0-9]{40}"))) {
                throw IOException("Invalid asset hash in asset index: $name")
            }
            assetEntries.add(name to hash)
        }

        val assetObjectsDir = File(paths.sharedAssetsDirectory, "objects")
        ensureDirectory(assetObjectsDir)
        val finished = AtomicInteger(0)
        val totalAssets = assetEntries.size
        val uniqueAssetHashes = MinecraftAssetLayout.uniqueObjectHashes(assetEntries)
        val uniqueObjectCount = uniqueAssetHashes.size
        progress("Checking $totalAssets Minecraft assets ($uniqueObjectCount unique objects)…")

        // An asset index can map multiple names to the same SHA-1. Download
        // each shared object once so parallel tasks never race on the same
        // destination or its .part file.
        val semaphore = Semaphore(5)
        uniqueAssetHashes.chunked(40).forEach { chunk ->
            coroutineScope {
                chunk.map { hash ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val prefix = hash.substring(0, 2)
                            val folder = File(assetObjectsDir, prefix)
                            ensureDirectory(folder)
                            val destination = File(folder, hash)
                            if (!isVerifiedFile(destination, hash, -1L)) {
                                val assetUrl = "https://resources.download.minecraft.net/$prefix/$hash"
                                downloadVerified(assetUrl, destination, hash, -1L)
                            }
                            val done = finished.incrementAndGet()
                            if (done % 50 == 0 || done == uniqueObjectCount) {
                                progress("Minecraft asset objects: $done / $uniqueObjectCount")
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val mapToResources = assetIndexJson.optBoolean("map_to_resources", false)
        val virtualAssets = assetIndexJson.optBoolean("virtual", false)
        val legacyAssets = if (mapToResources || virtualAssets) {
            val legacyEntries = buildList {
                val names = objects.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val descriptor = objects.optJSONObject(name)
                        ?: throw IOException("Invalid legacy asset entry: " + name)
                    add(
                        MinecraftLegacyAsset(
                            name = name,
                            hash = descriptor.optString("hash"),
                            size = descriptor.optLong("size", -1L)
                        )
                    )
                }
            }
            MinecraftAssetLayout.materialize(
                virtualAssets,
                mapToResources,
                legacyEntries,
                paths.sharedAssetsDirectory,
                File(paths.sharedAssetsDirectory.parentFile ?: paths.sharedAssetsDirectory, "resources")
            )
        } else {
            0
        }
        if (legacyAssets > 0) {
            progress("Prepared " + legacyAssets + " legacy asset paths for older Minecraft versions.")
        }

        JavaGameInstallResult(
            versionId = version.id,
            clientJar = clientJar,
            metadataFile = metadataFile,
            libraryCount = installedLibraries,
            assetCount = totalAssets,
            requiredJavaMajor = requiredJava,
            assetIndexId = assetIndexId
        )
    }

    private fun ensureAsmCoreLibrary(metadata: JSONObject) {
        val libraries = metadata.optJSONArray("libraries")
            ?: org.json.JSONArray().also { metadata.put("libraries", it) }
        var hasAsmCore = false
        var hasAsmUber = false
        for (index in 0 until libraries.length()) {
            val name = libraries.optJSONObject(index)?.optString("name").orEmpty()
            if (name.startsWith("org.ow2.asm:asm:")) hasAsmCore = true
            if (name.startsWith("org.ow2.asm:asm-all:")) hasAsmUber = true
        }
        if (hasAsmCore || hasAsmUber) return

        val path = "org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
        val artifact = JSONObject()
            .put("path", path)
            .put("size", 126093)
            .put("url", "https://repo.maven.apache.org/maven2/$path")
        val downloads = JSONObject().put("artifact", artifact)
        libraries.put(
            JSONObject()
                .put("name", "org.ow2.asm:asm:9.7.1")
                .put("downloads", downloads)
        )
    }

    private fun jnaNativeArchivePath(library: JSONObject, artifact: JSONObject?): String {
        val artifactPath = artifact?.optString("path").orEmpty()
        if (artifactPath.isNotBlank()) {
            val withoutExtension = artifactPath.substringBeforeLast('.', artifactPath)
            return "$withoutExtension.aar"
        }

        val coordinates = library.optString("name").split(':')
        require(coordinates.size >= 3) { "Invalid JNA Maven coordinates" }
        val groupPath = coordinates[0].replace('.', '/')
        val artifactName = coordinates[1]
        val versionName = coordinates[2]
        val classifier = coordinates.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
        return "$groupPath/$artifactName/$versionName/$artifactName-$versionName$classifier.aar"
    }

    private fun extractAmethystNativeArchive(versionId: String, archive: File) {
        try {
            val extractorClass = Class.forName("net.kdt.pojavlaunch.tasks.NativesExtractor")
            val destination = File(context.cacheDir, "natives/$versionId")
            ensureDirectory(destination)
            val extractor = extractorClass
                .getConstructor(File::class.java)
                .newInstance(destination)
            extractorClass
                .getMethod("extractFromAar", File::class.java)
                .invoke(extractor, archive)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw IOException(
                "Amethyst could not extract Android JNA native files.",
                error.targetException ?: error
            )
        } catch (error: Exception) {
            throw IOException("Amethyst Android native extractor is unavailable.", error)
        }
    }

    private fun libraryAllowed(library: JSONObject): Boolean {
        val rules = library.optJSONArray("rules") ?: return true
        var allowed = false
        val deviceArch = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().lowercase(Locale.ROOT)
        val architecture = when {
            deviceArch.contains("arm64") || deviceArch.contains("aarch64") -> "aarch64"
            deviceArch.contains("armeabi") || deviceArch == "arm" -> "arm"
            deviceArch.contains("x86_64") || deviceArch.contains("amd64") -> "x86_64"
            deviceArch == "x86" || deviceArch.contains("i386") || deviceArch.contains("i686") -> "x86"
            else -> "unsupported"
        }

        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            val os = rule.optJSONObject("os")
            val osMatches = if (os == null) true else {
                val name = os.optString("name")
                val nameMatches = name.isBlank() || name == "linux"
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
            // TSI's current profile is non-demo, with no quick-play or custom-resolution flags.
            val featuresMatch = features == null || featureRulesMatch(features)
            if (osMatches && featuresMatch) {
                allowed = rule.optString("action") == "allow"
            }
        }
        return allowed
    }

    private fun featureRulesMatch(features: JSONObject): Boolean {
        val keys = features.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val expected = features.optBoolean(key, false)
            val actual = when (key) {
                "is_demo_user" -> false
                "has_custom_resolution" -> false
                "has_quick_plays_support" -> false
                "is_quick_play_singleplayer" -> false
                "is_quick_play_multiplayer" -> false
                "is_quick_play_realms" -> false
                else -> false
            }
            if (actual != expected) return false
        }
        return true
    }

    private fun downloadText(rawUrl: String): String {
        val connection = openConnection(rawUrl)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Metadata request failed: HTTP $code")
            validateUrl(connection.url.toURI().toString())
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadVerified(
        rawUrl: String,
        target: File,
        expectedSha1: String?,
        expectedSize: Long
    ) {
        val connection = openConnection(rawUrl)
        val temporary = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-1")
        var bytes = 0L
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Download failed: HTTP $code for $rawUrl")

            val finalUrl = connection.url.toURI()
            validateUrl(finalUrl.toString())
            ensureDirectory(target.parentFile ?: throw IOException("Target has no parent folder"))

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        bytes += count
                    }
                    output.fd.sync()
                }
            }

            if (expectedSize >= 0 && bytes != expectedSize) {
                throw IOException("File size verification failed for ${target.name}")
            }
            if (expectedSha1 != null) {
                val actual = digest.digest().toHex()
                if (!actual.equals(expectedSha1, ignoreCase = true)) {
                    throw IOException("SHA-1 verification failed for ${target.name}")
                }
            }
            replaceAtomically(temporary, target)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun isVerifiedFile(file: File, sha1: String?, expectedSize: Long): Boolean {
        if (!file.isFile) return false
        if (expectedSize >= 0 && file.length() != expectedSize) return false
        if (sha1 == null) return expectedSize >= 0 && file.length() == expectedSize
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().toHex().equals(sha1, ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun openConnection(rawUrl: String): HttpURLConnection {
        validateUrl(rawUrl)
        return (URI(rawUrl).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("User-Agent", "TSI-Launcher/0.2")
        }
    }

    private fun validateUrl(rawUrl: String) {
        val uri = URI(rawUrl)
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("Download URL has no hostname")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val allowed = host == "mojang.com" ||
            host.endsWith(".mojang.com") ||
            host == "minecraft.net" ||
            host.endsWith(".minecraft.net") ||
            host == "libraries.minecraft.net" ||
            host == "repo.maven.apache.org" ||
            host == "repo1.maven.org" ||
            host == "resources.download.minecraft.net"
        require(scheme == "https" && allowed) {
            "Refusing non-HTTPS or non-Minecraft download URL: $host"
        }
    }

    private fun safeChild(parent: File, relativePath: String): File {
        require(!relativePath.startsWith("/") && !relativePath.contains('\\')) {
            "Invalid library path"
        }
        val root = parent.canonicalFile
        val target = File(parent, relativePath).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) {
            "Library path escapes its installation folder"
        }
        return target
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        ensureDirectory(target.parentFile ?: throw IOException("Metadata target has no parent"))
        val temporary = File(target.parentFile, target.name + ".part")
        FileOutputStream(temporary).use {
            it.write(bytes)
            it.fd.sync()
        }
        replaceAtomically(temporary, target)
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create directory ${directory.absolutePath}")
        }
        if (!directory.isDirectory) throw IOException("Not a directory: ${directory}.absolutePath")
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(Locale.ROOT, it) }
}
