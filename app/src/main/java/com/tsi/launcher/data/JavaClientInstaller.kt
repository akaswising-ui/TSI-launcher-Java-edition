package com.tsi.launcher.data

import com.tsi.launcher.storage.VersionDirectoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

class JavaClientInstaller(
    private val directoryManager: VersionDirectoryManager
) {
    suspend fun downloadClientJar(version: MinecraftVersion): ClientDownloadResult =
        withContext(Dispatchers.IO) {
            val paths = directoryManager.ensureVersionLayout(version.id)
            val metadataText = downloadText(version.url)
            val metadata = JSONObject(metadataText)

            val metadataId = metadata.optString("id")
            require(metadataId == version.id) {
                "Version metadata ID did not match the selected version"
            }

            val javaMajor = metadata
                .optJSONObject("javaVersion")
                ?.optInt("majorVersion")
                ?.takeIf { it > 0 }

            val client = metadata
                .getJSONObject("downloads")
                .getJSONObject("client")

            val clientUrl = client.getString("url")
            val expectedSha1 = client.optString("sha1").lowercase(Locale.ROOT)
            val expectedSize = client.optLong("size", -1L)

            require(expectedSha1.matches(Regex("[a-f0-9]{40}"))) {
                "Mojang client metadata did not contain a valid SHA-1 checksum"
            }

            val target = File(paths.versionDirectory, "${version.id}.jar")
            val temporary = File(paths.versionDirectory, "${version.id}.jar.part")
            downloadAndVerify(clientUrl, temporary, expectedSha1, expectedSize)
            replaceAtomically(temporary, target)

            val metadataFile = File(paths.versionDirectory, "${version.id}.json")
            val metadataTemp = File(paths.versionDirectory, "${version.id}.json.part")
            FileOutputStream(metadataTemp).use { output ->
                output.write(metadataText.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            replaceAtomically(metadataTemp, metadataFile)

            ClientDownloadResult(version.id, target.absolutePath, metadataFile.absolutePath, javaMajor)
        }

    private fun downloadText(rawUrl: String): String {
        val connection = openMojangConnection(rawUrl)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Version metadata download failed: HTTP $code")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndVerify(
        rawUrl: String,
        destination: File,
        expectedSha1: String,
        expectedSize: Long
    ) {
        val connection = openMojangConnection(rawUrl)
        val digest = MessageDigest.getInstance("SHA-1")
        var bytesWritten = 0L

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Minecraft client download failed: HTTP $code")
            }

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { fileOutput ->
                    val output = fileOutput.buffered()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        bytesWritten += count
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }

        val actualSha1 = digest.digest().joinToString("") {
            "%02x".format(Locale.ROOT, it)
        }

        if (expectedSize >= 0 && bytesWritten != expectedSize) {
            destination.delete()
            throw IOException("Client JAR size check failed")
        }
        if (!actualSha1.equals(expectedSha1, ignoreCase = true)) {
            destination.delete()
            throw IOException("Client JAR SHA-1 verification failed")
        }
    }

    private fun openMojangConnection(rawUrl: String): HttpURLConnection {
        val uri = URI(rawUrl)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("Download URL has no host")

        val isMojangHost =
            host == "mojang.com" ||
            host.endsWith(".mojang.com") ||
            host == "minecraft.net" ||
            host.endsWith(".minecraft.net") ||
            (host == "s3.amazonaws.com" && uri.path.orEmpty().startsWith("/Minecraft.Download/"))

        require(scheme == "https" && isMojangHost) {
            "Refusing a non-official Minecraft download URL"
        }

        return (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "TSI-Launcher/0.1")
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
