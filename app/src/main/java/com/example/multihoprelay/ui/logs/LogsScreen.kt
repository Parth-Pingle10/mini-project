package com.example.multihoprelay.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.multihoprelay.model.LogLevel
import com.example.multihoprelay.ui.AppViewModel

@Composable
fun LogsScreen(viewModel: AppViewModel) {
    val logs by viewModel.logs.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Debug Logs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.clearLogs() }) {
                Text("Clear", maxLines = 1, softWrap = false)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(logs.asReversed()) { entry ->
                Text(
                    entry.formatted(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (entry.level) {
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        LogLevel.WARN -> androidx.compose.ui.graphics.Color(0xFFB8860B)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}
