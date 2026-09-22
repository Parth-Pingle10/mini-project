package com.example.multihoprelay.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.multihoprelay.Screen
import com.example.multihoprelay.bluetooth.BlePermissions
import com.example.multihoprelay.ui.AppViewModel

@Composable
fun HomeScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val neighbors by viewModel.neighbors.collectAsState()
    val relaySettings by viewModel.relaySettings.collectAsState()
    val bluetoothEnabled = BlePermissions.isBluetoothEnabled(context)
    val hasPermissions = BlePermissions.hasAllPermissions(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("My Node", style = MaterialTheme.typography.labelSmall)
                    Text(viewModel.nodeId.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusRow(
                        icon = if (bluetoothEnabled) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled,
                        label = "Bluetooth",
                        value = if (bluetoothEnabled) "Enabled" else "Disabled",
                        good = bluetoothEnabled
                    )
                    StatusRow(
                        icon = Icons.Filled.Devices,
                        label = "Relay Mode",
                        value = if (relaySettings.relayModeEnabled) "ON" else "OFF",
                        good = relaySettings.relayModeEnabled
                    )
                    StatusRow(
                        icon = Icons.Filled.Wifi,
                        label = "Nearby Nodes",
                        value = neighbors.size.toString(),
                        good = neighbors.isNotEmpty()
                    )
                    if (!hasPermissions) {
                        Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                            Text("Grant Bluetooth Permissions")
                        }
                    }
                }
            }
        }

        item {
            Text("Actions", style = MaterialTheme.typography.titleLarge)
        }

        items(
            listOf(
                Triple("Send File", Icons.Filled.Send, Screen.Send.route),
                Triple("Nearby Devices", Icons.Filled.Wifi, Screen.Nearby.route),
                Triple("Transfers", Icons.Filled.List, Screen.Transfers.route),
                Triple("Relay", Icons.Filled.Devices, Screen.Relay.route),
                Triple("Settings", Icons.Filled.Settings, Screen.Settings.route)
            )
        ) { (label, icon, route) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(route) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(icon, contentDescription = null)
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, good: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label)
        }
        Text(
            value,
            color = if (good) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}
