package dev.arnv.bluke.bluetooth

import org.junit.Assert.*
import org.junit.Test

class HogpGattTest {
    @Test fun uuidsAreBluetoothSigBaseUuids() {
        assertEquals("00001812-0000-1000-8000-00805f9b34fb", HogpGatt.HID_SERVICE.toString())
        assertEquals("00002a4d-0000-1000-8000-00805f9b34fb", HogpGatt.REPORT.toString())
        assertEquals("00002908-0000-1000-8000-00805f9b34fb", HogpGatt.REPORT_REFERENCE.toString())
    }

    @Test fun reportMapsReuseTheClassicDescriptors() {
        assertArrayEquals(SHARED_HID_DESCRIPTOR, BleReportMap.FULL.descriptor)
        assertEquals(65, KEYBOARD_ONLY_HID_DESCRIPTOR.size)
        assertArrayEquals(SHARED_HID_DESCRIPTOR.copyOfRange(0, 65), KEYBOARD_ONLY_HID_DESCRIPTOR)
        assertEquals(listOf(1, 2, 3, 4), BleReportMap.FULL.inputReportIds)
        assertEquals(listOf(1), BleReportMap.KEYBOARD_ONLY.inputReportIds)
        assertEquals(1, BleReportMap.FULL.referenceId(1))
        assertEquals(0, BleReportMap.KEYBOARD_ONLY.referenceId(1))
        assertEquals(BleReportMap.KEYBOARD_ONLY, BleReportMap.fromPreference(null))
        assertEquals(BleReportMap.KEYBOARD_ONLY, BleReportMap.fromPreference("garbage"))
        assertEquals(BleReportMap.FULL, BleReportMap.fromPreference("full"))
        assertEquals(BleReportMap.KEYBOARD_ONLY, BleReportMap.fromPreference("keyboard_only"))
    }

    @Test fun keyboardOnlyMapHasNoReportIdAndBootLayout() {
        val map = BleReportMap.KEYBOARD_ONLY.descriptor
        assertEquals(63, map.size)
        assertArrayEquals(KEYBOARD_ONLY_HID_DESCRIPTOR.copyOfRange(0, 6), map.copyOfRange(0, 6))
        assertArrayEquals(KEYBOARD_ONLY_HID_DESCRIPTOR.copyOfRange(8, 65), map.copyOfRange(6, 63))
        // No REPORT_ID item anywhere (0x85 as an item prefix).
        var offset = 0
        while (offset < map.size) {
            val prefix = map[offset].toInt() and 0xff
            assertNotEquals("REPORT_ID at $offset", 0x85, prefix)
            offset += 1 + when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
        }
        assertEquals(0xc0.toByte(), map.last())
    }

    @Test fun descriptorCopiesAreIndependent() {
        BleReportMap.FULL.descriptor.fill(0)
        assertEquals(0x05.toByte(), BleReportMap.FULL.descriptor[0])
    }

    @Test fun hidInformationAndPnpIdAreWellFormed() {
        assertArrayEquals(byteArrayOf(0x11, 0x01, 0x00, 0x03), HogpGatt.HID_INFORMATION_VALUE)
        assertEquals(7, HogpGatt.PNP_ID_VALUE.size)
        assertEquals(0x02.toByte(), HogpGatt.PNP_ID_VALUE[0]) // vendor ID source: USB
        val vid = (HogpGatt.PNP_ID_VALUE[1].toInt() and 0xff) or ((HogpGatt.PNP_ID_VALUE[2].toInt() and 0xff) shl 8)
        assertNotEquals(0x05AC, vid) // never Apple's vendor ID
        assertArrayEquals(byteArrayOf(0x01, 0x01), HogpGatt.reportReference(1, HogpGatt.REPORT_TYPE_INPUT))
        assertArrayEquals(byteArrayOf(0x01, 0x02), HogpGatt.reportReference(1, HogpGatt.REPORT_TYPE_OUTPUT))
    }

    @Test fun longReadsSliceFromOffsetAndRejectPastEnd() {
        val value = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(value, HogpGatt.readSlice(value, 0))
        assertArrayEquals(byteArrayOf(3, 4), HogpGatt.readSlice(value, 2))
        assertArrayEquals(ByteArray(0), HogpGatt.readSlice(value, 4))
        assertNull(HogpGatt.readSlice(value, 5))
        assertNull(HogpGatt.readSlice(value, -1))
    }

    @Test fun clientConfigEnableBitParsing() {
        assertTrue(HogpGatt.isNotificationEnable(byteArrayOf(0x01, 0x00)))
        assertTrue(HogpGatt.isNotificationEnable(byteArrayOf(0x03, 0x00)))
        assertFalse(HogpGatt.isNotificationEnable(byteArrayOf(0x00, 0x00)))
        assertFalse(HogpGatt.isNotificationEnable(byteArrayOf(0x02, 0x00))) // indications only
        assertFalse(HogpGatt.isNotificationEnable(null))
        assertFalse(HogpGatt.isNotificationEnable(ByteArray(0)))
    }

    @Test fun queueSendsFirstReportImmediatelyAndHoldsTheRest() {
        val queue = BleNotificationQueue()
        val a = PendingBleReport(1, byteArrayOf(1))
        val b = PendingBleReport(1, byteArrayOf(2))
        assertSame(a, queue.offer(a))
        assertTrue(queue.isInFlight)
        assertNull(queue.offer(b))
        assertEquals(1, queue.size)
        assertSame(b, queue.sent())
        assertNull(queue.sent())
        assertFalse(queue.isInFlight)
    }

    @Test fun queueKeepsKeyboardOrderAndDropsPointerFirstWhenFull() {
        val queue = BleNotificationQueue(capacity = 3)
        queue.offer(PendingBleReport(1, byteArrayOf(0)))         // in flight
        queue.offer(PendingBleReport(2, byteArrayOf(1)))         // queued mouse
        queue.offer(PendingBleReport(1, byteArrayOf(2)))         // queued key press
        queue.offer(PendingBleReport(1, byteArrayOf(3)))         // queued key release -> full
        queue.offer(PendingBleReport(1, byteArrayOf(4)))         // drops the mouse report
        val drained = generateSequence { queue.sent() }.map { it.value[0].toInt() }.toList()
        assertEquals(listOf(2, 3, 4), drained)
    }

    @Test fun queueDropsOldestKeyboardOnlyWhenNothingElseRemains() {
        val queue = BleNotificationQueue(capacity = 2)
        queue.offer(PendingBleReport(1, byteArrayOf(0)))
        queue.offer(PendingBleReport(1, byteArrayOf(1)))
        queue.offer(PendingBleReport(1, byteArrayOf(2)))
        queue.offer(PendingBleReport(1, byteArrayOf(3)))
        val drained = generateSequence { queue.sent() }.map { it.value[0].toInt() }.toList()
        assertEquals(listOf(2, 3), drained)
    }

    @Test fun failedSendFreesTheSlot() {
        val queue = BleNotificationQueue()
        queue.offer(PendingBleReport(1, byteArrayOf(0)))
        val next = PendingBleReport(2, byteArrayOf(1))
        queue.offer(next)
        assertSame(next, queue.failed())
        queue.clear()
        assertFalse(queue.isInFlight)
        assertEquals(0, queue.size)
    }

    @Test fun hostAttachedOnlyWhenConnected() {
        assertTrue(BleHidState.Connected("AA", null, subscribed = false).isHostAttached())
        assertFalse(BleHidState.Advertising("Bluke").isHostAttached())
        assertFalse(BleHidState.Disabled.isHostAttached())
    }
}
