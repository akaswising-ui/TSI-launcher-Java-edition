# Pojav / Amethyst and TSI Java Edition feature comparison

## What is being compared

PojavLauncher is the original Android Java Edition launcher lineage. This TSI build embeds the pinned AngelAuraMC Amethyst-Android revision, which is based on PojavLauncher and is the active engine source used by this repository. The engine is not a small compatibility library: the Android app module, Java launch activities, native libraries, SDL/LWJGL components, modloader code, settings and control system are compiled into the engine APK.

The TSI repository adds a separate Compose dashboard and adapters. It should not replace the Amethyst engine with a desktop-style `java -jar` runner.

## Feature matrix

| Area | Pojav / pinned Amethyst engine | TSI Java Edition |
|---|---|---|
| Java game process | Android JVM and native game host | Uses Amethyst through `AmethystEngineBridge` |
| Minecraft versions | Broad Java version support; actual compatibility varies | Mojang manifest catalogue, filters and complete metadata/client/library/asset installer |
| Android Java runtimes | MultiRT and Android OpenJDK support | Adds verified Java 8, 17, 21 and 25 runtime packages, ABI validation and auto-pick |
| Forge / NeoForge | Installer workflows | Retained, with common installer-JRE selection |
| Fabric / Quilt | JSON/profile installer workflows | Retained, with shared inherited-version resolver |
| OptiFine | Installer workflow | Retained, with vanilla metadata refresh and common installer-JRE selection |
| Modpack search | Modrinth and CurseForge APIs | Connected to the TSI Mod Pack screen |
| Modpack import | CurseForge ZIP and Modrinth MRPACK | Connected to the TSI Mod Pack screen |
| Individual JAR mod installation | Native document picker and mod installer | TSI Settings shortcut opens the original Amethyst JAR installer |
| Game rendering | Android renderer bridge and LWJGL compatibility | Uses the embedded Amethyst renderer/native stack |
| Audio | OpenAL path | Uses the embedded Amethyst audio path |
| Touch / keyboard / mouse / gamepad | Native input and custom control maps | Engine input retained; TSI Settings now links directly to the custom touch editor and gamepad mapper |
| Renderer/video options | Upstream preference screens | Still available in embedded engine settings; not all have duplicate TSI Compose controls |
| Logs, game folders and file operations | Engine facilities | TSI per-version data folders plus engine facilities |
| Microsoft online authentication | Supported by upstream flow | Available through the original Amethyst account selector and Microsoft login fragment |
| Bedrock Edition | Not part of the Java engine | Separate TSI Bedrock repository / app integration |

## Account compatibility

The TSI dashboard's existing profile-plus action opens Amethyst's original account selector. Microsoft OAuth sign-in, refresh and local/offline account creation stay in the upstream implementation; TSI does not substitute a custom password form. The selected Microsoft account is preserved across TSI startup and launch-profile preparation.

The TSI Settings card also exposes direct actions for the upstream custom touch-control editor, gamepad remapper and individual JAR mod installer. These actions route into Amethyst's original activities/fragments rather than reimplementing those systems in Compose. The touch editor bootstraps the bundled default map only when no usable map exists, leaving an existing custom layout untouched.

## TSI dashboard access to preserved engine features

The TSI Mod Pack screen exposes live provider-backed modpack search and ZIP/MRPACK import. It also has a direct action to open Amethyst's existing Forge, Fabric, Quilt, NeoForge and OptiFine installer screens. The TSI Settings page has an Advanced Engine Settings action that opens the existing renderer, video, control, gamepad, mouse and JVM preference screens. These are entry points into the embedded engine features, not a second implementation of their UI.

## TSI-specific integration work

- Uses the same Amethyst `.minecraft` storage root for game files.
- Keeps each selected version's game directory isolated.
- Verifies Mojang client/library/assets before marking an installation ready.
- Installs Android OpenJDK packages into Amethyst's MultiRT runtime directory.
- Validates JRE Java major, Android ABI, `release` metadata and `libjvm.so` before selection.
- Resolves modloader parent vanilla JSON and client JAR requirements.
- Allows offline profiles to prepare and install local modloader/modpack data; network downloads still require internet.
- Keeps demo/Microsoft accounts out of the offline-only account selector.

## Important boundaries

The original Amethyst launcher home remains the Android entry screen. The TSI Compose dashboard is a separate screen and keeps its existing visual design and app icon. Native renderer, video, JVM and advanced control preferences remain in Amethyst; TSI provides an explicit Settings entry point. TSI Settings also has direct shortcuts for touch-layout editing, gamepad remapping and JAR mod installation. The Mod Pack screen remains connected to real provider APIs.

Offline profiles do not confer Minecraft ownership or authenticated-server access. Renderer support, native library availability, Java compatibility and memory requirements vary by device and game version. APK CI checks do not replace testing on a physical Android device.
