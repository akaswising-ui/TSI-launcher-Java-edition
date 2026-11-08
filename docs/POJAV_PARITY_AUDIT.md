# PojavLauncher feature-parity audit for TSI Launcher Java Edition

Audit baseline: repository `main` at 2026-11-08. This document distinguishes code that is already integrated from work that still needs runtime/device verification. It does not claim that a successful APK build proves gameplay.

## Non-negotiable product constraints

- Keep the existing TSI dashboard UI and navigation. Do not replace it with PojavLauncher screens.
- Preserve the existing TSI launcher icon and all existing branding assets.
- Preserve the pinned Amethyst Android engine and existing TSI installers, runtime manager, storage layout, account handling and modpack work.
- Reuse the embedded Amethyst/Pojav engine for game execution. Do not implement a desktop `java -jar` launch path on Android.

## Current architecture found in the repository

- `engine/Amethyst-Android` is a pinned Git submodule at revision `28d6e12d68121b7dd75f2e841058a6b7cdfe4a29`.
- `scripts/integrate-amethyst-engine.sh` integrates the Android engine module and native assets.
- `AmethystEngineBridge` targets `net.kdt.pojavlaunch.LauncherActivity`, checks SDL/LWJGL and native components, unpacks engine components and prepares the engine game host.
- TSI has its own Mojang version catalogue, verified game installer, Android JRE installer/runtime manager, per-version storage, VM controller and Compose dashboard.
- The original Amethyst account flow is used for Microsoft and local/offline profiles.
- The existing feature-parity document describes the Modrinth/CurseForge modpack search/import path and entry points into the engine's modloader and advanced settings screens.

## Feature parity matrix

| Pojav/Amethyst capability | TSI integration found | Remaining work |
|---|---|---|
| Android JVM startup and game activity | Embedded Amethyst engine and launch bridge | Verify real launch on supported Android devices |
| LWJGL 2 / LWJGL 3 | Engine integration and component checks | Test representative old and new Minecraft versions |
| SDL game surface and native renderer | Embedded engine host | Test GPU/driver compatibility and lifecycle resume |
| OpenGL rendering | Engine renderer path | Test renderer choices and error recovery on devices |
| OpenAL audio | Embedded engine audio path | Test output, focus changes and cleanup after exit |
| Touch controls and custom layouts | Upstream engine controls retained | Verify touch mapping, resize and persistence |
| Keyboard, mouse and gamepad | Upstream input bridges retained | Verify external devices and hot-plug behaviour |
| Microsoft account | Original Amethyst account selector/authentication | Test sign-in, refresh, expired-token and cancellation paths |
| Local/offline account | TSI bridge creates/selects Amethyst-compatible profile | Test restart persistence and offline launch |
| Vanilla version installation | Mojang manifest and verified client/library/assets installer | Test interrupted downloads, hash failures and resume |
| Java runtime management | Android JRE packages and Auto selection | Test runtime selection against version metadata and ABI |
| Forge / NeoForge | Engine installer paths retained | Test installer-JRE choice and parent-version resolution |
| Fabric / Quilt | Engine installer paths retained | Test inherited vanilla metadata and client JAR resolution |
| OptiFine | Engine installer path retained | Test supported base versions and installer runtime |
| Modrinth / CurseForge modpacks | TSI provider search and import integration | Test real downloads, dependencies, import errors and launch |
| Game directory, logs and file operations | Shared Amethyst storage root and per-version directories | Test SAF/storage permissions and log access on Android 8–15 |
| JVM / video / renderer preferences | Original engine settings remain available | Confirm every dashboard shortcut opens the correct engine screen |

## Known dashboard-vs-engine distinction

A feature present in Amethyst is not automatically a native TSI Compose screen. The requested scope is to keep the TSI UI, so engine-owned advanced screens should remain accessible through explicit TSI entry points rather than being replaced or visually cloned. Avoid duplicating engine settings unless a real integration gap is demonstrated.

## Required verification before calling parity complete

1. Build the engine-integrated APK using `.github/workflows/android-engine.yml`; do not use the dashboard-shell smoke artifact as a playable APK.
2. Install the APK on a physical Android device and verify cold start, resume, rotation and clean exit.
3. Launch one representative release using Java 8, one modern release using Java 17/21, and a version requiring Java 25 where supported by the game metadata and engine.
4. Test Microsoft and local/offline account selection separately. Offline mode must not be represented as granting authenticated-server access or game ownership.
5. Test vanilla, Fabric, Quilt, Forge, NeoForge and OptiFine installation and launch separately. Record the parent vanilla version, selected installer JRE and resulting log for each.
6. Test touch, keyboard, mouse, gamepad, audio, renderer changes and game-window lifecycle.
7. Test modpack search, version selection, MRPACK import and CurseForge ZIP import with real packages.
8. Verify the existing launcher icon and TSI dashboard screens are unchanged in the produced APK.
9. Attach `.minecraft/logs/latest.log` (or the engine's actual latest log path) and Android crash output for every failed scenario. Do not mark an item complete based only on source presence or CI compilation.

## Completion rule

Only mark a feature complete after its code path is connected and its acceptance test passes. Source presence, dependency inclusion, documentation and APK compilation are not substitutes for an on-device game launch test.
