package not.exist.hopway.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogEntry(val seq: Long, val time: Long, val level: Int, val message: String)

/** 固定容量的環狀日誌;任意執行緒可寫入(Go 端 callback 併發呼叫)。 */
class LogBuffer(private val capacity: Int = 2000) {
    private val buf = ArrayDeque<LogEntry>(capacity)
    private var seq = 0L
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(level: Int, message: String) {
        synchronized(buf) {
            if (buf.size == capacity) buf.removeFirst()
            buf.addLast(LogEntry(seq++, System.currentTimeMillis(), level, message))
            _entries.value = buf.toList()
        }
    }

    fun clear() = synchronized(buf) {
        buf.clear()
        _entries.value = emptyList()
    }

    companion object {
        const val DEBUG = 0
        const val INFO = 1
        const val WARN = 2
        const val ERROR = 3
    }
}
