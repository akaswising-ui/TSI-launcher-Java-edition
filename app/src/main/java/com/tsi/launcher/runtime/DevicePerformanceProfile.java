package com.tsi.launcher.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import java.util.Locale;

/** Hardware-based defaults for the embedded Android JVM and game engine. */
public final class DevicePerformanceProfile {
    private DevicePerformanceProfile() { }

    public static DevicePerformanceProfileSnapshot detect(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memoryInfo);
        int physicalRam = memoryInfo.totalMem > 0L
                ? Math.max(1024, (int) (memoryInfo.totalMem / (1024L * 1024L)))
                : 4096;
        boolean lowRam = manager != null ? manager.isLowRamDevice() : physicalRam <= 2560;
        boolean is64Bit = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? Process.is64Bit()
                : Build.SUPPORTED_64_BIT_ABIS.length > 0;
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        return profileFor(physicalRam, lowRam, is64Bit, cores, abi);
    }

    public static int recommendedHeapMb(Context context) {
        return detect(context).getRecommendedHeapMb();
    }

    public static int minHeapMb(Context context) {
        return detect(context).getMinimumHeapMb();
    }

    public static int maxHeapMb(Context context) {
        return detect(context).getMaximumHeapMb();
    }

    public static int clampHeap(Context context, int requestedMb) {
        DevicePerformanceProfileSnapshot profile = detect(context);
        return Math.max(profile.getMinimumHeapMb(),
                Math.min(profile.getMaximumHeapMb(), requestedMb));
    }

    /** Stable hardware key, versioned so changed tuning defaults are applied once. */
    public static String profileKey(Context context) {
        DevicePerformanceProfileSnapshot p = detect(context);
        return String.join("|",
                "adaptive-v2",
                p.getTierLabel(),
                String.valueOf(p.getTotalPhysicalRamMb()),
                String.valueOf(p.isLowMemoryDevice()),
                String.valueOf(p.is64BitProcess()),
                String.valueOf(p.getCpuCores()),
                p.getPrimaryAbi());
    }

    /** Pure profile calculation for regression tests. */
    public static DevicePerformanceProfileSnapshot profileFor(
            int totalRamMb, boolean lowRamDevice, boolean is64BitProcess,
            int cpuCores, String primaryAbi) {
        int ram = Math.max(1024, totalRamMb);
        boolean lowMemory = lowRamDevice || ram < 2560;
        int cores = Math.max(1, cpuCores);

        Tuning defaults;
        if (lowMemory) {
            defaults = new Tuning("Low-memory", 296, 384, 512, 2, 4, 3, 30, 0);
        } else if (ram < 3584) {
            defaults = new Tuning("Entry", 384, 512, 768, 2, 5, 4, 45, 0);
        } else if (ram < 4608) {
            defaults = new Tuning("Balanced", 512, 768, 1024, 3, 6, 4, 60, 1);
        } else if (ram < 6656) {
            defaults = new Tuning("Performance", 512, 1024, 1536, 3, 8, 6, 60, 1);
        } else if (ram < 8192) {
            defaults = new Tuning("High", 768, 1536, 2048, 4, 10, 8, 90, 2);
        } else {
            defaults = new Tuning("Flagship", 1024, 2048, 3072, 4, 12, 10, 120, 2);
        }

        int processLimit = is64BitProcess ? 3072 : 1024;
        int maximum = Math.min(defaults.maximumHeapMb, processLimit);
        int minimum = Math.min(defaults.minimumHeapMb, maximum);
        int recommended = Math.max(minimum, Math.min(maximum, defaults.recommendedHeapMb));
        int workers = Math.max(1, Math.min(defaults.workerThreads,
                Math.min(cores, lowMemory ? 2 : 4)));

        return new DevicePerformanceProfileSnapshot(
                ram, lowMemory, is64BitProcess, cores,
                primaryAbi == null ? "unknown" : primaryAbi,
                defaults.label, minimum, recommended, maximum, workers,
                defaults.renderDistance, defaults.simulationDistance,
                defaults.maxFps, defaults.mipmapLevels);
    }

    private static final class Tuning {
        final String label;
        final int minimumHeapMb;
        final int recommendedHeapMb;
        final int maximumHeapMb;
        final int workerThreads;
        final int renderDistance;
        final int simulationDistance;
        final int maxFps;
        final int mipmapLevels;

        Tuning(String label, int minimumHeapMb, int recommendedHeapMb,
               int maximumHeapMb, int workerThreads, int renderDistance,
               int simulationDistance, int maxFps, int mipmapLevels) {
            this.label = label;
            this.minimumHeapMb = minimumHeapMb;
            this.recommendedHeapMb = recommendedHeapMb;
            this.maximumHeapMb = maximumHeapMb;
            this.workerThreads = workerThreads;
            this.renderDistance = renderDistance;
            this.simulationDistance = simulationDistance;
            this.maxFps = maxFps;
            this.mipmapLevels = mipmapLevels;
        }
    }
}
