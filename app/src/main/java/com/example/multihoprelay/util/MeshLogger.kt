package com.example.multihoprelay.util

import android.util.Log
import com.example.multihoprelay.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Locale

data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun formatted(): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(timestampMillis)
        return "$time [$tag] $message"
    }
}

/**
 * Central, in-memory ring-buffer logger surfaced on the Logs screen for development/demo
 * purposes. By design and by convention enforced through code review, callers must NEVER
 * pass private keys, symmetric file keys, decrypted file bytes/contents, or raw file
 * contents to this logger - only routing/protocol metadata (node ids, packet ids, chunk
 * indices, sizes, states). See SECURITY.md for the full list of what is/isn't logged.
 */
class MeshLogger(private val capacity: Int = 500) {

    private val buffer = ArrayDeque<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    @Synchronized
    private fun add(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        buffer.addLast(entry)
        if (buffer.size > capacity) buffer.removeFirst()
        _entries.value = buffer.toList()

        when (level) {
            LogLevel.DEBUG -> Log.d("MultiHopRelay/$tag", message)
            LogLevel.INFO -> Log.i("MultiHopRelay/$tag", message)
            LogLevel.WARN -> Log.w("MultiHopRelay/$tag", message)
            LogLevel.ERROR -> Log.e("MultiHopRelay/$tag", message)
        }
    }

    fun debug(tag: String, message: String) = add(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = add(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = add(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = add(LogLevel.ERROR, tag, message)

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }

    @Synchronized
    fun exportAsText(): String = buffer.joinToString("\n") { it.formatted() }
}
