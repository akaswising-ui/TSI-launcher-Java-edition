package com.tsi.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class VersionManifestRepository {
    companion object {
        const val MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }

    suspend fun fetchVersions(): List<MinecraftVersion> = withContext(Dispatchers.IO) {
        val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "TSI-Launcher/0.1")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Mojang manifest request failed: HTTP $code")
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val versions = JSONObject(body).getJSONArray("versions")
            buildList {
                for (index in 0 until versions.length()) {
                    val item = versions.getJSONObject(index)
                    val id = item.optString("id")
                    val type = item.optString("type")
                    val url = item.optString("url")
                    if (id.isNotBlank() && type.isNotBlank() && url.isNotBlank()) {
                        add(
                            MinecraftVersion(id, type, url, item.optString("releaseTime"))
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
