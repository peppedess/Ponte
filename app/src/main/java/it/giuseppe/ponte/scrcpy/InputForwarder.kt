package it.giuseppe.ponte.scrcpy

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Converte tocchi, mouse e tastiera del dispositivo che fa da schermo in messaggi scrcpy.
 * Le coordinate vengono riportate alla risoluzione del display virtuale.
 */
class InputForwarder(private val session: DexSession) {

    var viewWidth = 1
    var viewHeight = 1

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
}
