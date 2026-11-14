#!/usr/bin/env python3
"""Wire installed FCL/Zalith renderer APK plugins into pinned Amethyst's renderer bridge."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: apply-fcl-renderer-plugins.py <Amethyst-Android-root>")

root = Path(sys.argv[1]).resolve()
tools_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java"
jre_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java"

for path in (tools_path, jre_path):
    if not path.is_file():
        raise SystemExit(f"Required Amethyst source file is missing: {path}")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source marker, found {count}")
    return text.replace(old, new, 1)

tools = tools_path.read_text(encoding="utf-8")
tools = replace_once(
    tools,
    """        sCompatibleRenderers = new RenderersList(rendererIds,
                rendererNames.toArray(new String[0]));""",
    """        // TSI: add only installed FCL/Zalith plugins whose active-ABI GL library exists.
        for (String externalRendererId :
                com.tsi.launcher.plugins.ExternalRendererRegistry.getRendererIds(context)) {
            rendererIds.add(externalRendererId);
            rendererNames.add(
                    com.tsi.launcher.plugins.ExternalRendererRegistry.getRendererDisplayName(
                            context, externalRendererId));
        }

        sCompatibleRenderers = new RenderersList(rendererIds,
                rendererNames.toArray(new String[0]));""",
    "Amethyst external renderer enumeration",
)
tools_path.write_text(tools, encoding="utf-8")

jre = jre_path.read_text(encoding="utf-8")
jre = replace_once(
    jre,
    """        readCustomEnv(envMap); // Must be last so it overrides anything the user sets for obvious reasons.""",
    """        // TSI: load a user-selected, separately installed FCL renderer plugin.
        // The registry validates its native library directory and dependency paths
        // against the active Android ABI before any native code is loaded.
        if (LOCAL_RENDERER != null &&
                com.tsi.launcher.plugins.ExternalRendererRegistry.isExternalRenderer(
                        activity, LOCAL_RENDERER)) {
            try {
                com.tsi.launcher.plugins.ExternalRendererRegistry.RendererPluginInfo plugin =
                        com.tsi.launcher.plugins.ExternalRendererRegistry.prepare(
                                activity, LOCAL_RENDERER);
                com.tsi.launcher.plugins.ExternalRendererRegistry.loadDependencies(plugin);
                LD_LIBRARY_PATH = plugin.getLibraryPath() + ":" + LD_LIBRARY_PATH;
                envMap.put("LD_LIBRARY_PATH", LD_LIBRARY_PATH);
                envMap.putAll(plugin.environment);
            } catch (Exception error) {
                throw new IllegalStateException(
                        "Selected external renderer could not be prepared: " + error.getMessage(), error);
            }
        }

        readCustomEnv(envMap); // Must be last so it overrides anything the user sets for obvious reasons.""",
    "Amethyst external renderer environment",
)
jre = replace_once(
    jre,
    """            default:
                Log.w("RENDER_LIBRARY", "No renderer selected, defaulting to opengles_mobileglues");
                renderLibrary = "libmobileglues.so";
                break;""",
    """            default:
                // TSI external FCL renderers provide an absolute, active-ABI native library path.
                String externalRendererLibrary =
                        com.tsi.launcher.plugins.ExternalRendererRegistry.getPreparedGraphicsLibrary(
                                LOCAL_RENDERER);
                if (externalRendererLibrary != null) {
                    renderLibrary = externalRendererLibrary;
                } else {
                    Log.w("RENDER_LIBRARY", "Unknown renderer " + LOCAL_RENDERER
                            + "; defaulting to opengles_mobileglues");
                    renderLibrary = "libmobileglues.so";
                }
                break;""",
    "Amethyst external renderer graphics library",
)
jre_path.write_text(jre, encoding="utf-8")
print("Installed FCL/Zalith renderer APK discovery and launch bridge.")
