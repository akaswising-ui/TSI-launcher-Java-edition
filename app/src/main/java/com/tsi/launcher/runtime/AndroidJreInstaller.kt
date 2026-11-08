package com.tsi.launcher.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import com.tsi.launcher.storage.VersionDirectoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

data class RuntimePackage(
    val javaMajor: Int,
    val architecture: String,
    val url: String,
    val size: Long,
    val sha256: String
)

/**
 * Installs Android-built OpenJDK runtimes for the device ABI.
 * This installs a JRE only. Minecraft's Android JVM/LWJGL engine bridge
 * remains a separate integration requirement.
 */
class AndroidJreInstaller(context: Context) {
    private val gameRoot = VersionDirectoryManager(context.applicationContext).root()

    // This is the same folder Amethyst sets as Tools.MULTIRT_HOME:
    // context.filesDir.parentFile/runtimes. Its MultiRT loader reads JREs here.
    private val runtimeRoot = File(context.filesDir.parentFile ?: context.filesDir, "runtimes")
    private val legacyRuntimeRoots = listOf(
        File(context.noBackupFilesDir, "tsi/.minecraft/runtimes"),
        File(gameRoot, "runtimes")
    )
    private val runtimeNamePrefix = "TSI-External-"

    fun supportedRuntimeMajors(): List<Int> = listOf(8, 17, 21, 25)

    /**
     * Returns the lowest bundled Android JRE that satisfies a Minecraft
     * version's minimum Java major. Java 17 can run Java 16-targeted releases;
     * Java 25 can run Java 22–25-targeted releases.
     */
    fun recommendedRuntimeMajor(requiredMajor: Int): Int? =
        supportedRuntimeMajors().firstOrNull { it >= requiredMajor }

    fun deviceArchitecture(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.ROOT) ?: return null
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "arm64"
            abi.contains("armeabi") || abi == "arm" -> "arm"
            abi.contains("x86_64") || abi.contains("amd64") -> "x86_64"
            abi == "x86" -> "x86"
            else -> null
        }
    }

    fun isInstalled(javaMajor: Int): Boolean = findInstalledRuntime(javaMajor) != null

    private fun findInstalledRuntime(javaMajor: Int): File? {
        val preferred = File(runtimeRoot, "$runtimeNamePrefix$javaMajor")
        if (isValidRuntime(preferred, javaMajor)) return preferred

        // Keep any older TSI installs usable, but copy them into Amethyst's
        // MultiRT_HOME without deleting the source directory.
        val legacyCandidates = buildList {
            add(File(runtimeRoot, "External-$javaMajor"))
            legacyRuntimeRoots.forEach { root ->
                add(File(root, "$runtimeNamePrefix$javaMajor"))
                add(File(root, "External-$javaMajor"))
            }
        }
        for (legacy in legacyCandidates) {
            if (!isValidRuntime(legacy, javaMajor)) continue
            ensureDirectory(runtimeRoot)
            if (!preferred.exists()) {
                legacy.copyRecursively(preferred, overwrite = false)
            }
            if (isValidRuntime(preferred, javaMajor)) return preferred
        }
        return null
    }

    private fun isValidRuntime(directory: File, javaMajor: Int): Boolean {
        val release = File(directory, "release")
        if (!release.isFile) return false
        return runCatching {
            val properties = parseRelease(release)
            val version = properties["JAVA_VERSION"].orEmpty().trim('"')
            val arch = properties["OS_ARCH"].orEmpty().trim('"')
            parseJavaMajor(version) == javaMajor &&
                isDeviceCompatible(arch) &&
                findJvmLibrary(directory) != null
        }.getOrDefault(false)
    }

    suspend fun downloadAndInstall(
        javaMajor: Int,
        progress: suspend (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val arch = deviceArchitecture()
            ?: throw IOException("This Android CPU architecture is not supported by the available JRE builds.")
        val pkg = packageFor(javaMajor, arch)
            ?: throw IOException("Java $javaMajor is not available for Android architecture $arch.")

        findInstalledRuntime(javaMajor)?.let { installed ->
            return@withContext installed
        }

        ensureDirectory(runtimeRoot)
        val archive = File(runtimeRoot, "jre$javaMajor-android-$arch.tar.xz.part")
        val temporaryDirectory = File(runtimeRoot, "$runtimeNamePrefix$javaMajor.installing")
        val finalDirectory = File(runtimeRoot, "$runtimeNamePrefix$javaMajor")
        deleteRecursively(temporaryDirectory)

        progress("Downloading Android OpenJDK $javaMajor for $arch…")
        downloadAndVerify(pkg, archive, progress)
        progress("Extracting Java $javaMajor runtime…")
        extractTarXz(archive, temporaryDirectory)
        archive.delete()

        val release = File(temporaryDirectory, "release")
        if (!release.isFile) {
            deleteRecursively(temporaryDirectory)
            throw IOException("The downloaded JRE package has no release metadata.")
        }

        val properties = parseRelease(release)
        val parsedMajor = parseJavaMajor(properties["JAVA_VERSION"].orEmpty().trim('"'))
        val runtimeArch = properties["OS_ARCH"].orEmpty().trim('"')
        if (parsedMajor != javaMajor || !isDeviceCompatible(runtimeArch) ||
            findJvmLibrary(temporaryDirectory) == null
        ) {
            deleteRecursively(temporaryDirectory)
            throw IOException("The extracted Java runtime failed version, ABI or libjvm validation.")
        }

        val backup = File(runtimeRoot, "$runtimeNamePrefix$javaMajor.previous")
        deleteRecursively(backup)
        if (finalDirectory.exists() && !finalDirectory.renameTo(backup)) {
            deleteRecursively(temporaryDirectory)
            throw IOException("Cannot move the existing Java $javaMajor runtime aside.")
        }
        if (!temporaryDirectory.renameTo(finalDirectory)) {
            if (backup.exists()) backup.renameTo(finalDirectory)
            deleteRecursively(temporaryDirectory)
            throw IOException("Cannot activate the Java $javaMajor runtime.")
        }
        deleteRecursively(backup)
        // Amethyst may have cached this runtime folder before TSI installed it.
        // Force a fresh MultiRT read so the engine can auto-pick the new JRE.
        runCatching {
            val multiRt = Class.forName("net.kdt.pojavlaunch.multirt.MultiRTUtils")
            multiRt.getMethod("forceReread", String::class.java)
                .invoke(null, "$runtimeNamePrefix$javaMajor")
        }
        progress("Java $javaMajor runtime installed and verified.")
        finalDirectory
    }

    private fun packageFor(major: Int, arch: String): RuntimePackage? {
        val digests = when (major) {
            8 -> mapOf(
                "arm" to ("9dbee3b09af5f170e2ed9dd596bc81d0e573c88f8bf760c59c8fadbb9073d1e7" to 27035664L),
                "arm64" to ("9a59124d9791957d55c68be664ab76831f336cf2e1e1cd4414220c6fdbf0e06d" to 28026736L),
                "x86" to ("b96ce49fab52b28688dccc1a7d85dfc6d0f4048636aac826e1967524b28f75af" to 27473340L),
                "x86_64" to ("b1fbcef4965c17925894febe8216d089c6dd47b37950b5f945a89616443c1d0e" to 29132884L)
            )
            17 -> mapOf(
                "arm" to ("4a9134f1ebf6340dd855805d351712462cddab6f3c2684da7e7da10ccf06648d" to 25001168L),
                "arm64" to ("e162c860fe05ee4a4e4af7606437419879f6c748386a7b09fa77d10db6a64091" to 26900804L),
                "x86" to ("223a2d54606a9eb853c8451cf1d6bda1a8f09cb357f40b790140e57d45731ed7" to 26951040L),
                "x86_64" to ("893e27d2aed8b40407f29fe939e2a0f193e5d55f72892a303db634b0808a2b61" to 27780012L)
            )
            21 -> mapOf(
                "arm" to ("96c297487def64666e379a9a363d9955c05b1a0b091b0cf24af88359a66f394a" to 26586292L),
                "arm64" to ("8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe" to 28675516L),
                "x86" to ("9b8c7d10c5f751acb3b33506593da44ece52a0fd03e0b3c283ba08a7f285a40f" to 28689112L),
                "x86_64" to ("cb88723961f5f9ad63afa1f212eb199816c27cabfd7dc66567bde1d8fb69713b" to 29662988L)
            )
            25 -> mapOf(
                "arm" to ("9ae13aee9cba7b2d2d8f40965061667e876d7380d866f37a992db3eff296ffb5" to 28252088L),
                "arm64" to ("d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37" to 38031580L),
                "x86_64" to ("7fca862ee1b2d5fe23cd9c9c3d9b7ad3c241947ad1a6cc9464ef2e674867105d" to 39061384L)
            )
            else -> return null
        }
        val asset = digests[arch] ?: return null
        val name = "jre$major-android-$arch.tar.xz"
        val url = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre$major/$name"
        return RuntimePackage(major, arch, url, asset.second, asset.first)
    }

    private suspend fun downloadAndVerify(
        pkg: RuntimePackage,
        target: File,
        progress: suspend (String) -> Unit
    ) {
        val connection = (URI(pkg.url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "TSI-Launcher/0.2")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("JRE download failed: HTTP $code")
            val finalUri = connection.url.toURI()
            require(finalUri.scheme == "https" &&
                (finalUri.host.equals("release-assets.githubusercontent.com", true) ||
                    finalUri.host.equals("github.com", true))
            ) { "JRE download redirected to an unexpected host." }

            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            ensureDirectory(target.parentFile ?: throw IOException("Runtime folder missing"))
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        total += count
                        if (total - lastReport >= 1024 * 1024) {
                            lastReport = total
                            progress("Java ${pkg.javaMajor}: downloaded ${total / (1024 * 1024)} MB")
                        }
                    }
                    output.fd.sync()
                }
            }
            val hash = digest.digest().toHex()
            if (total != pkg.size || !hash.equals(pkg.sha256, true)) {
                target.delete()
                throw IOException("JRE archive size or SHA-256 verification failed.")
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarXz(archive: File, destination: File) {
        ensureDirectory(destination)
        val rootPath = destination.canonicalFile
        BufferedInputStream(archive.inputStream()).use { source ->
            XZCompressorInputStream(source).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val output = File(destination, entry.name).canonicalFile
                        require(output.path == rootPath.path ||
                            output.path.startsWith(rootPath.path + File.separator)
                        ) { "JRE archive contains a path outside its install folder." }

                        if (entry.isDirectory) {
                            ensureDirectory(output)
                        } else if (entry.isSymbolicLink) {
                            ensureDirectory(output.parentFile ?: destination)
                            val linkName = entry.linkName
                            require(!File(linkName).isAbsolute) { "Absolute JRE symlink is not allowed." }
                            val resolvedLink = File(output.parentFile, linkName).canonicalFile
                            require(resolvedLink.path == rootPath.path ||
                                resolvedLink.path.startsWith(rootPath.path + File.separator)
                            ) { "JRE symlink escapes its install folder." }
                            Files.deleteIfExists(output.toPath())
                            Os.symlink(linkName, output.absolutePath)
                        } else if (entry.isLink) {
                            val sourcePath = File(destination, entry.linkName).canonicalFile
                            require(sourcePath.path.startsWith(rootPath.path + File.separator) && sourcePath.isFile) {
                                "JRE archive contains an invalid hard link."
                            }
                            ensureDirectory(output.parentFile ?: destination)
                            sourcePath.copyTo(output, overwrite = true)
                        } else if (entry.isFile) {
                            ensureDirectory(output.parentFile ?: destination)
                            FileOutputStream(output).use { fileOut ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = tar.read(buffer)
                                    if (count < 0) break
                                    fileOut.write(buffer, 0, count)
                                }
                                fileOut.fd.sync()
                            }
                            if ((entry.mode and 73) != 0) output.setExecutable(true, false)
                        }
                    }
                }
            }
        }
    }

    private fun parseRelease(file: File): Map<String, String> =
        file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()

    private fun parseJavaMajor(version: String): Int? {
        val value = version.removePrefix("1.")
        return value.substringBefore('.').substringBefore('-').toIntOrNull()
    }

    private fun isDeviceCompatible(arch: String): Boolean {
        val normalized = arch.lowercase(Locale.ROOT)
        return when {
            normalized.contains("aarch64") || normalized.contains("arm64") ->
                Build.SUPPORTED_ABIS.any { it.contains("arm64") }
            normalized.contains("arm") ->
                Build.SUPPORTED_ABIS.any { it.contains("armeabi") }
            normalized.contains("x86_64") || normalized.contains("amd64") ->
                Build.SUPPORTED_ABIS.any { it.contains("x86_64") }
            normalized == "x86" || normalized.contains("i386") || normalized.contains("i686") ->
                Build.SUPPORTED_ABIS.any { it == "x86" }
            else -> false
        }
    }

    private fun findJvmLibrary(directory: File): File? {
        val names = listOf(
            "lib/server/libjvm.so",
            "lib/client/libjvm.so",
            "lib/amd64/server/libjvm.so",
            "lib/amd64/client/libjvm.so",
            "lib/aarch64/server/libjvm.so",
            "lib/aarch64/client/libjvm.so",
            "lib/arm/server/libjvm.so",
            "lib/arm/client/libjvm.so",
            "lib/x86_64/server/libjvm.so",
            "lib/x86/server/libjvm.so"
        )
        return names.asSequence().map { File(directory, it) }.firstOrNull { it.isFile }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create $directory")
        if (!directory.isDirectory) throw IOException("Not a directory: $directory")
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return
        if (Files.isSymbolicLink(file.toPath())) {
            Files.deleteIfExists(file.toPath())
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        if (!file.delete()) throw IOException("Cannot remove previous install path: $file")
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(Locale.ROOT, it) }
}
