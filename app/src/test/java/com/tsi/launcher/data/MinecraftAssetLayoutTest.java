package com.tsi.launcher.data;

import kotlin.Pair;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class MinecraftAssetLayoutTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void sameSizeCorruptObjectFailsSha1Verification() throws Exception {
        File root = temporaryFolder.newFolder("sha");
        File file = new File(root, "object.bin");
        write(file, "correct".getBytes("UTF-8"));
        String expected = sha1("correct".getBytes("UTF-8"));
        assertTrue(MinecraftAssetLayout.hasExpectedSha1(file, expected));
        write(file, "corrupt".getBytes("UTF-8"));
        assertFalse(MinecraftAssetLayout.hasExpectedSha1(file, expected));
    }

    @Test public void duplicateAssetHashesAreDownloadedOnlyOnce() {
        List<Pair<String, String>> entries = Arrays.asList(
                new Pair<>("textures/gui/icons.png", "abc"),
                new Pair<>("textures/gui/widgets.png", "abc"),
                new Pair<>("sounds/menu/click.ogg", "def"));
        assertEquals(Arrays.asList("abc", "def"), MinecraftAssetLayout.uniqueObjectHashes(entries));
    }

    @Test public void virtualAssetsAreMaterializedUnderVirtualLegacyDirectory() throws Exception {
        File root = temporaryFolder.newFolder("virtual");
        File assets = new File(root, "assets");
        File resources = new File(root, "resources");
        byte[] bytes = "legacy-texture".getBytes("UTF-8");
        String hash = sha1(bytes);
        writeObject(assets, hash, bytes);
        List<MinecraftLegacyAsset> entries = Arrays.asList(
                new MinecraftLegacyAsset("textures/gui/icons.png", hash, bytes.length));
        assertEquals(1, MinecraftAssetLayout.materialize(true, false, entries, assets, resources));
        assertArrayEquals(bytes, read(new File(assets, "virtual/legacy/textures/gui/icons.png")));
        assertEquals(1, MinecraftAssetLayout.materialize(true, false, entries, assets, resources));
    }

    @Test public void mapToResourcesAssetsAreMaterializedUnderResourcesRoot() throws Exception {
        File root = temporaryFolder.newFolder("resources");
        File assets = new File(root, "assets");
        File resources = new File(root, "resources");
        byte[] bytes = "legacy-sound".getBytes("UTF-8");
        String hash = sha1(bytes);
        writeObject(assets, hash, bytes);
        List<MinecraftLegacyAsset> entries = Arrays.asList(
                new MinecraftLegacyAsset("newsound/menu/click.ogg", hash, bytes.length));
        assertEquals(1, MinecraftAssetLayout.materialize(false, true, entries, assets, resources));
        assertTrue(new File(resources, "newsound/menu/click.ogg").isFile());
        assertFalse(new File(assets, "newsound/menu/click.ogg").exists());
    }

    @Test(expected = java.io.IOException.class)
    public void legacyAssetPathCannotEscapeDestination() throws Exception {
        File root = temporaryFolder.newFolder("traversal");
        File assets = new File(root, "assets");
        byte[] bytes = "x".getBytes("UTF-8");
        String hash = sha1(bytes);
        writeObject(assets, hash, bytes);
        List<MinecraftLegacyAsset> entries = Arrays.asList(
                new MinecraftLegacyAsset("../outside.bin", hash, bytes.length));
        MinecraftAssetLayout.materialize(true, false, entries, assets, new File(root, "resources"));
    }

    private static void writeObject(File assets, String hash, byte[] bytes) throws Exception {
        File object = new File(new File(new File(assets, "objects"), hash.substring(0, 2)), hash);
        write(object, bytes);
    }

    private static void write(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("mkdirs failed");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
            output.write(data);
        }
    }

    private static byte[] read(File file) throws Exception {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String sha1(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte value : digest) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }
}
