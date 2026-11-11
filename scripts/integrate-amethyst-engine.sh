#!/usr/bin/env bash
set -euo pipefail

TSI_ROOT="$(cd "${1:-.}" && pwd)"
AMETHYST_ROOT="$(cd "${2:-.build/Amethyst-Android}" && pwd)"
APP_ROOT="$AMETHYST_ROOT/app_pojavlauncher"
TSI_JAVA_ROOT="$TSI_ROOT/app/src/main/java/com/tsi/launcher"

test -d "$APP_ROOT/src/main/java/net/kdt/pojavlaunch"
test -d "$TSI_JAVA_ROOT"
test -f "$AMETHYST_ROOT/LICENSE"
test -f "$APP_ROOT/src/main/AndroidManifest.xml"

python3 - "$AMETHYST_ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
root_gradle = root / "build.gradle"
gradle_properties = root / "gradle.properties"
app_gradle = root / "app_pojavlauncher/build.gradle"
manifest_path = root / "app_pojavlauncher/src/main/AndroidManifest.xml"
application_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/PojavApplication.java"
main_activity_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java"
launcher_activity_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/LauncherActivity.java"
profile_type_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/fragments/ProfileTypeSelectFragment.java"
main_menu_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/fragments/MainMenuFragment.java"
main_menu_layout_path = root / "app_pojavlauncher/src/main/res/layout/fragment_launcher.xml"
main_menu_land_layout_path = root / "app_pojavlauncher/src/main/res/layout-land/fragment_launcher.xml"

def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description}; found {count}")
    return text.replace(old, new, 1)

# TSI's app source is Java-only. Do not enable the Kotlin compiler or
# add the Compose compiler to the embedded Amethyst application.
text = root_gradle.read_text()
if "id 'com.android.library' version '9.3.1' apply false" not in text:
    raise SystemExit("Unexpected Amethyst root Gradle plugin layout.")
text = gradle_properties.read_text()
if "android.builtInKotlin=false" not in text:
    raise SystemExit("Amethyst built-in Kotlin must remain disabled for the Java-only TSI app.")

text = app_gradle.read_text()
text = replace_once(
    text,
    "    compileSdk = 37",
    "    compileSdk {\n        version = release(36) {\n            minorApiLevel = 1\n        }\n    }",
    "Amethyst Android SDK platform compatibility",
)
text = replace_once(
    text,
    "plugins {\n    id 'com.android.application' version '9.3.1'\n}",
    "plugins {\n    id 'com.android.application' version '9.3.1'\n}",
    "Java-only Amethyst app plugin block",
)
text = replace_once(
    text,
    '        applicationId "org.angelauramc.amethyst"',
    '        applicationId "com.tsi.launcher"',
    "Amethyst applicationId",
)
text = replace_once(
    text,
    "        minSdkVersion 21",
    "        minSdkVersion 26",
    "TSI minimum Android API",
)
text = replace_once(
    text,
    "    buildFeatures {\n        prefab true\n        buildConfig true\n    }",
    "    buildFeatures {\n        prefab true\n        buildConfig true\n    }",
    "Java-only Amethyst build features",
)

for old, new, label in [
    ('resValue "string", "app_name", "Amethyst (Debug)"',
     'resValue "string", "app_name", "TSI Launcher (Debug)"', "debug app name"),
    ('resValue "string", "app_short_name", "Amethyst (Debug)"',
     'resValue "string", "app_short_name", "TSI Launcher"', "debug short name"),
    ("resValue 'string', 'application_package', 'org.angelauramc.amethyst.debug'",
     "resValue 'string', 'application_package', 'com.tsi.launcher.debug'", "debug app package"),
    ("resValue 'string', 'storageProviderAuthorities', 'org.angelauramc.amethyst.scoped.gamefolder.debug'",
     "resValue 'string', 'storageProviderAuthorities', 'com.tsi.launcher.scoped.gamefolder.debug'", "debug storage authority"),
    ("resValue 'string', 'shareProviderAuthority', 'org.angelauramc.amethyst.scoped.controlfolder.debug'",
     "resValue 'string', 'shareProviderAuthority', 'com.tsi.launcher.scoped.controlfolder.debug'", "debug share authority"),
    ('resValue "string", "app_name", "Amethyst"',
     'resValue "string", "app_name", "TSI Launcher"', "release app name"),
    ('resValue "string", "app_short_name", "Amethyst"',
     'resValue "string", "app_short_name", "TSI Launcher"', "release short name"),
    ("resValue 'string', 'storageProviderAuthorities', 'org.angelauramc.amethyst.scoped.gamefolder'",
     "resValue 'string', 'storageProviderAuthorities', 'com.tsi.launcher.scoped.gamefolder'", "release storage authority"),
    ("resValue 'string', 'application_package', 'org.angelauramc.amethyst'",
     "resValue 'string', 'application_package', 'com.tsi.launcher'", "release app package"),
]:
    text = replace_once(text, old, new, label)

dependency_marker = '    implementation project(":androidnsbypass")'
java_dependencies = (
    dependency_marker + "\n\n"
    "    // TSI Java runtime installer dependencies.\n"
    '    implementation "org.apache.commons:commons-compress:1.28.0"\n'
    '    implementation "org.tukaani:xz:1.10"'
)
text = replace_once(text, dependency_marker, java_dependencies, "TSI Java dependency insertion")
app_gradle.write_text(text)

# Initialize the default SharedPreferences before Amethyst reads JVM/profile settings.
application = application_path.read_text()
application = replace_once(
    application,
    "\t\t\tsuper.onCreate();\n\t\t\tif(Tools.checkStorageRoot(this)){",
    "\t\t\tsuper.onCreate();\n"
    "\t\t\tLauncherPreferences.DEFAULT_PREF = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);\n"
    "\t\t\tif(Tools.checkStorageRoot(this)){",
    "Amethyst default preferences initialization",
)
# Initialize Amethyst's worker pool before any asset extraction. The number of
# workers follows actual device RAM and CPU capacity, not a fixed four-thread pool.
application = replace_once(
    application,
    "public static final ExecutorService sExecutorService = new ThreadPoolExecutor(4, 4, 500, TimeUnit.MILLISECONDS,  new LinkedBlockingQueue<>());",
    "public static ExecutorService sExecutorService;",
    "adaptive worker-pool declaration",
)
application = replace_once(
    application,
    "public void onCreate() {\n\t\tContextExecutor.setApplication(this);",
    "public void onCreate() {\n\t\tsExecutorService = createAdaptiveExecutorService(this);\n\t\tContextExecutor.setApplication(this);",
    "adaptive worker-pool initialization",
)
adaptive_executor = """\tprivate static ExecutorService createAdaptiveExecutorService(Context context) {
\t\tandroid.app.ActivityManager manager =
\t\t\t\t(android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
\t\tandroid.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
\t\tif (manager != null) manager.getMemoryInfo(memory);
\t\tint totalRamMb = memory.totalMem > 0L ? (int) (memory.totalMem / (1024L * 1024L)) : 4096;
\t\tboolean lowRam = manager != null && manager.isLowRamDevice();
\t\tint cores = Math.max(1, java.lang.Runtime.getRuntime().availableProcessors());
\t\tint workers;
\t\tif (lowRam || totalRamMb < 2560) workers = Math.min(2, cores);
\t\telse if (totalRamMb < 6144) workers = Math.min(3, cores);
\t\telse workers = Math.min(4, cores);
\t\treturn new ThreadPoolExecutor(workers, workers, 500, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
\t}

\t@Override
\tpublic void onCreate() {"""
application = replace_once(
    application,
    "\t@Override\n\tpublic void onCreate() {",
    adaptive_executor,
    "adaptive worker-pool factory",
)
if "createAdaptiveExecutorService(this)" not in application:
    raise SystemExit("Adaptive worker pool is not initialized before Amethyst startup.")
application_path.write_text(application)


# Amethyst's default touch controls are packaged in assets/default.json but its
# default CTRLDEF_FILE points to an external-files path. Ensure it exists exactly
# when the game Activity loads controls, before LayoutConverter opens the file.
main_activity = main_activity_path.read_text()
control_loader = "    private void loadControls() {\n        try {"
control_loader_with_bootstrap = (
    "    private void ensureDefaultControlMap() throws java.io.IOException {\n"
    "        java.io.File target = new java.io.File(Tools.CTRLDEF_FILE);\n"
    "        if (target.isFile() && target.length() > 0L) return;\n"
    "        java.io.File parent = target.getParentFile();\n"
    "        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {\n"
    "            throw new java.io.IOException(\"Could not create Amethyst controlmap directory: \" + parent);\n"
    "        }\n"
    "        java.io.File temporary = new java.io.File(parent, \"default.json.tmp\");\n"
    "        try (java.io.InputStream input = getAssets().open(\"default.json\");\n"
    "             java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {\n"
    "            byte[] buffer = new byte[8192];\n"
    "            int count;\n"
    "            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);\n"
    "            output.getFD().sync();\n"
    "        }\n"
    "        if (!temporary.isFile() || temporary.length() == 0L) {\n"
    "            throw new java.io.IOException(\"Amethyst bundled default controls are empty.\");\n"
    "        }\n"
    "        if (target.exists() && !target.delete()) {\n"
    "            temporary.delete();\n"
    "            throw new java.io.IOException(\"Cannot replace empty default control layout.\");\n"
    "        }\n"
    "        if (!temporary.renameTo(target)) {\n"
    "            temporary.delete();\n"
    "            throw new java.io.IOException(\"Cannot install default control layout.\");\n"
    "        }\n"
    "    }\n\n"
    "    private void loadControls() {\n        try {\n            ensureDefaultControlMap();"
)
main_activity = replace_once(
    main_activity,
    control_loader,
    control_loader_with_bootstrap,
    "Amethyst default control map bootstrap",
)
main_activity_path.write_text(main_activity)

# Creating a Vanilla/OptiFine/Forge/Fabric/etc. launcher profile is not an
# entitlement check. Keep Microsoft ownership checks at game launch/server
# authentication, but do not block local profile setup for offline accounts.
profile_type = profile_type_path.read_text()
old_gate = """    private void tryInstall(Class<? extends Fragment> fragmentClass, String tag){
        if(!hasOnlineProfile()){
            hasNoOnlineProfileDialog(requireActivity());
        } else {
            Tools.swapFragment(requireActivity(), fragmentClass, tag, null);
        }
    }"""
new_gate = """    private void tryInstall(Class<? extends Fragment> fragmentClass, String tag){
        // Profile creation and loader installation do not grant a Minecraft
        // license. Offline users may prepare profiles; authenticated online
        // services and multiplayer still enforce their normal account checks.
        Tools.swapFragment(requireActivity(), fragmentClass, tag, null);
    }"""
profile_type = replace_once(
    profile_type,
    old_gate,
    new_gate,
    "offline-compatible profile creation gate",
)
profile_type_path.write_text(profile_type)

# Fail the build if the offline profile-creation route accidentally regresses
# to Amethyst's generic Microsoft-ownership gate. This gate is not needed to
# create a local profile or install a local loader profile.
try_install_match = re.search(
    r"private void tryInstall\([^)]*\)\s*\{(.*?)\n    \}",
    profile_type,
    flags=re.DOTALL,
)
if try_install_match is None:
    raise SystemExit("Could not verify ProfileTypeSelectFragment.tryInstall().")
if "hasOnlineProfile()" in try_install_match.group(1) or "hasNoOnlineProfileDialog" in try_install_match.group(1):
    raise SystemExit("Offline profile setup is still blocked by the Microsoft-account gate.")

launcher_source = launcher_activity_path.read_text()
original_resume = """    protected void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        mInstallTracker.attach();
    }"""
auto_launch_resume = """    protected void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        mInstallTracker.attach();

        // TSI dashboard actions reuse Amethyst's original screens.
        // Account management opens the original Microsoft/local account selector.
        boolean openModloaderManager = getIntent().getBooleanExtra("tsi_open_modloader_manager", false);
        boolean openEngineSettings = getIntent().getBooleanExtra("tsi_open_engine_settings", false);
        boolean openAccountManager = getIntent().getBooleanExtra("tsi_open_account_manager", false);
        if (openModloaderManager || openEngineSettings || openAccountManager) {
            getIntent().removeExtra("tsi_open_modloader_manager");
            getIntent().removeExtra("tsi_open_engine_settings");
            getIntent().removeExtra("tsi_open_account_manager");
            getWindow().getDecorView().post(() -> {
                if (openModloaderManager) {
                    Tools.swapFragment(this,
                            net.kdt.pojavlaunch.fragments.ProfileTypeSelectFragment.class,
                            net.kdt.pojavlaunch.fragments.ProfileTypeSelectFragment.TAG, null);
                } else if (openEngineSettings) {
                    Tools.swapFragment(this,
                            net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment.class,
                            SETTING_FRAGMENT_TAG, null);
                } else {
                    Tools.swapFragment(this,
                            net.kdt.pojavlaunch.fragments.SelectAuthFragment.class,
                            net.kdt.pojavlaunch.fragments.SelectAuthFragment.TAG, null);
                }
            });
        }

        // TSI launches through Amethyst's normal launcher pipeline. This
        // preserves profile validation, downloader setup, memory warnings,
        // per-version natives, LWJGL selection and crash mitigations.
        if (getIntent().getBooleanExtra("tsi_auto_launch", false)) {
            getIntent().removeExtra("tsi_auto_launch");
            getWindow().getDecorView().post(() -> {
                // MainMenuFragment loads launcher_profiles.json from its
                // lifecycle callback. Reload synchronously as a safeguard so
                // the launch listener cannot race that fragment callback.
                try {
                    LauncherProfiles.load();
                } catch (RuntimeException error) {
                    android.util.Log.e("TSI Launcher", "Could not load the selected game profile.", error);
                }
                ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
            });
        }
    }"""
launcher_source = replace_once(
    launcher_source,
    original_resume,
    auto_launch_resume,
    "Amethyst normal game-launch handoff",
)
launcher_activity_path.write_text(launcher_source)

manifest = manifest_path.read_text()
manifest = replace_once(
    manifest,
    '    <uses-permission android:name="android.permission.INTERNET" />',
    '    <uses-permission android:name="android.permission.INTERNET" />\n'
    '    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />',
    "optional all-files access permission",
)
# Keep Amethyst's original TestStorageActivity as the Android app entry point.
# Its normal flow opens LauncherActivity and retains the original Pojav UI.
# The TSI Java dashboard is a separate internal screen opened from that UI.
launcher_activity = (
    '        <activity\n'
    '            android:name="com.tsi.launcher.MainActivity"\n'
    '            android:exported="false"\n'
    '            android:label="TSI Java Edition Dashboard" />\n'
)
marker = '        <activity\n            android:name=".MissingStorageActivity"'
manifest = replace_once(
    manifest,
    marker,
    launcher_activity + marker,
    "internal TSI Java dashboard activity declaration",
)
manifest_path.write_text(manifest)

# Local/offline accounts are valid for local gameplay and browsing/importing
# modpacks. Microsoft ownership is only required by online entitlement services.
main_menu = main_menu_path.read_text()
old_modpack_gate = """        if (hasOnlineProfile()) {
            mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
            mInstallJarButton.setOnLongClickListener(v -> {
                runInstallerWithConfirmation(true);
                return true;
            });
        } else mInstallJarButton.setOnClickListener(v -> hasNoOnlineProfileDialog(requireActivity()));"""
new_modpack_gate = """        mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
        mInstallJarButton.setOnLongClickListener(v -> {
            runInstallerWithConfirmation(true);
            return true;
        });"""
main_menu = replace_once(
    main_menu,
    old_modpack_gate,
    new_modpack_gate,
    "offline-compatible modpack installer entry",
)
main_menu_path.write_text(main_menu)

if "hasOnlineProfile()" in new_modpack_gate or "hasNoOnlineProfileDialog" in new_modpack_gate:
    raise SystemExit("Modpack installer still has an online-account gate.")

# Opening a local game folder is a file-manager operation, not an entitlement
# check. Keep the Demo-specific warning but allow normal offline accounts.
main_menu = main_menu_path.read_text()
old_directory_gate = """        mOpenDirectoryButton.setOnClickListener((v)-> {
            if (Tools.isDemoProfile(v.getContext())){ // Say a different message when on demo profile since they might see the hidden demo folder
                hasNoOnlineProfileDialog(getActivity(), getString(R.string.demo_unsupported), getString(R.string.change_account));
            } else if (!hasOnlineProfile()) { // Otherwise display the generic pop-up to log in
                hasNoOnlineProfileDialog(requireActivity());
            } else openPath(v.getContext(), getCurrentProfileDirectory(), false);

        });"""
new_directory_gate = """        mOpenDirectoryButton.setOnClickListener((v)-> openPath(v.getContext(), getCurrentProfileDirectory(), false));"""
main_menu = replace_once(
    main_menu,
    old_directory_gate,
    new_directory_gate,
    "offline-compatible game-directory access",
)
# The main-menu account gates are no longer used for local file/mod operations.
main_menu = main_menu.replace("import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;\\n", "")
main_menu_path.write_text(main_menu)

if "hasOnlineProfile()" in main_menu or "hasNoOnlineProfileDialog(requireActivity())" in main_menu:
    raise SystemExit("Main menu still gates local file/mod operations on Microsoft ownership.")

main_menu = main_menu_path.read_text()
main_menu = replace_once(
    main_menu,
    '        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);',
    '        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);\n'
    '        Button mTsiJavaDashboardButton = view.findViewById(R.id.tsi_java_dashboard_button);',
    "TSI Java dashboard menu button binding",
)
main_menu = replace_once(
    main_menu,
    '        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);',
    '        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);\n'
    '        mTsiJavaDashboardButton.setOnClickListener(v ->\n'
    '                startActivity(new Intent(requireContext(), com.tsi.launcher.MainActivity.class)));',
    "TSI Java dashboard menu button action",
)
main_menu_path.write_text(main_menu)

menu_button = (
    '            <com.kdt.mcgui.LauncherMenuButton\n'
    '                android:id="@+id/tsi_java_dashboard_button"\n'
    '                style="@style/LauncherMenuButton.Universal"\n'
    '                android:layout_width="match_parent"\n'
    '                android:drawableStart="@drawable/ic_menu_settings"\n'
    '                android:text="TSI Java Edition Dashboard"\n'
    '                app:layout_constraintTop_toBottomOf="@id/open_files_button" />'
)
for layout_path in (main_menu_layout_path, main_menu_land_layout_path):
    layout = layout_path.read_text()
    anchor = 'app:layout_constraintTop_toBottomOf="@id/share_logs_button" />'
    if layout.count(anchor) != 1:
        raise SystemExit(
            f"Expected one share-logs button anchor in {layout_path.name}; found {layout.count(anchor)}"
        )
    layout = layout.replace(anchor, anchor + "\n\n" + menu_button, 1)
    layout_path.write_text(layout)
PY


python3 - "$AMETHYST_ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label}; found {count}")
    return text.replace(old, new, 1)

# Select a local profile at application startup, before Amethyst creates its account spinner.
application_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/PojavApplication.java"
application = application_path.read_text()
application = replace_once(
    application,
    "import net.kdt.pojavlaunch.utils.FileUtils;",
    "import net.kdt.pojavlaunch.utils.FileUtils;\nimport com.tsi.launcher.runtime.AmethystEngineBridge;",
    "offline bootstrap import",
)
application = replace_once(
    application,
    "Tools.DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture();",
    "Tools.DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture();\n"
    "            try {\n"
    "                AmethystEngineBridge.ensureOfflineAccountSelected(this);\n"
    "            } catch (Throwable accountError) {\n"
    "                Log.e(\"TSI Launcher\", \"Could not initialise the offline profile.\", accountError);\n"
    "            }",
    "startup offline profile selection",
)
application_path.write_text(application)

# Keep Amethyst's original account selector and authentication screens intact.
# This preserves Microsoft OAuth sign-in and local/offline profiles. Do not filter
# account files or replace SelectAuthFragment with local login.
# TSI creates a local profile only when no valid account is already selected.

# Preserve the upstream offline path for game versions whose required files already exist.
downloader_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/MinecraftDownloader.java"
downloader = downloader_path.read_text()
if "if(isLocalProfile || !isOnline)" not in downloader:
    raise SystemExit("Existing offline installed-version launch path is missing.")
PY


python3 - "$AMETHYST_ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label}; found {count}")
    return text.replace(old, new, 1)

# An offline identity is not a reason to skip downloads. When the device has
# internet, local accounts must still be able to install Minecraft and JRE files.
downloader_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/MinecraftDownloader.java"
downloader = downloader_path.read_text()
downloader = replace_once(
    downloader,
    "if(isLocalProfile || !isOnline) {",
    "if(!isOnline) {",
    "network-based offline-install decision",
)

# Without a connection, fail clearly when the selected modloader's vanilla
# source version or client JAR has not been installed yet.
old_local_check = """                        File vanillaJsonFile = new File(Tools.DIR_HOME_VERSION + "/" + providedJson.inheritsFrom + "/" + providedJson.inheritsFrom + ".json");
                        versionMessage = providedJson.inheritsFrom != null ? providedJson.inheritsFrom : versionMessage;

                        // Ensure they're both not some 0 byte corrupted json
                        if (providedJsonFile.length() == 0 || vanillaJsonFile.exists() && vanillaJsonFile.length() == 0){
                            throw new RuntimeException("Minecraft "+versionMessage+ " is needed by " +realVersion); }

                        listener.onDownloadDone();"""
new_local_check = """                        File vanillaJsonFile = providedJson.inheritsFrom == null
                                ? providedJsonFile
                                : new File(Tools.DIR_HOME_VERSION + "/" + providedJson.inheritsFrom + "/" + providedJson.inheritsFrom + ".json");
                        versionMessage = providedJson.inheritsFrom != null ? providedJson.inheritsFrom : versionMessage;
                        File requiredClientJar = new File(vanillaJsonFile.getParentFile(),
                                vanillaJsonFile.getName().replace(".json", ".jar"));

                        // A modloader profile is not a substitute for its vanilla
                        // source version. Do not report success when the parent JAR is absent.
                        if (!providedJsonFile.isFile() || providedJsonFile.length() == 0 ||
                                !vanillaJsonFile.isFile() || vanillaJsonFile.length() == 0 ||
                                !requiredClientJar.isFile() || requiredClientJar.length() == 0) {
                            throw new IOException("Minecraft " + versionMessage +
                                    " is required by " + realVersion +
                                    " but is not installed. Connect to the internet and press Play to download it.");
                        }

                        listener.onDownloadDone();"""
downloader = replace_once(
    downloader,
    old_local_check,
    new_local_check,
    "offline inherited-version and client-JAR validation",
)
downloader_path.write_text(downloader)

# The launcher's in-memory version table can be stale for up to 24 hours.
# Resolve a missing inherited vanilla version from Mojang's official manifest
# on the downloader worker thread, not on the UI thread.
async_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncMinecraftDownloader.java"
async_source = async_path.read_text()
async_source = replace_once(
    async_source,
    "import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;",
    """import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;""",
    "Mojang version-manifest fallback imports",
)
method_anchor = "    public interface DoneListener{"
fallback_method = """    /**
     * Resolve an inherited vanilla version even when the startup version-list
     * cache is stale. Call only from the background downloader, never the UI.
     */
    public static synchronized JMinecraftVersionList.Version refreshListedVersion(String versionId) throws IOException {
        String manifestJson = DownloadUtils.downloadString(LauncherPreferences.PREF_VERSION_REPOS);
        JMinecraftVersionList refreshed = Tools.GLOBAL_GSON.fromJson(manifestJson, JMinecraftVersionList.class);
        if (refreshed == null || refreshed.versions == null) {
            throw new IOException("Mojang returned an invalid version manifest.");
        }

        File cacheFile = new File(Tools.DIR_CACHE, "version_list.json");
        File parent = cacheFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create the Minecraft version-list cache folder.");
        }
        try (FileOutputStream output = new FileOutputStream(cacheFile)) {
            output.write(manifestJson.getBytes("UTF-8"));
            output.flush();
            output.getFD().sync();
        }
        ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, refreshed);

        for (JMinecraftVersionList.Version version : refreshed.versions) {
            if (versionId.equals(version.id)) return version;
        }
        return null;
    }

"""
async_source = replace_once(
    async_source,
    method_anchor,
    fallback_method + method_anchor,
    "background source-version manifest resolver",
)
async_path.write_text(async_source)

# If a Fabric/Forge/other custom profile inherits a base version that is missing
# from the cached Mojang catalogue, fetch that base version's metadata descriptor.
downloader = downloader_path.read_text()
old_inheritance = """        if(Tools.isValidString(verInfo.inheritsFrom)) {
            JMinecraftVersionList.Version inheritedVersion = AsyncMinecraftDownloader.getListedVersion(verInfo.inheritsFrom);
            // Infinite inheritance !?! :noway:
            return downloadAndProcessMetadata(activity, inheritedVersion, verInfo.inheritsFrom);
        }"""
new_inheritance = """        if(Tools.isValidString(verInfo.inheritsFrom)) {
            JMinecraftVersionList.Version inheritedVersion = AsyncMinecraftDownloader.getListedVersion(verInfo.inheritsFrom);
            if (inheritedVersion == null && activity != null && Tools.isOnline(activity)) {
                inheritedVersion = AsyncMinecraftDownloader.refreshListedVersion(verInfo.inheritsFrom);
            }
            File inheritedJson = createGameJsonPath(verInfo.inheritsFrom);
            if (inheritedVersion == null && !inheritedJson.isFile()) {
                throw new IOException("Can't find the source version for " + versionName +
                        " (required Minecraft version=" + verInfo.inheritsFrom + "). Connect to the internet and retry.");
            }
            // Infinite inheritance !?! :noway:
            return downloadAndProcessMetadata(activity, inheritedVersion, verInfo.inheritsFrom);
        }"""
downloader = replace_once(
    downloader,
    old_inheritance,
    new_inheritance,
    "missing inherited vanilla version fallback",
)
downloader_path.write_text(downloader)

# Amethyst may have cached a runtime folder before TSI installed it. Refresh the
# runtime objects from each release file before looking for a compatible JRE.
multi_rt_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/MultiRTUtils.java"
multi_rt = multi_rt_path.read_text()
old_runtime_list = """        ArrayList<Runtime> runtimes = new ArrayList<>();
        File[] files = RUNTIME_FOLDER.listFiles();
        if(files != null) for(File f : files) {
            runtimes.add(read(f.getName()));
        }
        else throw new RuntimeException("The runtime directory does not exist");

        return runtimes;"""
new_runtime_list = """        ArrayList<Runtime> runtimes = new ArrayList<>();
        File[] files = RUNTIME_FOLDER.listFiles();
        if(files != null) {
            for(File f : files) {
                if (!f.isDirectory()) continue;
                // TSI can install a JRE after Amethyst first scanned this folder.
                // Drop the stale cached Runtime (including a cached Java 0 entry).
                sCache.remove(f.getName());
                Runtime runtime = read(f.getName());
                if (isRuntimeUsable(runtime)) {
                    runtimes.add(runtime);
                }
            }
        } else throw new RuntimeException("The runtime directory does not exist");

        return runtimes;"""
multi_rt = replace_once(
    multi_rt,
    old_runtime_list,
    new_runtime_list,
    "refresh installed Android JRE list",
)
runtime_helper_anchor = "    public static List<Runtime> getInstalledRuntimes() {"
runtime_helper = """    /**
     * Reject stale, incomplete or wrong-ABI runtime folders before they can be auto-picked.
     */
    public static boolean isRuntimeUsable(Runtime runtime) {
        if (runtime == null || runtime.name == null || runtime.versionString == null ||
                runtime.versionString.isEmpty() || runtime.javaVersion <= 0 || runtime.arch == null ||
                !runtime.name.matches("[A-Za-z0-9._+-]{1,80}")) return false;
        if (Architecture.archAsInt(runtime.arch) != getDeviceArchitecture()) return false;
        try {
            File runtimeHome = new File(RUNTIME_FOLDER, runtime.name).getCanonicalFile();
            File runtimeRoot = RUNTIME_FOLDER.getCanonicalFile();
            if (!runtimeHome.getPath().startsWith(runtimeRoot.getPath() + File.separator) ||
                    !new File(runtimeHome, "release").isFile()) return false;
            File[] jvmCandidates = new File[] {
                new File(runtimeHome, "lib/server/libjvm.so"),
                new File(runtimeHome, "lib/client/libjvm.so"),
                new File(runtimeHome, "lib/" + runtime.arch + "/server/libjvm.so"),
                new File(runtimeHome, "lib/" + runtime.arch + "/client/libjvm.so"),
                new File(runtimeHome, "lib/amd64/server/libjvm.so"),
                new File(runtimeHome, "lib/amd64/client/libjvm.so"),
                new File(runtimeHome, "lib/aarch64/server/libjvm.so"),
                new File(runtimeHome, "lib/aarch64/client/libjvm.so"),
                new File(runtimeHome, "lib/arm/server/libjvm.so"),
                new File(runtimeHome, "lib/arm/client/libjvm.so"),
                new File(runtimeHome, "lib/x86_64/server/libjvm.so"),
                new File(runtimeHome, "lib/x86/server/libjvm.so")
            };
            for (File candidate : jvmCandidates) {
                if (candidate.isFile()) return true;
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }
"""
multi_rt = replace_once(
    multi_rt,
    runtime_helper_anchor,
    runtime_helper + runtime_helper_anchor,
    "MultiRT runtime integrity and ABI validator",
)

multi_rt_path.write_text(multi_rt)

# Avoid a null dereference when no JRE can be selected, and report the missing
# Java major instead of the vague autopick message.
tools_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java"
tools = tools_path.read_text()
old_picker = """        String runtime = getSelectedRuntime(minecraftProfile);
        String profileRuntime = getRuntimeName(minecraftProfile.javaDir);
        Runtime pickedRuntime = MultiRTUtils.read(runtime);
        if(runtime == null || pickedRuntime.javaVersion == 0 || pickedRuntime.javaVersion < targetJavaVersion) {
            String preferredRuntime = MultiRTUtils.getNearestJreName(targetJavaVersion);
            if(preferredRuntime == null) throw new RuntimeException("Failed to autopick runtime!");
            if(profileRuntime != null) minecraftProfile.javaDir = Tools.LAUNCHERPROFILES_RTPREFIX+preferredRuntime;
            runtime = preferredRuntime;
        }"""
new_picker = """        if (targetJavaVersion < 8 || targetJavaVersion > 99) {
            throw new IllegalArgumentException("Invalid Minecraft Java requirement: " + targetJavaVersion);
        }

        Runtime selectedRuntime = null;
        String profileRuntime = getRuntimeName(minecraftProfile.javaDir);

        // Prefer the profile-specific runtime when it is valid and new enough.
        if (profileRuntime != null && !profileRuntime.isEmpty()) {
            Runtime candidate = MultiRTUtils.forceReread(profileRuntime);
            if (MultiRTUtils.isRuntimeUsable(candidate) &&
                    candidate.javaVersion >= targetJavaVersion) {
                selectedRuntime = candidate;
            }
        }

        // Next try the launcher default. A blank or stale default must not stop auto-picking.
        if (selectedRuntime == null) {
            String defaultRuntime = LauncherPreferences.PREF_DEFAULT_RUNTIME;
            if (defaultRuntime != null && !defaultRuntime.isEmpty()) {
                Runtime candidate = MultiRTUtils.forceReread(defaultRuntime);
                if (MultiRTUtils.isRuntimeUsable(candidate) &&
                        candidate.javaVersion >= targetJavaVersion) {
                    selectedRuntime = candidate;
                }
            }
        }

        // Finally scan every valid installed MultiRT entry and select the lowest compatible major.
        if (selectedRuntime == null) {
            List<Runtime> installedRuntimes = MultiRTUtils.getInstalledRuntimes();
            for (Runtime candidate : installedRuntimes) {
                if (!MultiRTUtils.isRuntimeUsable(candidate) ||
                        candidate.javaVersion < targetJavaVersion) {
                    continue;
                }
                if (selectedRuntime == null || candidate.javaVersion < selectedRuntime.javaVersion) {
                    selectedRuntime = candidate;
                }
            }
        }

        if (selectedRuntime == null) {
            StringBuilder detected = new StringBuilder();
            for (Runtime candidate : MultiRTUtils.getInstalledRuntimes()) {
                if (candidate == null || candidate.versionString == null || candidate.versionString.isEmpty()) continue;
                if (detected.length() > 0) detected.append(", ");
                detected.append(candidate.name).append(" (Java ").append(candidate.javaVersion).append(")");
            }
            String available = detected.length() == 0 ? "none detected" : detected.toString();
            throw new RuntimeException("No compatible Android Java runtime found for Minecraft Java " +
                    targetJavaVersion + ". Installed runtimes: " + available +
                    ". Install Java " + targetJavaVersion + " or newer in TSI VM Controller, then retry.");
        }

        // Save the runtime on this profile, including when it had no Java override before.
        minecraftProfile.javaDir = Tools.LAUNCHERPROFILES_RTPREFIX + selectedRuntime.name;
        LauncherProfiles.write();
        String runtime = selectedRuntime.name;"""
tools = replace_once(tools, old_picker, new_picker, "runtime auto-picker and stale-cache fix")
tools_path.write_text(tools)
if "No compatible Android Java runtime found for Minecraft Java " not in tools:
    raise SystemExit("Robust Java runtime auto-picker was not integrated.")
if "LauncherProfiles.write();" not in tools:
    raise SystemExit("Selected Java runtime is not persisted to the active launcher profile.")

# NewJREUtil can have no candidates on unsupported/offline devices. Check for
# that case before reading selectedRankedRuntime.value.
new_jre_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/NewJREUtil.java"
new_jre = new_jre_path.read_text()
old_selected_runtime = """        String profileRuntime = Tools.getSelectedRuntime(minecraftProfile);
        Runtime runtime = MultiRTUtils.read(profileRuntime);
        // Partly trust the user with his own selection, if the game can even try to run in this case
        if (runtime.javaVersion >= gameRequiredVersion) {"""
new_selected_runtime = """        String profileRuntime = Tools.getSelectedRuntime(minecraftProfile);
        Runtime runtime = profileRuntime == null || profileRuntime.isEmpty()
                ? null : MultiRTUtils.forceReread(profileRuntime);
        // Partly trust the user with his own selection, but never trust a stale or unreadable runtime entry.
        if (MultiRTUtils.isRuntimeUsable(runtime) &&
                runtime.javaVersion >= gameRequiredVersion) {"""
new_jre = replace_once(
    new_jre,
    old_selected_runtime,
    new_selected_runtime,
    "refresh selected runtime before Java requirement check",
)

old_null_order = """        // Check if the selected runtime actually exists in the APK, else download it
        // If it isn't InternalRuntime then it wasn't in the apk in the first place!
        if (selectedRankedRuntime.value instanceof InternalRuntime)"""
new_null_order = """        // No possible selections
        if(selectedRankedRuntime == null) {
            showRuntimeFail(activity, versionInfo);
            return false;
        }

        // Check if the selected runtime actually exists in the APK, else download it
        // If it isn't InternalRuntime then it wasn't in the apk in the first place!
        if (selectedRankedRuntime.value instanceof InternalRuntime)"""
new_jre = replace_once(new_jre, old_null_order, new_null_order, "runtime autopick null guard")
old_late_null = """        // No possible selections
        if(selectedRankedRuntime == null) {
            showRuntimeFail(activity, versionInfo);
            return false;
        }

        Object selected = selectedRankedRuntime.value;"""
new_late_null = """        Object selected = selectedRankedRuntime.value;"""
new_jre = replace_once(new_jre, old_late_null, new_late_null, "remove late runtime null check")
new_jre_path.write_text(new_jre)
PY


python3 - "$AMETHYST_ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label}; found {count}")
    return text.replace(old, new, 1)

# OptiFine installs the vanilla client before running its installer. Resolve
# the selected vanilla release from a refreshed Mojang manifest if the cached
# startup version list does not contain it.
optifine_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/modloaders/OptiFineDownloadTask.java"
optifine = optifine_path.read_text()
optifine = replace_once(
    optifine,
    """        JMinecraftVersionList.Version minecraftJsonVersion = AsyncMinecraftDownloader.getListedVersion(minecraftVersion);
        if(minecraftJsonVersion == null) return false;""",
    """        JMinecraftVersionList.Version minecraftJsonVersion = AsyncMinecraftDownloader.getListedVersion(minecraftVersion);
        if (minecraftJsonVersion == null) {
            try {
                minecraftJsonVersion = AsyncMinecraftDownloader.refreshListedVersion(minecraftVersion);
            } catch (IOException error) {
                mDownloaderThrowable = error;
                return false;
            }
        }
        if (minecraftJsonVersion == null) {
            mDownloaderThrowable = new IOException("Minecraft " + minecraftVersion +
                    " is not present in Mojang's official version manifest.");
            return false;
        }""",
    "OptiFine vanilla-version manifest fallback",
)
optifine_path.write_text(optifine)

# All installer JARs (Forge, NeoForge and OptiFine) need a JVM before they
# can create their Minecraft profile. Use the same internal/external Android
# JRE selection used by Minecraft game launches instead of failing when the
# currently selected runtime is too old or its cached entry is stale.
new_jre_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/NewJREUtil.java"
new_jre = new_jre_path.read_text()
method_anchor = "    private static void showRuntimeFail(Activity activity, JMinecraftVersionList.Version verInfo) {"
installer_runtime_helper = """    /**
     * Ensure that a compatible Android JRE is installed and selected before
     * running a third-party Minecraft installer JAR (Forge/NeoForge/OptiFine).
     */
    public static Runtime ensureRuntimeForInstaller(Activity activity, int targetJavaVersion) {
        if (targetJavaVersion < 8 || targetJavaVersion > 99) {
            throw new IllegalArgumentException("Invalid installer Java requirement: " + targetJavaVersion);
        }

        JMinecraftVersionList.Version installerVersion = new JMinecraftVersionList.Version();
        installerVersion.javaVersion = new JMinecraftVersionList.JavaVersionInfo();
        installerVersion.javaVersion.component = "jre";
        installerVersion.javaVersion.majorVersion = targetJavaVersion;

        if (!installNewJreIfNeeded(activity, installerVersion)) {
            throw new RuntimeException("Could not install or select an Android Java runtime for Java " +
                    targetJavaVersion + ".");
        }

        LauncherProfiles.load();
        MinecraftProfile currentProfile = LauncherProfiles.getCurrentProfile();
        if (currentProfile == null) {
            throw new RuntimeException("The launcher profile is unavailable after selecting the Android JRE.");
        }

        String selectedName = Tools.getSelectedRuntime(currentProfile);
        Runtime selected = selectedName == null ? null : MultiRTUtils.forceReread(selectedName);
        if (!MultiRTUtils.isRuntimeUsable(selected) ||
                selected.javaVersion < targetJavaVersion) {
            throw new RuntimeException("No compatible Android JRE is installed for installer Java " +
                    targetJavaVersion + ".");
        }
        return selected;
    }

"""
new_jre = replace_once(
    new_jre,
    method_anchor,
    installer_runtime_helper + method_anchor,
    "shared installer runtime selector",
)
new_jre_path.write_text(new_jre)

java_gui_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java"
java_gui = java_gui_path.read_text()
old_selector = """        String nearestRuntime = MultiRTUtils.getNearestJreName(javaVersion);
        if(nearestRuntime == null) {
            finalErrorDialog(getString(R.string.multirt_nocompatiblert, javaVersion));
            return null;
        }
        Runtime selectedRuntime = MultiRTUtils.forceReread(nearestRuntime);
        int selectedJavaVersion = Math.max(javaVersion, selectedRuntime.javaVersion);
        return selectedRuntime;"""
new_selector = """        try {
            return NewJREUtil.ensureRuntimeForInstaller(this, javaVersion);
        } catch (RuntimeException error) {
            finalErrorDialog("Could not prepare Java " + javaVersion +
                    " for this modloader installer: " +
                    (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            return null;
        }"""
java_gui = replace_once(
    java_gui,
    old_selector,
    new_selector,
    "Forge/NeoForge/OptiFine installer JRE selection",
)
java_gui_path.write_text(java_gui)

# Keep a source-level integration assertion for every loader family. Their
# installer profiles all flow through the common Amethyst MinecraftDownloader.
downloader_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/MinecraftDownloader.java"
downloader = downloader_path.read_text()
if "if(!isOnline) {" not in downloader:
    raise SystemExit("Minecraft downloader is still incorrectly gated by offline account type.")
if "refreshListedVersion(verInfo.inheritsFrom)" not in downloader:
    raise SystemExit("Common modloader parent-version resolver is missing.")
if "requiredClientJar.isFile()" not in downloader:
    raise SystemExit("Common modloader parent client-JAR validation is missing.")

# Fabric and Quilt both create profiles from this JSON endpoint; the common
# parent resolver must remain in the game downloader, not duplicated per UI.
fabric_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/modloaders/FabriclikeDownloadTask.java"
fabric = fabric_path.read_text()
if "createJsonDownloadUrl(mGameVersion, mLoaderVersion)" not in fabric:
    raise SystemExit("Fabric/Quilt profile download path changed; inspect before applying common inheritance fix.")

# Forge and NeoForge use JavaGUILauncherActivity to run their installer JARs.
for path in (
    root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/fragments/ForgeInstallFragment.java",
    root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/fragments/NeoForgeInstallFragment.java",
):
    source = path.read_text()
    if "JavaGUILauncherActivity.class" not in source:
        raise SystemExit(f"Expected installer launch integration not found in {path.name}.")

if "ensureRuntimeForInstaller(this, javaVersion)" not in java_gui:
    raise SystemExit("Shared Java runtime selection was not connected to installer execution.")
PY

# Defer account/settings fragment navigation if Android has already saved the Activity state.
# The account spinner can emit SELECT_AUTH_METHOD after onSaveInstanceState(); an
# immediate FragmentTransaction.commit() then throws "Can not perform this action
# after onSaveInstanceState". Wait for the next RESUME and retry on the UI thread.
python3 - "$AMETHYST_ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
tools_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java"
source = tools_path.read_text()

old = """    /** Swap the main fragment with another */
    public static void swapFragment(FragmentActivity fragmentActivity , Class<? extends Fragment> fragmentClass,
                                    @Nullable String fragmentTag, @Nullable Bundle bundle) {
        // When people tab out, it might happen
        //TODO handle custom animations
        fragmentActivity.getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .addToBackStack(fragmentClass.getName())
                .replace(R.id.container_fragment, fragmentClass, bundle, fragmentTag).commit();
    }"""

new = """    /** Swap the main fragment with another, deferring safely across saved Activity state. */
    public static void swapFragment(FragmentActivity fragmentActivity , Class<? extends Fragment> fragmentClass,
                                    @Nullable String fragmentTag, @Nullable Bundle bundle) {
        if (fragmentActivity == null || fragmentActivity.isFinishing() || fragmentActivity.isDestroyed()) {
            return;
        }

        // Fragment transactions must run on the main thread.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            fragmentActivity.runOnUiThread(() -> swapFragment(fragmentActivity, fragmentClass, fragmentTag, bundle));
            return;
        }

        androidx.fragment.app.FragmentManager fragmentManager =
                fragmentActivity.getSupportFragmentManager();

        // Account spinner callbacks may arrive after onSaveInstanceState().
        // Do not use commitAllowingStateLoss(): keep the requested navigation and
        // replay it only after FragmentManager has resumed and cleared saved state.
        if (fragmentManager.isStateSaved()) {
            androidx.lifecycle.LifecycleEventObserver retryOnResume =
                    new androidx.lifecycle.LifecycleEventObserver() {
                @Override
                public void onStateChanged(androidx.lifecycle.LifecycleOwner owner,
                                           androidx.lifecycle.Lifecycle.Event event) {
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                        owner.getLifecycle().removeObserver(this);
                        return;
                    }
                    if (event != androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        return;
                    }

                    owner.getLifecycle().removeObserver(this);
                    fragmentActivity.getWindow().getDecorView().post(() -> {
                        if (!fragmentActivity.isFinishing() && !fragmentActivity.isDestroyed()) {
                            swapFragment(fragmentActivity, fragmentClass, fragmentTag, bundle);
                        }
                    });
                }
            };
            fragmentActivity.getLifecycle().addObserver(retryOnResume);
            return;
        }

        //TODO handle custom animations
        fragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .addToBackStack(fragmentClass.getName())
                .replace(R.id.container_fragment, fragmentClass, bundle, fragmentTag).commit();
    }"""

count = source.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one original Tools.swapFragment implementation; found {count}")
if "retryOnResume" in source:
    raise SystemExit("Lifecycle-safe swapFragment patch appears to be applied already.")
source = source.replace(old, new, 1)
tools_path.write_text(source)

# Build-time assertion: the unsafe unconditional commit must not return.
if "if (fragmentManager.isStateSaved())" not in source or "retryOnResume" not in source:
    raise SystemExit("Lifecycle-safe fragment navigation was not integrated.")
PY


# Fix format-string diagnostics in the pinned Amethyst JNI sources. These are
# upstream files in the build checkout, so patch them reproducibly rather than
# editing an ephemeral workspace by hand.
python3 - "$AMETHYST_ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
patches = [
    (
        "app_pojavlauncher/src/main/jni/ctxbridges/gl_bridge.c",
        'printf("EGLBridge: bind failed: %p\\n", eglGetError_p());',
        'printf("EGLBridge: bind failed: %d\\n", eglGetError_p());',
        "EGL error format",
    ),
    (
        "app_pojavlauncher/src/main/jni/ctxbridges/swap_interval_no_egl.c",
        'LOGW("ANativeWindow version does not match. Expected %i, got %i",\n'
        '                            sizeof(struct ANativeWindow_real), nativeWindowReal->common.version);',
        'LOGW("ANativeWindow version does not match. Expected %zu, got %i",\n'
        '                            sizeof(struct ANativeWindow_real), nativeWindowReal->common.version);',
        "ANativeWindow size format",
    ),
    (
        "app_pojavlauncher/src/main/jni/input_bridge_v3.c",
        '"nativeSetWindowAttrib failed: %i", return;',
        '"nativeSetWindowAttrib failed", return;',
        "JNI attach diagnostic format",
    ),
    (
        "app_pojavlauncher/src/main/jni/jre_launcher.c",
        '"Failed to set signal hander for signal %i: %s", i, strerror(errno));',
        '"Failed to set signal hander for signal %zu: %s", i, strerror(errno));',
        "signal index format",
    ),
]

for relative_path, old, new, label in patches:
    path = root / relative_path
    source = path.read_text()
    count = source.count(old)
    if count == 0 and new in source:
        continue
    if count != 1:
        raise SystemExit(f"Expected one {label} in {relative_path}; found {count}")
    path.write_text(source.replace(old, new, 1))

print("Applied deterministic JNI format-string fixes.")
PY

# The original storage-check launcher activity previously had no content view.
# On slower/older Android devices this exposed an empty window while storage and
# engine setup ran. Give it a real startup screen and keep actionable diagnostics
# visible if setup fails instead of leaving the user on a blank surface.
python3 - "$AMETHYST_ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
activity_path = root / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/TestStorageActivity.java"
layout_path = root / "app_pojavlauncher/src/main/res/layout/activity_tsi_startup.xml"

activity = activity_path.read_text()
old_imports = """import android.widget.Toast;
"""
new_imports = """import android.widget.Toast;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.util.Log;
"""
if old_imports in activity:
    if activity.count(old_imports) != 1:
        raise SystemExit("Unexpected TestStorageActivity import layout.")
    activity = activity.replace(old_imports, new_imports, 1)
elif "import android.widget.TextView;" not in activity:
    raise SystemExit("Could not add TSI startup view imports.")

activity = activity.replace(
    "public class TestStorageActivity extends Activity {\n    private final int REQUEST_STORAGE_REQUEST_CODE = 1;",
    """public class TestStorageActivity extends Activity {
    private final int REQUEST_STORAGE_REQUEST_CODE = 1;
    private TextView startupStatus;
    private TextView startupError;
    private ProgressBar startupProgress;
    private Button startupRetry;
    private boolean launchStarted;
""",
    1,
) if "private TextView startupStatus;" not in activity else activity

old_on_create = """    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29 && !isStorageAllowed(this)) requestStoragePermission();
        else exit();
    }"""
new_on_create = """    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tsi_startup);
        startupStatus = findViewById(R.id.tsi_startup_status);
        startupError = findViewById(R.id.tsi_startup_error);
        startupProgress = findViewById(R.id.tsi_startup_progress);
        startupRetry = findViewById(R.id.tsi_startup_retry);
        startupRetry.setOnClickListener(v -> exit());

        if(Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29 && !isStorageAllowed(this)) {
            startupStatus.setText("Storage permission is required to prepare Minecraft files.");
            requestStoragePermission();
        } else {
            exit();
        }
    }"""
if old_on_create in activity:
    activity = activity.replace(old_on_create, new_on_create, 1)
elif "setContentView(R.layout.activity_tsi_startup)" not in activity:
    raise SystemExit("Could not patch TestStorageActivity.onCreate().")

old_exit = """    private void exit() {
        if(!Tools.checkStorageRoot(this)) {
            startActivity(new Intent(this, MissingStorageActivity.class));
            return;
        }
        //Initialize constants (implicitly) and preferences after we confirm that we have storage.
        LauncherPreferences.loadPreferences(this);
        AsyncAssetManager.unpackComponents(this);
        AsyncAssetManager.unpackSingleFiles(this);

        Intent intent =  new Intent(this, LauncherActivity.class);
        startActivity(intent);
        finish();
    }"""
new_exit = """    private void exit() {
        if (launchStarted) return;
        if(!Tools.checkStorageRoot(this)) {
            startupStatus.setText("Device storage is unavailable. Check storage access and try again.");
            startActivity(new Intent(this, MissingStorageActivity.class));
            finish();
            return;
        }

        launchStarted = true;
        startupRetry.setVisibility(View.GONE);
        startupError.setVisibility(View.GONE);
        startupProgress.setVisibility(View.VISIBLE);
        startupStatus.setText("Preparing TSI Launcher and Minecraft components…");
        try {
            // Initialize launcher paths/preferences only after storage is confirmed.
            LauncherPreferences.loadPreferences(this);
            startupStatus.setText("Preparing Java engine components…");
            AsyncAssetManager.unpackComponents(this);
            AsyncAssetManager.unpackSingleFiles(this);

            Intent intent = new Intent(this, LauncherActivity.class);
            startActivity(intent);
            finish();
        } catch (Throwable error) {
            launchStarted = false;
            Log.e("TSIStartup", "Launcher startup failed before the home screen opened.", error);
            startupProgress.setVisibility(View.GONE);
            startupStatus.setText("TSI Launcher could not finish opening.");
            startupError.setText(Log.getStackTraceString(error));
            startupError.setVisibility(View.VISIBLE);
            startupRetry.setVisibility(View.VISIBLE);
        }
    }"""
if old_exit in activity:
    activity = activity.replace(old_exit, new_exit, 1)
elif "Launcher startup failed before the home screen opened." not in activity:
    raise SystemExit("Could not patch TestStorageActivity.exit().")

activity_path.write_text(activity)

layout = """<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true"
    android:background="#25282D">
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:orientation="vertical"
        android:padding="28dp">
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="TSI LAUNCHER"
            android:textColor="#FFFFFF"
            android:textSize="26sp"
            android:textStyle="bold"
            android:letterSpacing="0.08" />
        <ProgressBar
            android:id="@+id/tsi_startup_progress"
            android:layout_width="44dp"
            android:layout_height="44dp"
            android:layout_marginTop="26dp"
            android:indeterminate="true"
            android:indeterminateTint="#65C7D8" />
        <TextView
            android:id="@+id/tsi_startup_status"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:gravity="center"
            android:text="Starting TSI Launcher…"
            android:textColor="#FFFFFF"
            android:textSize="16sp" />
        <TextView
            android:id="@+id/tsi_startup_error"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:background="#333941"
            android:padding="12dp"
            android:textColor="#FFD6D6"
            android:textSize="12sp"
            android:visibility="gone" />
        <Button
            android:id="@+id/tsi_startup_retry"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:text="RETRY"
            android:visibility="gone" />
    </LinearLayout>
</ScrollView>
"""
layout_path.write_text(layout)

# Verify that the activity can no longer be built without an initial UI and
# that its failure path exposes a stack trace and a retry action.
if "setContentView(R.layout.activity_tsi_startup)" not in activity:
    raise SystemExit("TSI startup screen is not attached to TestStorageActivity.")
if "Log.getStackTraceString(error)" not in activity or "startupRetry.setVisibility(View.VISIBLE)" not in activity:
    raise SystemExit("TSI startup failure diagnostics/retry are missing.")
if not layout_path.is_file() or "tsi_startup_status" not in layout_path.read_text():
    raise SystemExit("TSI startup layout was not written.")
print("Installed visible TSI startup screen and actionable startup error reporting.")
PY

mkdir -p "$APP_ROOT/src/main/java/com/tsi/launcher"
cp -a "$TSI_JAVA_ROOT/." "$APP_ROOT/src/main/java/com/tsi/launcher/"

mkdir -p "$APP_ROOT/src/main/assets/licenses"
cp "$AMETHYST_ROOT/LICENSE" "$APP_ROOT/src/main/assets/licenses/AMETHYST-LGPL-3.0.txt"

cat > "$APP_ROOT/src/main/assets/TSI_ENGINE_NOTICES.txt" <<'EOF'
TSI Launcher Java Edition engine integration
Engine: AngelAuraMC Amethyst-Android
Source: https://github.com/AngelAuraMC/Amethyst-Android
Pinned upstream revision: 28d6e12d68121b7dd75f2e841058a6b7cdfe4a29
License: GNU Lesser General Public License v3.0 (LGPL-3.0)
The Amethyst source tree, JNI code, renderer/input bridge, and third-party component notices are retained by the engine integration build.
See licenses/AMETHYST-LGPL-3.0.txt and the upstream source repository for license and dependency notices.
EOF

echo "TSI Compose launcher injected into Amethyst Android engine project."
