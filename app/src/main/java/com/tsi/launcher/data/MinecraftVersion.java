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
