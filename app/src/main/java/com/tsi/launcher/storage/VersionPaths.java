package com.tsi.launcher.storage;

import java.io.File;

public final class VersionPaths {
    private final File versionDirectory;
    private final File gameDirectory;
    private final File sharedAssetsDirectory;
    private final File sharedLibrariesDirectory;

    public VersionPaths(File versionDirectory, File gameDirectory,
                        File sharedAssetsDirectory, File sharedLibrariesDirectory) {
        this.versionDirectory = versionDirectory;
        this.gameDirectory = gameDirectory;
        this.sharedAssetsDirectory = sharedAssetsDirectory;
        this.sharedLibrariesDirectory = sharedLibrariesDirectory;
    }

    public File getVersionDirectory() { return versionDirectory; }
    public File getGameDirectory() { return gameDirectory; }
    public File getSharedAssetsDirectory() { return sharedAssetsDirectory; }
    public File getSharedLibrariesDirectory() { return sharedLibrariesDirectory; }
}
