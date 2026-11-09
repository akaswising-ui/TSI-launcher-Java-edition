package com.tsi.launcher.runtime

import android.app.ActivityManager
import android.content.Context

/**
 * Conservative Minecraft heap budget based on physical Android RAM.
 *
 * Android reports totalMem without marketing "extended RAM" additions. Keep
 * enough memory for the launcher, Android services, graphics buffers and the
 * embedded JVM/native engine instead of assigning almost all device RAM to
 * the Java heap.
 */
object DevicePerformanceProfile {
    fun totalPhysicalRamMb(context: Context): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 4096
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return (info.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(1024)
    }

    fun recommendedHeapMb(context: Context): Int =
        recommendedHeapMbFor(totalPhysicalRamMb(context))

    fun maxHeapMb(context: Context): Int =
        maxHeapMbFor(totalPhysicalRamMb(context))

    fun clampHeap(context: Context, requestedMb: Int): Int =
        clampHeapFor(totalPhysicalRamMb(context), requestedMb)

    /** Pure helpers keep the RAM policy testable without an Android device. */
    internal fun recommendedHeapMbFor(totalMb: Int): Int = when {
        totalMb >= 7680 -> 2048
        totalMb >= 4608 -> 1536
        totalMb >= 3584 -> 1024
        totalMb >= 2816 -> 768
        else -> 512
    }

    internal fun maxHeapMbFor(totalMb: Int): Int = when {
        totalMb >= 7680 -> 3072
        totalMb >= 4608 -> 2048
        totalMb >= 3584 -> 1536
        totalMb >= 2816 -> 1024
        else -> 768
    }

    internal fun clampHeapFor(totalMb: Int, requestedMb: Int): Int =
        requestedMb.coerceIn(512, maxHeapMbFor(totalMb))
}
