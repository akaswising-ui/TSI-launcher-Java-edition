package com.tsi.launcher.data;

import java.io.File;

public final class JavaGameInstallResult {
    private final String versionId;
    private final File clientJar;
    private final File metadataFile;
    private final int libraryCount;
    private final int assetCount;
    private final Integer requiredJavaMajor;
    private final String assetIndexId;

    public JavaGameInstallResult(String versionId, File clientJar, File metadataFile,
                                 int libraryCount, int assetCount,
                                 Integer requiredJavaMajor, String assetIndexId) {
        this.versionId = versionId;
        this.clientJar = clientJar;
        this.metadataFile = metadataFile;
        this.libraryCount = libraryCount;
        this.assetCount = assetCount;
        this.requiredJavaMajor = requiredJavaMajor;
        this.assetIndexId = assetIndexId;
    }

    public String getVersionId() { return versionId; }
    public File getClientJar() { return clientJar; }
    public File getMetadataFile() { return metadataFile; }
    public int getLibraryCount() { return libraryCount; }
    public int getAssetCount() { return assetCount; }
    public Integer getRequiredJavaMajor() { return requiredJavaMajor; }
    public String getAssetIndexId() { return assetIndexId; }
}
