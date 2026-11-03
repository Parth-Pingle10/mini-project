package com.example.multihoprelay.ui.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Relay", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Intermediate Relay Mode", fontWeight = FontWeight.Bold)
                    Text(
                        "When enabled, this phone acts as an intermediate node forwarding mesh packets between other nodes without seeing data. When disabled, this node only acts as a terminal source/destination.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Text("Clear Relay Cache", maxLines = 1, softWrap = false)
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
