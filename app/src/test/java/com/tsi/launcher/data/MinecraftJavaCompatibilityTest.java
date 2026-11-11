package com.tsi.launcher.data;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MinecraftJavaCompatibilityTest {
    @Test public void oldAlphaAndBetaMetadataFallsBackToJavaEight() {
        assertEquals(Integer.valueOf(8), MinecraftJavaCompatibility.fallbackJavaMajorFor("a1.2.6", true));
        assertEquals(Integer.valueOf(8), MinecraftJavaCompatibility.fallbackJavaMajorFor("b1.7.3", true));
        assertEquals(Integer.valueOf(8), MinecraftJavaCompatibility.fallbackJavaMajorFor("rd-132211", true));
    }

    @Test public void legacyReleaseVersionsWithoutJavaMetadataUseJavaEight() {
        assertEquals(Integer.valueOf(8), MinecraftJavaCompatibility.fallbackJavaMajorFor("1.12.2", false));
        assertEquals(Integer.valueOf(8), MinecraftJavaCompatibility.fallbackJavaMajorFor("1.16.5", false));
    }

    @Test public void modernVersionsAndUnrecognizedIdsDoNotGetAnInventedFallback() {
        assertNull(MinecraftJavaCompatibility.fallbackJavaMajorFor("1.21.4", false));
        assertNull(MinecraftJavaCompatibility.fallbackJavaMajorFor("24w14a", false));
    }
}
