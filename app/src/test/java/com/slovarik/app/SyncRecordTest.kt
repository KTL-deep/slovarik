package com.slovarik.app

import org.junit.Assert.*
import org.junit.Test

class SyncRecordTest {
    private fun record(stamp: Long, device: String) = SyncRecord("row", "cards", stamp, device, false, emptyMap())

    @Test fun newerStampWinsRegardlessOfDeviceOrder() {
        assertTrue(compareSyncVersions(record(2, "a"), record(1, "z")) > 0)
        assertTrue(compareSyncVersions(record(1, "z"), record(2, "a")) < 0)
    }

    @Test fun concurrentVersionsUseStableDeviceTieBreak() {
        assertTrue(compareSyncVersions(record(1, "b"), record(1, "a")) > 0)
        assertTrue(compareSyncVersions(record(1, "a"), record(1, "b")) < 0)
        assertEquals(0, compareSyncVersions(record(1, "a"), record(1, "a")))
    }
}
