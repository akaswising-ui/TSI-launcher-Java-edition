package com.tsi.launcher.runtime;

public final class DevicePerformanceProfileSnapshot {
    private final int totalPhysicalRamMb;
    private final boolean lowMemoryDevice;
    private final boolean is64BitProcess;
    private final int cpuCores;
    private final String primaryAbi;
    private final String tierLabel;
    private final int minimumHeapMb;
    private final int recommendedHeapMb;
    private final int maximumHeapMb;
    private final int workerThreads;
    private final int renderDistance;
    private final int simulationDistance;
    private final int maxFps;
    private final int mipmapLevels;

    public DevicePerformanceProfileSnapshot(
            int totalPhysicalRamMb, boolean lowMemoryDevice, boolean is64BitProcess,
            int cpuCores, String primaryAbi, String tierLabel, int minimumHeapMb,
            int recommendedHeapMb, int maximumHeapMb, int workerThreads,
            int renderDistance, int simulationDistance, int maxFps, int mipmapLevels) {
        this.totalPhysicalRamMb = totalPhysicalRamMb;
        this.lowMemoryDevice = lowMemoryDevice;
        this.is64BitProcess = is64BitProcess;
        this.cpuCores = cpuCores;
        this.primaryAbi = primaryAbi;
        this.tierLabel = tierLabel;
        this.minimumHeapMb = minimumHeapMb;
        this.recommendedHeapMb = recommendedHeapMb;
        this.maximumHeapMb = maximumHeapMb;
        this.workerThreads = workerThreads;
        this.renderDistance = renderDistance;
        this.simulationDistance = simulationDistance;
        this.maxFps = maxFps;
        this.mipmapLevels = mipmapLevels;
    }

    public int getTotalPhysicalRamMb() { return totalPhysicalRamMb; }
    public boolean isLowMemoryDevice() { return lowMemoryDevice; }
    public boolean is64BitProcess() { return is64BitProcess; }
    public int getCpuCores() { return cpuCores; }
    public String getPrimaryAbi() { return primaryAbi; }
    public String getTierLabel() { return tierLabel; }
    public int getMinimumHeapMb() { return minimumHeapMb; }
    public int getRecommendedHeapMb() { return recommendedHeapMb; }
    public int getMaximumHeapMb() { return maximumHeapMb; }
    public int getWorkerThreads() { return workerThreads; }
    public int getRenderDistance() { return renderDistance; }
    public int getSimulationDistance() { return simulationDistance; }
    public int getMaxFps() { return maxFps; }
    public int getMipmapLevels() { return mipmapLevels; }
}
