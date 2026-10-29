package com.slovarik.app

import org.junit.Assert.*
import org.junit.Test

class SyncWireTest {
    @Test fun nestedReminderNumbersMatchFirestoreAfterRoundTrip() {
        val local = mapOf("key" to "reminder", "value" to mapOf("enabled" to true, "hour" to 19, "minute" to 30))
        val cloud = mapOf("key" to "reminder", "value" to mapOf("enabled" to true, "hour" to 19L, "minute" to 30L))
        assertEquals(canonicalCloudValue(local), canonicalCloudValue(cloud))
    }

    @Test fun canonicalizationPreservesBooleanStringNullAndFloatingPoint() {
        assertEquals(listOf(true, "ru", null, 2.5, 0L), canonicalCloudValue(listOf(true, "ru", null, 2.5, 0)))
    }
}
