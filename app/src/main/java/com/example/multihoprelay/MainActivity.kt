package com.example.multihoprelay

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.multihoprelay.bluetooth.BlePermissions
import com.example.multihoprelay.model.PendingIncomingTransfer
import com.example.multihoprelay.model.TransferDirection
import com.example.multihoprelay.model.TransferInfo
import com.example.multihoprelay.model.TransferState
import com.example.multihoprelay.service.MeshForegroundService
import com.example.multihoprelay.ui.AppViewModel
import com.example.multihoprelay.ui.home.HomeScreen
import com.example.multihoprelay.ui.logs.LogsScreen
import com.example.multihoprelay.ui.nearby.NearbyDevicesScreen
import com.example.multihoprelay.ui.relay.RelayScreen
import com.example.multihoprelay.ui.send.SendScreen
import com.example.multihoprelay.ui.settings.SettingsScreen
import com.example.multihoprelay.ui.theme.MultiHopRelayTheme
import com.example.multihoprelay.ui.transfers.TransfersScreen
import com.example.multihoprelay.util.FormatUtils

sealed class Screen(val route: String, val label: String) {
    object Home : Screen("home", "Home")
    object Nearby : Screen("nearby", "Nearby")
    object Send : Screen("send", "Send")
    object Transfers : Screen("transfers", "Transfers")
    object Relay : Screen("relay", "Relay")
    object Settings : Screen("settings", "Settings")
    object Logs : Screen("logs", "Logs")
}

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startMeshService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsIfNeeded()

        setContent {
            MultiHopRelayTheme {
                MultiHopRelayApp(viewModel = viewModel, onRequestPermissions = { requestPermissionsIfNeeded() })
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (!BlePermissions.isBluetoothSupported(this)) return
        val required = BlePermissions.requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startMeshService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startMeshService() {
        val intent = Intent(this, MeshForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
fun MultiHopRelayApp(viewModel: AppViewModel, onRequestPermissions: () -> Unit) {
    val navController = rememberNavController()
    val items = listOf(Screen.Home, Screen.Nearby, Screen.Send, Screen.Transfers, Screen.Relay, Screen.Settings)

    val pendingIncomingReq by viewModel.pendingIncomingRequest.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val pendingFromTransfers = transfers.firstOrNull {
        it.direction == TransferDirection.INCOMING && it.state == TransferState.PENDING_APPROVAL
    }

    val activePending: PendingIncomingTransfer? = pendingIncomingReq ?: pendingFromTransfers?.let {
        PendingIncomingTransfer(
            transferId = it.transferId,
            peerNodeId = it.peerNodeId,
            fileName = it.fileName,
            fileSizeBytes = it.fileSizeBytes,
            totalChunks = it.totalChunks,
            createdAtMillis = it.createdAtMillis,
            timeoutSeconds = 60,
            remainingSeconds = 60
        )
    }

    if (activePending != null) {
        LaunchedEffect(activePending.transferId) {
            viewModel.logInfo("TRANSFER", "APPROVAL_DIALOG_RENDERED: transferId=${activePending.transferId} file=${activePending.fileName}")
        }
        IncomingTransferDialog(
            transfer = activePending,
            onAllow = { viewModel.acceptIncomingTransfer(activePending.transferId) },
            onReject = { viewModel.rejectIncomingTransfer(activePending.transferId) }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(screenIcon(screen), contentDescription = screen.label) },
                        label = { Text(screen.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigate = { route -> navController.navigate(route) },
                    onRequestPermissions = onRequestPermissions
                )
            }
            composable(Screen.Nearby.route) { NearbyDevicesScreen(viewModel, onRequestPermissions = onRequestPermissions) }
            composable(Screen.Send.route) { SendScreen(viewModel) }
            composable(Screen.Transfers.route) { TransfersScreen(viewModel) }
            composable(Screen.Relay.route) { RelayScreen(viewModel) }
            composable(Screen.Settings.route) { SettingsScreen(viewModel, onNavigate = { navController.navigate(it) }) }
            composable(Screen.Logs.route) { LogsScreen(viewModel) }
        }
    }
}

@Composable
private fun screenIcon(screen: Screen) = when (screen) {
    Screen.Home -> Icons.Filled.Home
    Screen.Nearby -> Icons.Filled.Wifi
    Screen.Send -> Icons.Filled.Send
    Screen.Transfers -> Icons.Filled.List
    Screen.Relay -> Icons.Filled.Devices
    Screen.Settings -> Icons.Filled.Settings
    Screen.Logs -> Icons.Filled.List
}

@Composable
fun IncomingTransferDialog(
    transfer: PendingIncomingTransfer,
    onAllow: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                Icons.Filled.Send,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Incoming File Transfer", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Device ${transfer.peerNodeId.value} wants to send you a file over MultiHopRelay mesh.",
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("File Name:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(transfer.fileName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("File Size:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(FormatUtils.formatFileSize(transfer.fileSizeBytes), style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sender Node:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(transfer.peerNodeId.value, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Accept this transfer within ${transfer.remainingSeconds} seconds",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (transfer.remainingSeconds <= 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (transfer.remainingSeconds.toFloat() / transfer.timeoutSeconds.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    color = if (transfer.remainingSeconds <= 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Text(
                    "Do you want to allow this file transfer?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32)
                )
            ) {
                Text("Allow")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onReject) {
                Text("Reject")
            }
        }
    )
}

