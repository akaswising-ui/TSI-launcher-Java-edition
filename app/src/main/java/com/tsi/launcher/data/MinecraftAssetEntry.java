package com.tsi.launcher.data;

public final class MinecraftAssetEntry {
    private final String name;
    private final String hash;

    public MinecraftAssetEntry(String name, String hash) {
        this.name = name;
        this.hash = hash;
    }
    public String getName() { return name; }
    public String getHash() { return hash; }
}
