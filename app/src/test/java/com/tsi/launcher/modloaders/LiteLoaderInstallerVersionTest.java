package com.tsi.launcher.modloaders;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiteLoaderInstallerVersionTest {
    @Test public void comparesNumericMinecraftVersionSegments() {
        assertTrue(LiteLoaderInstaller.compareVersions("1.9", "1.12.2") < 0);
        assertTrue(LiteLoaderInstaller.compareVersions("1.10.2", "1.8.9") > 0);
        assertEquals(0, LiteLoaderInstaller.compareVersions("1.12.2", "1.12.2"));
        assertTrue(LiteLoaderInstaller.compareVersions("1.12", "1.12.2") < 0);
    }
}
