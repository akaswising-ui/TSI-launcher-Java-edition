package com.tsi.launcher.instances;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class InstalledModManagerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void importsListsDisablesEnablesAndDeletesMod() throws Exception {
        File game = temporary.newFolder("game");
        InstalledModManager manager = new InstalledModManager(game);
        InstalledModManager.ModFile imported = manager.importJar("example-mod.jar",
                new ByteArrayInputStream(validJar()));

        assertEquals("example-mod.jar", imported.getName());
        List<InstalledModManager.ModFile> mods = manager.listMods();
        assertEquals(1, mods.size());
        assertTrue(mods.get(0).isEnabled());

        InstalledModManager.ModFile disabled = manager.setEnabled(imported.getFile(), false);
        assertFalse(disabled.isEnabled());
        assertTrue(disabled.getName().endsWith(".jar.disabled"));

        InstalledModManager.ModFile enabled = manager.setEnabled(disabled.getFile(), true);
        assertTrue(enabled.isEnabled());
        assertEquals("example-mod.jar", enabled.getName());

        manager.delete(enabled.getFile());
        assertTrue(manager.listMods().isEmpty());
    }

    @Test public void duplicateNamesAreNotOverwritten() throws Exception {
        InstalledModManager manager = new InstalledModManager(temporary.newFolder("game"));
        manager.importJar("example-mod.jar", new ByteArrayInputStream(validJar()));
        InstalledModManager.ModFile second = manager.importJar("example-mod.jar",
                new ByteArrayInputStream(validJar()));
        assertEquals("example-mod (2).jar", second.getName());
        assertEquals(2, manager.listMods().size());
    }

    @Test public void rejectsPathTraversalAndInvalidJar() throws Exception {
        InstalledModManager manager = new InstalledModManager(temporary.newFolder("game"));
        try {
            manager.importJar("../outside.jar", new ByteArrayInputStream(validJar()));
            fail("path traversal name should be rejected");
        } catch (java.io.IOException expected) { }
        try {
            manager.importJar("not-a-mod.jar", new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
            fail("invalid JAR should be rejected");
        } catch (java.io.IOException expected) { }
        assertTrue(manager.listMods().isEmpty());
    }

    private static byte[] validJar() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes("UTF-8"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("example/Mod.class"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
