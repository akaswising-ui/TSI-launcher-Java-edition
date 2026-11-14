#!/usr/bin/env python3
"""Enable selectable AdrenoTools ZIP drivers through Amethyst's Turnip namespace hook."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: apply-adreno-driver-packages.py <Amethyst-Android-root>")

root = Path(sys.argv[1]).resolve()
jre_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java"
hook_path = root / "app_pojavlauncher/src/main/jni/driver_helper/internal_android_dlopen_hook/turnip/hook.c"
for path in (jre_path, hook_path):
    if not path.is_file():
        raise SystemExit(f"Required Amethyst source file is missing: {path}")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source marker, found {count}")
    return text.replace(old, new, 1)

jre = jre_path.read_text(encoding="utf-8")
jre = replace_once(
    jre,
    """        if(info.isAdreno() && !PREF_ZINK_PREFER_SYSTEM_DRIVER) {
            envMap.put("POJAV_LOAD_TURNIP", "1");
        }

        envMap.put("DALVIK_APPLICATION", Tools.jObjectToString(activity.getApplication()));""",
    """        if(info.isAdreno() && !PREF_ZINK_PREFER_SYSTEM_DRIVER) {
            envMap.put("POJAV_LOAD_TURNIP", "1");
        }

        // TSI: a validated, user-selected AdrenoTools package can replace the
        // bundled libvulkan_freedreno.so through the same isolated Turnip namespace.
        com.tsi.launcher.vulkan.AdrenoDriverPackageManager.DriverPackageInfo customDriver =
                com.tsi.launcher.vulkan.AdrenoDriverPackageManager.selectedForNativeLoader(activity);
        if (customDriver != null && info.isAdreno()
                && Tools.checkVulkanSupport(activity.getPackageManager())
                && !PREF_ZINK_PREFER_SYSTEM_DRIVER) {
            envMap.put("POJAV_LOAD_TURNIP", "1");
            envMap.put("POJAV_TURNIP_DIR", customDriver.directory);
            envMap.put("POJAV_TURNIP_LIBRARY", customDriver.libraryName);
        }

        envMap.put("DALVIK_APPLICATION", Tools.jObjectToString(activity.getApplication()));""",
    "Amethyst custom Turnip driver environment",
)
jre_path.write_text(jre, encoding="utf-8")

hook = hook_path.read_text(encoding="utf-8")
hook = replace_once(
    hook,
    """        turnipNs = private_create_namespace(
                "turnip-driver-NS",
                NULL,
                getenv("POJAV_NATIVEDIR"),""",
    """        const char *nativeDir = getenv("POJAV_NATIVEDIR");
        const char *driverDir = getenv("POJAV_TURNIP_DIR");
        const char *driverLibrary = getenv("POJAV_TURNIP_LIBRARY");
        if (nativeDir == NULL || nativeDir[0] == '\\0') {
            printf("AdrenoSupp: POJAV_NATIVEDIR is missing\\n");
            return NULL;
        }
        if (driverDir == NULL || driverDir[0] == '\\0') driverDir = nativeDir;
        if (driverLibrary == NULL || driverLibrary[0] == '\\0') {
            driverLibrary = "libvulkan_freedreno.so";
        }
        static char turnipSearchPath[8192];
        snprintf(turnipSearchPath, sizeof(turnipSearchPath), "%s:%s", driverDir, nativeDir);

        turnipNs = private_create_namespace(
                "turnip-driver-NS",
                NULL,
                turnipSearchPath,""",
    "Turnip namespace custom search path",
)
hook = replace_once(
    hook,
    """        turnipHandle = linker_ns_dlopen("libvulkan_freedreno.so", RTLD_LOCAL | RTLD_NOW, turnipNs);""",
    """        turnipHandle = linker_ns_dlopen(driverLibrary, RTLD_LOCAL | RTLD_NOW, turnipNs);""",
    "Turnip selected driver library",
)
if '#include <stdio.h>' not in hook:
    hook = replace_once(hook, '#include <stdlib.h>', '#include <stdlib.h>\n#include <stdio.h>', "Turnip snprintf include")
hook_path.write_text(hook, encoding="utf-8")
print("Installed TSI custom Adreno Turnip driver package hook.")
