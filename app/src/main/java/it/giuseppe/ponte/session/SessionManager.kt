package it.giuseppe.ponte.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.content.ContextCompat
import it.giuseppe.ponte.Diag
import it.giuseppe.ponte.Prefs
import it.giuseppe.ponte.adb.GalaxyLink
import it.giuseppe.ponte.adb.PonteAdb
import it.giuseppe.ponte.scrcpy.DexSession
import it.giuseppe.ponte.scrcpy.SessionConfig
import it.giuseppe.ponte.scrcpy.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Possiede la sessione attiva, indipendentemente dalle schermate: si può uscire dall'app e tornare
 * senza perdere il desktop. Gestisce anche la riconnessione automatica.
 */
object SessionManager {

    private const val TAG = "manager"
    const val MAX_RECONNECTS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startJob: Job? = null
    private var watchJob: Job? = null

    private val _session = MutableStateFlow<DexSession?>(null)
    val session: StateFlow<DexSession?> = _session.asStateFlow()

    /** Stato da mostrare: connessione in corso, desktop attivo, errore. */
    private val _status = MutableStateFlow<SessionState>(SessionState.Closed)
    val status: StateFlow<SessionState> = _status.asStateFlow()

    private val _reconnectAttempts = MutableStateFlow(0)
    val reconnectAttempts: StateFlow<Int> = _reconnectAttempts.asStateFlow()

    /** True da quando si avvia una sessione a quando la si chiude (anche durante gli errori mostrati). */
    val isActive: Boolean get() = _status.value !is SessionState.Closed

    private var lastSyncedClipboard: String? = null

    fun start(context: Context, reconnect: Boolean = false) {
        val app = context.applicationContext
        if (!reconnect) _reconnectAttempts.value = 0
        startJob?.cancel()
        watchJob?.cancel()
        _session.value?.close()
        _session.value = null
        _status.value = SessionState.Starting(
            if (reconnect) {
                "Connessione persa, mi ricollego… (${_reconnectAttempts.value}/$MAX_RECONNECTS)"
            } else {
                "Connessione al telefono…"
            },
        )
        if (!SessionService.running) {
            // Durante una riconnessione l'app può essere in sottofondo: lì Android non permette di
            // avviare servizi in primo piano, ma il servizio è già attivo dalla prima connessione.
            runCatching { ContextCompat.startForegroundService(app, Intent(app, SessionService::class.java)) }
                .onFailure { Diag.w(TAG, "Servizio in primo piano non avviato", it) }
        }

        startJob = scope.launch {
            try {
                GalaxyLink.connect(app)
            } catch (e: Exception) {
                Diag.e(TAG, "Connessione non riuscita", e)
                onFailure(app, SessionState.Failed(e.message ?: "Connessione non riuscita", recoverable = reconnect))
                return@launch
            }
            val newSession = DexSession(
                context = app,
                adb = PonteAdb.get(app),
                config = buildConfig(app),
                onClipboard = { text -> Handler(Looper.getMainLooper()).post { copyToLocal(app, text) } },
            )
            _session.value = newSession
            watchJob = scope.launch {
                newSession.state.collect { state ->
                    when (state) {
                        is SessionState.Running -> {
                            _reconnectAttempts.value = 0
                            _status.value = state
                        }
                        is SessionState.Failed -> onFailure(app, state)
                        SessionState.Closed -> Unit
                        else -> _status.value = state
                    }
                }
            }
            newSession.start()
        }
    }

    private fun onFailure(app: Context, state: SessionState.Failed) {
        if (state.recoverable && _reconnectAttempts.value < MAX_RECONNECTS) {
            _reconnectAttempts.value++
            val attempt = _reconnectAttempts.value
            Diag.w(TAG, "Riconnessione automatica $attempt/$MAX_RECONNECTS: ${state.message}")
            _status.value = SessionState.Starting("Connessione persa, mi ricollego… ($attempt/$MAX_RECONNECTS)")
            scope.launch {
                delay(1_000L * attempt)
                start(app, reconnect = true)
            }
        } else {
            _status.value = state
        }
    }

    /** Termina la sessione e il servizio. */
    fun stop(context: Context) {
        startJob?.cancel()
        watchJob?.cancel()
        _session.value?.close()
        _session.value = null
        _status.value = SessionState.Closed
        _reconnectAttempts.value = 0
        context.applicationContext.stopService(Intent(context.applicationContext, SessionService::class.java))
    }

    /** Ciò che è stato copiato su questo dispositivo diventa incollabile anche sul desktop. */
    fun syncClipboardToRemote(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        if (!text.isNullOrEmpty() && text != lastSyncedClipboard) {
            lastSyncedClipboard = text
            _session.value?.setClipboard(text)
        }
    }

    private fun copyToLocal(context: Context, text: String) {
        lastSyncedClipboard = text
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Ponte", text)) }
    }

    fun buildConfig(context: Context): SessionConfig {
        val prefs = Prefs.get(context)
        val (rawW, rawH) = screenSize(context)
        val scale = prefs.resolution.scale
        val width = ((maxOf(rawW, rawH) * scale).toInt() / 8) * 8
        val height = ((minOf(rawW, rawH) * scale).toInt() / 8) * 8
        val baseDpi = if (prefs.dpi > 0) prefs.dpi else autoDpi(context)
        return SessionConfig(
            width = width,
            height = height,
            dpi = (baseDpi * scale).roundToInt().coerceAtLeast(80),
            bitRate = prefs.bitRateMbps * 1_000_000,
            codec = prefs.codec,
            audio = prefs.audio,
            keepActive = prefs.keepActive,
            host = prefs.lastHost,
            directChannel = prefs.directChannel,
            imePolicy = if (prefs.localKeyboard) "hide" else "local",
        )
    }

    /** Pixel reali dello schermo di questo dispositivo (indipendenti dall'orientamento). */
    @Suppress("DEPRECATION")
    fun screenSize(context: Context): Pair<Int, Int> {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /** Densità predefinita: circa il 60% di quella del dispositivo, a multipli di 10. */
    fun autoDpi(context: Context): Int {
        val device = context.resources.displayMetrics.densityDpi
        return ((device * 0.6f / 10f).roundToInt() * 10).coerceIn(120, 360)
    }
}
