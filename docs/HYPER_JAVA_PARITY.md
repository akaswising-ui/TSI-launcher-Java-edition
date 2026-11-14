# Hyper Launcher 3 Java Edition feature parity in TSI

This document compares the published **Minecraft: Java Edition** feature set in Hyper Launcher 3 with the TSI Java Edition branch. The goal is feature compatibility while retaining TSI's own app identity, launcher icon and existing dashboard. It is not a claim that TSI uses Hyper's codebase or UI.

Reference inspected: [Hyper Launcher 3 README](https://github.com/ashmeet-op/Hyper_Launcher_v3/blob/master/README.md), source revision `4a5efbbf0421afaa36c0b925b0d9a702cdfe8de9`.

## Feature matrix

| Hyper Java feature | TSI Java Edition implementation | Status / boundary |
|---|---|---|
| Broad Minecraft Java version catalogue | Mojang manifest catalogue, version filtering, verified metadata/client/library/asset installer, embedded Amethyst Java game engine | Supported; actual version compatibility still depends on game version, Java and device |
| Fabric | Existing Amethyst installer flow and TSI modpack import | Supported |
| Forge | Existing Amethyst installer flow and TSI modpack import | Supported |
| NeoForge | Existing Amethyst installer flow and TSI modpack import | Supported |
| Quilt | Existing Amethyst installer flow and TSI modpack import | Supported |
| OptiFine | Existing Amethyst installer flow and TSI modpack import | Supported |
| LiteLoader (experimental in Hyper) | Not exposed by the pinned Amethyst engine's installer selection | Not implemented |
| Modrinth `.mrpack` and CurseForge ZIP | Live provider search, version selection, installation and archive import | Supported |
| Instance management | Create, select, pin, rename, delete, isolated game folders, MultiMC/Prism import/export, native Hyper `hyper_instance.json` ZIP import and bounded `.7z` import | Supported for documented archive structures |
| Per-instance options | Runtime, renderer, JVM args, control-layout name, args mode and shared-data selection; settings are now passed into the Amethyst launch profile | Implemented; the selected runtime/control layout must exist on the device |
| Custom Java runtime selection | Scan and select installed Android runtimes; verified Java 8/17/21/25 packages and automatic Java-major compatibility selection | Supported for compatible installed/package runtimes; Hyper-private runtime paths are not imported |
| Accounts | Original Amethyst account selector, Microsoft OAuth and local/offline profiles | Supported through the embedded engine |
| Renderer selection | Existing renderer picker, compatibility validation, adaptive default selection, LTW preference where available, GL4ES/MobileGlues and other Amethyst renderer options | Built-in Amethyst renderers plus installed legacy FCL/Zalith V1 renderer APK discovery and launch bridge; Hyper Renderer V2 packages are not yet supported |
| Performance tuning | Device-aware FPS options and manual re-apply action | Implemented; no device benchmark is implied |
| MobileGlues | Existing Amethyst MobileGlues renderer support when its native component is present | Supported on compatible devices/builds |
| Controller / touch / keyboard / mouse | Existing Amethyst controls and input implementation | Retained from the embedded engine |
| Built-in local mod tools | New per-instance `.jar` mod manager: import, validate, enable, disable and delete | Implemented for local JAR files; this is not an online mod catalogue |
| Download handling | TSI verified downloads and progress reporting, plus Amethyst's existing download pipeline | Retained |

## Import and portability rules

- TSI imports Hyper native instance-folder ZIPs containing `hyper_instance.json`, as well as MultiMC/Prism ZIPs.
- MultiMC/Prism export from TSI is the recommended cross-launcher transfer format because it carries the standard instance metadata and loader components.
- Hyper modded raw-folder ZIPs need the matching loader version JSON in the archive or already present in TSI. An archive without that metadata is rejected instead of creating a profile that cannot launch.
- Worlds, mods, configs and `options.txt` are transferred with the instance. Hyper's app-private JRE folders and control-map file paths are not copied because they are private to Hyper's Android storage sandbox.
- Hyper renderer IDs are mapped only when a known Amethyst-equivalent exists. Unsupported plugin renderer IDs fall back to TSI/Amethyst selection.
- The new local mod manager modifies only the selected instance's `.minecraft/instances/<id>/game/mods` directory. It does not edit another instance's mods folder.

## Intentionally not cloned

- Hyper's Material 3 launcher interface and app icon: TSI's existing icon and interface remain unchanged.
- Hyper-only external renderer APK plugin format: TSI uses the renderers packaged or supported by Amethyst.
- Hyper's private MojoExec backend and Horizon/Rust engine: TSI Java Edition continues to launch through its pinned Amethyst Android Java engine. A different game backend cannot be substituted by copying profile settings.
- LiteLoader: absent from the pinned Amethyst installer flow inspected for this integration.
- Arbitrary Hyper-private runtime/control file paths: not portable across Android app-private storage.

## Additional Hyper source-tree tools and TSI implementation

The following tools have now been added to the TSI branch. They are TSI implementations that respect Android permissions and Amethyst's available native hooks; they are not byte-for-byte copies of Hyper's UI or backend.

| Source-tree tool | TSI implementation | Compatibility boundary |
|---|---|---|
| Game screen recorder and recordings gallery | Android MediaProjection foreground service, user confirmation for every session, H.264 MP4 gallery with open/share/delete | Records the visible screen, video only, up to 720p/30 FPS. It does not record internal game audio or microphone audio |
| Vulkan / Adreno driver manager | AdrenoTools-style ZIP import, bounded extraction, metadata and ELF ABI validation, select/remove, custom Turnip library path through Amethyst's existing namespace hook | Custom driver is used only on Adreno devices with Vulkan support. Hyper's `qgl_config.txt` override is not available in the pinned Amethyst engine |
| External renderer APK plugins | Discovers installed legacy FCL/Zalith V1 APKs using `fclPlugin` / `zalithRendererPlugin` metadata, checks active-ABI `.so` libraries and DLOPEN dependencies, adds validated renderers to Amethyst | Requires the plugin APK already installed. Hyper Renderer V2 JSON plugins are not supported yet. `QUERY_ALL_PACKAGES` is required for package discovery and may need store-policy approval |
| File manager | Android SAF folder picker with browse, create folder, open, copy/paste file, rename, share and delete | Operates only inside the folder tree explicitly granted by the user. It is not unrestricted root or full-storage access |
| Custom instance images | Pick a local image, preview it on the instance card, reset to default | Stored in TSI app-private data by instance ID; not included in exported instance ZIPs |
| Quick-settings / game overlay | User-enabled floating TSI shortcut bubble with recorder, gallery, file manager, crash analyzer, Vulkan packages and engine settings | This is a shortcut panel, not a clone of Hyper's full side-drawer quick controls. Android Display over other apps and foreground-service notices apply |
| Gemini crash analyzer | Local log heuristics plus optional Gemini API analysis, encrypted API key in Android Keystore-backed storage, explicit checkbox consent before upload | The user must supply a Gemini API key and approve sending the displayed diagnostics. No automatic log upload |
| LiteLoader | Experimental dedicated installer reads the official LiteLoader manifest, downloads HTTPS Maven (m2) artifacts and dependencies, writes an inherited child version JSON and switches the selected instance to it | Supported for compatible manifest entries on Minecraft 1.8.9–1.12.2; Ivy-only versions and non-HTTPS repositories are rejected. The LiteLoader service itself is legacy and may not work with every modpack |

## Remaining compatibility boundaries

- Hyper's Material 3 launcher interface and app icon are not cloned. TSI keeps its existing icon and dashboard.
- Hyper's private MojoExec backend and Horizon/Rust engine are separate game runtimes. TSI Java Edition continues to use the pinned Amethyst Java engine.
- Hyper Renderer V2 plugin packages and its custom Vulkan `qgl_config` override are not implemented. The legacy FCL/Zalith plugin metadata and Turnip driver loader paths described above are supported only where the pinned Amethyst native runtime can consume them.
- Hyper-private JRE and control-map file paths are not copied. TSI uses its own installed Android JREs and control maps.
- LiteLoader is experimental and limited to the official manifest's HTTPS Maven (m2) entries for Minecraft 1.8.9–1.12.2. Ivy-only metadata and non-HTTPS repositories are rejected; this does not imply compatibility with every legacy mod or phone.

The engine-integrated build must pass before an APK is published. CI verifies compilation and packaging, not physical-phone recording, native driver loading, renderer stability, Gemini API behavior or Minecraft gameplay. A device smoke test is still required for those behaviors.
