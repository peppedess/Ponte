package it.giuseppe.ponte.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import it.giuseppe.ponte.scrcpy.ControlMessages
import it.giuseppe.ponte.scrcpy.DexSession
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Comandi flottanti sopra il desktop: un pulsante che si trascina dove dà meno fastidio e si
 * apre in una barra con navigazione, app, tastiera, touchpad, statistiche, riduci ed esci.
 */
@Composable
fun FloatingControls(
    touchpad: Boolean,
    statsVisible: Boolean,
    keyboardVisible: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onApps: () -> Unit,
    onKeyboard: () -> Unit,
    onTouchpad: () -> Unit,
    onStats: () -> Unit,
    onMinimize: () -> Unit,
    onExit: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    // Posizione salvata come frazione dello schermo, così resta sensata se cambia la dimensione
    var fx by rememberSaveable { mutableStateOf(0.5f) }
    var fy by rememberSaveable { mutableStateOf(0.02f) }

    LaunchedEffect(expanded, interaction) {
        if (expanded) {
            delay(6_000)
            expanded = false
        }
    }

    fun act(action: () -> Unit): () -> Unit = {
        interaction++
        action()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val itemW = with(density) { (if (expanded) 520.dp else 48.dp).toPx() }.coerceAtMost(maxW)
        val itemH = with(density) { 56.dp.toPx() }
        val x = (fx * maxW - itemW / 2).coerceIn(0f, (maxW - itemW).coerceAtLeast(0f))
        val y = (fy * maxH).coerceIn(0f, (maxH - itemH).coerceAtLeast(0f))

        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        fx = (fx + drag.x / maxW).coerceIn(0f, 1f)
                        fy = (fy + drag.y / maxH).coerceIn(0f, 1f)
                        interaction++
                    }
                },
        ) {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.7f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.7f))
                },
                label = "comandi",
            ) { open ->
                if (!open) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(Unit) { detectTapGestures { expanded = true } },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.DragIndicator, contentDescription = "Comandi")
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BarButton(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro", act(onBack))
                            BarButton(Icons.Rounded.Home, "Home", act(onHome))
                            BarButton(Icons.Rounded.SpaceDashboard, "App recenti", act(onRecents))
                            BarButton(Icons.Rounded.Apps, "App") { expanded = false; onApps() }
                            VerticalDivider(Modifier.padding(vertical = 10.dp).size(width = 1.dp, height = 28.dp))
                            BarToggle(
                                if (keyboardVisible) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
                                "Tastiera", keyboardVisible,
                            ) { expanded = false; onKeyboard() }
                            BarToggle(
                                if (touchpad) Icons.Rounded.Mouse else Icons.Rounded.TouchApp,
                                if (touchpad) "Touchpad" else "Tocco diretto", touchpad, act(onTouchpad),
                            )
                            BarToggle(Icons.Rounded.Speed, "Statistiche", statsVisible, act(onStats))
                            VerticalDivider(Modifier.padding(vertical = 10.dp).size(width = 1.dp, height = 28.dp))
                            BarButton(Icons.Rounded.CloseFullscreen, "Riduci (resta collegato)") { expanded = false; onMinimize() }
                            FilledTonalIconButton(onClick = onExit) {
                                Icon(Icons.Rounded.Close, contentDescription = "Esci")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun BarToggle(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    FilledTonalIconToggleButton(checked = checked, onCheckedChange = { onClick() }) {
        Icon(icon, contentDescription = label)
    }
}

/**
 * Tastiera di questo dispositivo: un campo invisibile riceve ciò che si digita e lo inoltra al
 * desktop (lettere, cancella, invio). Il testo non ASCII passa dagli appunti.
 */
@Composable
fun LocalKeyboardBar(session: DexSession, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Surface(
        modifier = modifier
            .imePadding()
            .padding(12.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Keyboard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                "Scrivi: il testo va nel campo attivo del desktop",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // Campo invisibile che riceve la tastiera
            BasicTextField(
                value = value,
                onValueChange = { new ->
                    // Si inoltra la differenza col testo precedente (anche durante i suggerimenti della
                    // tastiera); il campo si svuota solo a parola conclusa, per non rompere la composizione.
                    forwardDiff(session, value.text, new.text)
                    value = if (new.composition == null && (new.text.length > 40 || new.text.length < SENTINEL.length)) {
                        TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
                    } else {
                        new
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.None,
                ),
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focus),
            )
            IconButton(onClick = { session.send(enterDown()); session.send(enterUp()) }) {
                Text("↵", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = {
                keyboard?.hide()
                onClose()
            }) {
                Icon(Icons.Rounded.KeyboardHide, contentDescription = "Chiudi tastiera")
            }
        }
    }
}

/** Il campo contiene sempre due caratteri invisibili: così si riconosce anche "cancella" a campo vuoto. */
private const val SENTINEL = "​​"

private fun forwardDiff(session: DexSession, old: String, new: String) {
    var prefix = 0
    while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
    repeat(old.length - prefix) {
        session.send(ControlMessages.keycode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0, 0))
        session.send(ControlMessages.keycode(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0, 0))
    }
    val inserted = new.substring(prefix).replace("​", "")
    if (inserted.isEmpty()) return
    val parts = inserted.split('\n')
    parts.forEachIndexed { i, part ->
        if (part.isNotEmpty()) session.typeText(part)
        if (i < parts.lastIndex) {
            session.send(enterDown())
            session.send(enterUp())
        }
    }
}

private fun enterDown() = ControlMessages.keycode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0)
private fun enterUp() = ControlMessages.keycode(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0)
