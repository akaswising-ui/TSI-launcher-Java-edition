package com.tsi.launcher.data;

public final class MinecraftLegacyAsset {
    private final String name;
    private final String hash;
    private final long size;

    public MinecraftLegacyAsset(String name, String hash, long size) {
        this.name = name;
        this.hash = hash;
        this.size = size;
    }
    public String getName() { return name; }
    public String getHash() { return hash; }
    public long getSize() { return size; }
}
