package com.tsi.launcher.instances;

import android.content.Context;
import com.tsi.launcher.storage.AmethystStoragePaths;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Instance metadata and portable MultiMC/Prism ZIP transfer.
 * Shared version/library/asset files stay in .minecraft; game state is per instance.
 */
public final class GameInstanceRepository {
    private static final String META_FILE = "tsi-instance.json";
    private static final String ACTIVE_INSTANCE_KEY = "current_instance_id";
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final Set<String> SHARED_ROOTS =
            new HashSet<>(Arrays.asList("versions", "libraries", "assets"));

    private final Context appContext;
    private final File minecraftRoot;
    private final File instancesRoot;
    private final File sharedDataDirectory;
    private final android.content.SharedPreferences preferences;

    public GameInstanceRepository(Context context) {
        appContext = context.getApplicationContext();
        minecraftRoot = AmethystStoragePaths.minecraftHome(appContext);
        instancesRoot = new File(minecraftRoot, "instances");
        sharedDataDirectory = new File(
                minecraftRoot.getParentFile() == null ? minecraftRoot : minecraftRoot.getParentFile(),
                "shared_dir");
        preferences = appContext.getSharedPreferences("tsi_launcher_preferences", Context.MODE_PRIVATE);
    }

    public synchronized List<GameInstance> list() throws IOException {
        ensureDirectory(instancesRoot);
        File[] folders = instancesRoot.listFiles();
        List<GameInstance> result = new ArrayList<>();
        if (folders != null) {
            for (File folder : folders) {
                if (folder.isDirectory() && new File(folder, META_FILE).isFile()) {
                    GameInstance instance = readInstance(folder);
                    if (instance != null) result.add(instance);
                }
            }
        }
        Collections.sort(result, new Comparator<GameInstance>() {
            @Override public int compare(GameInstance a, GameInstance b) {
                if (a.isPinned() != b.isPinned()) return a.isPinned() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return result;
    }

    public synchronized GameInstance selected() throws IOException {
        String id = preferences.getString(ACTIVE_INSTANCE_KEY, null);
        if (id == null) return null;
        return readInstance(instanceRoot(id));
    }

    /** Adopts the old per-version save folder without moving or deleting it. */
    public synchronized GameInstance ensureDefault(String versionId) throws IOException {
        requireSafeVersion(versionId);
        GameInstance current = selected();
        if (current != null) return current;

        String legacyId = safeSegment(versionId);
        File legacyRoot = instanceRoot(legacyId);
        File legacyGame = new File(legacyRoot, "game");
        if (legacyRoot.isDirectory() && legacyGame.isDirectory()) {
            GameInstance legacy = new GameInstance(
                    legacyId, "Default", versionId, versionId, true,
                    null, null, legacyRoot.lastModified(), legacyGame,
                    null, null, null, null, 0, false);
            writeInstance(legacy);
            select(legacy.getId());
            return legacy;
        }
        GameInstance created = createInternal("Default", versionId, true, false);
        select(created.getId());
        return created;
    }

    public synchronized GameInstance create(String name, String versionId) throws IOException {
        return create(name, versionId, false, false);
    }

    public synchronized GameInstance create(String name, String versionId,
                                             boolean cloneSelected, boolean sharedData) throws IOException {
        requireSafeVersion(versionId);
        GameInstance created = createInternal(validateName(name), versionId, false, sharedData);
        if (cloneSelected) {
            GameInstance source = selected();
            if (source != null && source.getGameDirectory().isDirectory()) {
                copyTree(source.getGameDirectory(), created.getGameDirectory());
            }
        }
        return created;
    }

    public synchronized void select(String id) throws IOException {
        GameInstance instance = readInstance(instanceRoot(id));
        if (instance == null) throw new IOException("The selected instance no longer exists.");
        if (!preferences.edit().putString(ACTIVE_INSTANCE_KEY, instance.getId()).commit()) {
            throw new IOException("Could not save the selected instance.");
        }
    }

    public synchronized GameInstance rename(String id, String name) throws IOException {
        GameInstance current = requireInstance(id);
        GameInstance result = new GameInstance(current.getId(), validateName(name),
                current.getVersionId(), current.getMinecraftVersion(), current.isPinned(),
                current.getLoaderId(), current.getLoaderVersion(), current.getCreatedAt(),
                current.getGameDirectory(), current.getRendererId(), current.getJavaArgs(),
                current.getSelectedRuntime(), current.getControlLayout(), current.getArgsMode(),
                current.isSharedData());
        writeInstance(result);
        return result;
    }

    public synchronized GameInstance setPinned(String id, boolean pinned) throws IOException {
        GameInstance current = requireInstance(id);
        GameInstance result = new GameInstance(current.getId(), current.getName(),
                current.getVersionId(), current.getMinecraftVersion(), pinned,
                current.getLoaderId(), current.getLoaderVersion(), current.getCreatedAt(),
                current.getGameDirectory(), current.getRendererId(), current.getJavaArgs(),
                current.getSelectedRuntime(), current.getControlLayout(), current.getArgsMode(),
                current.isSharedData());
        writeInstance(result);
        return result;
    }

    public synchronized GameInstance updateLaunchSettings(
            String id, String rendererId, String javaArgs, String selectedRuntime,
            String controlLayout, int argsMode) throws IOException {
        GameInstance current = requireInstance(id);
        String safeRenderer = optionalIdentifier(rendererId, "renderer ID", 80);
        String safeRuntime = optionalIdentifier(selectedRuntime, "runtime name", 80);
        String safeControl = optionalIdentifier(stripJson(controlLayout), "control layout name", 100);
        if (argsMode < 0 || argsMode > 2) throw new IllegalArgumentException("Unsupported JVM argument merge mode.");
        String safeArgs = javaArgs == null || javaArgs.trim().isEmpty() ? null : javaArgs.trim();
        if (safeArgs != null && (safeArgs.length() > 8192 || safeArgs.indexOf('\u0000') >= 0)) {
            throw new IllegalArgumentException("Java arguments must be no longer than 8192 characters.");
        }
        GameInstance result = new GameInstance(current.getId(), current.getName(),
                current.getVersionId(), current.getMinecraftVersion(), current.isPinned(),
                current.getLoaderId(), current.getLoaderVersion(), current.getCreatedAt(),
                current.getGameDirectory(), safeRenderer, safeArgs, safeRuntime, safeControl,
                argsMode, current.isSharedData());
        writeInstance(result);
        return result;
    }

    public synchronized GameInstance updateVersion(String id, String versionId) throws IOException {
        requireSafeVersion(versionId);
        GameInstance current = requireInstance(id);
        GameInstance result = new GameInstance(current.getId(), current.getName(), versionId,
                versionId, current.isPinned(), null, null, current.getCreatedAt(),
                current.getGameDirectory(), current.getRendererId(), current.getJavaArgs(),
                current.getSelectedRuntime(), current.getControlLayout(), current.getArgsMode(),
                current.isSharedData());
        writeInstance(result);
        return result;
    }

    public synchronized String delete(String id) throws IOException {
        GameInstance current = requireInstance(id);
        if (list().size() <= 1) throw new IOException("The last remaining instance cannot be deleted.");
        File root = instanceRoot(current.getId()).getCanonicalFile();
        File base = instancesRoot.getCanonicalFile();
        if (!root.getPath().startsWith(base.getPath() + File.separator)) {
            throw new IOException("Refusing to delete data outside the instances folder.");
        }
        deleteTree(root);
        if (id.equals(preferences.getString(ACTIVE_INSTANCE_KEY, null))) {
            List<GameInstance> remaining = list();
            if (remaining.isEmpty()) throw new IOException("No instance remains after deletion.");
            select(remaining.get(0).getId());
            return remaining.get(0).getId();
        }
        GameInstance active = selected();
        return active == null ? null : active.getId();
    }

    /** Import a MultiMC/Prism instance with path traversal and size protections. */
    public synchronized GameInstance importMultiMc(File archive) throws IOException {
        if (!archive.isFile() || archive.length() <= 0L) throw new IOException("The selected ZIP is empty.");
        // Hyper Launcher can supply either a MultiMC-compatible export or a ZIP
        // of its native instance folder containing hyper_instance.json.
        try (ZipFile probe = new ZipFile(archive)) {
            if (findEntry(probe, "instance.cfg") == null) {
                ZipEntry hyperMetadata = findEntry(probe, "hyper_instance.json");
                if (hyperMetadata != null) {
                    return importHyperInstanceArchive(archive, hyperMetadata.getName());
                }
            }
        }
        File staging = new File(appContext.getCacheDir(), "tsi-mmc-import-" + UUID.randomUUID());
        File stagedMinecraft = new File(staging, ".minecraft");
        GameInstance created = null;
        try {
            ensureDirectory(staging);
            try (ZipFile zip = new ZipFile(archive)) {
                ZipEntry configEntry = findEntry(zip, "instance.cfg");
                if (configEntry == null) {
                    throw new IOException("This ZIP is not a MultiMC/Prism instance: instance.cfg is missing.");
                }
                String prefixPart = configEntry.getName().contains("/")
                        ? configEntry.getName().substring(0, configEntry.getName().lastIndexOf('/')) : "";
                String prefix = prefixPart.isEmpty() ? "" : prefixPart + "/";
                String configText = readZipEntry(zip, configEntry);
                Map<String, String> config = parseConfig(configText);
                ZipEntry packEntry = zip.getEntry(prefix + "mmc-pack.json");
                JSONObject pack = packEntry == null ? null : new JSONObject(readZipEntry(zip, packEntry));
                JSONArray components = pack == null ? new JSONArray() :
                        pack.optJSONArray("components") == null ? new JSONArray() : pack.optJSONArray("components");

                String minecraftVersion = componentVersion(components, "net.minecraft");
                if (minecraftVersion == null) minecraftVersion = config.get("IntendedVersion");
                if (minecraftVersion == null) throw new IOException("The instance does not declare a Minecraft version.");
                requireSafeVersion(minecraftVersion);

                LoaderComponent loader = findLoader(components);
                boolean sharedData = parseBoolean(config.get("TSI_SharedData"),
                        parseBoolean(config.get("SharedData"), false));
                int argsMode = parseInt(config.get("TSI_ArgsMode"), 0);
                argsMode = Math.max(0, Math.min(2, argsMode));
                String rawName = config.get("name");
                if (rawName == null || rawName.trim().isEmpty()) {
                    String archiveName = archive.getName();
                    int dot = archiveName.lastIndexOf('.');
                    rawName = dot > 0 ? archiveName.substring(0, dot) : archiveName;
                }
                String instanceName = validateName(rawName);

                String minecraftPrefix = findMinecraftPrefix(zip, prefix);
                if (minecraftPrefix == null) {
                    throw new IOException("The archive does not contain a .minecraft or minecraft folder.");
                }

                ensureDirectory(stagedMinecraft);
                int entriesSeen = 0;
                long bytesWritten = 0L;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(minecraftPrefix)) continue;
                    entriesSeen++;
                    if (entriesSeen > MAX_ENTRIES) throw new IOException("The archive contains too many files.");
                    if (entry.getSize() > 1024L * 1024L * 1024L) {
                        throw new IOException("An imported file exceeds the 1 GB per-file limit.");
                    }
                    String relative = safeRelative(entry.getName().substring(minecraftPrefix.length()));
                    File target = safeChild(stagedMinecraft, relative);
                    ensureDirectory(target.getParentFile() == null ? stagedMinecraft : target.getParentFile());
                    try (java.io.InputStream input = zip.getInputStream(entry);
                         FileOutputStream output = new FileOutputStream(target)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            bytesWritten += count;
                            if (bytesWritten > MAX_UNCOMPRESSED_BYTES) {
                                throw new IOException("The extracted archive exceeds the 4 GB safety limit.");
                            }
                            output.write(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                }
                if (entriesSeen == 0) throw new IOException("The instance ZIP contains no Minecraft game files.");

                String versionId = resolveImportedVersionId(stagedMinecraft, minecraftVersion, loader);
                created = createInternal(instanceName, versionId, false, sharedData);
                mergeStagedMinecraft(stagedMinecraft, created.getGameDirectory());
                GameInstance completed = new GameInstance(
                        created.getId(), created.getName(), created.getVersionId(), minecraftVersion,
                        created.isPinned(), loader == null ? null : loader.id,
                        loader == null ? null : loader.version, created.getCreatedAt(),
                        created.getGameDirectory(), config.get("TSI_Renderer"),
                        config.get("JavaArgs") != null && !"false".equalsIgnoreCase(config.get("OverrideJavaArgs"))
                                ? config.get("JavaArgs") : null,
                        config.get("TSI_Runtime"), config.get("TSI_ControlLayout"), argsMode, sharedData);
                writeInstance(completed);
                select(completed.getId());
                return completed;
            }
        } catch (Exception error) {
            if (created != null) {
                try { deleteTree(instanceRoot(created.getId())); } catch (Exception ignored) { }
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not import the MultiMC/Prism instance: " +
                    (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()), error);
        } finally {
            try { deleteTree(staging); } catch (Exception ignored) { }
        }
    }


    /**
     * Import a ZIP made from a Hyper Launcher instance folder. Hyper's native
     * instance format stores hyper_instance.json beside mods/config/saves.
     * Shared game assets are kept in TSI's shared .minecraft root; player data
     * is copied into a new isolated TSI instance.
     */
    private GameInstance importHyperInstanceArchive(File archive, String metadataPath) throws IOException {
        File staging = new File(appContext.getCacheDir(), "tsi-hyper-import-" + UUID.randomUUID());
        File stagedMinecraft = new File(staging, ".minecraft");
        GameInstance created = null;
        try {
            ensureDirectory(stagedMinecraft);
            try (ZipFile zip = new ZipFile(archive)) {
                ZipEntry metadataEntry = zip.getEntry(metadataPath);
                if (metadataEntry == null || metadataEntry.isDirectory()) {
                    throw new IOException("The Hyper instance metadata file could not be read.");
                }
                String metadataText = readZipEntry(zip, metadataEntry, 1024L * 1024L);
                JSONObject metadata = new JSONObject(metadataText);

                String versionId = nonBlank(metadata.optString("versionId"));
                if (versionId == null || "latest_release".equals(versionId) ||
                        "latest_snapshot".equals(versionId)) {
                    throw new IOException(
                            "This Hyper instance uses a moving Minecraft version alias. " +
                            "Select a concrete Minecraft version in Hyper Launcher and export it again.");
                }
                requireSafeVersion(versionId);

                String baseMinecraftVersion = resolveHyperMinecraftVersion(stagedMinecraft, versionId);
                requireSafeVersion(baseMinecraftVersion);

                String rawName = nonBlank(metadata.optString("name"));
                if (rawName == null) {
                    String archiveName = archive.getName();
                    int dot = archiveName.lastIndexOf('.');
                    rawName = dot > 0 ? archiveName.substring(0, dot) : archiveName;
                }
                String instanceName = validateName(rawName);

                String prefixPart = metadataPath.contains("/")
                        ? metadataPath.substring(0, metadataPath.lastIndexOf('/')) : "";
                String prefix = prefixPart.isEmpty() ? "" : prefixPart + "/";
                int entriesSeen = 0;
                long bytesWritten = 0L;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;

                    String relative = entry.getName().substring(prefix.length());
                    if (relative.isEmpty() || relative.equals("hyper_instance.json") ||
                            relative.equals("icon.webp") || relative.equals("icon.png") ||
                            relative.equals(META_FILE)) {
                        continue;
                    }
                    if (relative.startsWith(".minecraft/")) {
                        relative = relative.substring(".minecraft/".length());
                    } else if (relative.startsWith("minecraft/")) {
                        relative = relative.substring("minecraft/".length());
                    }
                    if (relative.isEmpty()) continue;

                    entriesSeen++;
                    if (entriesSeen > MAX_ENTRIES) {
                        throw new IOException("The Hyper archive contains too many files.");
                    }
                    if (entry.getSize() > 1024L * 1024L * 1024L) {
                        throw new IOException("An imported Hyper file exceeds the 1 GB per-file limit.");
                    }

                    String safePath = safeRelative(relative);
                    File target = safeChild(stagedMinecraft, safePath);
                    ensureDirectory(target.getParentFile() == null ? stagedMinecraft : target.getParentFile());
                    try (java.io.InputStream input = zip.getInputStream(entry);
                         FileOutputStream output = new FileOutputStream(target)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            bytesWritten += count;
                            if (bytesWritten > MAX_UNCOMPRESSED_BYTES) {
                                throw new IOException("The Hyper archive exceeds the 4 GB extraction safety limit.");
                            }
                            output.write(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                }
                if (entriesSeen == 0) {
                    throw new IOException("The Hyper archive contains no instance files.");
                }

                LoaderComponent loader = loaderFromHyperVersionId(versionId, baseMinecraftVersion);
                if (loader != null) {
                    File stagedVersionJson = new File(
                            new File(new File(stagedMinecraft, "versions"), versionId), versionId + ".json");
                    File installedVersionJson = new File(
                            new File(new File(minecraftRoot, "versions"), versionId), versionId + ".json");
                    if (!stagedVersionJson.isFile() && !installedVersionJson.isFile()) {
                        throw new IOException(
                                "This Hyper modded instance ZIP does not include its loader version JSON. " +
                                "Export it from Hyper as a MultiMC/Prism-compatible ZIP so TSI can restore the loader.");
                    }
                }
                String rendererId = normalizeHyperRenderer(metadata.optString("renderer"));
                String javaArgs = nonBlank(metadata.optString("jvmArgs"));
                if (javaArgs != null && (javaArgs.length() > 8192 || javaArgs.indexOf('\u0000') >= 0)) {
                    throw new IOException("The Hyper instance has invalid or excessively long JVM arguments.");
                }
                int argsMode = Math.max(0, Math.min(2, metadata.optInt("argsMode", 0)));

                // Do not carry over another launcher's runtime/control paths.
                // Those point into Hyper's private app storage and may not exist in TSI.
                created = createInternal(instanceName, versionId, metadata.optBoolean("isPinned", false), false);
                mergeStagedMinecraft(stagedMinecraft, created.getGameDirectory());

                GameInstance imported = new GameInstance(
                        created.getId(), created.getName(), versionId, baseMinecraftVersion,
                        created.isPinned(),
                        loader == null ? null : loader.id,
                        loader == null ? null : loader.version,
                        created.getCreatedAt(), created.getGameDirectory(), rendererId,
                        javaArgs, null, null, argsMode, false);
                writeInstance(imported);
                select(imported.getId());
                return imported;
            }
        } catch (Exception error) {
            if (created != null) {
                try { deleteTree(instanceRoot(created.getId())); } catch (Exception ignored) { }
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not import the Hyper Launcher instance: " +
                    (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()), error);
        } finally {
            try { deleteTree(staging); } catch (Exception ignored) { }
        }
    }

    private String resolveHyperMinecraftVersion(File stagedMinecraft, String versionId) throws IOException {
        File[] candidates = new File[] {
                new File(new File(stagedMinecraft, "versions"), versionId),
                new File(new File(minecraftRoot, "versions"), versionId)
        };
        for (File versionRoot : candidates) {
            File jsonFile = new File(versionRoot, versionId + ".json");
            if (!jsonFile.isFile()) continue;
            try {
                JSONObject versionJson = new JSONObject(readText(jsonFile));
                String inherited = nonBlank(versionJson.optString("inheritsFrom"));
                if (inherited != null) return inherited;
                String declaredId = nonBlank(versionJson.optString("id"));
                if (declaredId != null) return declaredId;
            } catch (Exception error) {
                throw new IOException("The Hyper instance contains invalid version metadata.", error);
            }
        }

        if (versionId.startsWith("fabric-loader-") || versionId.startsWith("quilt-loader-")) {
            int separator = versionId.lastIndexOf('-');
            if (separator > 0 && separator < versionId.length() - 1) {
                return versionId.substring(separator + 1);
            }
        }
        int forgeSeparator = versionId.indexOf("-forge-");
        if (forgeSeparator > 0) return versionId.substring(0, forgeSeparator);

        if (versionId.startsWith("neoforge-") || versionId.startsWith("optifine-")) {
            throw new IOException(
                    "The Hyper instance is a modded profile but its export does not include " +
                    "the version JSON needed to identify the Minecraft base version. " +
                    "Export it as a MultiMC/Prism-compatible ZIP from Hyper Launcher instead.");
        }
        return versionId;
    }

    private LoaderComponent loaderFromHyperVersionId(String versionId, String baseVersion) {
        if (versionId.startsWith("fabric-loader-")) {
            String value = versionId.substring("fabric-loader-".length());
            int separator = value.lastIndexOf('-');
            if (separator > 0 && value.substring(separator + 1).equals(baseVersion)) {
                return new LoaderComponent("net.fabricmc.fabric-loader", value.substring(0, separator));
            }
        }
        if (versionId.startsWith("quilt-loader-")) {
            String value = versionId.substring("quilt-loader-".length());
            int separator = value.lastIndexOf('-');
            if (separator > 0 && value.substring(separator + 1).equals(baseVersion)) {
                return new LoaderComponent("org.quiltmc.quilt-loader", value.substring(0, separator));
            }
        }
        int forgeSeparator = versionId.indexOf("-forge-");
        if (forgeSeparator > 0 && versionId.substring(0, forgeSeparator).equals(baseVersion)) {
            return new LoaderComponent("net.minecraftforge", versionId.substring(forgeSeparator + "-forge-".length()));
        }
        if (versionId.startsWith("neoforge-")) {
            return new LoaderComponent("net.neoforged.neoforge", versionId.substring("neoforge-".length()));
        }
        return null;
    }

    private String normalizeHyperRenderer(String renderer) {
        String value = nonBlank(renderer);
        if (value == null) return null;
        if ("mobileglues".equals(value)) return "opengles_mobileglues";
        Set<String> supported = new HashSet<>(Arrays.asList(
                "opengles2", "opengles2_5", "opengles3", "opengles3_ltw",
                "opengles_mobileglues", "vulkan_zink", "opengles3_desktopgl_zink_kopper",
                "opengles_system_gles"));
        return supported.contains(value) ? value : null;
    }

    /** Export a portable ZIP accepted by MultiMC/Prism and Hyper Launcher. */
    public synchronized void exportMultiMc(String instanceId, File destination) throws IOException {
        GameInstance instance = requireInstance(instanceId);
        File parent = destination.getParentFile() == null ? appContext.getCacheDir() : destination.getParentFile();
        File temporary = new File(parent, destination.getName() + ".part");
        if (temporary.exists()) temporary.delete();
        try {
            ensureDirectory(parent);
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
                List<String> configLines = new ArrayList<>();
                configLines.add("InstanceType=OneSix");
                configLines.add("name=" + instance.getName());
                configLines.add("IntendedVersion=" + instance.getMinecraftVersion());
                if (instance.getJavaArgs() != null) {
                    configLines.add("OverrideJavaArgs=true");
                    configLines.add("JavaArgs=" + instance.getJavaArgs().replace('\n', ' ').replace('\r', ' '));
                }
                if (instance.getRendererId() != null) configLines.add("TSI_Renderer=" + instance.getRendererId());
                if (instance.getSelectedRuntime() != null) configLines.add("TSI_Runtime=" + instance.getSelectedRuntime());
                if (instance.getControlLayout() != null) configLines.add("TSI_ControlLayout=" + instance.getControlLayout());
                configLines.add("TSI_ArgsMode=" + instance.getArgsMode());
                configLines.add("TSI_SharedData=" + instance.isSharedData());
                addTextEntry(zip, "instance.cfg", join(configLines, "\n") + "\n");

                JSONArray components = new JSONArray().put(new JSONObject()
                        .put("uid", "net.minecraft").put("version", instance.getMinecraftVersion()));
                if (notBlank(instance.getLoaderId()) && notBlank(instance.getLoaderVersion())) {
                    components.put(new JSONObject().put("uid", instance.getLoaderId())
                            .put("version", instance.getLoaderVersion()));
                }
                JSONObject pack = new JSONObject().put("formatVersion", 1).put("components", components);
                addTextEntry(zip, "mmc-pack.json", pack.toString(2));

                Set<String> exportVersions = new LinkedHashSet<>();
                exportVersions.add(instance.getMinecraftVersion());
                exportVersions.add(instance.getVersionId());
                for (String version : exportVersions) {
                    File versionRoot = new File(new File(minecraftRoot, "versions"), version);
                    if (versionRoot.isDirectory()) addTreeToZip(zip, versionRoot, ".minecraft/versions/" + version);
                }
                if (instance.getGameDirectory().isDirectory()) addTreeToZip(zip, instance.getGameDirectory(), ".minecraft");
            }
            if (!temporary.isFile() || temporary.length() <= 0L) throw new IOException("The exported archive is empty.");
            if (destination.exists() && !destination.delete()) throw new IOException("Could not replace the old export file.");
            if (!temporary.renameTo(destination)) throw new IOException("Could not finalize the exported instance ZIP.");
        } catch (Exception error) {
            temporary.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not export the instance: " + error.getMessage(), error);
        }
    }

    /** Adopt a modpack installed by Amethyst's Modrinth/CurseForge installer. */
    public synchronized GameInstance importInstalledModpack(String name, String versionId,
                                                              String profileGameDirectory) throws IOException {
        requireSafeVersion(versionId);
        String relative = profileGameDirectory.replace('\\', '/');
        if (relative.startsWith("./")) relative = relative.substring(2);
        while (relative.startsWith("/")) relative = relative.substring(1);
        String[] parts = relative.split("/");
        if (parts.length < 2 || !"custom_instances".equals(parts[0])) {
            throw new IOException("The installed modpack profile points outside custom_instances.");
        }
        for (String part : parts) {
            if (part.trim().isEmpty() || ".".equals(part) || "..".equals(part) || part.contains(":")) {
                throw new IOException("The installed modpack profile points outside custom_instances.");
            }
        }
        File gameHome = minecraftRoot.getParentFile() == null ? minecraftRoot : minecraftRoot.getParentFile();
        File customRoot = new File(gameHome, "custom_instances").getCanonicalFile();
        File source = safeChild(gameHome, relative);
        if (!source.isDirectory() ||
                !source.getCanonicalPath().startsWith(customRoot.getPath() + File.separator)) {
            throw new IOException("The installed modpack data directory was not found.");
        }

        GameInstance created = createInternal(validateName(name), versionId, false, false);
        GameInstance completed;
        try {
            copyTree(source, created.getGameDirectory());
            File versionMetadata = new File(new File(new File(minecraftRoot, "versions"), versionId), versionId + ".json");
            String baseVersion = versionId;
            try {
                if (versionMetadata.isFile()) {
                    String inherits = new JSONObject(readText(versionMetadata)).optString("inheritsFrom").trim();
                    if (!inherits.isEmpty()) baseVersion = inherits;
                }
            } catch (Exception ignored) { }
            completed = new GameInstance(created.getId(), created.getName(), created.getVersionId(),
                    baseVersion, created.isPinned(), created.getLoaderId(), created.getLoaderVersion(),
                    created.getCreatedAt(), created.getGameDirectory(), created.getRendererId(),
                    created.getJavaArgs(), created.getSelectedRuntime(), created.getControlLayout(),
                    created.getArgsMode(), created.isSharedData());
            writeInstance(completed);
        } catch (Exception error) {
            try { deleteTree(instanceRoot(created.getId())); } catch (Exception ignored) { }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not adopt the installed modpack directory.", error);
        }
        select(completed.getId());
        try { deleteTree(source); } catch (Exception ignored) { }
        return completed;
    }

    private GameInstance createInternal(String name, String versionId, boolean pinned,
                                        boolean sharedData) throws IOException {
        ensureDirectory(instancesRoot);
        String id = UUID.randomUUID().toString();
        File folder = instanceRoot(id);
        ensureDirectory(folder);
        File game = sharedData ? sharedDataDirectory : new File(folder, "game");
        ensureGameFolders(game);
        GameInstance instance = new GameInstance(id, name, versionId, versionId, pinned,
                null, null, System.currentTimeMillis(), game, null, null, null, null, 0, sharedData);
        writeInstance(instance);
        return instance;
    }

    private GameInstance requireInstance(String id) throws IOException {
        GameInstance instance = readInstance(instanceRoot(id));
        if (instance == null) throw new IOException("Instance not found.");
        return instance;
    }

    private GameInstance readInstance(File folder) {
        File metadata = new File(folder, META_FILE);
        if (!metadata.isFile()) return null;
        try {
            JSONObject json = new JSONObject(readText(metadata));
            String id = json.getString("id");
            if (!safeSegment(id).equals(folder.getName())) return null;
            boolean sharedData = json.optBoolean("sharedData", false);
            File expectedGame = sharedData ? sharedDataDirectory : new File(folder, "game");
            File game = expectedGame.getCanonicalFile();
            File allowedRoot = (sharedData ? sharedDataDirectory : folder).getCanonicalFile();
            if (!game.getPath().startsWith(allowedRoot.getPath()) ||
                    (!sharedData && game.getPath().equals(allowedRoot.getPath()))) return null;
            String version = json.getString("versionId");
            requireSafeVersion(version);
            String baseVersion = json.optString("minecraftVersion", version);
            requireSafeVersion(baseVersion);
            return new GameInstance(id,
                    validateName(json.optString("name", "Minecraft instance")),
                    version, baseVersion, json.optBoolean("pinned", false),
                    nonBlank(json.optString("loaderId")), nonBlank(json.optString("loaderVersion")),
                    json.optLong("createdAt", folder.lastModified()), game,
                    nonBlank(json.optString("rendererId")), nonBlank(json.optString("javaArgs")),
                    nonBlank(json.optString("selectedRuntime")), nonBlank(json.optString("controlLayout")),
                    Math.max(0, Math.min(2, json.optInt("argsMode", 0))), sharedData);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeInstance(GameInstance instance) throws IOException {
        File folder = instanceRoot(instance.getId());
        ensureDirectory(folder);
        ensureGameFolders(instance.getGameDirectory());
        final String encoded;
        try {
            JSONObject json = new JSONObject()
                    .put("schemaVersion", 1)
                    .put("id", instance.getId())
                    .put("name", validateName(instance.getName()))
                    .put("versionId", safeVersion(instance.getVersionId()))
                    .put("minecraftVersion", safeVersion(instance.getMinecraftVersion()))
                    .put("pinned", instance.isPinned())
                    .put("createdAt", instance.getCreatedAt())
                    .put("loaderId", orEmpty(instance.getLoaderId()))
                    .put("loaderVersion", orEmpty(instance.getLoaderVersion()))
                    .put("rendererId", orEmpty(instance.getRendererId()))
                    .put("javaArgs", orEmpty(instance.getJavaArgs()))
                    .put("selectedRuntime", orEmpty(instance.getSelectedRuntime()))
                    .put("controlLayout", orEmpty(instance.getControlLayout()))
                    .put("argsMode", Math.max(0, Math.min(2, instance.getArgsMode())))
                    .put("sharedData", instance.isSharedData());
            encoded = json.toString(2);
        } catch (org.json.JSONException error) {
            throw new IOException("Could not encode game instance metadata.", error);
        }
        writeAtomically(new File(folder, META_FILE), encoded);
    }

    private void mergeStagedMinecraft(File staged, File gameDirectory) throws IOException {
        File[] children = staged.listFiles();
        if (children == null) throw new IOException("Could not read staged instance files.");
        for (File child : children) {
            File destination = SHARED_ROOTS.contains(child.getName())
                    ? new File(minecraftRoot, child.getName())
                    : new File(gameDirectory, child.getName());
            copyMissingTree(child, destination);
        }
    }

    private String resolveImportedVersionId(File stagedMinecraft, String vanillaVersion,
                                              LoaderComponent loader) {
        if (loader == null) return vanillaVersion;
        String candidate;
        switch (loader.id) {
            case "net.fabricmc.fabric-loader":
                candidate = "fabric-loader-" + loader.version + "-" + vanillaVersion; break;
            case "org.quiltmc.quilt-loader":
                candidate = "quilt-loader-" + loader.version + "-" + vanillaVersion; break;
            case "net.minecraftforge":
                candidate = vanillaVersion + "-forge-" + loader.version; break;
            case "net.neoforged.neoforge":
                candidate = "neoforge-" + loader.version; break;
            default: return vanillaVersion;
        }
        File customVersion = new File(new File(stagedMinecraft, "versions"), candidate);
        return new File(customVersion, candidate + ".json").isFile() ? candidate : vanillaVersion;
    }

    private LoaderComponent findLoader(JSONArray components) {
        Set<String> supported = new HashSet<>(Arrays.asList(
                "net.fabricmc.fabric-loader", "org.quiltmc.quilt-loader",
                "net.minecraftforge", "net.neoforged.neoforge"));
        for (int i = 0; i < components.length(); i++) {
            JSONObject item = components.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("uid");
            String version = item.optString("version");
            if (supported.contains(id) && !version.trim().isEmpty()) return new LoaderComponent(id, version);
        }
        return null;
    }

    private String componentVersion(JSONArray components, String uid) {
        for (int i = 0; i < components.length(); i++) {
            JSONObject item = components.optJSONObject(i);
            if (item != null && uid.equals(item.optString("uid"))) return nonBlank(item.optString("version"));
        }
        return null;
    }

    private ZipEntry findEntry(ZipFile zip, String name) {
        ZipEntry direct = zip.getEntry(name);
        if (direct != null) return direct;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().endsWith("/" + name)) return entry;
        }
        return null;
    }

    private String findMinecraftPrefix(ZipFile zip, String prefix) {
        List<String> candidates = Arrays.asList(prefix + ".minecraft/", prefix + "minecraft/");
        for (String base : candidates) {
            if (zip.getEntry(base) != null) return base;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith(base)) return base;
            }
        }
        return null;
    }

    private Map<String, String> parseConfig(String text) {
        Map<String, String> values = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        return values;
    }

    private void ensureGameFolders(File game) throws IOException {
        String[] names = {"mods", "config", "saves", "resourcepacks", "shaderpacks", "screenshots", "logs"};
        for (String name : names) ensureDirectory(new File(game, name));
    }

    private void copyMissingTree(File source, File destination) throws IOException {
        if (!source.exists()) return;
        if (source.isDirectory()) {
            if (destination.exists() && !destination.isDirectory()) return;
            ensureDirectory(destination);
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Cannot read " + source.getAbsolutePath());
            for (File child : children) copyMissingTree(child, new File(destination, child.getName()));
        } else if (source.isFile() && !destination.exists()) {
            File parent = destination.getParentFile();
            if (parent == null) throw new IOException("Missing destination parent.");
            ensureDirectory(parent);
            copyFile(source, destination, false);
        }
    }

    private void copyTree(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            ensureDirectory(destination);
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Cannot read " + source.getAbsolutePath());
            for (File child : children) copyTree(child, new File(destination, child.getName()));
        } else if (source.isFile()) {
            File parent = destination.getParentFile();
            if (parent == null) throw new IOException("Missing destination parent.");
            ensureDirectory(parent);
            copyFile(source, destination, false);
        }
    }

    private void addTreeToZip(ZipOutputStream zip, File source, String zipRoot) throws IOException {
        File base = source.getCanonicalFile();
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty()) {
            File current = queue.removeFirst();
            File canonical = current.getCanonicalFile();
            if (!canonical.getPath().equals(base.getPath()) &&
                    !canonical.getPath().startsWith(base.getPath() + File.separator)) {
                throw new IOException("Refusing to export a path outside the instance directory.");
            }
            String relative = relativePath(source, current);
            if (current.isDirectory()) {
                if (!relative.isEmpty()) {
                    zip.putNextEntry(new ZipEntry((zipRoot + "/" + relative + "/").replace("//", "/")));
                    zip.closeEntry();
                }
                File[] children = current.listFiles();
                if (children == null) throw new IOException("Cannot read " + current.getAbsolutePath());
                for (File child : children) queue.add(child);
            } else if (current.isFile()) {
                String entry = relative.isEmpty() ? zipRoot : zipRoot + "/" + relative;
                zip.putNextEntry(new ZipEntry(entry));
                try (FileInputStream input = new FileInputStream(current)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
            }
        }
    }

    private static String relativePath(File root, File child) throws IOException {
        String base = root.getCanonicalPath();
        String path = child.getCanonicalPath();
        if (path.equals(base)) return "";
        if (!path.startsWith(base + File.separator)) throw new IOException("Path escapes export root.");
        return path.substring(base.length() + 1).replace(File.separatorChar, '/');
    }

    private void addTextEntry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private File safeChild(File parent, String relative) throws IOException {
        File root = parent.getCanonicalFile();
        File child = new File(parent, relative).getCanonicalFile();
        if (!child.getPath().equals(root.getPath()) &&
                !child.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("Archive path escapes the import directory.");
        }
        return child;
    }

    private String safeRelative(String value) throws IOException {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        String[] parts = normalized.split("/", -1);
        if (normalized.trim().isEmpty()) throw new IOException("The archive contains an unsafe file path.");
        for (String part : parts) {
            if (part.trim().isEmpty() || ".".equals(part) || "..".equals(part) || part.contains(":")) {
                throw new IOException("The archive contains an unsafe file path.");
            }
        }
        return join(Arrays.asList(parts), File.separator);
    }

    private File instanceRoot(String id) throws IOException {
        File folder = new File(instancesRoot, safeSegment(id)).getCanonicalFile();
        File base = instancesRoot.getCanonicalFile();
        if (!folder.getPath().startsWith(base.getPath() + File.separator)) {
            throw new IOException("Invalid instance ID.");
        }
        return folder;
    }

    private String optionalIdentifier(String value, String label, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLength || !trimmed.matches("[A-Za-z0-9._+-]+") ||
                ".".equals(trimmed) || "..".equals(trimmed)) {
            throw new IllegalArgumentException("Invalid " + label + ".");
        }
        return trimmed;
    }

    private String safeSegment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._+-]{1,100}") ||
                ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("Invalid instance ID.");
        }
        return value;
    }

    private String validateName(String value) {
        String name = value == null ? "" : value.trim().replaceAll("[\\r\\n\\t]", " ");
        if (name.trim().isEmpty() || name.length() > 48) {
            throw new IllegalArgumentException("Instance name must contain 1–48 characters.");
        }
        return name;
    }

    private void requireSafeVersion(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._+-]{1,100}")) {
            throw new IllegalArgumentException("Invalid Minecraft version ID.");
        }
    }

    private String safeVersion(String value) {
        requireSafeVersion(value);
        return value;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String nonBlank(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
    private static String orEmpty(String value) { return value == null ? "" : value; }
    private static String stripJson(String value) {
        return value != null && value.endsWith(".json") ? value.substring(0, value.length() - 5) : value;
    }
    private static boolean notBlank(String value) { return value != null && !value.trim().isEmpty(); }
    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private void ensureDirectory(File directory) throws IOException {
        if (directory == null) throw new IOException("Invalid directory path.");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create " + directory.getAbsolutePath());
        if (!directory.isDirectory()) throw new IOException("Expected a directory: " + directory.getAbsolutePath());
    }

    private void writeAtomically(File destination, String text) throws IOException {
        File parent = destination.getParentFile();
        if (parent == null) throw new IOException("Invalid metadata destination.");
        ensureDirectory(parent);
        File temporary = new File(parent, destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not save instance metadata.");
        }
    }

    private static String readZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        return readZipEntry(zip, entry, MAX_UNCOMPRESSED_BYTES);
    }

    private static String readZipEntry(ZipFile zip, ZipEntry entry, long maximumBytes) throws IOException {
        try (java.io.InputStream input = zip.getInputStream(entry);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new IOException("Archive metadata exceeds its size limit.");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        File[] children = root.listFiles();
        if (root.isDirectory() && children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!root.delete()) throw new IOException("Could not remove " + root.getAbsolutePath());
    }

    private static void copyFile(File source, File destination, boolean overwrite) throws IOException {
        if (destination.exists() && !overwrite) return;
        File parent = destination.getParentFile();
        if (parent != null) ensureStaticDirectory(parent);
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static void ensureStaticDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create " + directory);
        if (!directory.isDirectory()) throw new IOException("Expected a directory: " + directory);
    }

    private static final class LoaderComponent {
        final String id;
        final String version;
        LoaderComponent(String id, String version) { this.id = id; this.version = version; }
    }
}
