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

    val routes by viewModel.routes.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Node Profile Header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "THIS DEVICE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        viewModel.nodeId.value,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        if (relaySettings.relayModeEnabled) "Role: Intermediate Node (Forwarding + Terminal)"
                        else "Role: Terminal Node (Source / Destination)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Live Status
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Mesh Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    StatusRow(
                        icon = if (bluetoothEnabled) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled,
                        label = "Bluetooth Radio",
                        value = if (bluetoothEnabled) "Ready" else "Disabled",
                        good = bluetoothEnabled
                    )
                    StatusRow(
                        icon = Icons.Filled.Devices,
                        label = "Relay Forwarding",
                        value = if (relaySettings.relayModeEnabled) "Active" else "Disabled",
                        good = relaySettings.relayModeEnabled
                    )
                    StatusRow(
                        icon = Icons.Filled.Wifi,
                        label = "Connected 1-Hop Neighbors",
                        value = neighbors.count { it.isConnected }.toString(),
                        good = neighbors.any { it.isConnected }
                    )
                    StatusRow(
                        icon = Icons.Filled.List,
                        label = "Total Mesh Paths Known",
                        value = routes.size.toString(),
                        good = routes.isNotEmpty()
                    )
                    if (!hasPermissions) {
                        Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("Grant Bluetooth Permissions")
                        }
                    }
                }
            }
        }

        item {
            Text("Quick Actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        items(
            listOf(
                Triple("Send File", Icons.Filled.Send, Screen.Send.route),
                Triple("Nearby & Mesh Devices", Icons.Filled.Wifi, Screen.Nearby.route),
                Triple("Transfer History", Icons.Filled.List, Screen.Transfers.route),
                Triple("Node Role / Relay", Icons.Filled.Devices, Screen.Relay.route),
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
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
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
