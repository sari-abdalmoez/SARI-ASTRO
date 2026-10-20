package com.sari.astro

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Converts ordinary (non-RAW) JPEG / PNG / WebP images into the mono float frames
 * that the native stacking engine consumes.
 *
 * Honesty rules:
 *  - No Bayer/CFA pattern is invented. Output frames are tagged cfa = MONO (-1).
 *  - Values are linear-light luminance (sRGB decoded, then Rec.709 weights), in 0..1.
 *    Stacking happens in linear light, which is the physically correct domain for averaging.
 *  - Pixels are decoded in horizontal strips with BitmapRegionDecoder and written through a
 *    memory-mapped file, so a full-resolution bitmap is never held in RAM.
 *  - EXIF orientation is applied while writing, so frames line up with how the phone displays them.
 */
object ImageFrameImporter {
    const val MONO_CFA = -1

    /** Thrown for corrupted, truncated, or unsupported images. The message is shown to the user. */
    class ImportException(message: String) : Exception(message)

    data class Result(val width: Int, val height: Int, val cfa: Int, val format: String)

    private const val STRIP_ROWS = 256

    // sRGB 8-bit -> linear light, precomputed once.
    private val srgbToLinear = FloatArray(256) { i ->
        val c = i / 255.0
        (if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)).toFloat()
    }

    /** Identifies the real format from file content, not from the (often missing) extension. */
    fun sniffFormat(file: File): String? {
        val head = ByteArray(12)
        val n = file.inputStream().use { it.read(head) }
        if (n < 4) return null
        val b = head.map { it.toInt() and 0xff }
        return when {
            b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF -> "jpeg"
            b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 -> "png"
            n >= 12 && b[0] == 'R'.code && b[1] == 'I'.code && b[2] == 'F'.code && b[3] == 'F'.code &&
                b[8] == 'W'.code && b[9] == 'E'.code && b[10] == 'B'.code && b[11] == 'P'.code -> "webp"
            else -> null
        }
    }

    fun importToMono(
        source: File,
        f32Target: File,
        propertiesTarget: File,
        displayName: String,
        format: String
    ): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) throw ImportException("$displayName: corrupted or unreadable image")

        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        }.getOrDefault(1)
        val rotated = orientation in 5..8
        val dw = if (rotated) sh else sw
        val dh = if (rotated) sw else sh
        val byteCount = dw.toLong() * dh * 4L
        if (byteCount > Int.MAX_VALUE) throw ImportException("$displayName: image is too large to stack")

        val decoder = try {
            BitmapRegionDecoder.newInstance(source.absolutePath)
        } catch (t: Throwable) {
            null
        } ?: throw ImportException("$displayName: corrupted or unsupported $format image")

        try {
            f32Target.parentFile?.mkdirs()
            RandomAccessFile(f32Target, "rw").use { raf ->
                raf.setLength(byteCount)
                raf.channel.use { ch ->
                    val buf = ch.map(FileChannel.MapMode.READ_WRITE, 0, byteCount)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    var y0 = 0
                    while (y0 < sh) {
                        val y1 = minOf(sh, y0 + STRIP_ROWS)
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                            inPremultiplied = false
                        }
                        val strip = decoder.decodeRegion(Rect(0, y0, sw, y1), opts)
                            ?: throw ImportException("$displayName: failed to decode pixels")
                        try {
                            if (strip.width != sw) throw ImportException("$displayName: unexpected decode size")
                            val bh = strip.height
                            val px = IntArray(sw * bh)
                            strip.getPixels(px, 0, sw, 0, 0, sw, bh)
                            for (row in 0 until bh) {
                                val sy = y0 + row
                                for (sx in 0 until sw) {
                                    val c = px[row * sw + sx]
                                    val a = ((c ushr 24) and 0xff) / 255f
                                    val r = srgbToLinear[(c ushr 16) and 0xff] * a
                                    val g = srgbToLinear[(c ushr 8) and 0xff] * a
                                    val b = srgbToLinear[c and 0xff] * a
                                    val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                                    buf.putFloat(destIndex(orientation, sx, sy, sw, sh, dw) * 4, lum)
                                }
                            }
                        } finally {
                            strip.recycle()
                        }
                        y0 = y1
                    }
                    buf.force()
                }
            }
        } catch (oom: OutOfMemoryError) {
            f32Target.delete()
            throw ImportException("$displayName: not enough memory to decode this image")
        } catch (t: ImportException) {
            f32Target.delete()
            throw t
        } catch (t: Throwable) {
            f32Target.delete()
            throw ImportException("$displayName: corrupted or unsupported $format image")
        } finally {
            decoder.recycle()
        }

        propertiesTarget.parentFile?.mkdirs()
        propertiesTarget.printWriter().use { out ->
            out.println("type=LIGHT")
            out.println("width=$dw")
            out.println("height=$dh")
            out.println("cfa=$MONO_CFA")
            out.println("mono=1")
            out.println("iso=0")
            out.println("exposureNs=0")
            out.println("sensorTimestamp=0")
            out.println("importedFrom=${displayName.replace('\n', ' ')}")
            out.println("sourceFormat=$format")
            out.println("orientation=$orientation")
        }
        return Result(dw, dh, MONO_CFA, format)
    }

    /**
     * Flat pixel index (row-major, width dw) of a source pixel after EXIF orientation (1..8).
     * Returns an Int with no per-pixel allocation, which matters on low-memory phones.
     */
    private fun destIndex(o: Int, sx: Int, sy: Int, sw: Int, sh: Int, dw: Int): Int {
        val dx: Int
        val dy: Int
        when (o) {
            2 -> { dx = sw - 1 - sx; dy = sy }
            3 -> { dx = sw - 1 - sx; dy = sh - 1 - sy }
            4 -> { dx = sx; dy = sh - 1 - sy }
            5 -> { dx = sy; dy = sx }
            6 -> { dx = sh - 1 - sy; dy = sx }
            7 -> { dx = sh - 1 - sy; dy = sw - 1 - sx }
            8 -> { dx = sy; dy = sw - 1 - sx }
            else -> { dx = sx; dy = sy }
        }
        return dy * dw + dx
    }
}
