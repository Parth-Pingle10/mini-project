package com.example.multihoprelay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.multihoprelay.Screen
import com.example.multihoprelay.ui.AppViewModel

@Composable
fun SettingsScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings by viewModel.relaySettings.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Node ID", style = MaterialTheme.typography.labelSmall)
                Text(viewModel.nodeId.value, fontWeight = FontWeight.Bold)
                Text(
                    "Public Key (Base64, truncated): ${viewModel.publicKeyBase64.take(48)}...",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Relay Mode")
                    Switch(checked = settings.relayModeEnabled, onCheckedChange = { viewModel.setRelayModeEnabled(it) })
                }

                LabeledSlider(
                    label = "Maximum relay storage: ${settings.maxRelayStorageBytes / (1024 * 1024)} MB",
                    value = (settings.maxRelayStorageBytes / (1024 * 1024)).toFloat(),
                    range = 50f..2000f,
                    onChange = { viewModel.setMaxRelayStorageBytes(it.toLong() * 1024 * 1024) }
                )
                LabeledSlider(
                    label = "Maximum packet age: ${settings.maxPacketAgeMillis / 60000} minutes",
                    value = (settings.maxPacketAgeMillis / 60000).toFloat(),
                    range = 1f..120f,
                    onChange = { viewModel.setMaxPacketAgeMillis(it.toLong() * 60000) }
                )
                LabeledSlider(
                    label = "Maximum hops: ${settings.maxHops}",
                    value = settings.maxHops.toFloat(),
                    range = 2f..16f,
                    onChange = { viewModel.setMaxHops(it.toInt()) }
                )
                LabeledSlider(
                    label = "Maximum concurrent relays: ${settings.maxConcurrentRelays}",
                    value = settings.maxConcurrentRelays.toFloat(),
                    range = 1f..10f,
                    onChange = { viewModel.setMaxConcurrentRelays(it.toInt()) }
                )
                LabeledSlider(
                    label = "Bandwidth limit: ${settings.maxBandwidthBytesPerSecond / 1024} KB/s",
                    value = (settings.maxBandwidthBytesPerSecond / 1024).toFloat(),
                    range = 10f..2000f,
                    onChange = { viewModel.setMaxBandwidth(it.toLong() * 1024) }
                )
            }
        }

        OutlinedButton(onClick = { viewModel.clearRelayCache() }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear Relay Cache")
        }

        OutlinedButton(onClick = { onNavigate(Screen.Logs.route) }, modifier = Modifier.fillMaxWidth()) {
            Text("View Debug Logs")
        }

        OutlinedButton(
            onClick = { showResetDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Reset Identity")
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Identity?") },
            text = {
                Text(
                    "This generates a brand-new key pair and Node ID for this device. " +
                        "Peers will see you as a completely different, unrelated node afterwards. " +
                        "Any transfer currently addressed to your old identity can no longer be " +
                        "decrypted by this device. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetIdentity()
                    showResetDialog = false
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
