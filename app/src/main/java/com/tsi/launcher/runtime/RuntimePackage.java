package com.tsi.launcher.runtime;

public final class RuntimePackage {
    private final int javaMajor;
    private final String architecture;
    private final String url;
    private final long size;
    private final String sha256;

    public RuntimePackage(int javaMajor, String architecture, String url, long size, String sha256) {
        this.javaMajor = javaMajor;
        this.architecture = architecture;
        this.url = url;
        this.size = size;
        this.sha256 = sha256;
    }
    public int getJavaMajor() { return javaMajor; }
    public String getArchitecture() { return architecture; }
    public String getUrl() { return url; }
    public long getSize() { return size; }
    public String getSha256() { return sha256; }
}
