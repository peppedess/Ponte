package it.giuseppe.ponte.adb

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Invio di un file al dispositivo con il protocollo "sync:" di ADB (come `adb push`). */
object AdbSync {

    private const val MAX_CHUNK = 64 * 1024

    fun push(adb: AbsAdbConnectionManager, data: ByteArray, remotePath: String, mode: Int = 420 /* 0644 */) =
        push(adb, data.inputStream(), remotePath, mode) { }

    /** Invia un file letto da [input], chiamando [onProgress] con i byte inviati finora. */
    fun push(
        adb: AbsAdbConnectionManager,
        input: java.io.InputStream,
        remotePath: String,
        mode: Int = 420,
        onProgress: (Long) -> Unit,
    ) {
        val stream = adb.openStream("sync:")
        try {
            val spec = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
            stream.write(header("SEND", spec.size) + spec, 0, 8 + spec.size)

            val chunk = ByteArray(MAX_CHUNK)
            var sent = 0L
            while (true) {
                var n = 0
                // Riempie il blocco il più possibile (meno pacchetti = più veloce)
                while (n < MAX_CHUNK) {
                    val r = input.read(chunk, n, MAX_CHUNK - n)
                    if (r < 0) break
                    n += r
                }
                if (n == 0) break
                val packet = ByteArray(8 + n)
                System.arraycopy(header("DATA", n), 0, packet, 0, 8)
                System.arraycopy(chunk, 0, packet, 8, n)
                stream.write(packet, 0, packet.size)
                sent += n
                onProgress(sent)
                if (n < MAX_CHUNK) break
            }

            val mtime = (System.currentTimeMillis() / 1000).toInt()
            stream.write(header("DONE", mtime), 0, 8)
            stream.flush()

            val reply = readFully(stream, 8)
            val id = String(reply, 0, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id != "OKAY") {
                val message = if (id == "FAIL" && len in 1..4096) {
                    String(readFully(stream, len), Charsets.UTF_8)
                } else {
                    "risposta inattesa $id"
                }
                throw IOException("Invio del server non riuscito: $message")
            }
            runCatching {
                stream.write(header("QUIT", 0), 0, 8)
                stream.flush()
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun header(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII))
            .putInt(value)
            .array()

    private fun readFully(stream: io.github.muntashirakon.adb.AdbStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = stream.read(out, read, size - read)
            if (n < 0) throw IOException("Connessione chiusa durante l'invio")
            read += n
        }
        return out
    }
}
