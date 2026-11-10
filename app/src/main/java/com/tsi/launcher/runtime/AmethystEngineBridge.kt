package com.tsi.launcher.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import com.tsi.launcher.storage.AmethystStoragePaths
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Adapter from TSI's installed game/runtime layout to Amethyst's Android game host.
 *
 * Amethyst is built into the same APK by scripts/integrate-amethyst-engine.sh.
 * Its Tools.DIR_GAME_HOME uses AmethystStoragePaths: app external files on
 * Android 10+, or /storage/emulated/0/games/Amethyst on Android 9 and older.
 * TSI installers, VM preflight and engine launch all use that same game path.
 * Its MultiRT_HOME resolves to context.filesDir.parentFile/runtimes, which is
 * also where AndroidJreInstaller installs the Android OpenJDK packages.
 */
object AmethystEngineBridge {
    private const val LAUNCHER_ACTIVITY = "net.kdt.pojavlaunch.LauncherActivity"
    private const val PROFILE_PREFS = "pojav_profile"
    private const val PROFILE_PREF_FILE = "file"
    private const val PROFILE_PREFIX = "amethyst://"

    private val requiredJavaClasses = listOf(
        LAUNCHER_ACTIVITY,
        "org.libsdl.app.SDL",
        "org.lwjgl.glfw.CallbackBridge",
        "net.kdt.pojavlaunch.AWTInputBridge",
        "net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode",
        "net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput"
    )

    // LWJGL runs inside the selected Minecraft JRE, so its OpenGL/OpenAL
    // Java classes are checked as extracted component JARs, not APK Dex classes.
    private val requiredLwjglFiles = listOf(
        "lwjgl3/3.3.3/lwjgl.jar",
        "lwjgl3/3.3.3/lwjgl-3.3.3-merged-modules.jar",
        "lwjgl3/3.3.3/lwjgl-lwjglx.jar",
        "lwjgl3/3.3.3/lwjgl-openal.jar",
        "lwjgl3/3.4.1/lwjgl.jar",
        "lwjgl3/3.4.1/lwjgl-3.4.1-merged-modules.jar",
        "lwjgl3/3.4.1/lwjgl-lwjglx.jar",
        "lwjgl3/3.4.1/lwjgl-openal.jar"
    )

    // These are loaded by Amethyst's own JVM startup path (JREUtils).
    private val requiredNativeLibraries = listOf(
        "libpojavexec.so",
        "libpojavexec_awt.so",
        "libexithook.so"
    )

    // Preflight must not require unpacked LWJGL files: prepare() extracts them
    // after the user presses Start VM. Check APK-embedded engine assets here.
    fun isAvailable(context: Context): Boolean = missingEmbeddedComponents(context).isEmpty()

    private fun missingEmbeddedComponents(context: Context): List<String> {
        val missing = mutableListOf<String>()
        requiredJavaClasses.forEach { className ->
            val available = runCatching {
                Class.forName(className, false, context.classLoader)
            }.isSuccess
            if (!available) missing += "Amethyst class: $className"
        }

        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        requiredNativeLibraries.forEach { library ->
            if (!File(nativeDirectory, library).isFile) {
                missing += "Amethyst native library: $library"
            }
        }

        requiredLwjglFiles.forEach { relativePath ->
            val assetPath = "components/$relativePath"
            val bundled = runCatching {
                context.assets.open(assetPath).use { it.read() }
            }.isSuccess
            if (!bundled) missing += "Bundled LWJGL component: $assetPath"
        }
        return missing
    }

    fun missingComponents(context: Context): List<String> {
        val missing = missingEmbeddedComponents(context).toMutableList()
        val gameRoot = AmethystStoragePaths.gameHome(context)
        if (!gameRoot.exists() || !gameRoot.isDirectory) {
            missing += "Amethyst game storage is unavailable"
        } else {
            requiredLwjglFiles.forEach { relativePath ->
                if (!File(gameRoot, relativePath).isFile) {
                    missing += "LWJGL component not extracted: $relativePath"
                }
            }
        }
        return missing
    }

    suspend fun prepare(
        context: Context,
        progress: suspend (String) -> Unit
    ) {
        ensureDefaultControlMap(context)
        progress("Preparing Amethyst SDL, LWJGL, graphics, audio and input components…")
        val assetManager = Class.forName("net.kdt.pojavlaunch.tasks.AsyncAssetManager")
        assetManager.getMethod("unpackComponents", Context::class.java).invoke(null, context)

        val gameRoot = AmethystStoragePaths.gameHome(context)
        val expectedLwjgl = requiredLwjglFiles.map { File(gameRoot, it) }
        val deadline = SystemClock.elapsedRealtime() + 90_000L
        while (expectedLwjgl.any { !it.isFile } && SystemClock.elapsedRealtime() < deadline) {
            delay(300)
        }

        val missing = missingComponents(context)
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "The Amethyst engine bundle is incomplete: " + missing.joinToString()
            )
        }
        progress("Amethyst JVM host, SDL, LWJGL OpenGL/OpenAL JARs, renderer and input components are ready.")
    }

    /**
     * Create and select an Amethyst-compatible offline/local player account.
     * Existing account files are preserved so an account with saved data is
     * never overwritten by the launcher UI.
     */
    /** Select a durable local account before Amethyst creates its account spinner. */
    fun ensureOfflineAccountSelected(context: Context): String {
        val preferences = context.getSharedPreferences("tsi_launcher_preferences", Context.MODE_PRIVATE)
        val currentName = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
            .getString(PROFILE_PREF_FILE, null)?.trim()
        if (!currentName.isNullOrBlank() &&
            (isLocalAccountFile(context, currentName) || selectedAccountIsMicrosoft(context))
        ) {
            val username = readAccountUsername(context, currentName) ?: currentName
            preferences.edit().putString("profile_name", username).apply()
            return username
        }
        val requested = preferences.getString("profile_name", "Steve001")
            ?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_]{3,16}")) } ?: "Steve001"
        val dataRoot = context.filesDir.parentFile ?: context.filesDir
        val accountsDirectory = File(dataRoot, "accounts")
        var candidate = requested
        if (File(accountsDirectory, candidate + ".json").exists() && !isLocalAccountFile(context, candidate)) {
            candidate = (1..99).asSequence().map { suffix ->
                val tail = "_" + suffix
                requested.take(16 - tail.length) + tail
            }.firstOrNull { name ->
                !File(accountsDirectory, name + ".json").exists() || isLocalAccountFile(context, name)
            } ?: throw IOException("Could not select an unused offline username.")
        }
        addOfflineAccount(context, candidate)
        if (!preferences.edit().putString("profile_name", candidate).commit()) {
            throw IOException("Could not save the offline username.")
        }
        return candidate
    }

    private fun readAccountUsername(context: Context, accountName: String): String? {
        val dataRoot = context.filesDir.parentFile ?: context.filesDir
        val accountFile = File(File(dataRoot, "accounts"), accountName + ".json")
        return runCatching { JSONObject(accountFile.readText(Charsets.UTF_8)).optString("username").trim().takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun isLocalAccountFile(context: Context, accountName: String): Boolean {
        if (!accountName.matches(Regex("[A-Za-z0-9_]{1,64}"))) return false
        val dataRoot = context.filesDir.parentFile ?: context.filesDir
        val accountFile = File(File(dataRoot, "accounts"), accountName + ".json")
        return runCatching {
            if (!accountFile.isFile) return@runCatching false
            val json = JSONObject(accountFile.readText(Charsets.UTF_8))
            json.optString("accessToken", "0") == "0" && !json.optString("username", accountName).startsWith("Demo.") && !json.optBoolean("isMicrosoft", false)
        }.getOrDefault(false)
    }
    /** Returns the current account channel for the dashboard status pill. */
    fun selectedAccountMode(context: Context): String = when {
        selectedAccountUsername(context) == null -> "No account"
        selectedAccountIsMicrosoft(context) -> "Microsoft"
        selectedAccountIsLocal(context) -> "Offline"
        else -> "No account"
    }

    /** Returns the selected Amethyst account username, if one is saved. */
    fun selectedAccountUsername(context: Context): String? {
        val accountName = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
            .getString(PROFILE_PREF_FILE, null)
            ?.trim()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,64}")) }
            ?: return null

        val dataRoot = context.filesDir.parentFile ?: context.filesDir
        val accountFile = File(File(dataRoot, "accounts"), "$accountName.json")
        if (!accountFile.isFile) return null
        return runCatching {
            JSONObject(accountFile.readText(Charsets.UTF_8))
                .optString("username")
                .trim()
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun selectedAccountIsMicrosoft(context: Context): Boolean {
        val accountName = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
            .getString(PROFILE_PREF_FILE, null)
            ?.trim()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,64}")) }
            ?: return false

        val dataRoot = context.filesDir.parentFile ?: context.filesDir
        val accountFile = File(File(dataRoot, "accounts"), "$accountName.json")
        return runCatching {
            accountFile.isFile &&
                JSONObject(accountFile.readText(Charsets.UTF_8)).optBoolean("isMicrosoft", false)
        }.getOrDefault(false)
    }

    private fun selectedAccountIsLocal(context: Context): Boolean {
        val name = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
            .getString(PROFILE_PREF_FILE, null)?.trim() ?: return false
        return isLocalAccountFile(context, name)
    }

    fun addOfflineAccount(context: Context, username: String): File {
        val playerName = username.trim()
        require(playerName.matches(Regex("[A-Za-z0-9_]{3,16}"))) {
            "Offline username must be 3–16 characters using letters, numbers or underscore."
        }

        val dataRoot = context.filesDir.parentFile ?: context.filesDir
        val accountsDirectory = File(dataRoot, "accounts")
        if (!accountsDirectory.exists() && !accountsDirectory.mkdirs()) {
            throw IOException("Could not create Amethyst's local account directory.")
        }

        val accountFile = File(accountsDirectory, "$playerName.json")
        if (!accountFile.isFile) {
            val account = JSONObject()
                .put("accessToken", "0")
                .put("clientToken", "0")
                .put("profileId", "00000000-0000-0000-0000-000000000000")
                .put("username", playerName)
                .put("isMicrosoft", false)
            writeAtomically(accountFile, account.toString(2))
        } else {
            // Do not silently select a malformed file under an otherwise valid name.
            try {
                val existing = JSONObject(accountFile.readText(Charsets.UTF_8))
                require(existing.optString("username").isNotBlank()) {
                    "The existing offline account file is invalid."
                }
            } catch (error: Exception) {
                throw IOException("Could not read the existing Amethyst account for $playerName.", error)
            }
        }

        val selected = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PROFILE_PREF_FILE, playerName)
            .commit()
        if (!selected) {
            throw IOException("Could not save the selected offline account.")
        }

        // Validate with Amethyst's own account parser, not just TSI's JSON
        // reader. This catches schema/path/preference mismatches before the
        // game activity starts and avoids a false "no account" launcher state.
        verifyAmethystOfflineAccount(context, playerName)
        return accountFile
    }

    private fun verifyAmethystOfflineAccount(context: Context, username: String) {
        try {
            val accountClass = Class.forName("net.kdt.pojavlaunch.value.MinecraftAccount")
            val account = accountClass
                .getMethod("load", String::class.java)
                .invoke(null, username)
                ?: throw IOException("Amethyst could not load the local account file.")

            val isLocal = accountClass.getMethod("isLocal").invoke(account) as? Boolean
            if (isLocal != true) {
                throw IOException("Amethyst did not classify $username as a local offline account.")
            }

            val profileClass = Class.forName("net.kdt.pojavlaunch.PojavProfile")
            val selectedName = profileClass
                .getMethod("getCurrentProfileName", Context::class.java)
                .invoke(null, context) as? String

            if (selectedName != username) {
                throw IOException("Amethyst did not select the local account $username.")
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Amethyst could not validate the selected local account.", error)
        }
    }

    private fun hasClientJarInInheritanceChain(minecraftRoot: File, versionId: String): Boolean {
        val versionsRoot = File(minecraftRoot, "versions")
        val visited = mutableSetOf<String>()
        var currentId = versionId

        repeat(16) {
            if (!currentId.matches(Regex("[A-Za-z0-9._+\\-]{1,100}")) || !visited.add(currentId)) {
                return false
            }
            val directory = File(versionsRoot, currentId)
            val metadataFile = File(directory, "$currentId.json")
            val metadata = runCatching {
                if (!metadataFile.isFile) null
                else JSONObject(metadataFile.readText(Charsets.UTF_8))
            }.getOrNull() ?: return false

            val clientJar = File(directory, "$currentId.jar")
            if (clientJar.isFile && clientJar.length() > 0L) {
                val client = metadata.optJSONObject("downloads")?.optJSONObject("client")
                val expectedSize = client?.optLong("size", -1L) ?: -1L
                if (expectedSize <= 0L || clientJar.length() == expectedSize) return true
            }

            val parent = metadata.optString("inheritsFrom").trim()
            if (parent.isBlank()) return false
            currentId = parent
        }
        return false
    }

    fun launch(
        context: Context,
        versionId: String,
        heapMb: Float,
        runtime: DetectedJavaRuntime,
        playerName: String,
        instanceId: String = versionId,
        rendererId: String? = null,
        javaArgs: String? = null,
        controlLayout: String? = null,
        argsMode: Int = 0,
        sharedData: Boolean = false
    ) {
        ensureDefaultControlMap(context)
        val safeVersion = versionId.trim()
        require(safeVersion.matches(Regex("[A-Za-z0-9._+\\-]{1,100}"))) {
            "Invalid Minecraft version identifier."
        }
        require(runtime.name.matches(Regex("[A-Za-z0-9._+\\-]{1,80}"))) {
            "Invalid Android runtime name."
        }
        val amethystRuntimeRoot = File(context.filesDir.parentFile ?: context.filesDir, "runtimes")
        val amethystRuntimeHome = File(amethystRuntimeRoot, runtime.name)
        require(
            amethystRuntimeHome.canonicalFile == runtime.home.canonicalFile &&
                File(amethystRuntimeHome, "release").isFile &&
                runtime.jvmLibrary.isFile
        ) {
            "Java ${runtime.majorVersion} is not installed in Amethyst's MultiRT folder. Return to VM Controller and install a compatible Android JRE."
        }
        val safeInstanceId = instanceId.trim()
        require(safeInstanceId.matches(Regex("[A-Za-z0-9._+\\-]{1,100}"))) {
            "Invalid game instance identifier."
        }
        val minecraftRoot = AmethystStoragePaths.minecraftHome(context)
        val versionDirectory = File(File(minecraftRoot, "versions"), safeVersion)
        require(File(versionDirectory, "$safeVersion.json").isFile) {
            "Minecraft $safeVersion version metadata is missing."
        }
        require(hasClientJarInInheritanceChain(minecraftRoot, safeVersion)) {
            "Minecraft $safeVersion and its inherited vanilla parent do not contain a usable client JAR."
        }

        val instancesRoot = File(minecraftRoot, "instances").canonicalFile
        val selectedInstanceRoot = File(instancesRoot, safeInstanceId).canonicalFile
        require(selectedInstanceRoot.path.startsWith(instancesRoot.path + File.separator)) {
            "Selected game instance points outside the instances folder."
        }
        val gameDirectory = if (sharedData) {
            File(minecraftRoot.parentFile ?: minecraftRoot, "shared_dir")
        } else {
            File(selectedInstanceRoot, "game")
        }
        if (!gameDirectory.exists() && !gameDirectory.mkdirs()) {
            throw IOException("Could not create the selected Minecraft game directory.")
        }
        ensureVivoFriendlyOptions(context, gameDirectory)

        val safePlayer = playerName
            .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
            .take(16)
            .let { if (it.length in 3..16) it else "Steve" }

        val safeRendererId = rendererId?.trim()?.takeIf { it.isNotBlank() }
        if (safeRendererId != null) {
            require(safeRendererId.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) {
                "Invalid renderer identifier."
            }
            val toolsClass = Class.forName("net.kdt.pojavlaunch.Tools")
            val compatible = toolsClass.getMethod(
                "checkRendererCompatible",
                Context::class.java,
                String::class.java
            ).invoke(null, context, safeRendererId) as? Boolean ?: false
            require(compatible) {
                "Renderer $safeRendererId is not available or is incompatible with this device."
            }
        }
        require(argsMode in 0..2) { "Unsupported JVM argument merge mode." }
        val rawJavaArgs = javaArgs?.trim()?.takeIf { it.isNotBlank() }
        require(rawJavaArgs == null || (rawJavaArgs.length <= 8192 && !rawJavaArgs.contains('\u0000'))) {
            "Per-instance JVM arguments exceed the 8192-character limit or contain an invalid character."
        }
        val defaultJavaArgs = context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE
        ).getString("javaArgs", "").orEmpty().trim()
        val instanceJavaArgs = rawJavaArgs.orEmpty()
        val safeJavaArgs = when (argsMode) {
            0 -> rawJavaArgs
            1 -> listOf(defaultJavaArgs, instanceJavaArgs)
                .filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
            2 -> listOf(instanceJavaArgs, defaultJavaArgs)
                .filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
            else -> throw IllegalArgumentException("Unsupported JVM argument merge mode.")
        }
        val safeControlLayout = controlLayout?.trim()?.removeSuffix(".json")?.takeIf { it.isNotBlank() }
        if (safeControlLayout != null) {
            require(safeControlLayout.matches(Regex("[A-Za-z0-9._+-]{1,100}")) &&
                safeControlLayout != "." && safeControlLayout != "..") {
                "Invalid control layout name."
            }
            val controlFile = File(
                File(AmethystStoragePaths.gameHome(context), "controlmap"),
                "$safeControlLayout.json"
            )
            require(controlFile.isFile && controlFile.length() > 0L) {
                "Control layout $safeControlLayout is not installed in Amethyst's controlmap folder."
            }
        }

        val safeHeapMb = DevicePerformanceProfile.clampHeap(context, heapMb.toInt())
        writeAmethystProfile(
            context = context,
            minecraftRoot = minecraftRoot,
            versionId = safeVersion,
            runtimeName = runtime.name,
            playerName = safePlayer,
            heapMb = safeHeapMb,
            instanceId = safeInstanceId,
            rendererId = safeRendererId,
            javaArgs = safeJavaArgs,
            controlLayout = safeControlLayout,
            sharedData = sharedData
        )
        activateAmethystRuntime(
            context = context,
            runtimeName = runtime.name,
            expectedMajor = runtime.majorVersion,
            heapMb = safeHeapMb
        )

        val launchIntent = Intent()
            .setClassName(context, LAUNCHER_ACTIVITY)
            .putExtra("tsi_auto_launch", true)

        if (context !is Activity) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launchIntent)
    }

    /**
     * Seed lightweight graphics options for a new version instance only.
     * Existing options.txt files are never overwritten.
     */
    private fun ensureVivoFriendlyOptions(context: Context, gameDirectory: File) {
        val optionsFile = File(gameDirectory, "options.txt")
        // Never overwrite an existing world's graphics settings.
        if (optionsFile.isFile && optionsFile.length() > 0L) return

        val device = DevicePerformanceProfile.detect(context)
        val defaults = listOf(
            "renderDistance:${device.renderDistance}",
            "simulationDistance:${device.simulationDistance}",
            "graphics:fast",
            "fancyGraphics:false",
            "ao:0.0",
            "entityDistanceScaling:0.5",
            "particles:1",
            "maxFps:${device.maxFps}",
            "mipmapLevels:${device.mipmapLevels}",
            "biomeBlendRadius:0",
            "renderClouds:fast",
            "entityShadows:false",
            "useVbo:true"
        ).joinToString(separator = "\n", postfix = "\n")

        writeAtomically(optionsFile, defaults)
    }

    /**
     * Amethyst loads controls from <external-files>/controlmap/default.json.
     * Its default layout is bundled in the APK assets, but a fresh TSI install
     * does not have the external file yet. Copy it once without replacing a
     * user's existing custom/default layout.
     */
    private fun ensureDefaultControlMap(context: Context) {
        val gameRoot = AmethystStoragePaths.gameHome(context)
        val controlDirectory = File(gameRoot, "controlmap")
        val destination = File(controlDirectory, "default.json")
        if (destination.isFile && destination.length() > 0L) return

        if (!controlDirectory.exists() && !controlDirectory.mkdirs()) {
            throw IOException("Could not create Amethyst's controlmap directory.")
        }

        val temporary = File(controlDirectory, "default.json.tmp")
        try {
            context.assets.open("default.json").use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (!temporary.isFile || temporary.length() == 0L) {
                throw IOException("Amethyst's bundled default control layout is empty.")
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace the empty default control layout.")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not install Amethyst's default control layout.")
            }
        } catch (error: Exception) {
            temporary.delete()
            throw IOException("Failed to initialize Amethyst's default touch controls.", error)
        }
    }

    private fun writeAmethystProfile(
        context: Context,
        minecraftRoot: File,
        versionId: String,
        runtimeName: String,
        playerName: String,
        heapMb: Int,
        instanceId: String,
        rendererId: String?,
        javaArgs: String?,
        controlLayout: String?,
        sharedData: Boolean
    ) {
        val profilesFile = File(minecraftRoot, "launcher_profiles.json")
        val launcherProfiles = if (profilesFile.isFile) {
            try {
                JSONObject(profilesFile.readText(Charsets.UTF_8))
            } catch (error: Exception) {
                throw IOException("Amethyst launcher_profiles.json is invalid; existing data was not overwritten.", error)
            }
        } else {
            JSONObject()
        }

        val profiles = launcherProfiles.optJSONObject("profiles") ?: JSONObject()
        val profileKey = UUID.nameUUIDFromBytes(
            ("tsi|" + versionId + "|" + playerName + "|" + instanceId).toByteArray(Charsets.UTF_8)
        ).toString()

        val profile = JSONObject()
            .put("name", "TSI " + versionId + " (" + instanceId.take(8) + ")")
            .put("type", "custom")
            .put("lastVersionId", versionId)
            .put(
                "gameDir",
                if (sharedData) PROFILE_PREFIX + "shared_dir"
                else PROFILE_PREFIX + ".minecraft/instances/" + instanceId + "/game"
            )
            .put("javaDir", PROFILE_PREFIX + runtimeName)

        rendererId?.let { profile.put("pojavRendererName", it) }
        javaArgs?.let { profile.put("javaArgs", it) }
        controlLayout?.let {
            profile.put(
                "controlFile",
                File(File(AmethystStoragePaths.gameHome(context), "controlmap"), "$it.json").absolutePath
            )
        }

        profiles.put(profileKey, profile)
        launcherProfiles.put("profiles", profiles)
        writeAtomically(profilesFile, launcherProfiles.toString(2))

        // Preserve an authenticated Microsoft account selected inside Amethyst.
        // Only choose TSI's local account when a Microsoft account is not active.
        if (!selectedAccountIsMicrosoft(context)) {
            addOfflineAccount(context, playerName)
        }

        // Match AndroidX PreferenceManager's default preference file name without
        // forcing the standalone TSI APK to depend on androidx.preference.
        val runtimePreferencesSaved = context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE
        ).edit()
            .putString("currentProfile", profileKey)
            .putString("defaultRuntime", runtimeName)
            .putInt("allocation", heapMb)
            .commit()
        if (!runtimePreferencesSaved) {
            throw IOException("Could not persist Amethyst's selected runtime and profile preferences.")
        }

    }

    private fun activateAmethystRuntime(context: Context, runtimeName: String, expectedMajor: Int, heapMb: Int) {
        try {
            val runtimeRoot = File(context.filesDir.parentFile ?: context.filesDir, "runtimes")
            val expectedHome = File(runtimeRoot, runtimeName)
            val toolsClass = Class.forName("net.kdt.pojavlaunch.Tools")
            val configuredRoot = toolsClass.getField("MULTIRT_HOME").get(null) as? String
            if (!configuredRoot.isNullOrBlank() &&
                File(configuredRoot).canonicalFile != runtimeRoot.canonicalFile
            ) {
                throw IOException("Amethyst MultiRT path does not match TSI's installed runtime folder.")
            }

            val multiRtClass = Class.forName("net.kdt.pojavlaunch.multirt.MultiRTUtils")
            val selectedRuntime = multiRtClass
                .getMethod("forceReread", String::class.java)
                .invoke(null, runtimeName)
                ?: throw IOException("Amethyst could not read the selected Java runtime.")

            val actualMajor = selectedRuntime.javaClass.getField("javaVersion").getInt(selectedRuntime)
            val versionString = selectedRuntime.javaClass.getField("versionString")
                .get(selectedRuntime) as? String
            if (actualMajor != expectedMajor || versionString.isNullOrBlank() ||
                !File(expectedHome, "release").isFile
            ) {
                throw IOException(
                    "Amethyst MultiRT cannot recognize Java $expectedMajor in " +
                        expectedHome.absolutePath + ". Runtime metadata is invalid or incomplete."
                )
            }

            val preferencesClass = Class.forName("net.kdt.pojavlaunch.prefs.LauncherPreferences")
            preferencesClass.getField("PREF_DEFAULT_RUNTIME").set(null, runtimeName)
            preferencesClass.getField("PREF_RAM_ALLOCATION").setInt(null, heapMb)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException(
                "Could not activate Java $expectedMajor in Amethyst's runtime selector.",
                error
            )
        }
    }

    private fun writeAtomically(destination: File, content: String) {
        val parentDirectory = destination.parentFile
            ?: throw IOException("Destination has no parent directory.")
        if (!parentDirectory.exists() && !parentDirectory.mkdirs()) {
            throw IOException("Could not create destination directory.")
        }

        val temporary = File(parentDirectory, destination.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(destination)) {
            throw IOException("Could not atomically update destination file.")
        }
    }
}
