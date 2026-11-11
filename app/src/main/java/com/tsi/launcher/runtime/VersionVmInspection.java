package com.tsi.launcher.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VersionVmInspection {
    private final String versionId;
    private final boolean clientJarExists;
    private final boolean metadataExists;
    private final Integer requiredJavaMajor;
    private final int libraryCount;
    private final int installedLibraryCount;
    private final String assetIndexId;
    private final boolean assetIndexExists;
    private final int assetObjectCount;
    private final int assetObjectTotal;
    private final DetectedJavaRuntime compatibleRuntime;
    private final List<DetectedJavaRuntime> detectedRuntimes;
    private final boolean engineBridgeAvailable;
    private final List<String> blockers;

    public VersionVmInspection(String versionId, boolean clientJarExists, boolean metadataExists,
            Integer requiredJavaMajor, int libraryCount, int installedLibraryCount,
            String assetIndexId, boolean assetIndexExists, int assetObjectCount,
            int assetObjectTotal, DetectedJavaRuntime compatibleRuntime,
            List<DetectedJavaRuntime> detectedRuntimes, boolean engineBridgeAvailable,
            List<String> blockers) {
        this.versionId = versionId;
        this.clientJarExists = clientJarExists;
        this.metadataExists = metadataExists;
        this.requiredJavaMajor = requiredJavaMajor;
        this.libraryCount = libraryCount;
        this.installedLibraryCount = installedLibraryCount;
        this.assetIndexId = assetIndexId;
        this.assetIndexExists = assetIndexExists;
        this.assetObjectCount = assetObjectCount;
        this.assetObjectTotal = assetObjectTotal;
        this.compatibleRuntime = compatibleRuntime;
        this.detectedRuntimes = Collections.unmodifiableList(new ArrayList<>(detectedRuntimes));
        this.engineBridgeAvailable = engineBridgeAvailable;
        this.blockers = Collections.unmodifiableList(new ArrayList<>(blockers));
    }

    public String getVersionId() { return versionId; }
    public boolean isClientJarExists() { return clientJarExists; }
    public boolean isMetadataExists() { return metadataExists; }
    public Integer getRequiredJavaMajor() { return requiredJavaMajor; }
    public int getLibraryCount() { return libraryCount; }
    public int getInstalledLibraryCount() { return installedLibraryCount; }
    public String getAssetIndexId() { return assetIndexId; }
    public boolean isAssetIndexExists() { return assetIndexExists; }
    public int getAssetObjectCount() { return assetObjectCount; }
    public int getAssetObjectTotal() { return assetObjectTotal; }
    public DetectedJavaRuntime getCompatibleRuntime() { return compatibleRuntime; }
    public List<DetectedJavaRuntime> getDetectedRuntimes() { return detectedRuntimes; }
    public boolean isEngineBridgeAvailable() { return engineBridgeAvailable; }
    public List<String> getBlockers() { return blockers; }
    public boolean isReadyToLaunch() { return blockers.isEmpty(); }
}
