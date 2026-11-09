package com.tsi.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePerformanceProfileTest {
    @Test
    fun twoGigabytePhoneUsesLowMemoryHeapBudget() {
        assertEquals(512, DevicePerformanceProfile.recommendedHeapMbFor(2048))
        assertEquals(768, DevicePerformanceProfile.maxHeapMbFor(2048))
        assertEquals(768, DevicePerformanceProfile.clampHeapFor(2048, 1536))
    }

    @Test
    fun threeGigabytePhoneUsesModerateHeapBudget() {
        assertEquals(768, DevicePerformanceProfile.recommendedHeapMbFor(3072))
        assertEquals(1024, DevicePerformanceProfile.maxHeapMbFor(3072))
    }

    @Test
    fun sixGigabyteProfileRemainsUnchanged() {
        assertEquals(1536, DevicePerformanceProfile.recommendedHeapMbFor(6144))
        assertEquals(2048, DevicePerformanceProfile.maxHeapMbFor(6144))
    }

    @Test
    fun heapRequestsAreClampedToDeviceLimit() {
        assertEquals(512, DevicePerformanceProfile.clampHeapFor(2048, 128))
        assertEquals(768, DevicePerformanceProfile.clampHeapFor(2048, 4096))
    }
}