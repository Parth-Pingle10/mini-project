package com.example.multihoprelay.ui.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.multihoprelay.ui.AppViewModel

@Composable
fun RelayScreen(viewModel: AppViewModel) {
    val stats by viewModel.relayStats.collectAsState()
    val settings by viewModel.relaySettings.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Relay", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Relay Mode", fontWeight = FontWeight.Bold)
                    Text(
                        "Allow this device to relay encrypted traffic for other nearby " +
                            "participating devices. This device can never see the contents.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Switch(checked = settings.relayModeEnabled, onCheckedChange = { viewModel.setRelayModeEnabled(it) })
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Relay Mode: ${if (stats.relayModeEnabled) "ON" else "OFF"}", fontWeight = FontWeight.Bold)
                Text("Active relays: ${stats.activeRelays}")
                Text("Queued packets (store-and-forward): ${stats.queuedPackets}")

                Column {
                    Text("Storage: ${stats.storageUsedBytes / 1024} KB / ${stats.storageLimitBytes / 1024} KB")
                    val fraction = if (stats.storageLimitBytes > 0) {
                        (stats.storageUsedBytes.toFloat() / stats.storageLimitBytes.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }

                Text("Packets forwarded: ${stats.packetsForwarded}")
                Text("Packets dropped: ${stats.packetsDropped}")
                Text("Bandwidth: ${stats.currentBandwidthBps / 1024} KB/s")
            }
        }

        OutlinedButton(onClick = { viewModel.clearRelayCache() }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear Relay Cache")
        }

        Text(
            "This device only ever forwards already-encrypted data on behalf of other " +
                "nodes' transfers. It cannot decrypt, open, or inspect the file contents of " +
                "packets it relays.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
