package app.ainderhybrid.hybrid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LogEntry(
    val atMillis: Long,
    val task: AiTask,
    val source: Source,
    val ms: Long,
    val cloudChars: Int,
    val note: String,
)

/** In-memory record of every AI call: task, where it ran, time, characters sent to the cloud. */
class HybridLog(private val max: Int = 200) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(entry: LogEntry) = _entries.update { (listOf(entry) + it).take(max) }

    fun cloudCharsFor(privacy: Privacy): Int =
        _entries.value.filter { it.task.privacy == privacy }.sumOf { it.cloudChars }
}
