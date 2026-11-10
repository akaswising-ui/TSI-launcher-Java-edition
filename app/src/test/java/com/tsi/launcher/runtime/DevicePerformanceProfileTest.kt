package com.tsi.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePerformanceProfileTest {
    @Test
    fun twoGigabytePhoneGetsLowMemorySettings() {
        val profile = DevicePerformanceProfile.profileFor(
            totalRamMb = 2048,
            lowRamDevice = false,
            is64BitProcess = true,
            cpuCores = 8,
            primaryAbi = "arm64-v8a"
        )
        assertEquals(296, profile.minimumHeapMb)
        assertEquals(384, profile.recommendedHeapMb)
        assertEquals(512, profile.maximumHeapMb)
        assertEquals(2, profile.workerThreads)
        assertEquals(4, profile.renderDistance)
        assertEquals(3, profile.simulationDistance)
        assertEquals(30, profile.maxFps)
    }

    @Test
    fun androidLowRamFlagOverridesAdvertisedMemoryTier() {
        val profile = DevicePerformanceProfile.profileFor(
            totalRamMb = 4096,
            lowRamDevice = true,
            is64BitProcess = true,
            cpuCores = 8,
            primaryAbi = "arm64-v8a"
        )
        assertEquals("Low-memory", profile.tierLabel)
        assertEquals(384, profile.recommendedHeapMb)
        assertEquals(2, profile.workerThreads)
    }

    @Test
    fun threeGigabytePhoneGetsEntryProfile() {
        val profile = DevicePerformanceProfile.profileFor(
            totalRamMb = 3072,
            lowRamDevice = false,
            is64BitProcess = true,
            cpuCores = 4,
            primaryAbi = "arm64-v8a"
        )
        assertEquals(512, profile.recommendedHeapMb)
        assertEquals(768, profile.maximumHeapMb)
        assertEquals(2, profile.workerThreads)
        assertEquals(5, profile.renderDistance)
    }

    @Test
    fun sixGigabytePhoneGetsPerformanceProfile() {
        val profile = DevicePerformanceProfile.profileFor(
            totalRamMb = 6144,
            lowRamDevice = false,
            is64BitProcess = true,
            cpuCores = 8,
            primaryAbi = "arm64-v8a"
        )
        assertEquals(1024, profile.recommendedHeapMb)
        assertEquals(1536, profile.maximumHeapMb)
        assertEquals(3, profile.workerThreads)
        assertEquals(8, profile.renderDistance)
    }

    @Test
    fun thirtyTwoBitProcessGetsSmallerAddressSpaceLimit() {
        val profile = DevicePerformanceProfile.profileFor(
            totalRamMb = 6144,
            lowRamDevice = false,
            is64BitProcess = false,
            cpuCores = 8,
            primaryAbi = "armeabi-v7a"
        )
        assertEquals(1024, profile.maximumHeapMb)
        assertEquals(1024, profile.recommendedHeapMb)
    }

    @Test
    fun workerCountNeverExceedsAvailableCpuCores() {
        val profile = DevicePerformanceProfile.profileFor(
            totalRamMb = 8192,
            lowRamDevice = false,
            is64BitProcess = true,
            cpuCores = 1,
            primaryAbi = "arm64-v8a"
        )
        assertEquals(1, profile.workerThreads)
    }
}
