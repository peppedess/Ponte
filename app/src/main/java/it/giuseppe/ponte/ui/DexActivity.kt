package it.giuseppe.ponte.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import it.giuseppe.ponte.Prefs
import it.giuseppe.ponte.adb.GalaxyLink
import it.giuseppe.ponte.adb.PonteAdb
import it.giuseppe.ponte.scrcpy.DexSession
import it.giuseppe.ponte.scrcpy.InputForwarder
import it.giuseppe.ponte.scrcpy.SessionConfig
import it.giuseppe.ponte.scrcpy.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Schermo intero con il desktop (DeX sui Galaxy) del telefono collegato. */
class DexActivity : ComponentActivity() {

    private val connecting = MutableStateFlow<SessionState>(SessionState.Starting("Connessione al telefono…"))
    private var session: DexSession? by mutableStateOf(null)
    private var forwarder: InputForwarder? = null
    private var lastSyncedClipboard: String? = null
    private var showApps by mutableStateOf(false)
    private var showStats by mutableStateOf(false)
    private var wifiLock: WifiManager.WifiLock? = null
    private val appsOfferedFor = mutableSetOf<DexSession>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireWifiLock()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val f = forwarder
                if (f != null && session?.state?.value is SessionState.Running) {
                    f.pressKey(KeyEvent.KEYCODE_BACK)
                } else {
                    finish()
                }
            }
        })

        setContent {
            PonteTheme {
                DexScreen()
            }
        }
        startSession()
    }

    private fun startSession() {
        session?.close()
        session = null
        forwarder = null
        connecting.value = SessionState.Starting("Connessione al telefono…")
        lifecycleScope.launch {
            try {
                GalaxyLink.connect(this@DexActivity)
            } catch (e: Exception) {
                connecting.value = SessionState.Failed(e.message ?: "Connessione non riuscita")
                return@launch
            }
            val newSession = DexSession(
                context = applicationContext,
                adb = PonteAdb.get(this@DexActivity),
                config = buildConfig(),
                onClipboard = { text -> runOnUiThread { copyToLocalClipboard(text) } },
            )
            forwarder = InputForwarder(newSession)
            session = newSession
            newSession.start()
        }
    }

    private fun buildConfig(): SessionConfig {
        val prefs = Prefs.get(this)
        val (rawW, rawH) = screenSize()
        val scale = prefs.resolution.scale
        val width = ((maxOf(rawW, rawH) * scale).toInt() / 8) * 8
        val height = ((minOf(rawW, rawH) * scale).toInt() / 8) * 8
        val baseDpi = if (prefs.dpi > 0) prefs.dpi else autoDpi(this)
        return SessionConfig(
            width = width,
            height = height,
            dpi = (baseDpi * scale).roundToInt().coerceAtLeast(80),
            bitRate = prefs.bitRateMbps * 1_000_000,
            codec = prefs.codec,
            audio = prefs.audio,
            keepActive = prefs.keepActive,
        )
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

    private fun copyToLocalClipboard(text: String) {
        lastSyncedClipboard = text
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Ponte", text)) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // Ciò che è stato copiato qui diventa incollabile anche in DeX
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        if (!text.isNullOrEmpty() && text != lastSyncedClipboard) {
            lastSyncedClipboard = text
            session?.setClipboard(text)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK && session?.state?.value is SessionState.Running) {
            if (forwarder?.onKey(event) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Wi-Fi sempre attivo e a bassa latenza mentre si guarda il desktop: evita gli scatti del risparmio energetico. */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "ponte").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    override fun onDestroy() {
        runCatching { wifiLock?.release() }
        if (isFinishing) session?.close()
        super.onDestroy()
    }

    // ---- Interfaccia ----

    @Composable
    private fun DexScreen() {
        val current = session
        val state by (current?.state ?: connecting).collectAsState()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (current != null) {
                val size by current.videoSize.collectAsState()
                VideoSurface(current, size.first.toFloat() / size.second.toFloat())
            }

            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() },
                label = "stato",
            ) { s ->
                when (s) {
                    is SessionState.Starting -> StartingPanel(s.step)
                    is SessionState.Failed -> FailurePanel(s.message)
                    SessionState.Closed -> FailurePanel("La sessione è terminata.")
                    is SessionState.Running -> Box(Modifier.fillMaxSize())
                }
            }

            if (state is SessionState.Running) {
                ControlHandle(Modifier.align(Alignment.TopCenter))
                if (showStats && current != null) {
                    val stats by current.stats.collectAsState()
                    Text(
                        stats.ifEmpty { "…" },
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (current != null) {
                    // Fuori da Samsung il display virtuale può essere vuoto: propone subito le app
                    LaunchedEffect(current) {
                        if (Prefs.get(this@DexActivity).isGenericDesktop && !appsOfferedFor.contains(current)) {
                            appsOfferedFor.add(current)
                            delay(1_500)
                            showApps = true
                        }
                    }
                    if (showApps) {
                        AppsSheet(current, onDismiss = { showApps = false })
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Composable
    private fun VideoSurface(current: DexSession, ratio: Float) {
        AndroidView(
            modifier = Modifier.aspectRatio(ratio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)),
            factory = { context ->
                object : SurfaceView(context) {
                    override fun onTouchEvent(event: MotionEvent): Boolean =
                        forwarder?.onTouch(event) ?: false

                    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
                        forwarder?.onGeneric(event) ?: super.onGenericMotionEvent(event)

                    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                        super.onSizeChanged(w, h, oldw, oldh)
                        forwarder?.viewWidth = w.coerceAtLeast(1)
                        forwarder?.viewHeight = h.coerceAtLeast(1)
                    }
                }.apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            current.attachSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            forwarder?.viewWidth = width.coerceAtLeast(1)
                            forwarder?.viewHeight = height.coerceAtLeast(1)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            current.detachSurface()
                        }
                    })
                }
            },
        )
    }

    @Composable
    private fun StartingPanel(step: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LoadingIndicator(Modifier.size(72.dp), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(step, color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }

    @Composable
    private fun FailurePanel(message: String) {
        Card(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .padding(24.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Impossibile avviare il desktop", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { finish() }) { Text("Chiudi") }
                    Button(onClick = { startSession() }, shapes = ButtonDefaults.shapes()) { Text("Riprova") }
                }
            }
        }
    }

    /** Linguetta in alto: si apre in una piccola barra con Indietro, Home, App recenti ed Esci. */
    @Composable
    private fun ControlHandle(modifier: Modifier) {
        var open by remember { mutableStateOf(false) }
        LaunchedEffect(open) {
            if (open) {
                delay(5_000)
                open = false
            }
        }
        Box(modifier.padding(top = 6.dp)) {
            AnimatedContent(
                targetState = open,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.6f)) },
                label = "maniglia",
            ) { expanded ->
                if (!expanded) {
                    Box(
                        Modifier
                            .size(width = 56.dp, height = 18.dp)
                            .clickable { open = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width = 40.dp, height = 5.dp)
                                .background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(50)),
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        tonalElevation = 6.dp,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalIconButton(onClick = { forwarder?.pressKey(KeyEvent.KEYCODE_BACK) }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
                            }
                            FilledTonalIconButton(onClick = { forwarder?.pressKey(KeyEvent.KEYCODE_HOME) }) {
                                Icon(Icons.Rounded.Home, contentDescription = "Home")
                            }
                            FilledTonalIconButton(onClick = { forwarder?.pressKey(KeyEvent.KEYCODE_APP_SWITCH) }) {
                                Icon(Icons.Rounded.SpaceDashboard, contentDescription = "App recenti")
                            }
                            FilledTonalIconButton(onClick = { showStats = !showStats }) {
                                Icon(Icons.Rounded.Speed, contentDescription = "Statistiche")
                            }
                            FilledTonalIconButton(onClick = { showApps = true }) {
                                Icon(Icons.Rounded.Apps, contentDescription = "App")
                            }
                            FilledTonalIconButton(onClick = { finish() }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Esci")
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Densità predefinita: circa il 60% di quella del dispositivo, a multipli di 10. */
        fun autoDpi(context: Context): Int {
            val device = context.resources.displayMetrics.densityDpi
            return ((device * 0.6f / 10f).roundToInt() * 10).coerceIn(120, 360)
        }
    }
}
