package com.tsi.launcher.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftPerformanceOptionsTest {
    @Test public void appliesLowMemoryFpsSettingsAndPreservesUnrelatedOptions() {
        DevicePerformanceProfileSnapshot profile = DevicePerformanceProfile.profileFor(
                2048, false, true, 8, "arm64-v8a");
        String existing = "soundCategory_master:0.7\n"
                + "renderDistance:16\n"
                + "key_key.forward: W\n"
                + "resourcePacks:[\n";

        String merged = MinecraftPerformanceOptions.merge(existing, profile);

        assertTrue(merged.contains("renderDistance:4\n"));
        assertTrue(merged.contains("simulationDistance:3\n"));
        assertTrue(merged.contains("particles:2\n"));
        assertTrue(merged.contains("renderClouds:off\n"));
        assertTrue(merged.contains("enableVsync:false\n"));
        assertTrue(merged.contains("soundCategory_master:0.7\n"));
        assertTrue(merged.contains("key_key.forward: W\n"));
        assertTrue(merged.contains("resourcePacks:[\n"));
        assertFalse(merged.contains("renderDistance:16\n"));
        assertEquals(1, occurrences(merged, "renderDistance:"));
    }

    @Test public void repeatedMergeDoesNotCreateDuplicateSettings() {
        DevicePerformanceProfileSnapshot profile = DevicePerformanceProfile.profileFor(
                6144, false, true, 8, "arm64-v8a");
        String once = MinecraftPerformanceOptions.merge("graphics:fancy\nmaxFps:260\n", profile);
        String twice = MinecraftPerformanceOptions.merge(once, profile);

        assertEquals(1, occurrences(twice, "graphics:"));
        assertEquals(1, occurrences(twice, "maxFps:"));
        assertTrue(twice.contains("graphics:fast\n"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
