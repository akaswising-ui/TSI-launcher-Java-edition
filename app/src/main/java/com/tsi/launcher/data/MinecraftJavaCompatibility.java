package com.tsi.launcher.data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves a safe Java fallback for metadata formats predating javaVersion. */
public final class MinecraftJavaCompatibility {
    private static final Pattern LEGACY_VERSION = Pattern.compile("^1\\.(\\d+)(?:\\.|$)");

    private MinecraftJavaCompatibility() { }

    public static Integer fallbackJavaMajorFor(String versionId, boolean hasLegacyArguments) {
        if (hasLegacyArguments) return 8;
        Matcher match = LEGACY_VERSION.matcher(versionId == null ? "" : versionId);
        if (!match.find()) return null;
        try {
            int minor = Integer.parseInt(match.group(1));
            return minor <= 16 ? 8 : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
