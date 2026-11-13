package dev.arnv.bluke.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dev.arnv.bluke.utils.DeveloperLogManager
import dev.arnv.bluke.utils.LogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * HID over GATT (HOGP) peripheral. Hosts that classify Bluetooth Classic input by Class of Device
 * (older iOS in particular) never accept an Android phone as a keyboard; over BLE the host reads
 * the HID service and report map instead, which the app fully controls.
 *
 * The server is independent of the classic HID device profile: it advertises, accepts one
 * central at a time, and delivers input reports as notifications. Reports arrive through
 * [sendInputReport]; output reports (keyboard LEDs) are forwarded to [onOutputReport].
 *
 * Characteristics are encrypted but do not require MITM protection: the iPad 3 and other
 * Bluetooth 4.0 hosts lack LE Secure Connections, and with legacy pairing two DisplayYesNo
 * devices can only do Just Works, so a MITM requirement would make pairing fail outright.
 */
@SuppressLint("MissingPermission")
internal class BleHidServer(
    private val context: Context,
    val reportMap: BleReportMap,
    private val onOutputReport: (reportId: Int, data: ByteArray) -> Unit,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<BleHidState>(BleHidState.Disabled)
    val state: StateFlow<BleHidState> = _state
    private val _connectedCentral = MutableStateFlow<BluetoothDevice?>(null)
    val connectedCentral: StateFlow<BluetoothDevice?> = _connectedCentral

    private val adapter: BluetoothAdapter? = try {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    } catch (_: Exception) {
        null
    }
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false
    private var closed = false

    private val servicesToAdd = ArrayDeque<BluetoothGattService>()
    private val inputReports = mutableMapOf<Int, BluetoothGattCharacteristic>()
    private var bootKeyboardInput: BluetoothGattCharacteristic? = null
    private var batteryLevel: BluetoothGattCharacteristic? = null
    /** Characteristics the current central enabled notifications on (identity-compared). */
    private val subscriptions = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<BluetoothGattCharacteristic, Boolean>())
    private val queue = BleNotificationQueue()
    private var protocolMode = HogpGatt.PROTOCOL_MODE_REPORT
    private var suspended = false
    private var central: BluetoothDevice? = null
    private val lastInputValues = mutableMapOf<Int, ByteArray>()

    private fun log(event: String, type: LogType = LogType.INFO) {
        if (!DeveloperLogManager.isEnabled) return
        val message = "t=${android.os.SystemClock.elapsedRealtime()} $event state=${_state.value} map=$reportMap"
        Log.d("BlukeBLE", message)
        DeveloperLogManager.log("BlukeBLE", message, type)
    }

    /** Opens the GATT server and starts advertising. Safe to call once per instance. */
    fun start() {
        synchronized(lock) {
            if (closed) return
            val adapter = adapter
            if (adapter == null || !adapter.isEnabled) {
                _state.value = BleHidState.Unsupported("Bluetooth is off.")
                return
            }
            if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) {
                _state.value = BleHidState.Unsupported("This phone has no Bluetooth LE radio.")
                return
            }
            val leAdvertiser = adapter.bluetoothLeAdvertiser
            if (leAdvertiser == null || !adapter.isMultipleAdvertisementSupported) {
                _state.value = BleHidState.Unsupported("This phone cannot act as a Bluetooth LE peripheral.")
                log("advertiser unavailable multipleAdvertisement=${adapter.isMultipleAdvertisementSupported}")
                return
            }
            advertiser = leAdvertiser
            _state.value = BleHidState.Starting
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val server = try {
                manager.openGattServer(context, gattCallback)
            } catch (e: Exception) {
                Log.e("BlukeBLE", "openGattServer failed", e)
                null
            }
            if (server == null) {
                _state.value = BleHidState.Error("Could not open the Bluetooth LE GATT server.")
                return
            }
            gattServer = server
            servicesToAdd.clear()
            servicesToAdd.addLast(buildHidService())
            servicesToAdd.addLast(buildBatteryService())
            servicesToAdd.addLast(buildDeviceInformationService())
            log("gatt server opened; adding ${servicesToAdd.size} services reportMapLength=${reportMap.descriptor.size}")
            addNextServiceLocked()
        }
    }

    /** Stops advertising, drops the central and closes the server. The instance cannot be restarted. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stopAdvertisingLocked()
            val server = gattServer
            central?.let { device ->
                try { server?.cancelConnection(device) } catch (_: Exception) {}
            }
            try { server?.clearServices() } catch (_: Exception) {}
            try { server?.close() } catch (_: Exception) {}
            gattServer = null
            central = null
            subscriptions.clear()
            queue.clear()
            _connectedCentral.value = null
            _state.value = BleHidState.Disabled
            log("closed")
        }
    }

    /** Drops the current central (the host may reconnect on its own). */
    fun disconnectCentral() {
        synchronized(lock) {
            val device = central ?: return
            log("disconnect requested device=${device.address}")
            try { gattServer?.cancelConnection(device) } catch (e: Exception) { Log.w("BlukeBLE", "cancelConnection failed", e) }
        }
    }

    val isSubscribed: Boolean
        get() = synchronized(lock) { central != null && subscriptions.isNotEmpty() }

    /**
     * Queue an input report for the connected central. Reports for IDs the map does not expose,
     * or while no central is subscribed, are dropped silently so callers need no transport checks.
     */
    fun sendInputReport(reportId: Int, value: ByteArray) {
        synchronized(lock) {
            lastInputValues[reportId] = value.copyOf()
            val device = central ?: return
            if (suspended) return
            val characteristic = targetCharacteristicLocked(reportId) ?: return
            if (characteristic !in subscriptions) return
            queue.offer(PendingBleReport(reportId, value.copyOf()))?.let { notifyLocked(device, it) }
        }
    }

    /** Pushes the phone battery level to a subscribed central. */
    fun updateBatteryLevel() {
        synchronized(lock) {
            val device = central ?: return
            val characteristic = batteryLevel ?: return
            if (characteristic !in subscriptions) return
            notifyValueLocked(device, characteristic, byteArrayOf(readBatteryPercent().toByte()))
        }
    }

    // ---- GATT table ---------------------------------------------------------------------------

    private fun buildHidService(): BluetoothGattService {
        val service = BluetoothGattService(HogpGatt.HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                HogpGatt.HID_INFORMATION,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                HogpGatt.REPORT_MAP,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                HogpGatt.HID_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                HogpGatt.PROTOCOL_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        for (id in reportMap.inputReportIds) {
            val input = BluetoothGattCharacteristic(
                HogpGatt.REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            )
            input.addDescriptor(clientConfigDescriptor())
            input.addDescriptor(
                BluetoothGattDescriptor(HogpGatt.REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED).apply {
                    value = HogpGatt.reportReference(reportMap.referenceId(id), HogpGatt.REPORT_TYPE_INPUT)
                },
            )
            service.addCharacteristic(input)
            inputReports[id] = input
        }
        // Keyboard LED output report (report ID 1, type output).
        val output = BluetoothGattCharacteristic(
            HogpGatt.REPORT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        output.addDescriptor(
            BluetoothGattDescriptor(HogpGatt.REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED).apply {
                value = HogpGatt.reportReference(reportMap.referenceId(KEYBOARD_REPORT_ID), HogpGatt.REPORT_TYPE_OUTPUT)
            },
        )
        service.addCharacteristic(output)
        // Boot protocol keyboard characteristics, for hosts that fall back to boot mode.
        val bootIn = BluetoothGattCharacteristic(
            HogpGatt.BOOT_KEYBOARD_INPUT_REPORT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )
        bootIn.addDescriptor(clientConfigDescriptor())
        service.addCharacteristic(bootIn)
        bootKeyboardInput = bootIn
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                HogpGatt.BOOT_KEYBOARD_OUTPUT_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        return service
    }

    private fun buildBatteryService(): BluetoothGattService {
        val service = BluetoothGattService(HogpGatt.BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val level = BluetoothGattCharacteristic(
            HogpGatt.BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        level.addDescriptor(clientConfigDescriptor())
        service.addCharacteristic(level)
        batteryLevel = level
        return service
    }

    private fun buildDeviceInformationService(): BluetoothGattService {
        val service = BluetoothGattService(HogpGatt.DEVICE_INFORMATION_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        for (uuid in listOf(HogpGatt.MANUFACTURER_NAME, HogpGatt.MODEL_NUMBER, HogpGatt.PNP_ID)) {
            service.addCharacteristic(
                BluetoothGattCharacteristic(uuid, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ),
            )
        }
        return service
    }

    private fun clientConfigDescriptor() = BluetoothGattDescriptor(
        HogpGatt.CLIENT_CHARACTERISTIC_CONFIGURATION,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
    )

    private fun addNextServiceLocked() {
        val server = gattServer ?: return
        val next = servicesToAdd.removeFirstOrNull()
        if (next == null) {
            startAdvertisingLocked()
            return
        }
        if (!server.addService(next)) {
            _state.value = BleHidState.Error("Android refused to add the ${next.uuid} GATT service.")
            log("addService refused uuid=${next.uuid}", LogType.ERROR)
        }
    }

    // ---- Advertising ------------------------------------------------------------------------

    private fun startAdvertisingLocked() {
        if (closed || advertising) return
        val leAdvertiser = advertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // The 31-byte advertisement carries flags and the HID service; the name rides in the
        // scan response so a long phone name cannot make the packet too large.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(HogpGatt.HID_SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        try {
            leAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            advertising = true
            log("advertising requested")
        } catch (e: Exception) {
            Log.e("BlukeBLE", "startAdvertising threw", e)
            _state.value = BleHidState.Error("Bluetooth LE advertising failed to start.")
        }
    }

    private fun stopAdvertisingLocked() {
        if (!advertising) return
        advertising = false
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        log("advertising stopped")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            synchronized(lock) {
                if (closed) return
                log("advertising started")
                if (central == null) _state.value = BleHidState.Advertising(adapter?.name ?: "Bluke")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            synchronized(lock) {
                advertising = false
                val reason = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertisement too large"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "already advertising"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                    else -> "error $errorCode"
                }
                log("advertising failed $reason", LogType.ERROR)
                if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                    advertising = true
                    return
                }
                _state.value = if (errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
                    BleHidState.Unsupported("This phone cannot advertise over Bluetooth LE.")
                } else {
                    BleHidState.Error("Bluetooth LE advertising failed: $reason.")
                }
            }
        }
    }

    // ---- GATT server callbacks ---------------------------------------------------------------

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            synchronized(lock) {
                if (closed) return
                log("service added uuid=${service.uuid} status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _state.value = BleHidState.Error("GATT service ${service.uuid} was rejected (status $status).")
                    return
                }
                addNextServiceLocked()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(lock) {
                if (closed) return
                log("connection device=${device.address} status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Android reports every LE connection to the phone; only the first one that
                        // talks to us is adopted. Others are left alone.
                        if (central == null) {
                            central = device
                            _connectedCentral.value = device
                            _state.value = BleHidState.Connected(device.address, device.name, subscribed = false)
                            stopAdvertisingLocked()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (central?.address == device.address) {
                            central = null
                            _connectedCentral.value = null
                            subscriptions.clear()
                            queue.clear()
                            protocolMode = HogpGatt.PROTOCOL_MODE_REPORT
                            suspended = false
                            _state.value = BleHidState.Starting
                            startAdvertisingLocked()
                        }
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value: ByteArray = synchronized(lock) {
                when (characteristic.uuid) {
                    HogpGatt.HID_INFORMATION -> HogpGatt.HID_INFORMATION_VALUE
                    HogpGatt.REPORT_MAP -> reportMap.descriptor
                    HogpGatt.PROTOCOL_MODE -> byteArrayOf(protocolMode)
                    HogpGatt.REPORT -> {
                        val id = inputReports.entries.firstOrNull { it.value === characteristic }?.key
                        if (id != null) lastInputValues[id] ?: ByteArray(reportLength(id)) else ByteArray(1)
                    }
                    HogpGatt.BOOT_KEYBOARD_INPUT_REPORT -> lastInputValues[KEYBOARD_REPORT_ID] ?: ByteArray(8)
                    HogpGatt.BOOT_KEYBOARD_OUTPUT_REPORT -> ByteArray(1)
                    HogpGatt.BATTERY_LEVEL -> byteArrayOf(readBatteryPercent().toByte())
                    HogpGatt.MANUFACTURER_NAME -> HogpGatt.MANUFACTURER_NAME_VALUE.toByteArray()
                    HogpGatt.MODEL_NUMBER -> HogpGatt.MODEL_NUMBER_VALUE.toByteArray()
                    HogpGatt.PNP_ID -> HogpGatt.PNP_ID_VALUE
                    else -> ByteArray(0)
                }
            }
            log("read device=${device.address} uuid=${characteristic.uuid} offset=$offset length=${value.size}")
            respond(device, requestId, offset, value)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val value = synchronized(lock) {
                when (descriptor.uuid) {
                    HogpGatt.CLIENT_CHARACTERISTIC_CONFIGURATION ->
                        if (central?.address == device.address && descriptor.characteristic in subscriptions) {
                            HogpGatt.NOTIFICATIONS_ENABLED
                        } else {
                            HogpGatt.NOTIFICATIONS_DISABLED
                        }
                    HogpGatt.REPORT_REFERENCE -> descriptor.value ?: ByteArray(2)
                    else -> ByteArray(0)
                }
            }
            log("read descriptor device=${device.address} uuid=${descriptor.uuid} of=${descriptor.characteristic.uuid} offset=$offset value=[${value.joinToString(" ") { "%02X".format(it) }}]")
            respond(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            synchronized(lock) {
                if (descriptor.uuid == HogpGatt.CLIENT_CHARACTERISTIC_CONFIGURATION) {
                    if (central == null) {
                        central = device
                        _connectedCentral.value = device
                        stopAdvertisingLocked()
                    }
                    if (central?.address == device.address) {
                        val enable = HogpGatt.isNotificationEnable(value)
                        if (enable) subscriptions.add(descriptor.characteristic) else subscriptions.remove(descriptor.characteristic)
                        log("cccd device=${device.address} characteristic=${descriptor.characteristic.uuid} enable=$enable subscribed=${subscriptions.size}")
                        val inputSubscribed = subscriptions.any { it.uuid == HogpGatt.REPORT || it.uuid == HogpGatt.BOOT_KEYBOARD_INPUT_REPORT }
                        _state.value = BleHidState.Connected(device.address, device.name, subscribed = inputSubscribed)
                    } else {
                        status = BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION
                    }
                } else {
                    status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                }
            }
            if (responseNeeded) {
                try { gattServer?.sendResponse(device, requestId, status, offset, value) } catch (_: Exception) {}
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            var output: Pair<Int, ByteArray>? = null
            synchronized(lock) {
                when (characteristic.uuid) {
                    HogpGatt.PROTOCOL_MODE -> {
                        val mode = value?.firstOrNull()
                        if (mode == HogpGatt.PROTOCOL_MODE_BOOT || mode == HogpGatt.PROTOCOL_MODE_REPORT) {
                            protocolMode = mode
                            log("protocol mode set=$mode (0=BOOT,1=REPORT) device=${device.address}")
                        } else {
                            status = HogpGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                        }
                    }
                    HogpGatt.HID_CONTROL_POINT -> {
                        when (value?.firstOrNull()) {
                            HogpGatt.CONTROL_POINT_SUSPEND -> suspended = true
                            HogpGatt.CONTROL_POINT_EXIT_SUSPEND -> suspended = false
                        }
                        log("control point value=${value?.firstOrNull()} suspended=$suspended")
                    }
                    HogpGatt.REPORT, HogpGatt.BOOT_KEYBOARD_OUTPUT_REPORT -> {
                        if (value != null) output = KEYBOARD_REPORT_ID to value
                        log("output report device=${device.address} uuid=${characteristic.uuid} length=${value?.size ?: 0}", LogType.BLUETOOTH_PACKET)
                    }
                    else -> status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                }
            }
            if (responseNeeded) {
                try { gattServer?.sendResponse(device, requestId, status, offset, value) } catch (_: Exception) {}
            }
            output?.let { (id, data) -> onOutputReport(id, data) }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(lock) {
                if (status != BluetoothGatt.GATT_SUCCESS) log("notification failed status=$status device=${device.address}", LogType.ERROR)
                else if (DeveloperLogManager.isEnabled && queue.size == 0) log("notification delivered device=${device.address}")
                val next = queue.sent() ?: return
                val target = central ?: return
                notifyLocked(target, next)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            log("mtu device=${device.address} mtu=$mtu")
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun respond(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        val slice = HogpGatt.readSlice(value, offset)
        try {
            val accepted = if (slice == null) {
                gattServer?.sendResponse(device, requestId, HogpGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
            if (accepted != true) log("sendResponse rejected requestId=$requestId", LogType.ERROR)
        } catch (e: Exception) {
            Log.w("BlukeBLE", "sendResponse failed", e)
        }
    }

    private fun targetCharacteristicLocked(reportId: Int): BluetoothGattCharacteristic? =
        if (reportId == KEYBOARD_REPORT_ID && protocolMode == HogpGatt.PROTOCOL_MODE_BOOT) {
            bootKeyboardInput
        } else {
            inputReports[reportId]
        }

    private fun notifyLocked(device: BluetoothDevice, report: PendingBleReport) {
        val characteristic = targetCharacteristicLocked(report.reportId)
        if (characteristic == null) {
            queue.failed()?.let { notifyLocked(device, it) }
            return
        }
        if (!notifyValueLocked(device, characteristic, report.value)) {
            queue.failed()?.let { notifyLocked(device, it) }
        } else if (DeveloperLogManager.isEnabled) {
            log("notify ID=0x${report.reportId.toString(16)} Data=[${report.value.joinToString(" ") { "%02X".format(it) }}]", LogType.BLUETOOTH_PACKET)
        }
    }

    private fun notifyValueLocked(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val server = gattServer ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (e: Exception) {
            Log.w("BlukeBLE", "notifyCharacteristicChanged failed", e)
            false
        }
    }

    private fun reportLength(reportId: Int): Int = when (reportId) {
        KEYBOARD_REPORT_ID -> 8
        MOUSE_REPORT_ID -> 4
        GAMEPAD_REPORT_ID -> GAMEPAD_REPORT_SIZE_BYTES
        CONSUMER_REPORT_ID -> 2
        else -> 1
    }

    private fun readBatteryPercent(): Int {
        val intent: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 100
    }
}
