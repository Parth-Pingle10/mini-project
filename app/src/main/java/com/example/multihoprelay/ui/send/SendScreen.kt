package com.example.multihoprelay.ui.send

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.TransferState
import com.example.multihoprelay.ui.AppViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
fun SendScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val neighbors by viewModel.neighbors.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedDestination by remember { mutableStateOf<NodeId?>(null) }
    var activeTransferId by remember { mutableStateOf<UUID?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedFileName = queryFileName(context, uri)
        }
    }

    val activeTransfer = transfers.firstOrNull { it.transferId == activeTransferId }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Send File", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("File", style = MaterialTheme.typography.labelSmall)
                Text(selectedFileName ?: "No file selected", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Choose File")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Destination", style = MaterialTheme.typography.labelSmall)
                if (neighbors.isEmpty()) {
                    Text("No nearby nodes yet - open Nearby Devices to discover peers.")
                } else {
                    neighbors.forEach { neighbor ->
                        Column {
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedDestination == neighbor.nodeId,
                                    onClick = { selectedDestination = neighbor.nodeId }
                                )
                                Text(neighbor.nodeId.value)
                            }
                        }
                    }
                }
            }
        }

        val route = selectedDestination?.let { viewModel.routes.collectAsState().value[it] }
        if (selectedDestination != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Route", style = MaterialTheme.typography.labelSmall)
                    Text(
                        route?.fullPath?.joinToString(" -> ") { it.value }
                            ?: "${viewModel.nodeId.value} -> ${selectedDestination!!.value} (discovering...)"
                    )
                    Text("Encrypted: YES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        Button(
            enabled = selectedUri != null && selectedDestination != null && activeTransfer?.state?.isTerminal != false,
            onClick = {
                val uri = selectedUri ?: return@Button
                val dest = selectedDestination ?: return@Button
                val name = selectedFileName ?: "file"
                scope.launch {
                    val localCopy = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        copyUriToCache(context, uri, name)
                    }
                    activeTransferId = viewModel.sendFile(dest, localCopy, name)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send")
        }

        activeTransfer?.let { info ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sending to ${info.peerNodeId.value}", fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { info.progressFraction }, modifier = Modifier.fillMaxWidth())
                    Text("${(info.progressFraction * 100).toInt()}%  (${info.chunksDone} / ${info.totalChunks} chunks)")
                    Text("Status: ${info.state}")
                    if (info.route.isNotEmpty()) {
                        Text("Route: ${info.route.joinToString(" -> ") { it.value }}")
                    }
                    info.errorMessage?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun queryFileName(context: android.content.Context, uri: Uri): String {
    var name = "file"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            name = cursor.getString(nameIndex) ?: name
        }
    }
    return name
}

private fun copyUriToCache(context: android.content.Context, uri: Uri, fileName: String): File {
    val outFile = File(context.cacheDir, "outgoing_$fileName")
    context.contentResolver.openInputStream(uri)?.use { input ->
        outFile.outputStream().use { output -> input.copyTo(output) }
    }
    return outFile
}
