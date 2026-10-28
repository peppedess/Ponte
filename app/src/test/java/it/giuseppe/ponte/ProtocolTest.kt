package it.giuseppe.ponte

import it.giuseppe.ponte.scrcpy.ControlMessages
import it.giuseppe.ponte.scrcpy.PacketHeader
import it.giuseppe.ponte.scrcpy.parseAppList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I messaggi devono essere identici byte per byte a quelli del client ufficiale scrcpy 5
 * (stessi vettori di app/tests/test_control_msg_serialize.c).
 */
class ProtocolTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun keycode() {
        val msg = ControlMessages.keycode(action = 1, keycode = 66, repeat = 5, metaState = 0x41)
        assertArrayEquals(
            bytes(0, 0x01, 0, 0, 0, 0x42, 0, 0, 0, 0x05, 0, 0, 0, 0x41),
            msg,
        )
    }

    @Test
    fun touch() {
        val msg = ControlMessages.touch(
            action = 0, pointerId = 0x1234567887654321L, x = 100, y = 200, width = 1080, height = 1920,
            pressure = 1f, actionButton = 1, buttons = 1,
        )
        assertArrayEquals(
            bytes(
                2, 0x00,
                0x12, 0x34, 0x56, 0x78, 0x87, 0x65, 0x43, 0x21,
                0, 0, 0, 0x64, 0, 0, 0, 0xc8,
                0x04, 0x38, 0x07, 0x80,
                0xff, 0xff,
                0, 0, 0, 1,
                0, 0, 0, 1,
            ),
            msg,
        )
    }

    @Test
    fun mousePointerIdIsAllOnes() {
        val msg = ControlMessages.touch(7, ControlMessages.POINTER_ID_MOUSE, 1, 1, 10, 10, 0f, 0, 0)
        assertTrue((2..9).all { msg[it] == 0xff.toByte() })
    }

    @Test
    fun scroll() {
        val msg = ControlMessages.scroll(260, 1026, 1080, 1920, hscroll = 16f, vscroll = -16f, buttons = 1)
        assertArrayEquals(
            bytes(
                3,
                0, 0, 0x01, 0x04, 0, 0, 0x04, 0x02,
                0x04, 0x38, 0x07, 0x80,
                0x7f, 0xff,
                0x80, 0x00,
                0, 0, 0, 1,
            ),
            msg,
        )
    }

    @Test
    fun injectText() {
        val msg = ControlMessages.injectText("hello, world!")
        assertEquals(1, msg[0].toInt())
        assertArrayEquals(bytes(0, 0, 0, 13), msg.copyOfRange(1, 5))
        assertEquals("hello, world!", String(msg, 5, 13))
    }

    @Test
    fun injectTextIsTruncatedTo300Bytes() {
        assertEquals(5 + 300, ControlMessages.injectText("a".repeat(500)).size)
    }

    @Test
    fun setClipboard() {
        val msg = ControlMessages.setClipboard(0x0102030405060708L, "hello, world!", paste = true)
        assertArrayEquals(
            bytes(9, 1, 2, 3, 4, 5, 6, 7, 8, 1, 0, 0, 0, 13),
            msg.copyOfRange(0, 14),
        )
        assertEquals("hello, world!", String(msg, 14, 13))
    }

    @Test
    fun startApp() {
        val msg = ControlMessages.startApp("firefox")
        assertArrayEquals(bytes(16, 7) + "firefox".toByteArray(), msg)
    }

    @Test
    fun resetVideo() {
        assertArrayEquals(bytes(17), ControlMessages.resetVideo())
    }

    @Test
    fun scanFile() {
        val msg = ControlMessages.scanFile("/sdcard/a.png")
        assertArrayEquals(bytes(22, 0, 0, 0, 13) + "/sdcard/a.png".toByteArray(), msg)
    }

    @Test
    fun sessionPacket() {
        val header = bytes(0x80, 0, 0, 1, 0, 0, 0x09, 0x60, 0, 0, 0x04, 0x38)
        assertEquals(PacketHeader.Session(2400, 1080), PacketHeader.parse(header))
    }

    @Test
    fun mediaPacketWithFlags() {
        // config + key frame, PTS 1000, 4096 byte
        val ptsFlags = (1L shl 62) or (1L shl 61) or 1000L
        val header = java.nio.ByteBuffer.allocate(12).putLong(ptsFlags).putInt(4096).array()
        val parsed = PacketHeader.parse(header) as PacketHeader.Media
        assertEquals(1000L, parsed.pts)
        assertTrue(parsed.config)
        assertTrue(parsed.keyFrame)
        assertEquals(4096, parsed.size)
    }

    @Test
    fun appList() {
        val output = """
            [server] INFO: Device: [Google] google Pixel 10 (Android 16)
            [server] INFO: List of apps:
             * Calcolatrice                   com.google.android.calculator
             - Un nome di app davvero molto lungo
                                            com.example.long
             - YouTube                        com.google.android.youtube
        """.trimIndent()
        val apps = parseAppList(output)
        assertEquals(listOf("Un nome di app davvero molto lungo", "YouTube", "Calcolatrice"), apps.map { it.name })
        assertEquals("com.example.long", apps[0].packageName)
        assertTrue(apps.last().system)
    }
}
