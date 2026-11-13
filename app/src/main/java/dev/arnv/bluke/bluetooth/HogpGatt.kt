package dev.arnv.bluke.bluetooth

import java.util.UUID

/**
 * HID over GATT (HOGP) attribute definitions shared by the BLE server and its tests.
 *
 * Everything here is pure data so the GATT table can be checked without Android.
 */
internal object HogpGatt {
    private fun sig(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))

    val HID_SERVICE: UUID = sig(0x1812)
    val BATTERY_SERVICE: UUID = sig(0x180F)
    val DEVICE_INFORMATION_SERVICE: UUID = sig(0x180A)

    val HID_INFORMATION: UUID = sig(0x2A4A)
    val REPORT_MAP: UUID = sig(0x2A4B)
    val HID_CONTROL_POINT: UUID = sig(0x2A4C)
    val REPORT: UUID = sig(0x2A4D)
    val PROTOCOL_MODE: UUID = sig(0x2A4E)
    val BOOT_KEYBOARD_INPUT_REPORT: UUID = sig(0x2A22)
    val BOOT_KEYBOARD_OUTPUT_REPORT: UUID = sig(0x2A32)
    val BATTERY_LEVEL: UUID = sig(0x2A19)
    val MANUFACTURER_NAME: UUID = sig(0x2A29)
    val MODEL_NUMBER: UUID = sig(0x2A24)
    val PNP_ID: UUID = sig(0x2A50)

    val CLIENT_CHARACTERISTIC_CONFIGURATION: UUID = sig(0x2902)
    val REPORT_REFERENCE: UUID = sig(0x2908)

    const val REPORT_TYPE_INPUT: Byte = 0x01
    const val REPORT_TYPE_OUTPUT: Byte = 0x02

    const val PROTOCOL_MODE_BOOT: Byte = 0x00
    const val PROTOCOL_MODE_REPORT: Byte = 0x01

    const val CONTROL_POINT_SUSPEND: Byte = 0x00
    const val CONTROL_POINT_EXIT_SUSPEND: Byte = 0x01

    /** bcdHID 1.11, country code 0, flags: remote wake (bit 0) and normally connectable (bit 1), as common BLE keyboards advertise. */
    val HID_INFORMATION_VALUE: ByteArray = byteArrayOf(0x11, 0x01, 0x00, 0x03)

    /** Vendor ID source USB, a homebrew-style VID/PID, product version 1.0. Never an Apple identifier. */
    val PNP_ID_VALUE: ByteArray = byteArrayOf(0x02, 0xED.toByte(), 0xFE.toByte(), 0x4B, 0x4B, 0x00, 0x01)

    const val MANUFACTURER_NAME_VALUE = "Bluke"
    const val MODEL_NUMBER_VALUE = "Bluke HID"

    val NOTIFICATIONS_ENABLED: ByteArray = byteArrayOf(0x01, 0x00)
    val NOTIFICATIONS_DISABLED: ByteArray = byteArrayOf(0x00, 0x00)

    const val GATT_INVALID_OFFSET = 0x07
    const val GATT_INVALID_ATTRIBUTE_LENGTH = 0x0D

    fun reportReference(reportId: Int, type: Byte): ByteArray = byteArrayOf(reportId.toByte(), type)

    /** Slice an attribute value for a (possibly long) read, or null when the offset is past the end. */
    fun readSlice(value: ByteArray, offset: Int): ByteArray? =
        if (offset < 0 || offset > value.size) null else value.copyOfRange(offset, value.size)

    fun isNotificationEnable(value: ByteArray?): Boolean =
        value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
}

/**
 * Which collections the BLE report map advertises. The ID-free keyboard map is the default: it is
 * the one layout verified to type on iOS 9 (iPad 3), which recognises the composite map but
 * discards its reports. The composite map stays available as a developer experiment.
 */
internal enum class BleReportMap(val preferenceValue: String) {
    FULL("full"),
    /** Keyboard collection only, with no REPORT_ID item; its reports carry reference ID 0. */
    KEYBOARD_ONLY("keyboard_only");

    val descriptor: ByteArray
        get() = when (this) {
            FULL -> SHARED_HID_DESCRIPTOR.copyOf()
            KEYBOARD_ONLY -> KEYBOARD_ONLY_NO_ID_HID_DESCRIPTOR.copyOf()
        }

    /** The ID written into a Report Reference descriptor for an internal report ID. */
    fun referenceId(reportId: Int): Int = when (this) {
        FULL -> reportId
        KEYBOARD_ONLY -> 0
    }

    /** Report IDs, in map order, that the server exposes as Input Report characteristics. */
    val inputReportIds: List<Int>
        get() = when (this) {
            FULL -> listOf(KEYBOARD_REPORT_ID, MOUSE_REPORT_ID, GAMEPAD_REPORT_ID, CONSUMER_REPORT_ID)
            KEYBOARD_ONLY -> listOf(KEYBOARD_REPORT_ID)
        }

    companion object {
        val DEFAULT = KEYBOARD_ONLY

        fun fromPreference(value: String?): BleReportMap =
            entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}

internal const val KEYBOARD_REPORT_ID = 1
internal const val MOUSE_REPORT_ID = 2
internal const val GAMEPAD_REPORT_ID = 3
internal const val CONSUMER_REPORT_ID = 4

internal const val BLE_HID_ENABLED_PREFERENCE = "ble_hid_enabled"
internal const val BLE_REPORT_MAP_PREFERENCE = "ble_hid_report_map"

/** A report waiting for its notification slot. */
internal data class PendingBleReport(val reportId: Int, val value: ByteArray)

/**
 * Single-flight notification pacing: Android's GATT server accepts one outstanding notification
 * per connection, so reports queue until onNotificationSent. Keyboard reports are never dropped
 * because press/release ordering matters; when the queue is full the oldest pointer, gamepad or
 * consumer report is discarded first.
 */
internal class BleNotificationQueue(private val capacity: Int = 64) {
    private val pending = ArrayDeque<PendingBleReport>()
    private var inFlight = false

    val size: Int get() = pending.size
    val isInFlight: Boolean get() = inFlight

    /** Returns the report to send now, or null if one is already in flight (or nothing is queued). */
    fun offer(report: PendingBleReport): PendingBleReport? {
        if (pending.size >= capacity) {
            val victim = pending.indexOfFirst { it.reportId != KEYBOARD_REPORT_ID }
            if (victim >= 0) pending.removeAt(victim) else pending.removeFirst()
        }
        pending.addLast(report)
        return takeNext()
    }

    /** Called from onNotificationSent; returns the next report to send, if any. */
    fun sent(): PendingBleReport? {
        inFlight = false
        return takeNext()
    }

    /** The in-flight send failed synchronously; free the slot and move on. */
    fun failed(): PendingBleReport? = sent()

    fun clear() {
        pending.clear()
        inFlight = false
    }

    private fun takeNext(): PendingBleReport? {
        if (inFlight) return null
        val next = pending.removeFirstOrNull() ?: return null
        inFlight = true
        return next
    }
}

/** Connection summary the UI shows for the BLE transport. */
sealed interface BleHidState {
    data object Disabled : BleHidState
    data class Unsupported(val reason: String) : BleHidState
    data object Starting : BleHidState
    data class Advertising(val name: String) : BleHidState
    /** A central is connected; [subscribed] once it enabled notifications on an input report. */
    data class Connected(val deviceAddress: String, val deviceName: String?, val subscribed: Boolean) : BleHidState
    data class Error(val message: String) : BleHidState
}

internal fun BleHidState.isHostAttached(): Boolean = this is BleHidState.Connected
