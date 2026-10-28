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

data class RemoteApp(val name: String, val packageName: String, val system: Boolean)

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
    private var audioDecoder: MediaCodec? = null
    @Volatile private var lowLatencyForced = false

    /** Statistiche della trasmissione, aggiornate ogni secondo: "60 fps · 7,8 Mbit/s" */
    private val _stats = MutableStateFlow("")
    val stats: StateFlow<String> = _stats.asStateFlow()
    private var statsFrames = 0
    private var statsBytes = 0L
    private var statsSince = System.nanoTime()

    fun start() {
        Thread({ run() }, "ponte-session").start()
    }

    private fun run() {
        try {
            _state.value = SessionState.Starting("Invio del server al telefono…")
            val server = context.assets.open("scrcpy-server.jar").use { it.readBytes() }
            AdbSync.push(adb, server, REMOTE_PATH)

            // Wi-Fi del telefono in modalità bassa latenza (niente risparmio energetico tra i pacchetti)
            runCatching { adb.shell("cmd wifi force-low-latency-mode enabled") }
            lowLatencyForced = true

            _state.value = SessionState.Starting("Avvio del desktop sul telefono…")
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
                    // AAC a 128 kbit/s invece di PCM grezzo (1,5 Mbit/s): lascia la banda al video
                    add("audio_codec=aac")
                    add("audio_bit_rate=128000")
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
        throw IOException("Il server sul telefono non risponde", last)
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
            0 -> throw IOException("Il telefono non ha fornito il video")
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
            updateStats(size)
        }
    }

    private fun updateStats(size: Int) {
        statsFrames++
        statsBytes += size
        val now = System.nanoTime()
        val elapsed = now - statsSince
        if (elapsed >= 1_000_000_000L) {
            val fps = statsFrames * 1_000_000_000L / elapsed
            val mbps = statsBytes * 8.0 * 1_000.0 / elapsed
            _stats.value = "%d fps · %.1f Mbit/s".format(fps, mbps)
            statsFrames = 0
            statsBytes = 0
            statsSince = now
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
                Log.w(TAG, "Audio non disponibile sul telefono ($codecId)")
                return
            }
            val track = createAudioTrack()
            audioTrack = track
            track.play()
            val isAac = codecId == CODEC_AAC
            val header = ByteArray(12)
            var data = ByteArray(16 * 1024)
            val info = MediaCodec.BufferInfo()
            var pcm = ByteArray(8 * 1024)
            while (!closed) {
                input.readFully(header)
                val ptsFlags = ByteBuffer.wrap(header).getLong(0)
                val size = ByteBuffer.wrap(header).getInt(8)
                if (size > data.size) data = ByteArray(size)
                input.readFully(data, 0, size)
                val isConfig = ptsFlags and FLAG_CONFIG != 0L

                if (!isAac) {
                    if (!isConfig) track.write(data, 0, size)
                    continue
                }

                if (isConfig) {
                    // AudioSpecificConfig: crea il decoder AAC
                    audioDecoder?.let { runCatching { it.stop(); it.release() } }
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(data.copyOf(size)))
                        setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    }
                    audioDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                        configure(format, null, null, 0)
                        start()
                    }
                    continue
                }
                val codec = audioDecoder ?: continue
                val inIndex = codec.dequeueInputBuffer(20_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    buffer.clear()
                    buffer.put(data, 0, size)
                    codec.queueInputBuffer(inIndex, 0, size, ptsFlags and PTS_MASK, 0)
                }
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outIndex < 0) break
                    val out = codec.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        if (info.size > pcm.size) pcm = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(pcm, 0, info.size)
                        track.write(pcm, 0, info.size)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "Audio interrotto", e)
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
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

    fun startApp(packageName: String) = send(ControlMessages.startApp(packageName))

    /**
     * Elenco delle app avviabili sul telefono, letto con l'opzione list_apps del server scrcpy
     * Il server cancella il proprio file all'avvio, quindi viene copiato di nuovo in un percorso a parte.
     * Bloccante: chiamarlo fuori dal thread UI.
     */
    fun listApps(): List<RemoteApp> {
        val server = context.assets.open("scrcpy-server.jar").use { it.readBytes() }
        AdbSync.push(adb, server, LIST_PATH)
        val output = adb.shell(
            "CLASSPATH=$LIST_PATH app_process / com.genymobile.scrcpy.Server $SERVER_VERSION list_apps=true log_level=info",
        )
        return parseAppList(output)
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
        audioDecoder?.let { runCatching { it.stop(); it.release() } }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        controlExecutor.shutdownNow()
        if (lowLatencyForced) {
            lowLatencyForced = false
            Thread { runCatching { adb.shell("cmd wifi force-low-latency-mode disabled") } }.start()
        }
    }

    private fun describe(e: Throwable): String {
        val log = synchronized(serverLog) { serverLog.toList() }
        val serverError = log.lastOrNull { it.contains("ERROR") || it.contains("Exception") }
        return when {
            serverError != null -> "Il server sul telefono ha segnalato un errore:\n${serverError.trim()}"
            e is EOFException -> "La connessione con il telefono si è interrotta."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    companion object {
        private const val TAG = "Ponte"
        const val SERVER_VERSION = "5.0.1"
        private const val REMOTE_PATH = "/data/local/tmp/ponte-scrcpy-server-$SERVER_VERSION.jar"
        private const val LIST_PATH = "/data/local/tmp/ponte-scrcpy-list-$SERVER_VERSION.jar"
        private const val CODEC_AAC = 0x00616163
        private const val FLAG_CONFIG = 1L shl 62
        private const val FLAG_KEY_FRAME = 1L shl 61
        private const val PTS_MASK = FLAG_KEY_FRAME - 1
    }
}
