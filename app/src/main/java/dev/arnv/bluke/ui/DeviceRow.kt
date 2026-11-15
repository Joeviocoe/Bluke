package dev.arnv.bluke.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothClass
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Categories of Bluetooth devices with custom dynamic icons, matching backgrounds, and keyboard HID capability flags
 */
enum class DeviceType(
    val id: String,
    val displayName: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val bgColor: Color,
    val tintColor: Color,
    val isSupported: Boolean
) {
    COMPUTER("computer", "Computer", Icons.Default.Laptop, Color(0xFFE8DDFF), Color(0xFF65558F), true),
    PHONE("phone", "Phone", Icons.Default.PhoneAndroid, Color(0xFFD6F0FF), Color(0xFF006494), true),
    TV("tv", "Smart TV", Icons.Default.Tv, Color(0xFFE0F7FA), Color(0xFF006064), true),
    AUDIO("audio", "Audio Device", Icons.Default.Headphones, Color(0xFFFBE7E9), Color(0xFFC00021), false),
    MOUSE("mouse", "Input Accessory", Icons.Default.Mouse, Color(0xFFFFD8E4), Color(0xFFB11E4C), false),
    GAMEPAD("gamepad", "Game Controller", Icons.Default.SportsEsports, Color(0xFFFFE9A6), Color(0xFF7D5200), false),
    WATCH("watch", "Smartwatch", Icons.Default.Watch, Color(0xFFF3EDF7), Color(0xFF65558F), false),
    DEFAULT("default", "Bluetooth Device", Icons.Default.Bluetooth, Color(0xFFE2F1FF), Color(0xFF0F5A9E), true)
}

/**
 * Categorise a device for the list icon and the supported/unsupported badge.
 *
 * The Bluetooth Class of Device is authoritative: a host that reports Computer or Phone is
 * always a host, whatever it is called. Name keywords are only consulted when the device
 * reports no class at all (common for BLE-only devices), and they match whole words so that
 * a hostname such as "GameSpot" or "Catkin" is not mistaken for a controller or a mouse.
 */
@SuppressLint("MissingPermission")
fun classifyDevice(name: String?, device: BluetoothDevice?): DeviceType {
    val bluetoothClass = try {
        device?.bluetoothClass
    } catch (_: Exception) {
        null
    }
    return classifyDevice(name, bluetoothClass?.majorDeviceClass, bluetoothClass?.deviceClass)
}

/** Pure classification from the name and the Class of Device fields, for tests and callers without a device. */
fun classifyDevice(name: String?, majorClass: Int?, minorClass: Int?): DeviceType {
    val words = name?.lowercase()?.split(NAME_WORD_SEPARATOR)?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    fun named(vararg keywords: String) = keywords.any { it in words }

    when (majorClass) {
        BluetoothClass.Device.Major.COMPUTER -> return DeviceType.COMPUTER
        BluetoothClass.Device.Major.PHONE -> return DeviceType.PHONE
        BluetoothClass.Device.Major.PERIPHERAL -> return if (named(*GAMEPAD_WORDS)) DeviceType.GAMEPAD else DeviceType.MOUSE
        BluetoothClass.Device.Major.TOY -> return DeviceType.GAMEPAD
        BluetoothClass.Device.Major.WEARABLE -> return DeviceType.WATCH
        BluetoothClass.Device.Major.AUDIO_VIDEO -> {
            val isTvOrDisplay = minorClass == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER ||
                minorClass == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR ||
                minorClass == BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX
            return if (isTvOrDisplay || named(*TV_WORDS)) DeviceType.TV else DeviceType.AUDIO
        }
    }

    // No usable class: fall back to whole-word name hints, hosts first.
    return when {
        named(*COMPUTER_WORDS) -> DeviceType.COMPUTER
        named(*PHONE_WORDS) -> DeviceType.PHONE
        named(*TV_WORDS) -> DeviceType.TV
        named(*GAMEPAD_WORDS) -> DeviceType.GAMEPAD
        named(*POINTER_WORDS) -> DeviceType.MOUSE
        named(*AUDIO_WORDS) -> DeviceType.AUDIO
        named(*WATCH_WORDS) -> DeviceType.WATCH
        else -> DeviceType.DEFAULT
    }
}

private val NAME_WORD_SEPARATOR = Regex("[^a-z0-9]+")
private val COMPUTER_WORDS = arrayOf("laptop", "pc", "computer", "desktop", "mac", "macbook", "imac", "linux", "windows", "host")
private val PHONE_WORDS = arrayOf("phone", "galaxy", "iphone", "pixel", "oneplus", "xiaomi", "redmi", "mobile", "tablet", "ipad")
private val TV_WORDS = arrayOf("tv", "television", "shield", "chromecast", "firestick", "roku", "tizen", "webos", "bravia", "mibox", "appletv")
private val GAMEPAD_WORDS = arrayOf("controller", "gamepad", "xbox", "playstation", "ps4", "ps5", "joycon", "nintendo", "dualsense", "dualshock")
private val POINTER_WORDS = arrayOf("mouse", "trackpad", "pointer", "keyboard", "stylus")
private val AUDIO_WORDS = arrayOf("buds", "earbuds", "headphone", "headphones", "headset", "audio", "speaker", "soundcore", "airpods")
private val WATCH_WORDS = arrayOf("watch", "fitbit", "band", "garmin", "wearable")

/**
 * Beautiful device row for bonded or discovered host entities with dynamic visual categorization
 */
@SuppressLint("MissingPermission")
@Composable
fun DeviceRow(
    name: String,
    address: String,
    showAddress: Boolean = true,
    isConnected: Boolean,
    bondState: Int,
    statusText: String? = null,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    device: BluetoothDevice? = null,
    onActionClick: () -> Unit
) {
    val isError = statusText?.let { 
        it.contains("Failed", ignoreCase = true) || 
        it.contains("error", ignoreCase = true) || 
        it.contains("reject", ignoreCase = true) ||
        it.contains("timed out", ignoreCase = true) ||
        it.contains("refused", ignoreCase = true)
    } == true
    val isWorking = (!isError && !isConnected && statusText?.let { 
        it.contains("Connecting", ignoreCase = true) || 
        it.contains("Pairing with", ignoreCase = true) ||
        it.contains("Waiting for", ignoreCase = true) ||
        it.contains("Switching to", ignoreCase = true)
    } == true) || (isConnected && statusText?.contains("disconnect", ignoreCase = true) == true)
    
    // Categorize device to show specialized icons and capability badges
    val deviceType = classifyDevice(name, device)

    val isHidCapable = try {
        device?.uuids?.contains(android.os.ParcelUuid.fromString("00001124-0000-1000-8000-00805f9b34fb")) == true
    } catch (_: Exception) {
        false
    }
    val isBonded = device?.bondState == BluetoothDevice.BOND_BONDED
    val isSupported = deviceType.isSupported || isBonded || isHidCapable

    Surface(
        onClick = onActionClick,
        enabled = isConnected || isSupported,
        shape = shape,
        color = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                // Beautiful dynamic icon container
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) MaterialTheme.colorScheme.surface else deviceType.bgColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), 
                            strokeWidth = 2.dp, 
                            color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = deviceType.icon,
                            contentDescription = deviceType.displayName,
                            tint = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer else if (isError) MaterialTheme.colorScheme.error else deviceType.tintColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Text(
                        text = name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    
                    if (showAddress) {
                        Text(
                            text = address,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    
                    // Compact Row of Status and Capability Tags
                    if (!isSupported || isError) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            // Compatibility warning tag if keyboard emulation cannot work on this device type
                            if (!isSupported) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "UNSUPPORTED",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            // Bluetooth pairing/connection error tag
                            if (isError) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "FAILED",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Button(
                onClick = onActionClick,
                enabled = isConnected || isSupported,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                colors = if (isConnected) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else if (isWorking) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (bondState == BluetoothDevice.BOND_BONDING) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "Disconnect" 
                           else "Connect",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}