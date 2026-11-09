package com.tsi.launcher.data

/**
 * Resolves a safe Java fallback for metadata formats predating javaVersion.
 */
object MinecraftJavaCompatibility {
    fun fallbackJavaMajorFor(versionId: String, hasLegacyArguments: Boolean): Int? {
        if (hasLegacyArguments) return 8

        val match = Regex("^1\\.(\\d+)(?:\\.|$)").find(versionId) ?: return null
        val minor = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        return if (minor <= 16) 8 else null
    }
}
