package io.github.jenziner.nisaba.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Verbindungsprotokoll im Arbeitsspeicher (Ringpuffer), für die Suche nach
 * Verbindungsabbrüchen. Enthält nie den API-Schlüssel und keine Chat-Inhalte,
 * nur Zeit, Ereignis, Netzweg, Dauer und Fehlerart. Geht beim Prozessende verloren.
 */
class ConnectionLog(private val capacity: Int = 300) {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun add(event: String) {
        val l = "${LocalTime.now().format(fmt)} $event"
        _lines.value = (_lines.value + l).takeLast(capacity)
    }

    fun asText(): String = lines.value.joinToString("\n")
}
