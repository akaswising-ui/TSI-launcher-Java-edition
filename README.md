# TSI Launcher Java Edition

An Android launcher dedicated to **Minecraft Java Edition**. Bedrock-specific screens, package detection and version-channel catalogues live in the separate [TSI Launcher Bedrock Edition repository](https://github.com/akaswising-ui/TSI-Launcher-Bedrock-edition-).

## Launcher interface

The original Amethyst/Pojav launcher home remains the Android app entry screen. The TSI Java Edition dashboard is a separate screen opened from the **TSI Java Edition Dashboard** button in that home screen. The original launcher UI remains intact. The profile-plus action in the TSI dashboard opens Amethyst's original account selector, including Microsoft sign-in and local/offline account creation. The runtime/version manager shares the same embedded Java engine.

## Java Edition features

- Java version catalogue from Mojang's official manifest.
- Release, Snapshot, Old Alpha and Old Beta filters.
- Per-version metadata and game directories.
- Verified downloads for version JSON, client JAR, libraries and assets.
- Android Java runtime detection and installation.
- Amethyst Android engine integration for the JVM, SDL, LWJGL, graphics and input host.
- Microsoft account sign-in through Amethyst's existing authentication flow, plus local/offline profiles. TSI preserves an already-selected Microsoft account across startup; a local profile is created only when no valid account is selected.
- VM preflight checks before launch.

The engine-integrated debug APK is assembled by `.github/workflows/android-engine.yml` using the pinned Amethyst Android revision. The current direct Java Edition download is [TSI-Launcher-engine-debug.apk](https://github.com/akaswising-ui/TSI-launcher-Java-edition/releases/download/android-debug-latest/TSI-Launcher-engine-debug.apk). Do not use the dashboard-only `TSI-dashboard-shell-smoke-only` artifact for gameplay. A successful CI build verifies APK packaging, the app ID and the original Pojav launcher entry. It does **not** prove that every historical Minecraft version will run on every phone; renderer, LWJGL, native-library and Java-version requirements vary by release.

## Feature parity with the Pojav/Amethyst engine

TSI embeds the pinned Amethyst Android engine rather than reimplementing a desktop JVM launcher. The original engine source, native libraries and launcher services are built into the engine APK. TSI adds its own Java version dashboard, verified installer, Android JRE manager and isolated game-data bridge.

- **Game engine:** Android JVM startup, LWJGL 2 compatibility / LWJGL 3, SDL game surface, OpenAL audio and native renderer/input layers are provided by Amethyst.
- **Versions and runtime:** TSI's catalogue, complete client/library/asset installer and Java 8/17/21/25 Android runtime packages connect to Amethyst's MultiRT/JVM pipeline.
- **Mod loaders:** Forge, Fabric, Quilt, NeoForge and OptiFine installer paths remain in the embedded engine. TSI's integration adds shared runtime selection and inherited vanilla-version resolution.
- **Modpacks:** The TSI Mod Pack screen now uses the embedded Modrinth + CurseForge APIs for live search, version selection, installation, and ZIP/MRPACK import. It no longer displays sample preview packs.
- **Controls and graphics:** Amethyst's custom touch controls, keyboard/mouse/gamepad input, renderer options and video settings are retained in the engine. They have not all been duplicated as Compose settings panels.
- **Accounts:** Microsoft sign-in and local/offline profiles both use Amethyst's original account system. TSI's account action opens the native account selector; it does not collect Microsoft passwords in TSI's own screens. Offline profiles do not grant authenticated-server access or bypass ownership checks.

The TSI dashboard and the original Amethyst launcher entry remain separate screens. A successful build confirms packaging and compilation, not gameplay on every Android ABI/GPU or every Minecraft release.

## Device-based Java heap limits

The launcher reads Android's reported physical RAM and applies a conservative Java heap budget:
- Up to 2.8 GB physical RAM: **512 MB recommended / 768 MB maximum**.
- 2.8–3.5 GB physical RAM: **768 MB recommended / 1024 MB maximum**.
- 3.5–4.5 GB physical RAM: **1024 MB recommended / 1536 MB maximum**.
- 4.5–7.5 GB physical RAM: **1536 MB recommended / 2048 MB maximum**.
- 7.5 GB or more: **2048 MB recommended / 3072 MB maximum**.
- Older saved values above the device limit are clamped before the JVM starts.

The lower profile avoids assigning too much of a 2 GB phone's memory to the Java heap while Android, SDL, LWJGL, graphics and audio still need RAM. Heap size is not total game memory. Very recent Minecraft versions and large modpacks may still exceed what a low-memory phone can run.

## FPS and low-end device optimization

The engine integration keeps the APK debuggable, but passes `NDK_DEBUG=0` to Amethyst's `ndk-build` so its native C/C++ host libraries are compiled with release optimizations (`-O2`) while retaining debug symbols. This avoids shipping an unoptimized native debug build as the gameplay APK.

On the first launch for each game instance, TSI applies a device-aware video preset to that instance's existing `options.txt`. It reduces render/simulation distance, switches graphics to Fast, minimizes particles, disables clouds and VSync, and applies a RAM-tier FPS ceiling. The manual **APPLY FPS OPTIMIZATION** action can reapply the preset later. Unrelated settings such as audio, key bindings and resource-pack selections are preserved. Once the automatic preset has been applied, later launches do not overwrite player changes unless the device profile changes.

This reduces avoidable launcher/native overhead and GPU work, but it cannot guarantee a specific FPS. Minecraft version, renderer, GPU driver, thermal throttling, mods and world complexity remain major factors. The Android launcher UI remains Java because it uses Android lifecycle and Amethyst APIs; replacing that glue with Rust/C++ would not itself accelerate Minecraft's renderer.

## Storage layout

Game data is stored in app-specific external storage when available:

```text
.minecraft/
  versions/<version>/
  instances/<version>/game/
  libraries/
  assets/
```

Each Minecraft version has its own instance directory so saves, options, mods and configuration files do not overwrite one another. Shared immutable libraries and verified assets may be reused.

## Build

Requirements:
- Android SDK Platform 36
- JDK 21 for the engine-integrated workflow
- Gradle 9.6.1 for the pinned Amethyst build

The CI workflow checks out the pinned upstream Amethyst Android source, applies `scripts/integrate-amethyst-engine.sh`, then builds:

```bash
./gradlew :app_pojavlauncher:assembleDebug
```

The build output is staged as `TSI-Launcher-engine-debug.apk`, uploaded as a 90-day Actions artifact, and published under that same filename in the `android-debug-latest` Release. The separate `TSI-dashboard-shell-smoke-only` artifact is a Java UI smoke test and does not contain the Minecraft game engine. Do not install it as the playable launcher.

## Scope and compatibility

- This repository is Java Edition only. It does not detect or launch the Bedrock Android package.
- A desktop Java installation is not sufficient. The launcher needs an Android-built JRE and the Android game host/native components.
- Java runtime selection follows the version metadata's required major version.
- Some historical versions may still require native or renderer compatibility work.
- A crash-free guarantee requires testing the actual phone and the selected Minecraft version. When a launch fails, attach `.minecraft/latestlog.txt` and the Android crash log; the first visible error alone may not identify the root cause.

## Upstream engine

The Android engine is pinned to [AngelAuraMC/Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) revision `28d6e12d68121b7dd75f2e841058a6b7cdfe4a29`. Preserve upstream license notices and native dependency notices when modifying the integration.
