package it.giuseppe.ponte.ui

import android.annotation.SuppressLint
import android.os.Bundle
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import it.giuseppe.ponte.Diag
import it.giuseppe.ponte.Prefs
import it.giuseppe.ponte.scrcpy.DexSession
import it.giuseppe.ponte.scrcpy.InputForwarder
import it.giuseppe.ponte.scrcpy.SessionState
import it.giuseppe.ponte.session.SessionManager

/**
 * Schermo intero con il desktop del telefono collegato (DeX sui Galaxy). La sessione vive in
 * [SessionManager]: chiudendo o riducendo questa schermata il collegamento resta attivo.
 */
class DexActivity : ComponentActivity() {

    private var forwarder: InputForwarder? = null
    private var showApps by mutableStateOf(false)
    private var showStats by mutableStateOf(false)
    private var showKeyboard by mutableStateOf(false)
    private var touchpad by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val f = forwarder
                if (f != null && SessionManager.status.value is SessionState.Running) {
                    f.pressKey(KeyEvent.KEYCODE_BACK)
                } else {
                    exit()
                }
            }
        })

        touchpad = Prefs.get(this).touchpad
        if (!SessionManager.isActive) SessionManager.start(this)

        setContent {
            PonteTheme {
                DexScreen()
            }
        }
    }

    /** Chiude il desktop e la sessione. */
    private fun exit() {
        SessionManager.stop(this)
        finish()
    }

    /** Torna alla home di questo dispositivo lasciando il desktop collegato in sottofondo. */
    private fun minimize() {
        moveTaskToBack(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SessionManager.syncClipboardToRemote(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!showKeyboard && event.keyCode != KeyEvent.KEYCODE_BACK &&
            SessionManager.status.value is SessionState.Running
        ) {
            if (forwarder?.onKey(event) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Interfaccia ----

    @Composable
    private fun DexScreen() {
        val current by SessionManager.session.collectAsState()
        val state by SessionManager.status.collectAsState()

        // Un InputForwarder per ogni sessione (cambia a ogni riconnessione)
        val session = current
        val inputForwarder = remember(session) { session?.let { InputForwarder(it) } }
        DisposableEffect(inputForwarder) {
            forwarder = inputForwarder
            inputForwarder?.touchpad = touchpad
            onDispose { }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (session != null) {
                val size by session.videoSize.collectAsState()
                VideoSurface(session, size.first.toFloat() / size.second.toFloat())
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

            if (state is SessionState.Running && session != null) {
                if (showStats) {
                    val stats by session.stats.collectAsState()
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

                if (showKeyboard) {
                    LocalKeyboardBar(
                        session = session,
                        onClose = { showKeyboard = false },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                FloatingControls(
                    touchpad = touchpad,
                    statsVisible = showStats,
                    keyboardVisible = showKeyboard,
                    onBack = { forwarder?.pressKey(KeyEvent.KEYCODE_BACK) },
                    onHome = { forwarder?.pressKey(KeyEvent.KEYCODE_HOME) },
                    onRecents = { forwarder?.pressKey(KeyEvent.KEYCODE_APP_SWITCH) },
                    onApps = { showApps = true },
                    onKeyboard = { showKeyboard = !showKeyboard },
                    onTouchpad = {
                        touchpad = !touchpad
                        forwarder?.touchpad = touchpad
                        Prefs.get(this@DexActivity).touchpad = touchpad
                    },
                    onStats = { showStats = !showStats },
                    onMinimize = { minimize() },
                    onExit = { exit() },
                )

                if (showApps) {
                    AppsSheet(session, onDismiss = { showApps = false })
                }
            }
        }
    }

    @Composable
    private fun VideoSurface(current: DexSession, ratio: Float) {
        Box(Modifier.aspectRatio(ratio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f))) {
            VideoView(current)
            if (touchpad) CursorOverlay(current)
        }
    }

    /** Puntatore della modalità touchpad, disegnato sopra il video. */
    @Composable
    private fun CursorOverlay(current: DexSession) {
        val f = forwarder ?: return
        val cursor by f.cursor.collectAsState()
        val size by current.videoSize.collectAsState()
        val c = cursor ?: return
        Canvas(Modifier.fillMaxSize()) {
            val x = c.first / size.first * this.size.width
            val y = c.second / size.second * this.size.height
            val s = 22.dp.toPx()
            val arrow = Path().apply {
                moveTo(x, y)
                lineTo(x, y + s)
                lineTo(x + s * 0.28f, y + s * 0.74f)
                lineTo(x + s * 0.5f, y + s * 1.1f)
                lineTo(x + s * 0.64f, y + s * 1.03f)
                lineTo(x + s * 0.42f, y + s * 0.68f)
                lineTo(x + s * 0.76f, y + s * 0.68f)
                close()
            }
            drawPath(arrow, Color.Black)
            drawPath(arrow, Color.White, style = Stroke(width = 1.5.dp.toPx()))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Composable
    private fun VideoView(current: DexSession) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
                .widthIn(max = 480.dp)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { Diag.share(this@DexActivity) }) { Text("Esporta registro") }
                    OutlinedButton(onClick = { exit() }) { Text("Chiudi") }
                    Button(onClick = { SessionManager.start(this@DexActivity) }, shapes = ButtonDefaults.shapes()) {
                        Text("Riprova")
                    }
                }
            }
        }
    }
}
