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
| Renderer selection | Existing renderer picker, compatibility validation, adaptive default selection, LTW preference where available, GL4ES/MobileGlues and other Amethyst renderer options | Supported for Amethyst renderer IDs; Hyper-only APK renderer plugins are not portable |
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

## Additional Hyper source-tree tools outside its README feature list

The Hyper source tree also contains tools that are not part of the published README feature summary. They are not represented as completed TSI features in this branch:

| Source-tree tool | Current TSI status |
|---|---|
| Game-surface screen recorder and recordings gallery | Not implemented in the TSI dashboard |
| Vulkan / Adreno driver package manager | Not implemented as a Hyper-compatible package manager; TSI exposes only renderers supported by the pinned Amethyst engine |
| External renderer APK plugin manager | Not compatible with Hyper plugin packages; only Amethyst-supported built-in renderers are selectable |
| Built-in file-manager UI | No Hyper-style in-launcher file browser; TSI uses Android document pickers and the embedded engine's file-selection facilities |
| Custom launcher themes / Hyper Material UI | Not cloned; TSI keeps its existing app identity and dashboard |
| Per-instance custom image icon | Hyper icon metadata is not imported into the TSI instance card model |
| Quick-settings game overlay and recording controls | Not cloned into TSI's existing interface |
| Gemini crash-analysis helper | Not implemented; it is an optional service-specific tool, not a Minecraft runtime requirement |

These omissions matter: a feature is not implemented merely because a button or placeholder can be drawn. Adding them properly would require separate Android permission flows, storage models, driver/plugin compatibility and, for some items, engine-level integration.

A successful CI build verifies compilation, packaging and repository-level tests. It does not replace installing the resulting APK and testing game launch, mod loading, rendering, input and crashes on a physical Android device.
