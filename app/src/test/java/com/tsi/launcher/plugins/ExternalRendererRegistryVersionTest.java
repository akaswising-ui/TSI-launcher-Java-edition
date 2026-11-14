package com.tsi.launcher.plugins;

import org.junit.Test;
import java.util.Collections;

import static org.junit.Assert.*;

public class ExternalRendererRegistryVersionTest {
    @Test public void comparesNumericVersionSegments() {
        assertTrue(ExternalRendererRegistry.compareNumericVersions("1.9", "1.10") < 0);
        assertTrue(ExternalRendererRegistry.compareNumericVersions("1.20.1", "1.20") > 0);
        assertEquals(0, ExternalRendererRegistry.compareNumericVersions("1.20", "1.20.0"));
    }

    @Test public void enforcesPluginMinecraftVersionRange() {
        ExternalRendererRegistry.RendererPluginInfo plugin =
                new ExternalRendererRegistry.RendererPluginInfo(
                        "example.plugin",
                        "tsi.fcl.example.plugin",
                        "gl4es",
                        "Example renderer",
                        "1.12.2",
                        "1.21.4",
                        "libGLESv2.so",
                        "libEGL.so",
                        "/data/app/example.plugin/lib/arm64",
                        Collections.emptyMap(),
                        Collections.emptyList());

        assertTrue(ExternalRendererRegistry.supportsMinecraftVersion(plugin, "1.12.2"));
        assertTrue(ExternalRendererRegistry.supportsMinecraftVersion(plugin, "1.20.1"));
        assertTrue(ExternalRendererRegistry.supportsMinecraftVersion(plugin, "1.21.4"));
        assertFalse(ExternalRendererRegistry.supportsMinecraftVersion(plugin, "1.8.9"));
        assertFalse(ExternalRendererRegistry.supportsMinecraftVersion(plugin, "1.21.5"));
        assertFalse(ExternalRendererRegistry.supportsMinecraftVersion(plugin, "24w12a"));
    }

    @Test public void pluginWithoutVersionLimitsAllowsSnapshots() {
        ExternalRendererRegistry.RendererPluginInfo plugin =
                new ExternalRendererRegistry.RendererPluginInfo(
                        "example.plugin",
                        "tsi.fcl.example.plugin",
                        "gl4es",
                        "Example renderer",
                        null,
                        null,
                        "libGLESv2.so",
                        "libEGL.so",
                        "/data/app/example.plugin/lib/arm64",
                        Collections.emptyMap(),
                        Collections.emptyList());

        assertTrue(ExternalRendererRegistry.supportsMinecraftVersion(plugin, "24w12a"));
    }
}
