package dev.arnv.bluke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.bluetooth.BleHidState

/** One-line summary of the BLE transport for a card subtitle. */
internal fun BleHidState.summary(): String = when (this) {
    BleHidState.Disabled -> "Off"
    is BleHidState.Unsupported -> reason
    BleHidState.Starting -> "Starting…"
    is BleHidState.Advertising -> "Advertising as \"$name\". Pair from the host's Bluetooth settings."
    is BleHidState.Connected -> {
        val who = deviceName ?: deviceAddress
        if (subscribed) "Connected to $who (typing only)" else "$who connected, waiting for it to subscribe…"
    }
    is BleHidState.Error -> message
}

@Composable
internal fun BleHidStatusCard(
    state: BleHidState,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val connected = state is BleHidState.Connected
    val problem = state is BleHidState.Error || state is BleHidState.Unsupported
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when {
                            connected -> Icons.Default.BluetoothConnected
                            state is BleHidState.Advertising -> Icons.Default.BluetoothSearching
                            else -> Icons.Default.Bluetooth
                        },
                        contentDescription = "Bluetooth LE",
                        tint = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bluetooth LE keyboard", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (connected) {
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
            } else if (problem) {
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }
    }
}
