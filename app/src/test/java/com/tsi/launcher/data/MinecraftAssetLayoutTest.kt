package com.tsi.launcher.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class MinecraftAssetLayoutTest {
    @Test
    fun duplicateAssetHashesAreDownloadedOnlyOnce() {
        val entries = listOf(
            "textures/gui/icons.png" to "abc",
            "textures/gui/widgets.png" to "abc",
            "sounds/menu/click.ogg" to "def"
        )
        assertEquals(listOf("abc", "def"), MinecraftAssetLayout.uniqueObjectHashes(entries))
    }

    @Test
    fun virtualAssetsAreMaterializedUnderAssetsRoot() {
        val root = Files.createTempDirectory("tsi-asset-virtual").toFile()
        try {
            val assets = File(root, "assets")
            val resources = File(root, "resources")
            val bytes = "legacy-texture".toByteArray()
            val hash = sha1(bytes)
            val objectFile = File(File(File(assets, "objects"), hash.substring(0, 2)), hash)
            objectFile.parentFile!!.mkdirs()
            objectFile.writeBytes(bytes)

            val index = JSONObject()
                .put("virtual", true)
                .put("objects", JSONObject().put(
                    "textures/gui/icons.png",
                    JSONObject().put("hash", hash).put("size", bytes.size)
                ))

            assertEquals(1, MinecraftAssetLayout.materialize(index, assets, resources))
            assertEquals(bytes.toList(), File(assets, "textures/gui/icons.png").readBytes().toList())
            assertEquals(1, MinecraftAssetLayout.materialize(index, assets, resources))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mapToResourcesAssetsAreMaterializedUnderResourcesRoot() {
        val root = Files.createTempDirectory("tsi-asset-resources").toFile()
        try {
            val assets = File(root, "assets")
            val resources = File(root, "resources")
            val bytes = "legacy-sound".toByteArray()
            val hash = sha1(bytes)
            val objectFile = File(File(File(assets, "objects"), hash.substring(0, 2)), hash)
            objectFile.parentFile!!.mkdirs()
            objectFile.writeBytes(bytes)

            val index = JSONObject()
                .put("map_to_resources", true)
                .put("objects", JSONObject().put(
                    "newsound/menu/click.ogg",
                    JSONObject().put("hash", hash).put("size", bytes.size)
                ))

            assertEquals(1, MinecraftAssetLayout.materialize(index, assets, resources))
            assertTrue(File(resources, "newsound/menu/click.ogg").isFile)
            assertTrue(!File(assets, "newsound/menu/click.ogg").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = java.io.IOException::class)
    fun legacyAssetPathCannotEscapeDestination() {
        val root = Files.createTempDirectory("tsi-asset-traversal").toFile()
        try {
            val assets = File(root, "assets")
            val bytes = "x".toByteArray()
            val hash = sha1(bytes)
            val objectFile = File(File(File(assets, "objects"), hash.substring(0, 2)), hash)
            objectFile.parentFile!!.mkdirs()
            objectFile.writeBytes(bytes)
            val index = JSONObject()
                .put("virtual", true)
                .put("objects", JSONObject().put(
                    "../outside.bin",
                    JSONObject().put("hash", hash).put("size", bytes.size)
                ))
            MinecraftAssetLayout.materialize(index, assets, File(root, "resources"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
