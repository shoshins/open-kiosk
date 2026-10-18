package org.openkiosk.hardware

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * A secondary screen exposed as a raw framebuffer rather than an Android
 * display. Today that means the F6 panel's round 240×240 GC9A01 screen; see
 * docs/hardware/f6.md. Found by probing, never assumed.
 */
class SecondaryScreen private constructor(
    private val framebuffer: File,
    private val backlight: File?,
    val width: Int,
    val height: Int,
    private val mirrored: Boolean,
) {
    fun draw(bitmap: Bitmap): Boolean {
        val frame = if (bitmap.width == width && bitmap.height == height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)
        if (frame !== bitmap) frame.recycle()
        return write(framebuffer, Rgb565.encode(pixels, width, height, mirrored))
    }

    fun backlight(on: Boolean): Boolean =
        backlight != null && write(backlight, (if (on) "on" else "off").toByteArray())

    private fun write(file: File, bytes: ByteArray): Boolean =
        try {
            FileOutputStream(file).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            Log.w("OpenKiosk", "secondary screen write failed: ${file.path}", e)
            false
        }

    companion object {
        private val GC9A01 = Profile(
            framebuffer = "/dev/graphics/fb0",
            driverName = "/sys/class/graphics/fb0/name",
            expectedDriver = "fb_gc9a01",
            backlight = "/proc/vendor/lcd_ctrl",
            width = 240,
            height = 240,
            mirrored = true,
        )

        @Volatile private var cached: SecondaryScreen? = null
        @Volatile private var probed = false

        /** The screen if this device has one we can drive, otherwise null. */
        fun detect(): SecondaryScreen? {
            if (probed) return cached
            cached = probe(GC9A01)
            probed = true
            return cached
        }

        private fun probe(profile: Profile): SecondaryScreen? {
            val driver = runCatching { File(profile.driverName).readText().trim() }.getOrNull()
            val framebuffer = File(profile.framebuffer)
            // An enforcing SELinux policy denies the write even when the mode
            // bits allow it, so canWrite() alone is not proof.
            if (driver != profile.expectedDriver || !framebuffer.canWrite()) return null
            val backlight = File(profile.backlight).takeIf { it.canWrite() }
            return SecondaryScreen(framebuffer, backlight, profile.width, profile.height, profile.mirrored)
        }
    }

    private data class Profile(
        val framebuffer: String,
        val driverName: String,
        val expectedDriver: String,
        val backlight: String,
        val width: Int,
        val height: Int,
        val mirrored: Boolean,
    )
}

/** ARGB pixels to little-endian RGB565, optionally mirrored left to right. */
object Rgb565 {
    fun encode(argb: IntArray, width: Int, height: Int, mirrored: Boolean): ByteArray {
        require(argb.size == width * height) { "expected ${width * height} pixels, got ${argb.size}" }
        val out = ByteArray(width * height * 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[y * width + (if (mirrored) width - 1 - x else x)]
                val value = ((p shr 19 and 0x1f) shl 11) or ((p shr 10 and 0x3f) shl 5) or (p shr 3 and 0x1f)
                val i = (y * width + x) * 2
                out[i] = value.toByte()
                out[i + 1] = (value shr 8).toByte()
            }
        }
        return out
    }
}
