package lumen.app.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import lumen.core.EventSink
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Single logcat tag "CamGuard"; keeps the last 200 events for the in-app screen. */
class EventLog : EventSink {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buf = ArrayDeque<String>()
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events

    override fun log(kind: String, id: String?, msg: String) {
        val line = "${fmt.format(Date())} $kind ${id?.let { "id=$it " } ?: ""}$msg"
        Log.i(TAG, line)
        synchronized(buf) {
            buf.addLast(line); while (buf.size > MAX) buf.removeFirst()
            _events.value = buf.toList()
        }
    }

    fun asText(): String = synchronized(buf) { buf.joinToString("\n") }

    companion object { const val TAG = "CamGuard"; const val MAX = 200 }
}
