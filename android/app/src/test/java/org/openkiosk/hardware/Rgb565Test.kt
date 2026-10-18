package org.openkiosk.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class Rgb565Test {
    private fun pixel(out: ByteArray, width: Int, x: Int, y: Int): Int {
        val i = (y * width + x) * 2
        return (out[i].toInt() and 0xff) or ((out[i + 1].toInt() and 0xff) shl 8)
    }

    @Test
    fun packsPureColoursLittleEndian() {
        assertEquals(0xf800, pixel(Rgb565.encode(intArrayOf(0xffff0000.toInt()), 1, 1, false), 1, 0, 0))
        assertEquals(0x07e0, pixel(Rgb565.encode(intArrayOf(0xff00ff00.toInt()), 1, 1, false), 1, 0, 0))
        assertEquals(0x001f, pixel(Rgb565.encode(intArrayOf(0xff0000ff.toInt()), 1, 1, false), 1, 0, 0))
        val red = Rgb565.encode(intArrayOf(0xffff0000.toInt()), 1, 1, false)
        assertEquals(0x00, red[0].toInt() and 0xff)
        assertEquals(0xf8, red[1].toInt() and 0xff)
    }

    @Test
    fun mirrorsLeftToRightWhenAsked() {
        val white = 0xffffffff.toInt()
        val black = 0xff000000.toInt()
        val row = intArrayOf(white, black, black, black)
        assertEquals(0xffff, pixel(Rgb565.encode(row, 4, 1, true), 4, 3, 0))
        assertEquals(0, pixel(Rgb565.encode(row, 4, 1, true), 4, 0, 0))
        assertEquals(0xffff, pixel(Rgb565.encode(row, 4, 1, false), 4, 0, 0))
    }

    @Test
    fun keepsRowsInPlace() {
        val out = Rgb565.encode(intArrayOf(0xffff0000.toInt(), 0xff0000ff.toInt()), 1, 2, true)
        assertEquals(0xf800, pixel(out, 1, 0, 0))
        assertEquals(0x001f, pixel(out, 1, 0, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTheWrongPixelCount() {
        Rgb565.encode(IntArray(3), 2, 2, false)
    }
}
