package com.tsi.launcher.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Hardware-based defaults for the embedded Android JVM and game engine.
 *
 * The profile uses physical RAM, Android's low-RAM signal, process bitness and
 * CPU cores. It never assumes that advertised "extended RAM" is real memory.
 * User-selected heap values are retained on the same device, then recalculated
 * when the installed launcher is moved to a materially different device.
 */
data class DevicePerformanceProfileSnapshot(
    val totalPhysicalRamMb: Int,
    val lowMemoryDevice: Boolean,
    val is64BitProcess: Boolean,
    val cpuCores: Int,
    val primaryAbi: String,
    val tierLabel: String,
    val minimumHeapMb: Int,
    val recommendedHeapMb: Int,
    val maximumHeapMb: Int,
    val workerThreads: Int,
    val renderDistance: Int,
    val simulationDistance: Int,
    val maxFps: Int,
    val mipmapLevels: Int
)

object DevicePerformanceProfile {
    fun detect(context: Context): DevicePerformanceProfileSnapshot {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memoryInfo)
        val physicalRam = if (memoryInfo.totalMem > 0L) {
            (memoryInfo.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(1024)
        } else {
            4096
        }
        val lowRam = manager?.isLowRamDevice ?: (physicalRam <= 2560)
        val is64Bit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Process.is64Bit()
        } else {
            Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" }
        return profileFor(physicalRam, lowRam, is64Bit, cores, abi)
    }

    fun recommendedHeapMb(context: Context): Int = detect(context).recommendedHeapMb
    fun minHeapMb(context: Context): Int = detect(context).minimumHeapMb
    fun maxHeapMb(context: Context): Int = detect(context).maximumHeapMb

    fun clampHeap(context: Context, requestedMb: Int): Int {
        val profile = detect(context)
        return requestedMb.coerceIn(profile.minimumHeapMb, profile.maximumHeapMb)
    }

    /** Stable hardware key, versioned so changed tuning defaults are applied once. */
    fun profileKey(context: Context): String {
        val profile = detect(context)
        return listOf(
            "adaptive-v2",
            profile.tierLabel,
            profile.totalPhysicalRamMb,
            profile.lowMemoryDevice,
            profile.is64BitProcess,
            profile.cpuCores,
            profile.primaryAbi
        ).joinToString("|")
    }

    /** Pure profile calculation for regression tests. */
    internal fun profileFor(
        totalRamMb: Int,
        lowRamDevice: Boolean,
        is64BitProcess: Boolean,
        cpuCores: Int,
        primaryAbi: String
    ): DevicePerformanceProfileSnapshot {
        val ram = totalRamMb.coerceAtLeast(1024)
        val lowMemory = lowRamDevice || ram < 2560
        val cores = cpuCores.coerceAtLeast(1)

        val defaults = when {
            lowMemory -> Tuning("Low-memory", 296, 384, 512, 2, 4, 3, 30, 0)
            ram < 3584 -> Tuning("Entry", 384, 512, 768, 2, 5, 4, 45, 0)
            ram < 4608 -> Tuning("Balanced", 512, 768, 1024, 3, 6, 4, 60, 1)
            ram < 6656 -> Tuning("Performance", 512, 1024, 1536, 3, 8, 6, 60, 1)
            ram < 8192 -> Tuning("High", 768, 1536, 2048, 4, 10, 8, 90, 2)
            else -> Tuning("Flagship", 1024, 2048, 3072, 4, 12, 10, 120, 2)
        }

        // 32-bit processes have a smaller address space and more fragmentation.
        val processLimit = if (is64BitProcess) 3072 else 1024
        val maximum = minOf(defaults.maximumHeapMb, processLimit)
        val minimum = minOf(defaults.minimumHeapMb, maximum)
        val recommended = defaults.recommendedHeapMb.coerceIn(minimum, maximum)
        val workers = minOf(defaults.workerThreads, cores, if (lowMemory) 2 else 4).coerceAtLeast(1)

        return DevicePerformanceProfileSnapshot(
            totalPhysicalRamMb = ram,
            lowMemoryDevice = lowMemory,
            is64BitProcess = is64BitProcess,
            cpuCores = cores,
            primaryAbi = primaryAbi,
            tierLabel = defaults.label,
            minimumHeapMb = minimum,
            recommendedHeapMb = recommended,
            maximumHeapMb = maximum,
            workerThreads = workers,
            renderDistance = defaults.renderDistance,
            simulationDistance = defaults.simulationDistance,
            maxFps = defaults.maxFps,
            mipmapLevels = defaults.mipmapLevels
        )
    }

    private data class Tuning(
        val label: String,
        val minimumHeapMb: Int,
        val recommendedHeapMb: Int,
        val maximumHeapMb: Int,
        val workerThreads: Int,
        val renderDistance: Int,
        val simulationDistance: Int,
        val maxFps: Int,
        val mipmapLevels: Int
    )
}
