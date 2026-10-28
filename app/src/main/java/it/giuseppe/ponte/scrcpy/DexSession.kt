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
import android.view.Surface
import io.github.muntashirakon.adb.AdbStream
import it.giuseppe.ponte.Diag
import it.giuseppe.ponte.adb.AdbSync
import it.giuseppe.ponte.adb.PonteAdb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Sessione scrcpy su un display virtuale del telefono collegato: sui Galaxy con One UI 8+ ci parte
 * DeX, sugli altri la modalità desktop di Android (se presente). Video H.264/H.265 decodificato con
 * MediaCodec, audio AAC, tocchi/mouse/tastiera inoltrati con i messaggi di controllo.
 *
 * Il trasporto è un canale TCP diretto (tramite il piccolo ponte ":relay" avviato sul telefono)
 * oppure, se la rete non lo permette, i flussi dentro la connessione ADB.
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

    /** "Diretto" o "ADB": come viaggiano i dati in questa sessione. */
    @Volatile var transport: String = ""
        private set

    @Volatile private var closed = false
    @Volatile private var reachedRunning = false
    private val serverLog = ArrayDeque<String>()

    private var shellStream: AdbStream? = null
    private var relayStream: AdbStream? = null
    private var videoChannel: Channel? = null
    private var audioChannel: Channel? = null
    private var controlChannel: Channel? = null

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

    /** Statistiche della trasmissione, aggiornate ogni secondo: "60 fps · 7,8 Mbit/s · diretto" */
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
            val socketName = "scrcpy_%08x".format(scid)
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
                add("display_ime_policy=${config.imePolicy}")
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
            Diag.i(TAG, "Avvio server: ${config.width}x${config.height}/${config.dpi} ${config.codec} ${config.bitRate / 1_000_000}M audio=${config.audio}")
            val shell = adb.openStream("shell:CLASSPATH=$REMOTE_PATH app_process / com.genymobile.scrcpy.Server $SERVER_VERSION $args")
            shellStream = shell
            startLogReader(shell)

            val count = if (config.audio) 3 else 2
            val channels = (if (config.directChannel) openDirect(socketName, count) else null)
                ?: openViaAdb(socketName, count)

            videoChannel = channels[0]
            if (config.audio) audioChannel = channels[1]
            controlChannel = channels.last()

            val videoIn = DataInputStream(channels[0].input)
            val nameBytes = ByteArray(64)
            videoIn.readFully(nameBytes)
            val deviceName = String(nameBytes, Charsets.UTF_8).substringBefore('\u0000').ifBlank { "Telefono" }
            Diag.i(TAG, "Collegato a $deviceName via $transport")

            audioChannel?.let { channel -> Thread({ runAudio(channel) }, "ponte-audio").start() }
            controlChannel?.let { channel -> Thread({ runDeviceMessages(channel) }, "ponte-device-msg").start() }

            _state.value = SessionState.Running(deviceName)
            reachedRunning = true
            runVideo(videoIn)
        } catch (e: Throwable) {
            if (!closed) {
                Diag.e(TAG, "Sessione terminata", e)
                _state.value = SessionState.Failed(describe(e), recoverable = reachedRunning && !hasServerError())
            }
        } finally {
            close()
        }
    }

    // ---- Trasporto ----

    /** Canale diretto: avvia il ponte TCP sul telefono e ci si collega. Null se la rete non lo permette. */
    private fun openDirect(socketName: String, count: Int): List<Channel>? {
        if (config.host.isEmpty()) return null
        val opened = mutableListOf<Channel>()
        try {
            val relayJar = context.assets.open("ponte-relay.jar").use { it.readBytes() }
            AdbSync.push(adb, relayJar, RELAY_PATH)
            val token = (1..32).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
            val relay = adb.openStream("shell:CLASSPATH=$RELAY_PATH app_process / it.giuseppe.ponte.relay.Relay $token $socketName $count")
            relayStream = relay

            // Legge la porta scelta dal ponte (prima riga "PONTE_RELAY_PORT n")
            val portFuture = CompletableFuture<Int>()
            Thread({
                runCatching {
                    val reader = relay.openInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        Diag.i("relay", line)
                        if (line.startsWith("PONTE_RELAY_PORT ")) {
                            portFuture.complete(line.substringAfter(' ').trim().toInt())
                        }
                    }
                }
                portFuture.complete(-1)
            }, "ponte-relay-log").start()
            val port = portFuture.get(5, TimeUnit.SECONDS)
            if (port <= 0) throw IOException("Il ponte TCP non si è avviato")

            repeat(count) { index ->
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.receiveBufferSize = 1 shl 20
                socket.connect(InetSocketAddress(config.host, port), 3_000)
                socket.getOutputStream().apply {
                    write(token.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val channel = TcpChannel(socket)
                opened += channel
                if (index == 0) {
                    socket.soTimeout = 15_000
                    if (channel.input.read() < 0) throw IOException("Il server si è chiuso subito")
                    socket.soTimeout = 0
                }
            }
            transport = "diretto"
            return opened
        } catch (e: Exception) {
            Diag.w(TAG, "Canale diretto non disponibile, uso ADB", e)
            opened.forEach { it.close() }
            runCatching { relayStream?.close() }
            relayStream = null
            if (opened.isNotEmpty()) {
                // Il server ha già accettato connessioni dal ponte: non si può ripiegare su ADB
                throw IOException("Connessione diretta interrotta durante l'avvio", e)
            }
            return null
        }
    }

    private fun openViaAdb(socketName: String, count: Int): List<Channel> {
        val channels = mutableListOf<Channel>()
        repeat(count) { index ->
            val channel = AdbChannel(openSocket("localabstract:$socketName"))
            channels += channel
            if (index == 0 && channel.input.read() < 0) throw IOException("Il server si è chiuso subito")
        }
        transport = "ADB"
        return channels
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
                    Diag.i("server", line)
                    synchronized(serverLog) {
                        serverLog.addLast(line)
                        while (serverLog.size > 60) serverLog.removeFirst()
                    }
                }
            } catch (_: Exception) {
            }
        }, "ponte-server-log").start()
    }

    // ---- Video ----

    private fun runVideo(input: DataInputStream) {
        when (input.readInt()) {
            0 -> throw IOException("Il telefono non ha fornito il video")
            1 -> throw IOException("Configurazione video non valida")
        }
        val header = ByteArray(PacketHeader.SIZE)
        while (!closed) {
            input.readFully(header)
            when (val packet = PacketHeader.parse(header)) {
                is PacketHeader.Session -> onSession(packet.width, packet.height)
                is PacketHeader.Media -> {
                    val data = ByteArray(packet.size)
                    input.readFully(data)
                    onVideoPacket(data, packet.config, packet.keyFrame, packet.pts)
                    updateStats(packet.size)
                }
            }
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
            _stats.value = "%d fps · %.1f Mbit/s · %s".format(fps, mbps, transport)
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
            Diag.w(TAG, "Decoder non pronto", e)
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
                Diag.e(TAG, "Errore del decoder", e)
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
        Diag.i(TAG, "Decoder ${codec.name} ${sessionWidth}x$sessionHeight")
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

    private fun runAudio(channel: Channel) {
        try {
            val input = DataInputStream(channel.input)
            val codecId = input.readInt()
            if (codecId == 0 || codecId == 1) {
                Diag.w(TAG, "Audio non disponibile sul telefono ($codecId)")
                return
            }
            val track = createAudioTrack()
            audioTrack = track
            track.play()
            val isAac = codecId == CODEC_AAC
            val header = ByteArray(PacketHeader.SIZE)
            var data = ByteArray(16 * 1024)
            val info = MediaCodec.BufferInfo()
            var pcm = ByteArray(8 * 1024)
            while (!closed) {
                input.readFully(header)
                val packet = PacketHeader.parse(header) as? PacketHeader.Media ?: continue
                val size = packet.size
                if (size > data.size) data = ByteArray(size)
                input.readFully(data, 0, size)

                if (!isAac) {
                    if (!packet.config) track.write(data, 0, size)
                    continue
                }

                if (packet.config) {
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
                    codec.queueInputBuffer(inIndex, 0, size, packet.pts, 0)
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
            if (!closed) Diag.w(TAG, "Audio interrotto", e)
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

    private fun runDeviceMessages(channel: Channel) {
        try {
            val input = DataInputStream(channel.input)
            while (!closed) {
                when (input.readUnsignedByte()) {
                    0 -> { // appunti del telefono
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
        val channel = controlChannel ?: return
        if (closed) return
        runCatching {
            controlExecutor.execute {
                try {
                    channel.write(message)
                } catch (e: Exception) {
                    if (!closed) Diag.w(TAG, "Controllo non inviato", e)
                }
            }
        }
    }

    fun setClipboard(text: String, paste: Boolean = false) {
        send(ControlMessages.setClipboard(clipboardSequence++, text, paste))
    }

    /** Scrive testo nel campo attivo: ASCII con l'iniezione diretta, il resto incollandolo dagli appunti. */
    fun typeText(text: String) {
        if (text.isEmpty()) return
        if (text.all { it.code in 32..126 }) {
            send(ControlMessages.injectText(text))
        } else {
            setClipboard(text, paste = true)
        }
    }

    fun startApp(packageName: String) = send(ControlMessages.startApp(packageName))

    /** Chiede al telefono di aggiungere un file appena ricevuto alla galleria / ai download. */
    fun scanFile(path: String) = send(ControlMessages.scanFile(path))

    /**
     * Elenco delle app avviabili sul telefono, letto con l'opzione list_apps del server scrcpy.
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

    // ---- Chiusura ----

    fun close() {
        if (closed) return
        closed = true
        if (_state.value !is SessionState.Failed) _state.value = SessionState.Closed
        for (channel in listOf(videoChannel, audioChannel, controlChannel)) {
            runCatching { channel?.close() }
        }
        runCatching { relayStream?.close() }
        runCatching { shellStream?.close() }
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

    private fun hasServerError(): Boolean = synchronized(serverLog) {
        serverLog.any { it.contains("ERROR") || it.contains("Exception") }
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
        private const val TAG = "sessione"
        const val SERVER_VERSION = "5.0.1"
        private const val REMOTE_PATH = "/data/local/tmp/ponte-scrcpy-server-$SERVER_VERSION.jar"
        private const val LIST_PATH = "/data/local/tmp/ponte-scrcpy-list-$SERVER_VERSION.jar"
        private const val RELAY_PATH = "/data/local/tmp/ponte-relay.jar"
        private const val CODEC_AAC = 0x00616163
    }
}
