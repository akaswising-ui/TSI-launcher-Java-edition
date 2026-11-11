package com.tsi.launcher.data;

public final class ClientDownloadResult {
    private final String versionId;
    private final String clientJarPath;
    private final String metadataPath;
    private final Integer requiredJavaMajor;

    public ClientDownloadResult(String versionId, String clientJarPath,
                                String metadataPath, Integer requiredJavaMajor) {
        this.versionId = versionId;
        this.clientJarPath = clientJarPath;
        this.metadataPath = metadataPath;
        this.requiredJavaMajor = requiredJavaMajor;
    }

    public String getVersionId() { return versionId; }
    public String getClientJarPath() { return clientJarPath; }
    public String getMetadataPath() { return metadataPath; }
    public Integer getRequiredJavaMajor() { return requiredJavaMajor; }
}
