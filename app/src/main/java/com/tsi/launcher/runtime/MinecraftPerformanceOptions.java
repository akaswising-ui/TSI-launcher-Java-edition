package com.tsi.launcher.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Updates only performance-related Minecraft options.
 * All unrelated lines are kept in their original order and spelling.
 */
public final class MinecraftPerformanceOptions {
    private MinecraftPerformanceOptions() { }

    public static String merge(String existing, DevicePerformanceProfileSnapshot device) {
        if (device == null) throw new IllegalArgumentException("Device profile is required.");

        LinkedHashMap<String, String> performance = new LinkedHashMap<>();
        performance.put("renderDistance", String.valueOf(device.getRenderDistance()));
        performance.put("simulationDistance", String.valueOf(device.getSimulationDistance()));
        performance.put("graphics", "fast");
        performance.put("fancyGraphics", "false");
        performance.put("ao", "0.0");
        performance.put("entityDistanceScaling", "0.5");
        performance.put("particles", "2");
        performance.put("maxFps", String.valueOf(device.getMaxFps()));
        performance.put("mipmapLevels", String.valueOf(device.getMipmapLevels()));
        performance.put("biomeBlendRadius", "0");
        performance.put("renderClouds", "off");
        performance.put("entityShadows", "false");
        performance.put("useVbo", "true");
        performance.put("enableVsync", "false");

        List<String> preservedLines = new ArrayList<>();
        if (existing != null && !existing.isEmpty()) {
            String[] lines = existing.split("\\r?\\n", -1);
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                // split(..., -1) keeps the final empty item when the source ends in a newline.
                if (index == lines.length - 1 && line.isEmpty()) continue;

                int separator = line.indexOf(':');
                if (separator > 0) {
                    String key = line.substring(0, separator).trim();
                    if (performance.containsKey(key)) continue;
                }
                // Keep non-performance options byte-for-byte at the line level. This
                // includes key bindings, resource-pack lists, comments and unknown keys.
                preservedLines.add(line);
            }
        }

        StringBuilder output = new StringBuilder();
        for (String line : preservedLines) output.append(line).append('\n');
        for (Map.Entry<String, String> entry : performance.entrySet()) {
            output.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        return output.toString();
    }
}
