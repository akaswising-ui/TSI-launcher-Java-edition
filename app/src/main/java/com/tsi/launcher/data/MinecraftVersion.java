package com.tsi.launcher.data;

public final class MinecraftVersion {
    private final String id;
    private final String type;
    private final String url;
    private final String releaseTime;

    public MinecraftVersion(String id, String type, String url, String releaseTime) {
        this.id = id;
        this.type = type;
        this.url = url;
        this.releaseTime = releaseTime;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getUrl() { return url; }
    public String getReleaseTime() { return releaseTime; }
}

enum VersionFilter {
    ALL("All versions"),
    RELEASE("Release"),
    SNAPSHOT("Snapshot"),
    OLD_ALPHA("Old Alpha"),
    OLD_BETA("Old Beta");

    private final String label;

    VersionFilter(String label) { this.label = label; }

    public String getLabel() { return label; }

    public boolean accepts(String type) {
        switch (this) {
            case ALL: return true;
            case RELEASE: return "release".equalsIgnoreCase(type);
            case SNAPSHOT: return "snapshot".equalsIgnoreCase(type);
            case OLD_ALPHA: return "old_alpha".equalsIgnoreCase(type);
            case OLD_BETA: return "old_beta".equalsIgnoreCase(type);
            default: return false;
        }
    }
}

final class ClientDownloadResult {
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
