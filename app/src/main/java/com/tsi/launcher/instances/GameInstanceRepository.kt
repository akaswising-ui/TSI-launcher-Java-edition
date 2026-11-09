package com.tsi.launcher.instances

import android.content.Context
import com.tsi.launcher.storage.AmethystStoragePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class GameInstance(
    val id: String,
    val name: String,
    val versionId: String,
    val minecraftVersion: String,
    val pinned: Boolean,
    val loaderId: String?,
    val loaderVersion: String?,
    val createdAt: Long,
    val gameDirectory: File
)

/**
 * Instance metadata and portable MultiMC/Prism ZIP transfer.
 * Minecraft versions, libraries and assets stay in the shared .minecraft root;
 * each instance owns its own saves, mods, configs, resource packs and options.
 */
class GameInstanceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val minecraftRoot = AmethystStoragePaths.minecraftHome(appContext)
    private val instancesRoot = File(minecraftRoot, "instances")
    private val preferences = appContext.getSharedPreferences("tsi_launcher_preferences", Context.MODE_PRIVATE)

    companion object {
        private const val META_FILE = "tsi-instance.json"
        private const val ACTIVE_INSTANCE_KEY = "current_instance_id"
        private const val MAX_ENTRIES = 100_000
        private const val MAX_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L
        private val SHARED_ROOTS = setOf("versions", "libraries", "assets")
    }

    @Synchronized
    fun list(): List<GameInstance> {
        ensureDirectory(instancesRoot)
        return (instancesRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, META_FILE).isFile }
            .mapNotNull(::readInstance)
            .sortedWith(compareByDescending<GameInstance> { it.pinned }.thenBy { it.name.lowercase() })
    }

    @Synchronized
    fun selected(): GameInstance? {
        val id = preferences.getString(ACTIVE_INSTANCE_KEY, null) ?: return null
        return readInstance(instanceRoot(id))
    }

    /** Adopt the old per-version save folder instead of moving or deleting it. */
    @Synchronized
    fun ensureDefault(versionId: String): GameInstance {
        requireSafeVersion(versionId)
        selected()?.let { return it }

        val legacyId = safeSegment(versionId)
        val legacyRoot = instanceRoot(legacyId)
        val legacyGame = File(legacyRoot, "game")
        if (legacyRoot.isDirectory && legacyGame.isDirectory) {
            val legacy = GameInstance(
                id = legacyId,
                name = "Default",
                versionId = versionId,
                minecraftVersion = versionId,
                pinned = true,
                loaderId = null,
                loaderVersion = null,
                createdAt = legacyRoot.lastModified(),
                gameDirectory = legacyGame
            )
            writeInstance(legacy)
            select(legacy.id)
            return legacy
        }

        val created = createInternal("Default", versionId, true)
        select(created.id)
        return created
    }

    @Synchronized
    fun create(name: String, versionId: String, cloneSelected: Boolean = false): GameInstance {
        requireSafeVersion(versionId)
        val created = createInternal(validateName(name), versionId, false)
        if (cloneSelected) {
            val source = selected()
            if (source != null && source.gameDirectory.isDirectory) copyTree(source.gameDirectory, created.gameDirectory)
        }
        return created
    }

    @Synchronized
    fun select(id: String) {
        val instance = readInstance(instanceRoot(id)) ?: throw IOException("The selected instance no longer exists.")
        if (!preferences.edit().putString(ACTIVE_INSTANCE_KEY, instance.id).commit()) {
            throw IOException("Could not save the selected instance.")
        }
    }

    @Synchronized
    fun rename(id: String, name: String): GameInstance {
        val current = readInstance(instanceRoot(id)) ?: throw IOException("Instance not found.")
        return current.copy(name = validateName(name)).also(::writeInstance)
    }

    @Synchronized
    fun setPinned(id: String, pinned: Boolean): GameInstance {
        val current = readInstance(instanceRoot(id)) ?: throw IOException("Instance not found.")
        return current.copy(pinned = pinned).also(::writeInstance)
    }

    @Synchronized
    fun updateVersion(id: String, versionId: String): GameInstance {
        requireSafeVersion(versionId)
        val current = readInstance(instanceRoot(id)) ?: throw IOException("Instance not found.")
        return current.copy(
            versionId = versionId,
            minecraftVersion = versionId,
            loaderId = null,
            loaderVersion = null
        ).also(::writeInstance)
    }

    @Synchronized
    fun delete(id: String): String? {
        val current = readInstance(instanceRoot(id)) ?: throw IOException("Instance not found.")
        if (list().size <= 1) throw IOException("The last remaining instance cannot be deleted.")
        val root = instanceRoot(current.id).canonicalFile
        val base = instancesRoot.canonicalFile
        if (!root.path.startsWith(base.path + File.separator)) {
            throw IOException("Refusing to delete data outside the instances folder.")
        }
        deleteTree(root)
        if (preferences.getString(ACTIVE_INSTANCE_KEY, null) == id) {
            val fallback = list().firstOrNull() ?: throw IOException("No instance remains after deletion.")
            select(fallback.id)
            return fallback.id
        }
        return selected()?.id
    }

    /**
     * Import a MultiMC/Prism instance. Extraction is staged and zip paths are
     * validated before any files are merged into the launcher data directory.
     * Existing shared libraries/assets and existing saves are not overwritten.
     */
    @Synchronized
    fun importMultiMc(archive: File): GameInstance {
        if (!archive.isFile || archive.length() <= 0L) throw IOException("The selected ZIP is empty.")
        val staging = File(appContext.cacheDir, "tsi-mmc-import-" + UUID.randomUUID())
        val stagedMinecraft = File(staging, ".minecraft")
        var created: GameInstance? = null

        try {
            ensureDirectory(staging)
            ZipFile(archive).use { zip ->
                val configEntry = findEntry(zip, "instance.cfg")
                    ?: throw IOException("This ZIP is not a MultiMC/Prism instance: instance.cfg is missing.")
                val prefixPart = configEntry.name.substringBeforeLast('/', "")
                val prefix = if (prefixPart.isEmpty()) "" else prefixPart + "/"
                val configText = zip.getInputStream(configEntry).bufferedReader().use { it.readText() }
                val config = parseConfig(configText)
                val packEntry = zip.getEntry(prefix + "mmc-pack.json")
                val pack = packEntry?.let {
                    JSONObject(zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() })
                }
                val components = pack?.optJSONArray("components") ?: JSONArray()
                val minecraftVersion = componentVersion(components, "net.minecraft")
                    ?: config["IntendedVersion"]
                    ?: throw IOException("The instance does not declare a Minecraft version.")
                requireSafeVersion(minecraftVersion)

                val loader = findLoader(components)
                val rawName = config["name"]?.takeIf { it.isNotBlank() } ?: archive.nameWithoutExtension
                val instanceName = validateName(rawName)
                val candidateBases = listOf(prefix + ".minecraft/", prefix + "minecraft/")
                val minecraftPrefix = candidateBases.firstOrNull { base ->
                    val entries = zip.entries()
                    var found = zip.getEntry(base) != null
                    while (!found && entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        found = !entry.isDirectory && entry.name.startsWith(base)
                    }
                    found
                } ?: throw IOException("The archive does not contain a .minecraft or minecraft folder.")

                ensureDirectory(stagedMinecraft)
                var entriesSeen = 0
                var bytesWritten = 0L
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith(minecraftPrefix)) continue
                    entriesSeen++
                    if (entriesSeen > MAX_ENTRIES) throw IOException("The archive contains too many files.")
                    if (entry.size > 1024L * 1024L * 1024L) {
                        throw IOException("An imported file exceeds the 1 GB per-file limit.")
                    }
                    val relative = safeRelative(entry.name.removePrefix(minecraftPrefix))
                    val target = safeChild(stagedMinecraft, relative)
                    ensureDirectory(target.parentFile ?: stagedMinecraft)
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytesWritten += count
                                if (bytesWritten > MAX_UNCOMPRESSED_BYTES) {
                                    throw IOException("The extracted archive exceeds the 4 GB safety limit.")
                                }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                }
                if (entriesSeen == 0) throw IOException("The instance ZIP contains no Minecraft game files.")

                val versionId = resolveImportedVersionId(stagedMinecraft, minecraftVersion, loader)
                created = createInternal(instanceName, versionId, false)
                mergeStagedMinecraft(stagedMinecraft, created.gameDirectory)
                val completed = created.copy(
                    minecraftVersion = minecraftVersion,
                    loaderId = loader?.first,
                    loaderVersion = loader?.second
                )
                writeInstance(completed)
                select(completed.id)
                return completed
            }
        } catch (error: Exception) {
            created?.let { runCatching { deleteTree(instanceRoot(it.id)) } }
            if (error is IOException) throw error
            throw IOException("Could not import the MultiMC/Prism instance: " +
                (error.message ?: error.javaClass.simpleName), error)
        } finally {
            runCatching { deleteTree(staging) }
        }
    }

    /** Export a portable ZIP accepted by MultiMC/Prism and Hyper Launcher. */
    @Synchronized
    fun exportMultiMc(instanceId: String, destination: File) {
        val instance = readInstance(instanceRoot(instanceId)) ?: throw IOException("Instance not found.")
        val temporary = File(destination.parentFile ?: appContext.cacheDir, destination.name + ".part")
        if (temporary.exists()) temporary.delete()

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                val config = "InstanceType=OneSix\nname=" + instance.name +
                    "\nIntendedVersion=" + instance.minecraftVersion + "\n"
                addTextEntry(zip, "instance.cfg", config)

                val components = JSONArray().put(
                    JSONObject().put("uid", "net.minecraft").put("version", instance.minecraftVersion)
                )
                if (!instance.loaderId.isNullOrBlank() && !instance.loaderVersion.isNullOrBlank()) {
                    components.put(JSONObject().put("uid", instance.loaderId).put("version", instance.loaderVersion))
                }
                val pack = JSONObject().put("formatVersion", 1).put("components", components)
                addTextEntry(zip, "mmc-pack.json", pack.toString(2))

                val exportVersions = linkedSetOf(instance.minecraftVersion, instance.versionId)
                exportVersions.forEach { version ->
                    val versionRoot = File(File(minecraftRoot, "versions"), version)
                    if (versionRoot.isDirectory) {
                        addTreeToZip(zip, versionRoot, ".minecraft/versions/" + version)
                    }
                }
                if (instance.gameDirectory.isDirectory) addTreeToZip(zip, instance.gameDirectory, ".minecraft")
            }
            if (!temporary.isFile || temporary.length() <= 0L) throw IOException("The exported archive is empty.")
            if (destination.exists() && !destination.delete()) throw IOException("Could not replace the old export file.")
            if (!temporary.renameTo(destination)) throw IOException("Could not finalize the exported instance ZIP.")
        } catch (error: Exception) {
            temporary.delete()
            if (error is IOException) throw error
            throw IOException("Could not export the instance: " +
                (error.message ?: error.javaClass.simpleName), error)
        }
    }

    private fun createInternal(name: String, versionId: String, pinned: Boolean): GameInstance {
        ensureDirectory(instancesRoot)
        val id = UUID.randomUUID().toString()
        val folder = instanceRoot(id)
        ensureDirectory(folder)
        val game = File(folder, "game")
        ensureGameFolders(game)
        val instance = GameInstance(
            id, name, versionId, versionId, pinned, null, null, System.currentTimeMillis(), game
        )
        writeInstance(instance)
        return instance
    }

    private fun readInstance(folder: File): GameInstance? {
        val metadata = File(folder, META_FILE)
        if (!metadata.isFile) return null
        return runCatching {
            val json = JSONObject(metadata.readText(Charsets.UTF_8))
            val id = json.getString("id")
            if (safeSegment(id) != folder.name) return@runCatching null
            val game = File(folder, "game").canonicalFile
            if (!game.path.startsWith(folder.canonicalFile.path + File.separator)) return@runCatching null
            val version = json.getString("versionId").also(::requireSafeVersion)
            val baseVersion = json.optString("minecraftVersion", version).also(::requireSafeVersion)
            GameInstance(
                id = id,
                name = validateName(json.optString("name", "Minecraft instance")),
                versionId = version,
                minecraftVersion = baseVersion,
                pinned = json.optBoolean("pinned", false),
                loaderId = json.optString("loaderId").takeIf { it.isNotBlank() },
                loaderVersion = json.optString("loaderVersion").takeIf { it.isNotBlank() },
                createdAt = json.optLong("createdAt", folder.lastModified()),
                gameDirectory = game
            )
        }.getOrNull()
    }

    private fun writeInstance(instance: GameInstance) {
        val folder = instanceRoot(instance.id)
        ensureDirectory(folder)
        ensureGameFolders(instance.gameDirectory)
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("id", instance.id)
            .put("name", validateName(instance.name))
            .put("versionId", instance.versionId.also(::requireSafeVersion))
            .put("minecraftVersion", instance.minecraftVersion.also(::requireSafeVersion))
            .put("pinned", instance.pinned)
            .put("createdAt", instance.createdAt)
            .put("loaderId", instance.loaderId ?: "")
            .put("loaderVersion", instance.loaderVersion ?: "")
        writeAtomically(File(folder, META_FILE), json.toString(2))
    }

    private fun mergeStagedMinecraft(staged: File, gameDirectory: File) {
        val children = staged.listFiles() ?: throw IOException("Could not read staged instance files.")
        for (child in children) {
            val destination = if (child.name in SHARED_ROOTS) File(minecraftRoot, child.name)
                else File(gameDirectory, child.name)
            copyMissingTree(child, destination)
        }
    }

    private fun resolveImportedVersionId(
        stagedMinecraft: File,
        vanillaVersion: String,
        loader: Pair<String, String>?
    ): String {
        if (loader == null) return vanillaVersion
        val candidate = when (loader.first) {
            "net.fabricmc.fabric-loader" -> "fabric-loader-" + loader.second + "-" + vanillaVersion
            "org.quiltmc.quilt-loader" -> "quilt-loader-" + loader.second + "-" + vanillaVersion
            "net.minecraftforge" -> vanillaVersion + "-forge-" + loader.second
            "net.neoforged.neoforge" -> "neoforge-" + loader.second
            else -> vanillaVersion
        }
        val customVersion = File(File(stagedMinecraft, "versions"), candidate)
        return if (File(customVersion, candidate + ".json").isFile) candidate else vanillaVersion
    }

    private fun findLoader(components: JSONArray): Pair<String, String>? {
        val supported = setOf(
            "net.fabricmc.fabric-loader",
            "org.quiltmc.quilt-loader",
            "net.minecraftforge",
            "net.neoforged.neoforge"
        )
        for (index in 0 until components.length()) {
            val item = components.optJSONObject(index) ?: continue
            val id = item.optString("uid")
            val version = item.optString("version")
            if (id in supported && version.isNotBlank()) return id to version
        }
        return null
    }

    private fun componentVersion(components: JSONArray, uid: String): String? {
        for (index in 0 until components.length()) {
            val item = components.optJSONObject(index) ?: continue
            if (item.optString("uid") == uid) return item.optString("version").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun findEntry(zip: ZipFile, name: String): ZipEntry? {
        zip.getEntry(name)?.let { return it }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.endsWith("/" + name)) return entry
        }
        return null
    }

    private fun parseConfig(text: String): Map<String, String> = buildMap {
        text.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator > 0) put(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
        }
    }

    private fun ensureGameFolders(game: File) {
        listOf("mods", "config", "saves", "resourcepacks", "shaderpacks", "screenshots", "logs").forEach {
            ensureDirectory(File(game, it))
        }
    }

    private fun copyMissingTree(source: File, destination: File) {
        if (!source.exists()) return
        if (source.isDirectory) {
            if (destination.exists() && !destination.isDirectory) return
            ensureDirectory(destination)
            val children = source.listFiles() ?: throw IOException("Cannot read " + source.absolutePath)
            children.forEach { copyMissingTree(it, File(destination, it.name)) }
        } else if (source.isFile && !destination.exists()) {
            ensureDirectory(destination.parentFile ?: throw IOException("Missing destination parent."))
            source.copyTo(destination, overwrite = false)
        }
    }

    private fun copyTree(source: File, destination: File) {
        if (source.isDirectory) {
            ensureDirectory(destination)
            val children = source.listFiles() ?: throw IOException("Cannot read " + source.absolutePath)
            children.forEach { copyTree(it, File(destination, it.name)) }
        } else if (source.isFile) {
            ensureDirectory(destination.parentFile ?: throw IOException("Missing destination parent."))
            source.copyTo(destination, overwrite = false)
        }
    }

    private fun addTreeToZip(zip: ZipOutputStream, source: File, zipRoot: String) {
        val base = source.canonicalFile
        val queue = ArrayDeque<File>()
        queue.add(source)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val canonical = current.canonicalFile
            if (canonical.path != base.path && !canonical.path.startsWith(base.path + File.separator)) {
                throw IOException("Refusing to export a path outside the instance directory.")
            }
            val relative = current.relativeTo(source).invariantSeparatorsPath
            if (current.isDirectory) {
                if (relative.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry((zipRoot + "/" + relative + "/").replace("//", "/")))
                    zip.closeEntry()
                }
                val children = current.listFiles() ?: throw IOException("Cannot read " + current.absolutePath)
                children.forEach(queue::add)
            } else if (current.isFile) {
                val entry = if (relative.isEmpty()) zipRoot else zipRoot + "/" + relative
                zip.putNextEntry(ZipEntry(entry))
                FileInputStream(current).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun addTextEntry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun safeChild(parent: File, relative: String): File {
        val root = parent.canonicalFile
        val child = File(parent, relative).canonicalFile
        if (child.path != root.path && !child.path.startsWith(root.path + File.separator)) {
            throw IOException("Archive path escapes the import directory.")
        }
        return child
    }

    private fun safeRelative(value: String): String {
        val normalized = value.replace('\\', '/').trimStart('/')
        val parts = normalized.split('/')
        if (normalized.isBlank() || parts.any {
                it.isBlank() || it == "." || it == ".." || it.contains(':')
            }
        ) throw IOException("The archive contains an unsafe file path.")
        return parts.joinToString(File.separator)
    }

    private fun instanceRoot(id: String): File {
        val folder = File(instancesRoot, safeSegment(id)).canonicalFile
        val base = instancesRoot.canonicalFile
        if (!folder.path.startsWith(base.path + File.separator)) throw IOException("Invalid instance ID.")
        return folder
    }

    private fun safeSegment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9._+-]{1,100}")) && value != "." && value != "..") {
            "Invalid instance ID."
        }
        return value
    }

    private fun validateName(value: String): String {
        val name = value.trim().replace(Regex("[\\r\\n\\t]"), " ")
        require(name.isNotBlank() && name.length <= 48) {
            "Instance name must contain 1–48 characters."
        }
        return name
    }

    private fun requireSafeVersion(value: String) {
        require(value.matches(Regex("[A-Za-z0-9._+-]{1,100}"))) {
            "Invalid Minecraft version ID."
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create " + directory.absolutePath)
        if (!directory.isDirectory) throw IOException("Expected a directory: " + directory.absolutePath)
    }

    private fun writeAtomically(destination: File, text: String) {
        ensureDirectory(destination.parentFile ?: throw IOException("Invalid metadata destination."))
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("Could not save instance metadata.")
        }
    }

    private fun deleteTree(root: File) {
        if (!root.exists()) return
        val children = root.listFiles()
        if (root.isDirectory && children != null) children.forEach(::deleteTree)
        if (!root.delete()) throw IOException("Could not remove " + root.absolutePath)
    }
}
