package it.giuseppe.ponte.scrcpy

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/**
 * Converte tocchi, mouse e tastiera del dispositivo che fa da schermo in messaggi scrcpy.
 * Le coordinate vengono riportate alla risoluzione del display virtuale.
 *
 * In modalità touchpad lo schermo si comporta come il trackpad di un portatile e il puntatore
 * viene disegnato da Ponte (gli eventi iniettati non mostrano il cursore sul display virtuale).
 */
class InputForwarder(private val session: DexSession) {

    var viewWidth = 1
    var viewHeight = 1

    @Volatile var touchpad = false
        set(value) {
            field = value
            if (value && _cursor.value == null) centerCursor()
            if (!value) _cursor.value = null
        }

    /** Posizione del puntatore in coordinate del display virtuale (solo in modalità touchpad). */
    private val _cursor = MutableStateFlow<Pair<Float, Float>?>(null)
    val cursor: StateFlow<Pair<Float, Float>?> = _cursor.asStateFlow()

    private fun mapX(x: Float, w: Int) = (x * w / viewWidth).toInt().coerceIn(0, w - 1)
    private fun mapY(y: Float, h: Int) = (y * h / viewHeight).toInt().coerceIn(0, h - 1)

    private fun isMouse(event: MotionEvent, index: Int = 0) =
        event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE

    fun onTouch(event: MotionEvent): Boolean {
        val (w, h) = session.videoSize.value
        if (isMouse(event)) {
            // I clic arrivano come BUTTON_PRESS/RELEASE in onGeneric(); qui solo i trascinamenti
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                session.send(
                    ControlMessages.touch(
                        MotionEvent.ACTION_MOVE, ControlMessages.POINTER_ID_MOUSE,
                        mapX(event.x, w), mapY(event.y, h), w, h,
                        if (event.buttonState != 0) 1f else 0f, 0, event.buttonState,
                    ),
                )
            }
            return true
        }
        if (touchpad) return onTouchpad(event, w, h)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> sendPointer(event, event.actionIndex, MotionEvent.ACTION_DOWN, w, h)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> sendPointer(event, event.actionIndex, MotionEvent.ACTION_UP, w, h)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) sendPointer(event, i, MotionEvent.ACTION_MOVE, w, h)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until event.pointerCount) sendPointer(event, i, MotionEvent.ACTION_UP, w, h)
        }
        return true
    }

    private fun sendPointer(event: MotionEvent, index: Int, action: Int, w: Int, h: Int) {
        val pressure = if (action == MotionEvent.ACTION_UP) 0f else event.getPressure(index).coerceIn(0.1f, 1f)
        session.send(
            ControlMessages.touch(
                action, event.getPointerId(index).toLong(),
                mapX(event.getX(index), w), mapY(event.getY(index), h), w, h,
                pressure, 0, 0,
            ),
        )
    }

    // ---- Touchpad ----

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var travelled = 0f
    private var maxPointers = 0
    private var scrolling = false
    private var scrollAccX = 0f
    private var scrollAccY = 0f
    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var dragging = false

    private fun centerCursor() {
        val (w, h) = session.videoSize.value
        _cursor.value = w / 2f to h / 2f
    }

    private fun cursorInt(w: Int, h: Int): Pair<Int, Int> {
        val c = _cursor.value ?: (w / 2f to h / 2f)
        return c.first.toInt().coerceIn(0, w - 1) to c.second.toInt().coerceIn(0, h - 1)
    }

    private fun onTouchpad(event: MotionEvent, w: Int, h: Int): Boolean {
        if (_cursor.value == null) centerCursor()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = SystemClock.uptimeMillis()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                travelled = 0f
                maxPointers = 1
                scrolling = false
                // Doppio tocco tenendo giù il dito: trascinamento (selezioni, spostare finestre)
                val nearLastTap = hypot(event.x - lastTapX, event.y - lastTapY) < TAP_SLOP * 3
                if (downTime - lastTapUpTime < DOUBLE_TAP_MS && nearLastTap) {
                    dragging = true
                    val (x, y) = cursorInt(w, h)
                    session.send(mouseButton(MotionEvent.ACTION_DOWN, x, y, w, h, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)
                if (event.pointerCount == 2) {
                    scrolling = true
                    scrollAccX = 0f
                    scrollAccY = 0f
                    lastX = avgX(event)
                    lastY = avgY(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrolling && event.pointerCount >= 2) {
                    val x = avgX(event)
                    val y = avgY(event)
                    scrollAccX += x - lastX
                    scrollAccY += y - lastY
                    travelled += hypot(x - lastX, y - lastY)
                    lastX = x
                    lastY = y
                    val stepsY = (scrollAccY / SCROLL_STEP_PX).toInt()
                    val stepsX = (scrollAccX / SCROLL_STEP_PX).toInt()
                    if (stepsX != 0 || stepsY != 0) {
                        scrollAccX -= stepsX * SCROLL_STEP_PX
                        scrollAccY -= stepsY * SCROLL_STEP_PX
                        val (cx, cy) = cursorInt(w, h)
                        // Scorrimento "naturale": il contenuto segue le dita
                        session.send(ControlMessages.scroll(cx, cy, w, h, -stepsX.toFloat(), stepsY.toFloat(), 0))
                    }
                } else if (!scrolling) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    travelled += hypot(dx, dy)
                    moveCursor(dx, dy, w, h)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 < 2) {
                    // Rimane un dito: riparte da lì per evitare salti del puntatore
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val (x, y) = cursorInt(w, h)
                val duration = SystemClock.uptimeMillis() - downTime
                val isTap = event.actionMasked == MotionEvent.ACTION_UP && duration < TAP_MS && travelled < TAP_SLOP
                when {
                    dragging -> {
                        dragging = false
                        session.send(mouseButton(MotionEvent.ACTION_UP, x, y, w, h, MotionEvent.BUTTON_PRIMARY, 0))
                        lastTapUpTime = 0
                    }
                    isTap && maxPointers >= 2 -> {
                        // Tocco con due dita: clic destro
                        session.send(mouseButton(MotionEvent.ACTION_DOWN, x, y, w, h, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY))
                        session.send(mouseButton(MotionEvent.ACTION_UP, x, y, w, h, MotionEvent.BUTTON_SECONDARY, 0))
                        lastTapUpTime = 0
                    }
                    isTap -> {
                        session.send(mouseButton(MotionEvent.ACTION_DOWN, x, y, w, h, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                        session.send(mouseButton(MotionEvent.ACTION_UP, x, y, w, h, MotionEvent.BUTTON_PRIMARY, 0))
                        lastTapUpTime = SystemClock.uptimeMillis()
                        lastTapX = downX
                        lastTapY = downY
                    }
                }
                scrolling = false
            }
        }
        return true
    }

    private fun moveCursor(dx: Float, dy: Float, w: Int, h: Int) {
        val c = _cursor.value ?: return
        // Accelerazione: movimenti rapidi portano il puntatore più lontano
        val speed = hypot(dx, dy)
        val gain = BASE_GAIN * (w.toFloat() / viewWidth) * (1f + (speed / 25f).coerceAtMost(1.5f))
        val nx = (c.first + dx * gain).coerceIn(0f, w - 1f)
        val ny = (c.second + dy * gain).coerceIn(0f, h - 1f)
        _cursor.value = nx to ny
        val buttons = if (dragging) MotionEvent.BUTTON_PRIMARY else 0
        session.send(
            ControlMessages.touch(
                if (dragging) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE,
                ControlMessages.POINTER_ID_MOUSE,
                nx.toInt(), ny.toInt(), w, h,
                if (dragging) 1f else 0f, 0, buttons,
            ),
        )
    }

    private fun mouseButton(action: Int, x: Int, y: Int, w: Int, h: Int, actionButton: Int, buttons: Int) =
        ControlMessages.touch(
            action, ControlMessages.POINTER_ID_MOUSE, x, y, w, h,
            if (action == MotionEvent.ACTION_UP) 0f else 1f, actionButton, buttons,
        )

    private fun avgX(e: MotionEvent): Float = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun avgY(e: MotionEvent): Float = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()

    // ---- Mouse fisico ----

    fun onGeneric(event: MotionEvent): Boolean {
        val (w, h) = session.videoSize.value
        val x = mapX(event.x, w)
        val y = mapY(event.y, h)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> session.send(
                ControlMessages.touch(
                    MotionEvent.ACTION_HOVER_MOVE, ControlMessages.POINTER_ID_MOUSE,
                    x, y, w, h, 0f, 0, 0,
                ),
            )
            MotionEvent.ACTION_BUTTON_PRESS -> session.send(
                ControlMessages.touch(
                    MotionEvent.ACTION_DOWN, ControlMessages.POINTER_ID_MOUSE,
                    x, y, w, h, 1f, event.actionButton, event.buttonState,
                ),
            )
            MotionEvent.ACTION_BUTTON_RELEASE -> session.send(
                ControlMessages.touch(
                    MotionEvent.ACTION_UP, ControlMessages.POINTER_ID_MOUSE,
                    x, y, w, h, 0f, event.actionButton, event.buttonState,
                ),
            )
            MotionEvent.ACTION_SCROLL -> session.send(
                ControlMessages.scroll(
                    x, y, w, h,
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    event.buttonState,
                ),
            )
            else -> return false
        }
        return true
    }

    // ---- Tastiera ----

    /** Inoltra un tasto della tastiera fisica. Restituisce false per i tasti da gestire qui. */
    fun onKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER -> return false
        }
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> KeyEvent.ACTION_DOWN
            KeyEvent.ACTION_UP -> KeyEvent.ACTION_UP
            else -> return false
        }
        session.send(ControlMessages.keycode(action, event.keyCode, event.repeatCount, event.metaState))
        return true
    }

    fun pressKey(keyCode: Int) {
        session.send(ControlMessages.keycode(KeyEvent.ACTION_DOWN, keyCode, 0, 0))
        session.send(ControlMessages.keycode(KeyEvent.ACTION_UP, keyCode, 0, 0))
    }

    private companion object {
        const val TAP_MS = 220L
        const val TAP_SLOP = 18f
        const val DOUBLE_TAP_MS = 280L
        const val SCROLL_STEP_PX = 28f
        const val BASE_GAIN = 1.3f
    }
}
