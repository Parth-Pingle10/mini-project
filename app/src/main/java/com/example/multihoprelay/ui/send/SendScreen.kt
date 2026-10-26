package com.example.multihoprelay.ui.send

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.TransferState
import com.example.multihoprelay.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class SelectedFileMeta(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String
)

@Composable
fun SendScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val reachableNodes by viewModel.reachableNodes.collectAsState()
    val preselectedDestination by viewModel.preselectedDestination.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedFile by remember { mutableStateOf<SelectedFileMeta?>(null) }
    var selectedDestination by remember { mutableStateOf<NodeId?>(null) }
    var activeTransferId by remember { mutableStateOf<UUID?>(null) }
    var filePickerError by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(preselectedDestination) {
        preselectedDestination?.let {
            selectedDestination = it
            viewModel.clearPreselectedDestination()
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                filePickerError = null
                selectedFile = queryFileMeta(context, uri)
            } catch (e: Exception) {
                filePickerError = "Failed to access selected file: ${e.message}"
            }
        }
    }

    val activeTransfer = transfers.firstOrNull { it.transferId == activeTransferId }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Send File", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        // 1. File Selection Card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select File to Transfer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (selectedFile != null) {
                    val file = selectedFile!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(file.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                            Text("Size: ${formatFileSize(file.sizeBytes)} • Type: ${file.mimeType}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Text("No file chosen yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                filePickerError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(if (selectedFile == null) "Choose File" else "Change File")
                }
            }
        }

        // 2. Destination Selection Card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Recipient Device (Direct & Reachable Nodes)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))

                if (reachableNodes.isEmpty()) {
                    Text(
                        "No nearby or reachable nodes discovered. Check the 'Nearby' tab to discover and connect to a peer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    reachableNodes.forEach { node ->
                        val isReachable = node.isAvailable

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedDestination == node.nodeId,
                                onClick = { selectedDestination = node.nodeId }
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(
                                    node.displayName,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val statusColor = if (isReachable) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                                Text(node.statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
                            }
                        }
                    }
                }
            }
        }

        // 3. Send Button & Status Check
        val isDestConnected = selectedDestination?.let { dest ->
            reachableNodes.firstOrNull { it.nodeId == dest }?.isAvailable == true
        } ?: false

        val canSend = selectedFile != null && selectedDestination != null && isDestConnected && (activeTransfer?.state?.isTerminal != false)

        Button(
            enabled = canSend,
            onClick = {
                val file = selectedFile ?: return@Button
                val dest = selectedDestination ?: return@Button
                scope.launch {
                    try {
                        val localCopy = withContext(Dispatchers.IO) {
                            copyUriToCache(context, file.uri, file.name)
                        }
                        activeTransferId = viewModel.sendFile(dest, localCopy, file.name)
                    } catch (e: Exception) {
                        filePickerError = "Error preparing file: ${e.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send File")
        }

        if (selectedDestination != null && !isDestConnected) {
            Text(
                "Selected node is not currently reachable. Connect to a direct neighbor or ensure an active route exists.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 4. Active Transfer Card
        activeTransfer?.let { info ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sending: ${info.fileName}", fontWeight = FontWeight.Bold)
                        if (info.state == TransferState.COMPLETED) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                        } else if (info.state.isFailure) {
                            Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        }
                    }

                    LinearProgressIndicator(progress = { info.progressFraction }, modifier = Modifier.fillMaxWidth())

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${(info.progressFraction * 100).toInt()}% (${info.chunksDone} / ${info.totalChunks} chunks)", style = MaterialTheme.typography.bodySmall)
                        Text(formatState(info.state), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    }

                    info.errorMessage?.let {
                        Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    if (!info.state.isTerminal) {
                        OutlinedButton(
                            onClick = { viewModel.cancelTransfer(info.transferId) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

private fun formatState(state: TransferState): String =
    com.example.multihoprelay.util.FormatUtils.formatTransferState(state)

private fun formatFileSize(bytes: Long): String =
    com.example.multihoprelay.util.FormatUtils.formatFileSize(bytes)

private fun queryFileMeta(context: android.content.Context, uri: Uri): SelectedFileMeta {
    var name = "file"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: name
                }
                if (sizeIndex >= 0) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (_: Exception) {
    }
    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return SelectedFileMeta(uri, name, size, mimeType)
}

private fun copyUriToCache(context: android.content.Context, uri: Uri, fileName: String): File {
    val cleanName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "upload_file" }
    val outFile = File(context.cacheDir, "outgoing_${System.currentTimeMillis()}_$cleanName")
    context.contentResolver.openInputStream(uri)?.use { input ->
        outFile.outputStream().use { output -> input.copyTo(output) }
    } ?: throw java.io.IOException("Failed to open input stream for $uri")
    return outFile
}
