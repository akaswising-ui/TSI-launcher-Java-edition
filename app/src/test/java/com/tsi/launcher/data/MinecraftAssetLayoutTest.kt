package com.tsi.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class MinecraftAssetLayoutTest {
    @Test
    fun sameSizeCorruptObjectFailsSha1Verification() {
        val root = Files.createTempDirectory("tsi-asset-sha").toFile()
        try {
            val file = File(root, "object.bin")
            file.writeBytes("correct".toByteArray())
            val expected = sha1("correct".toByteArray())
            assertTrue(MinecraftAssetLayout.hasExpectedSha1(file, expected))
            file.writeBytes("corrupt".toByteArray()) // Same length, wrong digest.
            assertTrue(!MinecraftAssetLayout.hasExpectedSha1(file, expected))
        } finally {
            root.deleteRecursively()
        }
    }

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
    fun virtualAssetsAreMaterializedUnderVirtualLegacyDirectory() {
        val root = Files.createTempDirectory("tsi-asset-virtual").toFile()
        try {
            val assets = File(root, "assets")
            val resources = File(root, "resources")
            val bytes = "legacy-texture".toByteArray()
            val hash = sha1(bytes)
            val objectFile = File(File(File(assets, "objects"), hash.substring(0, 2)), hash)
            objectFile.parentFile!!.mkdirs()
            objectFile.writeBytes(bytes)

            val entries = listOf(MinecraftLegacyAsset("textures/gui/icons.png", hash, bytes.size.toLong()))

            assertEquals(1, MinecraftAssetLayout.materialize(true, false, entries, assets, resources))
            assertEquals(bytes.toList(), File(assets, "virtual/legacy/textures/gui/icons.png").readBytes().toList())
            assertEquals(1, MinecraftAssetLayout.materialize(true, false, entries, assets, resources))
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

            val entries = listOf(MinecraftLegacyAsset("newsound/menu/click.ogg", hash, bytes.size.toLong()))

            assertEquals(1, MinecraftAssetLayout.materialize(false, true, entries, assets, resources))
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
            val entries = listOf(MinecraftLegacyAsset("../outside.bin", hash, bytes.size.toLong()))
            MinecraftAssetLayout.materialize(true, false, entries, assets, File(root, "resources"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
