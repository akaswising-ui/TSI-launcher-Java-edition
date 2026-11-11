package com.tsi.launcher.instances;

import java.io.File;

/** Persistent game profile and its per-instance launch configuration. */
public final class GameInstance {
    private final String id;
    private final String name;
    private final String versionId;
    private final String minecraftVersion;
    private final boolean pinned;
    private final String loaderId;
    private final String loaderVersion;
    private final long createdAt;
    private final File gameDirectory;
    private final String rendererId;
    private final String javaArgs;
    private final String selectedRuntime;
    private final String controlLayout;
    private final int argsMode;
    private final boolean sharedData;

    public GameInstance(String id, String name, String versionId, String minecraftVersion,
                        boolean pinned, String loaderId, String loaderVersion, long createdAt,
                        File gameDirectory, String rendererId, String javaArgs,
                        String selectedRuntime, String controlLayout, int argsMode,
                        boolean sharedData) {
        this.id = id;
        this.name = name;
        this.versionId = versionId;
        this.minecraftVersion = minecraftVersion;
        this.pinned = pinned;
        this.loaderId = loaderId;
        this.loaderVersion = loaderVersion;
        this.createdAt = createdAt;
        this.gameDirectory = gameDirectory;
        this.rendererId = rendererId;
        this.javaArgs = javaArgs;
        this.selectedRuntime = selectedRuntime;
        this.controlLayout = controlLayout;
        this.argsMode = argsMode;
        this.sharedData = sharedData;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getVersionId() { return versionId; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public boolean isPinned() { return pinned; }
    public String getLoaderId() { return loaderId; }
    public String getLoaderVersion() { return loaderVersion; }
    public long getCreatedAt() { return createdAt; }
    public File getGameDirectory() { return gameDirectory; }
    public String getRendererId() { return rendererId; }
    public String getJavaArgs() { return javaArgs; }
    public String getSelectedRuntime() { return selectedRuntime; }
    public String getControlLayout() { return controlLayout; }
    public int getArgsMode() { return argsMode; }
    public boolean isSharedData() { return sharedData; }
}
