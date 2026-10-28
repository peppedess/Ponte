package it.giuseppe.ponte.scrcpy

import java.nio.ByteBuffer

/** Messaggi di controllo del protocollo scrcpy 5.0.1 (big endian). */
object ControlMessages {

    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TOUCH_EVENT = 2
    private const val TYPE_INJECT_SCROLL_EVENT = 3
    private const val TYPE_SET_CLIPBOARD = 9
    private const val TYPE_START_APP = 16
    private const val TYPE_RESET_VIDEO = 17

    const val POINTER_ID_MOUSE = -1L

    fun keycode(action: Int, keycode: Int, repeat: Int, metaState: Int): ByteArray =
        ByteBuffer.allocate(14)
            .put(TYPE_INJECT_KEYCODE.toByte())
            .put(action.toByte())
            .putInt(keycode)
            .putInt(repeat)
            .putInt(metaState)
            .array()

    fun touch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
    ): ByteArray =
        ByteBuffer.allocate(32)
            .put(TYPE_INJECT_TOUCH_EVENT.toByte())
            .put(action.toByte())
            .putLong(pointerId)
            .putInt(x)
            .putInt(y)
            .putShort(width.toShort())
            .putShort(height.toShort())
            .putShort(floatToU16fp(pressure))
            .putInt(actionButton)
            .putInt(buttons)
            .array()

    fun scroll(x: Int, y: Int, width: Int, height: Int, hscroll: Float, vscroll: Float, buttons: Int): ByteArray =
        ByteBuffer.allocate(21)
            .put(TYPE_INJECT_SCROLL_EVENT.toByte())
            .putInt(x)
            .putInt(y)
            .putShort(width.toShort())
            .putShort(height.toShort())
            .putShort(floatToI16fp((hscroll / 16f).coerceIn(-1f, 1f)))
            .putShort(floatToI16fp((vscroll / 16f).coerceIn(-1f, 1f)))
            .putInt(buttons)
            .array()

    fun setClipboard(sequence: Long, text: String, paste: Boolean): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_CLIPBOARD) it.copyOf(MAX_CLIPBOARD) else it }
        return ByteBuffer.allocate(14 + raw.size)
            .put(TYPE_SET_CLIPBOARD.toByte())
            .putLong(sequence)
            .put(if (paste) 1 else 0)
            .putInt(raw.size)
            .put(raw)
            .array()
    }

    /** Avvia un'app sul display virtuale; "+" davanti al pacchetto la chiude prima di riaprirla. */
    fun startApp(packageName: String): ByteArray {
        val raw = packageName.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        return ByteBuffer.allocate(2 + raw.size)
            .put(TYPE_START_APP.toByte())
            .put(raw.size.toByte())
            .put(raw)
            .array()
    }

    fun resetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO.toByte())

    private const val MAX_CLIPBOARD = 256 * 1024 - 14

    private fun floatToU16fp(f: Float): Short {
        val u = (f.coerceIn(0f, 1f) * 65536f).toInt()
        return (if (u >= 0xffff) 0xffff else u).toShort()
    }

    private fun floatToI16fp(f: Float): Short {
        var i = (f * 32768f).toInt()
        if (i >= 0x7fff) i = 0x7fff
        if (i < -0x8000) i = -0x8000
        return i.toShort()
    }
}
