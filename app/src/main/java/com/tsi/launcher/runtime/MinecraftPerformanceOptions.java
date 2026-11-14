package com.tsi.launcher.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges device-aware performance settings into Minecraft options.txt.
 * Non-performance settings (audio, key bindings, resource packs, etc.) survive.
 */
public final class MinecraftPerformanceOptions {
    private MinecraftPerformanceOptions() { }

    public static String merge(String existing, DevicePerformanceProfileSnapshot device) {
        if (device == null) throw new IllegalArgumentException("Device profile is required.");

        LinkedHashMap<String, String> settings = new LinkedHashMap<>();
        List<String> preservedLines = new ArrayList<>();

        if (existing != null && !existing.isEmpty()) {
            String[] lines = existing.split("\\r?\\n");
            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) continue;
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) {
                    preservedLines.add(line);
                    continue;
                }
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    preservedLines.add(line);
                    continue;
                }
                String key = line.substring(0, separator).trim();
                if (!key.isEmpty()) {
                    settings.put(key, line.substring(separator + 1).trim());
                } else {
                    preservedLines.add(line);
                }
            }
        }

        // The low-cost renderer settings apply only to video/performance keys.
        settings.put("renderDistance", String.valueOf(device.getRenderDistance()));
        settings.put("simulationDistance", String.valueOf(device.getSimulationDistance()));
        settings.put("graphics", "fast");
        settings.put("fancyGraphics", "false");
        settings.put("ao", "0.0");
        settings.put("entityDistanceScaling", "0.5");
        settings.put("particles", "2");
        settings.put("maxFps", String.valueOf(device.getMaxFps()));
        settings.put("mipmapLevels", String.valueOf(device.getMipmapLevels()));
        settings.put("biomeBlendRadius", "0");
        settings.put("renderClouds", "off");
        settings.put("entityShadows", "false");
        settings.put("useVbo", "true");
        settings.put("enableVsync", "false");

        StringBuilder output = new StringBuilder();
        for (String line : preservedLines) output.append(line).append('\n');
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            output.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        return output.toString();
    }
}
