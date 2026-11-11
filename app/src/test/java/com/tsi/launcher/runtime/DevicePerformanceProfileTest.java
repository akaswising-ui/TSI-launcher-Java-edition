package com.tsi.launcher.runtime;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DevicePerformanceProfileTest {
    @Test public void twoGigabytePhoneGetsLowMemorySettings() {
        DevicePerformanceProfileSnapshot p = DevicePerformanceProfile.profileFor(
                2048, false, true, 8, "arm64-v8a");
        assertEquals(296, p.getMinimumHeapMb());
        assertEquals(384, p.getRecommendedHeapMb());
        assertEquals(512, p.getMaximumHeapMb());
        assertEquals(2, p.getWorkerThreads());
        assertEquals(4, p.getRenderDistance());
        assertEquals(3, p.getSimulationDistance());
        assertEquals(30, p.getMaxFps());
    }

    @Test public void androidLowRamFlagOverridesAdvertisedMemoryTier() {
        DevicePerformanceProfileSnapshot p = DevicePerformanceProfile.profileFor(
                4096, true, true, 8, "arm64-v8a");
        assertEquals("Low-memory", p.getTierLabel());
        assertEquals(384, p.getRecommendedHeapMb());
        assertEquals(2, p.getWorkerThreads());
    }

    @Test public void threeGigabytePhoneGetsEntryProfile() {
        DevicePerformanceProfileSnapshot p = DevicePerformanceProfile.profileFor(
                3072, false, true, 4, "arm64-v8a");
        assertEquals(512, p.getRecommendedHeapMb());
        assertEquals(768, p.getMaximumHeapMb());
        assertEquals(2, p.getWorkerThreads());
        assertEquals(5, p.getRenderDistance());
    }

    @Test public void sixGigabytePhoneGetsPerformanceProfile() {
        DevicePerformanceProfileSnapshot p = DevicePerformanceProfile.profileFor(
                6144, false, true, 8, "arm64-v8a");
        assertEquals(1024, p.getRecommendedHeapMb());
        assertEquals(1536, p.getMaximumHeapMb());
        assertEquals(3, p.getWorkerThreads());
        assertEquals(8, p.getRenderDistance());
    }

    @Test public void thirtyTwoBitProcessGetsSmallerAddressSpaceLimit() {
        DevicePerformanceProfileSnapshot p = DevicePerformanceProfile.profileFor(
                6144, false, false, 8, "armeabi-v7a");
        assertEquals(1024, p.getMaximumHeapMb());
        assertEquals(1024, p.getRecommendedHeapMb());
    }

    @Test public void workerCountNeverExceedsAvailableCpuCores() {
        DevicePerformanceProfileSnapshot p = DevicePerformanceProfile.profileFor(
                8192, false, true, 1, "arm64-v8a");
        assertEquals(1, p.getWorkerThreads());
    }
}
