package com.example.multihoprelay.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.multihoprelay.model.TransferDirection
import com.example.multihoprelay.model.TransferInfo
import com.example.multihoprelay.ui.AppViewModel

@Composable
fun TransfersScreen(viewModel: AppViewModel) {
    val transfers by viewModel.transfers.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Transfers", style = MaterialTheme.typography.titleLarge)
        if (transfers.isEmpty()) {
            Text(
                "No transfers yet. Start one from the Send screen.",
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(transfers, key = { it.transferId }) { transfer ->
                    TransferCard(transfer, onCancel = { viewModel.cancelTransfer(transfer.transferId) })
                }
            }
        }
    }
}

@Composable
private fun TransferCard(info: TransferInfo, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(info.fileName, fontWeight = FontWeight.Bold)
                Text(if (info.direction == TransferDirection.OUTGOING) "Sending" else "Receiving")
            }
            Text(
                if (info.direction == TransferDirection.OUTGOING) "To: ${info.peerNodeId.value}" else "From: ${info.peerNodeId.value}"
            )
            LinearProgressIndicator(progress = { info.progressFraction }, modifier = Modifier.fillMaxWidth())
            Text("Status: ${info.state}  (${info.chunksDone}/${info.totalChunks} chunks)")
            if (info.route.isNotEmpty()) {
                Text("Route: ${info.route.joinToString(" -> ") { it.value }}", style = MaterialTheme.typography.labelSmall)
            }
            info.errorMessage?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            if (!info.state.isTerminal) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
