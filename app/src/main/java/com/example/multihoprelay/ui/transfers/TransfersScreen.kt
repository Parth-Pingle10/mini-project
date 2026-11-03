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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multihoprelay.model.TransferDirection
import com.example.multihoprelay.model.TransferInfo
import com.example.multihoprelay.model.TransferState
import com.example.multihoprelay.ui.AppViewModel

@Composable
fun TransfersScreen(viewModel: AppViewModel) {
    val transfers by viewModel.transfers.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Transfers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (transfers.isEmpty()) {
            Text(
                "No transfers yet. Start one from the Send screen.",
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(transfers, key = { it.transferId }) { transfer ->
                    TransferCard(
                        info = transfer,
                        onAccept = { viewModel.acceptIncomingTransfer(transfer.transferId) },
                        onReject = { viewModel.rejectIncomingTransfer(transfer.transferId) },
                        onCancel = { viewModel.cancelTransfer(transfer.transferId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferCard(
    info: TransferInfo,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    info.fileName,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (info.direction == TransferDirection.OUTGOING) "Sending" else "Receiving",
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                if (info.direction == TransferDirection.OUTGOING) "To: ${info.peerNodeId.value}" else "From: ${info.peerNodeId.value}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Size: ${com.example.multihoprelay.util.FormatUtils.formatFileSize(info.fileSizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(progress = { info.progressFraction }, modifier = Modifier.fillMaxWidth())
            Text(
                "Status: ${com.example.multihoprelay.util.FormatUtils.formatTransferState(info.state)}  (${info.chunksDone}/${info.totalChunks} chunks)",
                style = MaterialTheme.typography.bodySmall
            )
            if (info.route.isNotEmpty()) {
                Text("Route: ${info.route.joinToString(" -> ") { it.value }}", style = MaterialTheme.typography.labelSmall)
            }
            info.errorMessage?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }

            if (info.direction == TransferDirection.INCOMING && info.state == TransferState.PENDING_APPROVAL) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.Button(
                        onClick = onAccept,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF2E7D32)
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("Allow", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onReject,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("Reject", maxLines = 1, softWrap = false)
                    }
                }
            } else if (!info.state.isTerminal) {
                OutlinedButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }

            if (info.state == TransferState.COMPLETED && !info.outputFilePath.isNullOrBlank()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val file = java.io.File(info.outputFilePath)
                if (file.exists()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.Button(
                            onClick = { openFile(context, file) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Open File", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = { shareFile(context, file) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Share", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

private fun openFile(context: android.content.Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mimeType = context.contentResolver.getType(uri) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Open file with"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot open file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: android.content.Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mimeType = context.contentResolver.getType(uri) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share file via"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot share file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
