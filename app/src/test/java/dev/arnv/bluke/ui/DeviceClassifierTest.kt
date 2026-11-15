package dev.arnv.bluke.ui

import android.bluetooth.BluetoothClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceClassifierTest {
    private val computer = BluetoothClass.Device.Major.COMPUTER
    private val phone = BluetoothClass.Device.Major.PHONE
    private val peripheral = BluetoothClass.Device.Major.PERIPHERAL
    private val audioVideo = BluetoothClass.Device.Major.AUDIO_VIDEO

    @Test
    fun deviceClassWinsOverAnyName() {
        assertEquals(DeviceType.COMPUTER, classifyDevice("GameSpot", computer, null))
        assertEquals(DeviceType.COMPUTER, classifyDevice("Catkin", computer, null))
        assertEquals(DeviceType.COMPUTER, classifyDevice("Bandit-Speaker", computer, null))
        assertEquals(DeviceType.PHONE, classifyDevice("Xbox Fan", phone, null))
        assertEquals(DeviceType.MOUSE, classifyDevice("My PC", peripheral, null))
        assertEquals(DeviceType.GAMEPAD, classifyDevice("Wireless Controller", peripheral, null))
        assertEquals(DeviceType.AUDIO, classifyDevice("Laptop", audioVideo, null))
        assertEquals(
            DeviceType.TV,
            classifyDevice("Bedroom", audioVideo, BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX),
        )
    }

    @Test
    fun classifiedHostsAreSupported() {
        assertTrue(classifyDevice("GameSpot", computer, null).isSupported)
        assertTrue(classifyDevice("anything", phone, null).isSupported)
    }

    @Test
    fun nameKeywordsOnlyApplyWithoutAClassAndMatchWholeWords() {
        assertEquals(DeviceType.GAMEPAD, classifyDevice("Xbox Wireless Controller", null, null))
        assertEquals(DeviceType.MOUSE, classifyDevice("MX Master 3 Mouse", null, null))
        assertEquals(DeviceType.COMPUTER, classifyDevice("DESKTOP-ABC123", null, null))
        assertEquals(DeviceType.DEFAULT, classifyDevice("GameSpot", null, null))
        assertEquals(DeviceType.DEFAULT, classifyDevice("Catkin", null, null))
        assertEquals(DeviceType.DEFAULT, classifyDevice(null, null, null))
    }
}
