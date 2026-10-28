package it.giuseppe.ponte.scrcpy

import io.github.muntashirakon.adb.AdbStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** Un flusso bidirezionale verso scrcpy-server: dentro ADB oppure su TCP diretto. */
interface Channel : Closeable {
    val input: InputStream
    fun write(bytes: ByteArray)
}

class AdbChannel(private val stream: AdbStream) : Channel {
    override val input: InputStream = BufferedInputStream(stream.openInputStream(), 64 * 1024)

    override fun write(bytes: ByteArray) {
        stream.write(bytes, 0, bytes.size)
        stream.flush()
    }

    override fun close() {
        runCatching { stream.close() }
    }
}

class TcpChannel(private val socket: Socket) : Channel {
    override val input: InputStream = BufferedInputStream(socket.getInputStream(), 64 * 1024)
    private val output: OutputStream = socket.getOutputStream()

    override fun write(bytes: ByteArray) {
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
