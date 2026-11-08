# TSI Launcher Architecture

## Non-destructive project rule

New modules are added without deleting existing repository files. The existing `.gitignore` is preserved. Any later engine integration must be added as a separate module or adapter and must retain upstream license files and notices.

## 1. Application layers

```text
Compose UI
  ├── Java Edition screen
  ├── Bedrock Edition screen
  ├── Profile / Authentication screen
  └── Settings / Instance manager
           |
           v
Domain layer
  ├── Version catalogue
  ├── Install coordinator
  ├── Instance manager
  ├── Launch specification builder
  └── Runtime capability checker
           |
           v
Data and platform adapters
  ├── Mojang metadata client
  ├── Verified download manager
  ├── Scoped storage manager
  ├── Amethyst Android JVM / game engine bridge
  └── Bedrock PackageManager adapter
```

Suggested next package boundaries (the existing files are not to be moved just to match this diagram):

```text
app/src/main/java/com/tsi/launcher/
  data/             # Mojang manifest and version metadata
  storage/          # instances, versions, assets and libraries
  bedrock/          # official installed-app integration
  data/             # Mojang manifest, metadata and library resolution
  storage/          # shared .minecraft root, versions and isolated game folders
  runtime/          # Android OpenJDK installer, runtime inspection and Amethyst bridge
  ui/               # Compose screens and navigation (can be split later)
```

## 2. Java version catalogue and install pipeline

The version manifest is a catalogue. Each entry's `url` points to a separate version JSON. That JSON is the source of truth for:
- `javaVersion.majorVersion`
- `mainClass`
- `downloads.client`
- `libraries[]`, including rules, downloads and native classifiers
- `assetIndex`
- `arguments.jvm` and `arguments.game`, or the older `minecraftArguments` field

The safe install pipeline is:

1. Fetch the HTTPS manifest.
2. Select the version and fetch its version JSON.
3. Validate that the metadata `id` matches the selected manifest ID.
4. Resolve the Java major version from `javaVersion.majorVersion`; do not guess it from the version label.
5. Evaluate every library's metadata `rules` and platform selectors. Android is not a desktop Linux target: select only libraries and native binaries for the actual Android ABI and the chosen engine's compatibility layer.
6. Download client, libraries, asset index and assets. Verify the size and cryptographic hash supplied by trusted metadata before promoting temporary files to final paths.
7. Extract only the selected version's required native libraries to a version/runtime-specific native directory.
8. Build and validate a launch specification before starting an embedded runtime.

The current installer fetches and validates the selected version JSON, downloads the client JAR, resolves permitted library artifacts, and downloads the asset index and hash-addressed asset objects. Downloads are checked against Mojang metadata and promoted from temporary files atomically. Amethyst supplies the Android native game host and its native/renderer components; TSI's preflight checks that the metadata, client, libraries, assets, compatible Android JRE and engine bridge are present before launch.

## 3. Launch arguments and dynamic classpath

Newer version JSON files use structured `arguments.jvm` and `arguments.game` arrays. Entries can be strings or objects with `rules` and `value`. Older versions commonly use a single `minecraftArguments` string.

Implementation rules:

- Parse structured argument objects; apply their OS/architecture/feature rules before expanding values.
- Keep arguments as a `List<String>`. Never join them into a shell command and split them again: paths, tokens and values may contain spaces.
- For legacy `minecraftArguments`, use a quote-aware tokenizer rather than whitespace splitting.
- Expand placeholders such as `${auth_player_name}`, `${version_name}`, `${game_directory}`, `${assets_root}`, `${assets_index_name}`, `${auth_uuid}`, `${auth_access_token}`, and `${user_type}` only after the selected profile and instance paths are known.
- Use the `mainClass` from metadata. Do not hard-code one main class for every Minecraft release.
- Construct the classpath from every selected library JAR plus the client JAR, using the platform's path separator.
- Resolve LWJGL and native-library variants from metadata and the runtime bridge. LWJGL 2-era versions and LWJGL 3-era versions do not share one universal native set or renderer initialization path.

A launch specification can be represented independently from the process that executes it:

```kotlin
data class JavaLaunchSpec(
    val javaMajor: Int,
    val mainClass: String,
    val classpath: List<File>,
    val jvmArguments: List<String>,
    val gameArguments: List<String>,
    val gameDirectory: File,
    val nativesDirectory: File
) {
    fun classpathArgument(): String =
        classpath.joinToString(File.pathSeparator) { it.absolutePath }
}
```

A typical JVM argument *shape* includes a bounded heap and native-library search paths:

```kotlin
fun buildRuntimeArguments(
    heapMb: Int,
    nativesDirectory: File,
    classpath: List<File>
): List<String> {
    require(heapMb in 512..4096)
    require(nativesDirectory.isDirectory)
    require(classpath.isNotEmpty())

    val cp = classpath.joinToString(File.pathSeparator) { it.absolutePath }
    return listOf(
        "-Xmx${heapMb}M",
        "-Djava.library.path=${nativesDirectory.absolutePath}",
        "-Dorg.lwjgl.librarypath=${nativesDirectory.absolutePath}",
        "-cp",
        cp
    )
}
```

This argument shape explains version metadata, but TSI does not run Minecraft with a desktop-style `java -jar client.jar` command. `AmethystEngineBridge` validates the Android JRE and shared game-data root, prepares the Amethyst profile, and hands off to Amethyst's normal Android launch pipeline. Amethyst owns JVM startup, LWJGL 2 compatibility and LWJGL 3, SDL/display surface, OpenAL audio, renderer lifecycle and touch/keyboard/mouse/gamepad input. TSI checks the version's required Java major before handing off.

The PojavLauncher repository has been discontinued and identifies Amethyst Android as its successor. Amethyst is the preferred research/integration target, not a drop-in Gradle dependency. Before importing or linking source, inspect the exact upstream commit, submodules, native build toolchain and each dependency's license. Keep license notices and implement TSI integration as an adapter. Do not describe TSI's current Java tab as capable of launching games until this bridge is in place.

## 4. Version isolation and storage

Current storage layout:

```text
<app-specific-files>/.minecraft/
  versions/
    <version-id>/
      <version-id>.json
      <version-id>.jar
  instances/
    <instance-id>/
      game/
        saves/
        resourcepacks/
        screenshots/
        mods/
        config/
        logs/
  assets/
  libraries/
```

Each version has an independent game directory. Assets and libraries may be shared only as immutable, hash-verified cache entries. A future instance model should let several instances use the same Minecraft version while keeping their saves, resource packs, mods, settings and logs separate.

The current implementation uses the app-specific external files directory and falls back to internal app files. It does not request broad storage permissions. Android deletes app-specific files on uninstall. A future backup/export feature should use the Storage Access Framework and let the user choose where to export worlds and instance data.

## 5. Bedrock Edition on Android

Bedrock's Android game package and game data are proprietary. TSI must not download, mirror, patch, silently downgrade, or redistribute the official game APK or its private data.

The implemented integration:
- queries visibility only for `com.mojang.minecraftpe`;
- reads the installed package's version name and code through `PackageManager`;
- opens the installed official app using its launch intent.

Android package signing and app sandboxing prevent TSI from replacing another app's signed package or accessing its private data. A launcher's Bedrock version selector can therefore show the installed version and its detected status, not claim to install every historical Bedrock build. If Minecraft is absent, the user installs it separately from its official store source.

## 6. Profiles and authentication

TSI supports Microsoft accounts and local/offline profiles through Amethyst's original account selector and authentication fragments. The TSI profile-plus action routes to that native account flow rather than implementing a second password or token handler. Amethyst owns Microsoft sign-in, token refresh, account files and selected-account persistence.

The TSI bootstrap creates a default local profile only when no valid local or Microsoft account is already selected. It preserves a selected Microsoft account across process startup and reports the current mode in the TSI dashboard. Offline mode remains clearly labelled and does not grant authenticated multiplayer access or bypass Minecraft ownership checks.

## 7. Settings and memory

The current RAM slider stores a requested heap target. It is not a guarantee that Android will permit that allocation. Before launch, calculate a safe cap from the app's memory class, current available memory and the runtime's requirements. Never allocate the full device RAM to the game.

The Java runtime choice is currently a preference only. At launch time, the resolver must compare it with `javaVersion.majorVersion` and the engine's actual supported Java builds. Show an incompatibility message rather than passing a version mismatch to the game.

## 8. Gradle and dependencies

Current dependencies are declared in `app/build.gradle.kts`:
- Android Gradle Plugin and Kotlin Android / Compose compiler plugins
- Jetpack Compose BOM, UI, Foundation, Material 3 and Activity Compose
- Kotlin Coroutines for network work away from the UI thread
- JUnit for JVM unit tests

Future additions should be introduced only when their module is implemented:
- AndroidX DataStore for durable settings/profile preferences;
- WorkManager for resumable/background downloads with user-visible progress;
- Room for indexed version/instance/download state if the catalog becomes too large for simple files;
- Android Keystore integration for auth credentials;
- Amethyst's exact JVM/renderer/native dependencies after source and license review.

## 9. Compatibility and security checklist

- HTTPS only; allowlist official metadata/download hosts and validate redirect destinations too.
- Use temporary files, expected sizes, hashes and atomic promotion for downloads.
- Sanitize version/instance IDs and canonicalize every path.
- Apply metadata library rules, including OS, architecture and feature checks.
- Never trust a downloaded version JSON's path or arbitrary URLs without validation.
- Keep user worlds isolated and provide export/import rather than broad storage access.
- Use official Microsoft authentication; do not store plaintext passwords.
- Never distribute proprietary Minecraft client binaries as TSI assets.
- Test on physical Android hardware. An emulator build alone does not prove LWJGL, GPU, audio, input, RAM and lifecycle compatibility.
