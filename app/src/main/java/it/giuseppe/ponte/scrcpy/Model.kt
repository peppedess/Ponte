package it.giuseppe.ponte.scrcpy

import it.giuseppe.ponte.Codec
import java.nio.ByteBuffer

data class SessionConfig(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val bitRate: Int,
    val codec: Codec,
    val audio: Boolean,
    val keepActive: Boolean,
    /** Indirizzo del telefono sulla rete locale, per il canale diretto. */
    val host: String,
    val directChannel: Boolean,
    /** "local": tastiera del telefono dentro il desktop; "hide": si scrive con la tastiera di questo dispositivo. */
    val imePolicy: String,
)

sealed interface SessionState {
    data class Starting(val step: String) : SessionState
    data class Running(val deviceName: String) : SessionState
    /** [recoverable]: la sessione funzionava e si è interrotta la connessione (si può riprovare da soli). */
    data class Failed(val message: String, val recoverable: Boolean = false) : SessionState
    data object Closed : SessionState
}

data class RemoteApp(val name: String, val packageName: String, val system: Boolean)

/** Intestazione di 12 byte che precede ogni pacchetto video/audio di scrcpy 5. */
sealed interface PacketHeader {
    /** Nuova sessione di cattura (avvio, rotazione, ridimensionamento): dimensioni del video. */
    data class Session(val width: Int, val height: Int) : PacketHeader

    data class Media(val pts: Long, val config: Boolean, val keyFrame: Boolean, val size: Int) : PacketHeader

    companion object {
        const val SIZE = 12
        private const val FLAG_CONFIG = 1L shl 62
        private const val FLAG_KEY_FRAME = 1L shl 61
        private const val PTS_MASK = FLAG_KEY_FRAME - 1

        fun parse(header: ByteArray): PacketHeader {
            require(header.size >= SIZE)
            val buffer = ByteBuffer.wrap(header, 0, SIZE)
            return if (header[0].toInt() and 0x80 != 0) {
                Session(buffer.getInt(4), buffer.getInt(8))
            } else {
                val ptsFlags = buffer.getLong(0)
                Media(
                    pts = ptsFlags and PTS_MASK,
                    config = ptsFlags and FLAG_CONFIG != 0L,
                    keyFrame = ptsFlags and FLAG_KEY_FRAME != 0L,
                    size = buffer.getInt(8),
                )
            }
        }
    }
}

/** Interpreta l'elenco stampato da scrcpy-server con list_apps=true. */
fun parseAppList(output: String): List<RemoteApp> {
    val apps = mutableListOf<RemoteApp>()
    var pending: Pair<String, Boolean>? = null
    val entry = Regex("""^\s?([*-]) (.*?\S)\s{2,}(\S+)$""")
    val start = Regex("""^\s?([*-]) (.*\S)$""")
    val pkgOnly = Regex("""^\s{3,}(\S+)$""")
    for (raw in output.lines()) {
        val line = raw.trimEnd().removePrefix("[server] INFO: ")
        val waiting = pending
        if (waiting != null) {
            pkgOnly.find(line)?.let { m ->
                apps += RemoteApp(waiting.first, m.groupValues[1], waiting.second)
            }
            pending = null
            continue
        }
        val e = entry.find(line)
        if (e != null && e.groupValues[3].contains('.')) {
            apps += RemoteApp(e.groupValues[2], e.groupValues[3], e.groupValues[1] == "*")
            continue
        }
        start.find(line)?.let { m -> pending = m.groupValues[2] to (m.groupValues[1] == "*") }
    }
    return apps
        .distinctBy { it.packageName }
        .sortedWith(compareBy<RemoteApp> { it.system }.thenBy { it.name.lowercase() })
}
