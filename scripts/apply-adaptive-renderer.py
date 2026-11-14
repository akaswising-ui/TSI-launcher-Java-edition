#!/usr/bin/env python3
"""Apply TSI's safe, adaptive default renderer policy to the pinned Amethyst tree."""

from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: apply-adaptive-renderer.py <Amethyst-Android-root>")

engine_root = Path(sys.argv[1]).resolve()
activity_path = (
    engine_root
    / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java"
)
if not activity_path.is_file():
    raise SystemExit(f"Amethyst MainActivity.java not found: {activity_path}")

source = activity_path.read_text(encoding="utf-8")
old = """        // FIXME: Automatic detection should be based on provided hint GLFW_CONTEXT_VERSION_MAJOR and GLFW_CONTEXT_VERSION_MINOR
        // Autoselect renderer
        boolean hasAngelica = hasMods("angelica");
        if (Tools.LOCAL_RENDERER == null) Tools.LOCAL_RENDERER = "opengles_mobileglues";
        // Angelica IS the FPE emulator
        if (hasAngelica) Tools.useSFPEW = false;

        // TODO: Detection for if a mod is present that can use system GLES as driver, set
        // Tools.LOCAL_RENDERER = "opengles_system_gles";

        // This only happens if an old renderer that was selected is removed. Uses renderer_values
        // as the list of priority to use, highest to lowest.
        if(!Tools.checkRendererCompatible(this, Tools.LOCAL_RENDERER)) {
            Tools.RenderersList renderersList = Tools.getCompatibleRenderers(this);
            String firstCompatibleRenderer = renderersList.rendererIds.get(0);
            Log.i("runCraft","Missing renderer "+Tools.LOCAL_RENDERER+ " will be replaced with "+firstCompatibleRenderer);
            Tools.LOCAL_RENDERER = firstCompatibleRenderer;
            Tools.releaseRenderersCache();
        }
"""

new = """        // TSI renderer policy:
        // - Preserve a renderer explicitly selected in the Minecraft profile.
        // - For a new/unconfigured profile on Minecraft 1.17+, prefer LTW
        //   when the device and bundled native library report it as compatible.
        // - Otherwise prefer GL4ES/Krypton, then MobileGlues as a safe fallback.
        // - Preserve any explicitly selected compatible renderer from the profile editor.
        // - Do not auto-select Zink: it is a compatibility/shader option, not the fast default.
        boolean hasAngelica = hasMods("angelica");
        if (hasAngelica) Tools.useSFPEW = false;

        if (Tools.LOCAL_RENDERER == null ||
                !Tools.checkRendererCompatible(this, Tools.LOCAL_RENDERER)) {
            String previousRenderer = Tools.LOCAL_RENDERER;
            Tools.RenderersList renderersList = Tools.getCompatibleRenderers(this);
            String selectedRenderer = null;

            // LTW is supported by Amethyst only for Minecraft 1.17 and newer.
            // Parse the numeric release prefix; unknown/snapshot IDs use the safe
            // general-purpose order and remain manually selectable in the profile editor.
            boolean isLtwMinecraftVersion = false;
            try {
                String numericVersion = version.split("-", 2)[0];
                String[] versionParts = numericVersion.split("[.]");
                if (versionParts.length >= 2) {
                    int majorVersion = Integer.parseInt(versionParts[0]);
                    int minorVersion = Integer.parseInt(versionParts[1]);
                    isLtwMinecraftVersion = majorVersion > 1 ||
                            (majorVersion == 1 && minorVersion >= 17);
                }
            } catch (RuntimeException ignored) {
                isLtwMinecraftVersion = false;
            }

            String[] preferredRendererOrder = isLtwMinecraftVersion
                    ? new String[] {
                            "opengles3_ltw",
                            "opengles2",
                            "opengles2_5",
                            "opengles3",
                            "opengles_mobileglues"
                    }
                    : new String[] {
                            "opengles2",
                            "opengles2_5",
                            "opengles3",
                            "opengles_mobileglues",
                            "opengles3_ltw"
                    };
            for (String candidate : preferredRendererOrder) {
                if (renderersList.rendererIds.contains(candidate)) {
                    selectedRenderer = candidate;
                    break;
                }
            }
            // Keep Amethyst's declared compatibility order as the final fallback.
            if (selectedRenderer == null && !renderersList.rendererIds.isEmpty()) {
                selectedRenderer = renderersList.rendererIds.get(0);
            }
            if (selectedRenderer == null) {
                throw new IllegalStateException("No compatible Android renderer is available.");
            }
            if (previousRenderer == null) {
                Log.i("runCraft", "Auto-selected high-performance renderer " + selectedRenderer);
            } else {
                Log.i("runCraft", "Missing renderer " + previousRenderer +
                        " will be replaced with " + selectedRenderer);
            }
            Tools.LOCAL_RENDERER = selectedRenderer;
            Tools.releaseRenderersCache();
        }
"""

if old in source:
    source = source.replace(old, new, 1)
elif "Auto-selected high-performance renderer " not in source:
    raise SystemExit(
        "Could not find the expected Amethyst renderer block; "
        "upstream source may have changed. No files were modified."
    )

# Guard against accidental loss of the safety properties in this patch.
required = [
    'if (Tools.LOCAL_RENDERER == null ||',
    '"opengles2"',
    '"opengles3_ltw"',
    'isLtwMinecraftVersion',
    '"opengles_mobileglues"',
    'renderersList.rendererIds.isEmpty()',
    'No compatible Android renderer is available.',
    'Auto-selected high-performance renderer'
]
missing = [item for item in required if item not in source]
if missing:
    raise SystemExit("Adaptive renderer patch verification failed: " + ", ".join(missing))

activity_path.write_text(source, encoding="utf-8")
print("Applied TSI adaptive renderer selection: GL4ES/Krypton preferred, compatible fallback retained.")
