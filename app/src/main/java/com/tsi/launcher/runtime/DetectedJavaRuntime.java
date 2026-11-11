package com.tsi.launcher.runtime;

import java.io.File;

public final class DetectedJavaRuntime {
    private final String name;
    private final int majorVersion;
    private final String version;
    private final String architecture;
    private final File home;
    private final File jvmLibrary;

    public DetectedJavaRuntime(String name, int majorVersion, String version,
                               String architecture, File home, File jvmLibrary) {
        this.name = name;
        this.majorVersion = majorVersion;
        this.version = version;
        this.architecture = architecture;
        this.home = home;
        this.jvmLibrary = jvmLibrary;
    }

    public String getName() { return name; }
    public int getMajorVersion() { return majorVersion; }
    public String getVersion() { return version; }
    public String getArchitecture() { return architecture; }
    public File getHome() { return home; }
    public File getJvmLibrary() { return jvmLibrary; }
}
