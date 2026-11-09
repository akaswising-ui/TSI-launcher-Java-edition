package com.tsi.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MinecraftJavaCompatibilityTest {
    @Test
    fun oldAlphaAndBetaMetadataFallsBackToJavaEight() {
        assertEquals(8, MinecraftJavaCompatibility.fallbackJavaMajorFor("a1.2.6", true))
        assertEquals(8, MinecraftJavaCompatibility.fallbackJavaMajorFor("b1.7.3", true))
        assertEquals(8, MinecraftJavaCompatibility.fallbackJavaMajorFor("rd-132211", true))
    }

    @Test
    fun legacyReleaseVersionsWithoutJavaMetadataUseJavaEight() {
        assertEquals(8, MinecraftJavaCompatibility.fallbackJavaMajorFor("1.12.2", false))
        assertEquals(8, MinecraftJavaCompatibility.fallbackJavaMajorFor("1.16.5", false))
    }

    @Test
    fun modernVersionsAndUnrecognizedIdsDoNotGetAnInventedFallback() {
        assertNull(MinecraftJavaCompatibility.fallbackJavaMajorFor("1.21.4", false))
        assertNull(MinecraftJavaCompatibility.fallbackJavaMajorFor("24w14a", false))
    }
}
