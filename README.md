# TSI Launcher Java Edition

An Android launcher dedicated to **Minecraft Java Edition**. Bedrock-specific screens, package detection and version-channel catalogues live in the separate [TSI Launcher Bedrock Edition repository](https://github.com/akaswising-ui/TSI-Launcher-Bedrock-edition-).

## Launcher interface

The original Amethyst/Pojav launcher home remains the Android app entry screen. The TSI Java Edition dashboard is a separate screen opened from the **TSI Java Edition Dashboard** button in that home screen. This keeps the normal launcher UI and the TSI runtime/version manager separate while sharing the same embedded Java engine.

## Java Edition features

- Java version catalogue from Mojang's official manifest.
- Release, Snapshot, Old Alpha and Old Beta filters.
- Per-version metadata and game directories.
- Verified downloads for version JSON, client JAR, libraries and assets.
- Android Java runtime detection and installation.
- Amethyst Android engine integration for the JVM, SDL, LWJGL, graphics and input host.
- Offline-only local profiles with a saved username; the launcher selects a local profile by default. Already-installed versions can launch offline; first-time game-file downloads still require internet access.
- VM preflight checks before launch.

The engine-integrated debug APK is assembled by `.github/workflows/android-engine.yml` using the pinned Amethyst Android revision. The direct Java Edition download is [app-debug.apk](https://github.com/akaswising-ui/TSI-launcher-Java-edition/releases/download/android-debug-latest/app-debug.apk). This release asset is produced by the engine workflow, not the dashboard-only shell workflow. A successful CI build verifies APK packaging, the app ID and the original Pojav launcher entry. It does **not** prove that every historical Minecraft version will run on every phone; renderer, LWJGL, native-library and Java-version requirements vary by release.

## Vivo Y22 / 6 GB RAM profile

The launcher reads Android's reported physical RAM and selects a conservative heap target:
- Recommended heap on a 6 GB-class device: **1536 MB**.
- Maximum heap on a 6 GB-class device: **2048 MB**.
- Older saved values above the device limit are clamped before the JVM starts.
- The remaining memory is left for Android, the launcher, native graphics/audio libraries and the game process.

Heap size is not the same as total game memory. Minecraft and its native renderer also use memory outside the Java heap. These settings reduce avoidable memory pressure; they cannot guarantee that every version or modpack fits.

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

The build output is staged as `TSI-Launcher-engine-debug.apk`, uploaded as a 90-day Actions artifact, and published as `app-debug.apk` in the `android-debug-latest` Release. The separate `TSI-dashboard-shell-smoke-only` artifact is only a Compose compile check and does not contain the Minecraft game engine. Do not install it as the playable launcher.

## Scope and compatibility

- This repository is Java Edition only. It does not detect or launch the Bedrock Android package.
- A desktop Java installation is not sufficient. The launcher needs an Android-built JRE and the Android game host/native components.
- Java runtime selection follows the version metadata's required major version.
- Some historical versions may still require native or renderer compatibility work.
- A crash-free guarantee requires testing the actual phone and the selected Minecraft version. When a launch fails, attach `.minecraft/latestlog.txt` and the Android crash log; the first visible error alone may not identify the root cause.

## Upstream engine

The Android engine is pinned to [AngelAuraMC/Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) revision `28d6e12d68121b7dd75f2e841058a6b7cdfe4a29`. Preserve upstream license notices and native dependency notices when modifying the integration.
