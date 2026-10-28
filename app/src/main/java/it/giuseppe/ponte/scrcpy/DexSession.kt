package it.giuseppe.ponte.scrcpy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.github.muntashirakon.adb.AdbStream
import it.giuseppe.ponte.Codec
import it.giuseppe.ponte.adb.AdbSync
import it.giuseppe.ponte.adb.PonteAdb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class SessionConfig(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val bitRate: Int,
    val codec: Codec,
    val audio: Boolean,
    val keepActive: Boolean,
)

sealed interface SessionState {
    data class Starting(val step: String) : SessionState
    data class Running(val deviceName: String) : SessionState
    data class Failed(val message: String) : SessionState
    data object Closed : SessionState
}

/**
 * Sessione scrcpy su un display virtuale del Galaxy: con One UI 8 o successiva, Samsung DeX si
 * avvia da solo su quel display. Video H.264/H.265 decodificato con MediaCodec, audio PCM,
 * tocchi/mouse/tastiera inoltrati con i messaggi di controllo.
 */
class DexSession(
    private val context: Context,
    private val adb: PonteAdb,
    private val config: SessionConfig,
    private val onClipboard: (String) -> Unit,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Starting("Avvio…"))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Dimensione attuale del video (= display virtuale) */
    private val _videoSize = MutableStateFlow(config.width to config.height)
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    @Volatile private var closed = false
    private val serverLog = ArrayDeque<String>()

    private var shellStream: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var audioStream: AdbStream? = null
    private var controlStream: AdbStream? = null

    private val controlExecutor = Executors.newSingleThreadExecutor { Thread(it, "ponte-control") }
    private var clipboardSequence = 1L

    // Decodifica video
    private val surfaceLock = Object()
    @Volatile private var surface: Surface? = null
    private var decoder: MediaCodec? = null
    private var decoderThread: HandlerThread? = null
    private var decoderInputs = LinkedBlockingQueue<Int>()
    private var lastConfigPacket: ByteArray? = null
    private var waitingKeyFrame = true
    private var sessionWidth = 0
    private var sessionHeight = 0

    private var audioTrack: AudioTrack? = null

    fun start() {
        Thread({ run() }, "ponte-session").start()
    }

    private fun run() {
        try {
            _state.value = SessionState.Starting("Invio del server al Galaxy…")
            val server = context.assets.open("scrcpy-server.jar").use { it.readBytes() }
            AdbSync.push(adb, server, REMOTE_PATH)

            _state.value = SessionState.Starting("Avvio di DeX sul Galaxy…")
            val scid = Random.nextInt(0, Int.MAX_VALUE)
            val socket = "localabstract:scrcpy_%08x".format(scid)
            val args = buildList {
                add("scid=%08x".format(scid))
                add("log_level=info")
                add("tunnel_forward=true")
                add("video=true")
                add("audio=${config.audio}")
                add("control=true")
                add("new_display=${config.width}x${config.height}/${config.dpi}")
                add("video_codec=${config.codec.serverName}")
                add("video_bit_rate=${config.bitRate}")
                add("max_fps=60")
                add("display_ime_policy=local")
                add("stay_awake=true")
                add("keep_active=${config.keepActive}")
                add("power_off_on_close=false")
                add("clipboard_autosync=true")
                if (config.audio) {
                    add("audio_codec=raw")
                }
            }.joinToString(" ")
            val command = "CLASSPATH=$REMOTE_PATH app_process / com.genymobile.scrcpy.Server $SERVER_VERSION $args"
            val shell = adb.openStream("shell:$command")
            shellStream = shell
            startLogReader(shell)

            val video = openSocket(socket)
            videoStream = video
            val videoIn = DataInputStream(video.openInputStream().buffered(64 * 1024))
            if (videoIn.read() < 0) throw IOException("Il server si è chiuso subito")

            if (config.audio) audioStream = openSocket(socket)
            controlStream = openSocket(socket)

            val nameBytes = ByteArray(64)
            videoIn.readFully(nameBytes)
            val deviceName = String(nameBytes, Charsets.UTF_8).substringBefore('\u0000').ifBlank { "Galaxy" }

            audioStream?.let { stream -> Thread({ runAudio(stream) }, "ponte-audio").start() }
            controlStream?.let { stream -> Thread({ runDeviceMessages(stream) }, "ponte-device-msg").start() }

            _state.value = SessionState.Running(deviceName)
            runVideo(videoIn)
        } catch (e: Throwable) {
            if (!closed) {
                Log.e(TAG, "Sessione terminata", e)
                _state.value = SessionState.Failed(describe(e))
            }
        } finally {
            close()
        }
    }

    /** Il server apre il socket qualche istante dopo l'avvio: riprova finché risponde. */
    private fun openSocket(name: String): AdbStream {
        val deadline = System.currentTimeMillis() + 10_000
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline && !closed) {
            try {
                return adb.openStream(name)
            } catch (e: ConnectException) {
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (shellStream?.isClosed == true) break
            Thread.sleep(100)
        }
        throw IOException("Il server sul Galaxy non risponde", last)
    }

    private fun startLogReader(stream: AdbStream) {
        Thread({
            val input = stream.openInputStream().bufferedReader()
            try {
                while (true) {
                    val line = input.readLine() ?: break
                    Log.i(TAG, "server: $line")
                    synchronized(serverLog) {
                        serverLog.addLast(line)
                        while (serverLog.size > 40) serverLog.removeFirst()
                    }
                }
            } catch (_: Exception) {
            }
        }, "ponte-server-log").start()
    }

    // ---- Video ----

    private fun runVideo(input: DataInputStream) {
        val codecId = input.readInt()
        when (codecId) {
            0 -> throw IOException("Il Galaxy non ha fornito il video")
            1 -> throw IOException("Configurazione video non valida")
        }
        val header = ByteArray(12)
        while (!closed) {
            input.readFully(header)
            if (header[0].toInt() and 0x80 != 0) {
                val buffer = ByteBuffer.wrap(header)
                val width = buffer.getInt(4)
                val height = buffer.getInt(8)
                onSession(width, height)
                continue
            }
            val ptsFlags = ByteBuffer.wrap(header).getLong(0)
            val size = ByteBuffer.wrap(header).getInt(8)
            val data = ByteArray(size)
            input.readFully(data)
            val isConfig = ptsFlags and FLAG_CONFIG != 0L
            val isKey = ptsFlags and FLAG_KEY_FRAME != 0L
            val pts = ptsFlags and PTS_MASK
            onVideoPacket(data, isConfig, isKey, pts)
        }
    }

    private fun onSession(width: Int, height: Int) {
        synchronized(surfaceLock) {
            if (width != sessionWidth || height != sessionHeight) {
                sessionWidth = width
                sessionHeight = height
                _videoSize.value = width to height
                releaseDecoder()
            }
            // Una nuova sessione riparte sempre da configurazione + fotogramma chiave
            lastConfigPacket = null
            waitingKeyFrame = true
        }
    }

    private fun onVideoPacket(data: ByteArray, isConfig: Boolean, isKey: Boolean, pts: Long) {
        synchronized(surfaceLock) {
            if (isConfig) lastConfigPacket = data
            var codec = decoder
            if (codec == null) {
                // createDecoder() ripete già l'ultimo pacchetto di configurazione
                codec = createDecoder() ?: return
                if (isConfig) return
            }
            if (isConfig) {
                queue(codec, data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0)
                return
            }
            if (waitingKeyFrame && !isKey) return
            waitingKeyFrame = false
            queue(codec, data, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0, pts)
        }
    }

    private fun queue(codec: MediaCodec, data: ByteArray, flags: Int, pts: Long) {
        val index = decoderInputs.poll(500, TimeUnit.MILLISECONDS) ?: return
        try {
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            if (data.size > buffer.capacity()) {
                codec.queueInputBuffer(index, 0, 0, pts, 0)
                return
            }
            buffer.put(data)
            codec.queueInputBuffer(index, 0, data.size, pts, flags)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoder non pronto", e)
        }
    }

    /** Crea il decoder solo quando c'è una superficie su cui disegnare. Chiamare con surfaceLock. */
    private fun createDecoder(): MediaCodec? {
        val target = surface ?: return null
        if (sessionWidth == 0 || sessionHeight == 0) return null
        val format = MediaFormat.createVideoFormat(config.codec.mime, sessionWidth, sessionHeight).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val thread = HandlerThread("ponte-decoder").apply { start() }
        val inputs = LinkedBlockingQueue<Int>()
        val codec = MediaCodec.createDecoderByType(config.codec.mime)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                inputs.offer(index)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                runCatching { codec.releaseOutputBuffer(index, true) }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Errore del decoder", e)
                requestKeyFrame()
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, Handler(thread.looper))
        codec.configure(format, target, null, 0)
        codec.start()
        decoder = codec
        decoderThread = thread
        decoderInputs = inputs
        waitingKeyFrame = true
        lastConfigPacket?.let { queue(codec, it, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0) }
        if (lastConfigPacket != null) requestKeyFrame()
        return codec
    }

    /** Chiamare con surfaceLock. */
    private fun releaseDecoder() {
        decoder?.let { codec ->
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        decoder = null
        decoderThread?.quitSafely()
        decoderThread = null
        decoderInputs.clear()
    }

    fun attachSurface(target: Surface) {
        synchronized(surfaceLock) {
            if (surface === target) return
            releaseDecoder()
            surface = target
        }
        requestKeyFrame()
    }

    fun detachSurface() {
        synchronized(surfaceLock) {
            releaseDecoder()
            surface = null
        }
    }

    private fun requestKeyFrame() = send(ControlMessages.resetVideo())

    // ---- Audio ----

    private fun runAudio(stream: AdbStream) {
        try {
            val input = DataInputStream(stream.openInputStream().buffered(32 * 1024))
            val codecId = input.readInt()
            if (codecId == 0 || codecId == 1) {
                Log.w(TAG, "Audio non disponibile sul Galaxy ($codecId)")
                return
            }
            val minBuffer = AudioTrack.getMinBufferSize(
                48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 48_000 * 4 / 10))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            audioTrack = track
            track.play()
            val header = ByteArray(12)
            var data = ByteArray(16 * 1024)
            while (!closed) {
                input.readFully(header)
                val ptsFlags = ByteBuffer.wrap(header).getLong(0)
                val size = ByteBuffer.wrap(header).getInt(8)
                if (size > data.size) data = ByteArray(size)
                input.readFully(data, 0, size)
                if (ptsFlags and FLAG_CONFIG != 0L) continue
                track.write(data, 0, size)
            }
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "Audio interrotto", e)
        }
    }

    // ---- Controllo ----

    private fun runDeviceMessages(stream: AdbStream) {
        try {
            val input = DataInputStream(stream.openInputStream())
            while (!closed) {
                when (input.readUnsignedByte()) {
                    0 -> { // appunti del Galaxy
                        val len = input.readInt()
                        val bytes = ByteArray(len)
                        input.readFully(bytes)
                        onClipboard(String(bytes, Charsets.UTF_8))
                    }
                    1 -> input.readLong() // conferma appunti
                    2 -> { // uscita UHID (non usata)
                        input.readUnsignedShort()
                        val len = input.readUnsignedShort()
                        input.skipBytes(len)
                    }
                    else -> return
                }
            }
        } catch (_: Exception) {
        }
    }

    fun send(message: ByteArray) {
        val stream = controlStream ?: return
        if (closed) return
        runCatching {
            controlExecutor.execute {
                try {
                    stream.write(message, 0, message.size)
                    stream.flush()
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "Controllo non inviato", e)
                }
            }
        }
    }

    fun setClipboard(text: String) {
        send(ControlMessages.setClipboard(clipboardSequence++, text, paste = false))
    }

    // ---- Chiusura ----

    fun close() {
        if (closed) return
        closed = true
        if (_state.value !is SessionState.Failed) _state.value = SessionState.Closed
        for (stream in listOf(videoStream, audioStream, controlStream, shellStream)) {
            runCatching { stream?.close() }
        }
        synchronized(surfaceLock) { releaseDecoder() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        controlExecutor.shutdownNow()
    }

    private fun describe(e: Throwable): String {
        val log = synchronized(serverLog) { serverLog.toList() }
        val serverError = log.lastOrNull { it.contains("ERROR") || it.contains("Exception") }
        return when {
            serverError != null -> "Il server sul Galaxy ha segnalato un errore:\n${serverError.trim()}"
            e is EOFException -> "La connessione con il Galaxy si è interrotta."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    companion object {
        private const val TAG = "Ponte"
        const val SERVER_VERSION = "5.0.1"
        private const val REMOTE_PATH = "/data/local/tmp/ponte-scrcpy-server-$SERVER_VERSION.jar"
        private const val FLAG_CONFIG = 1L shl 62
        private const val FLAG_KEY_FRAME = 1L shl 61
        private const val PTS_MASK = FLAG_KEY_FRAME - 1
    }
}
