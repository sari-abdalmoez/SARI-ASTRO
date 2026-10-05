package com.sari.astro

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Conservative DNG reader for uncompressed monochrome-CFA DNGs.
 * It intentionally refuses compressed/packed formats rather than inventing pixels.
 */
object DngImporter {
    data class Result(
        val width: Int,
        val height: Int,
        val cfa: Int,
        val iso: Int,
        val exposureNs: Long,
        val blackLevel: Float,
        val whiteLevel: Float
    )

    private data class Entry(val type: Int, val count: Long, val valueField: Long, val valueOffset: Long)

    private class Tiff(private val file: RandomAccessFile) : AutoCloseable {
        val littleEndian: Boolean
        init {
            val a = file.readUnsignedByte(); val b = file.readUnsignedByte()
            littleEndian = a == 'I'.code && b == 'I'.code
            require((littleEndian && a == 'I'.code && b == 'I'.code) || (!littleEndian && a == 'M'.code && b == 'M'.code)) { "Not TIFF/DNG" }
            require(u16() == 42) { "Unsupported TIFF magic" }
        }
        private fun u16(): Int {
            val a = file.readUnsignedByte(); val b = file.readUnsignedByte()
            return if (littleEndian) a or (b shl 8) else (a shl 8) or b
        }
        private fun u32(): Long {
            val a = u16().toLong(); val b = u16().toLong()
            return if (littleEndian) a or (b shl 16) else (a shl 16) or b
        }
        fun firstIfd(): Long { file.seek(4); return u32() }
        fun readEntryMap(offset: Long): Map<Int, Entry> {
            file.seek(offset)
            val count = u16()
            val out = HashMap<Int, Entry>(count)
            repeat(count) {
                val tag = u16(); val type = u16(); val n = u32(); val raw = u32()
                val bytes = n * typeSize(type)
                val dataOffset = if (bytes <= 4) file.filePointer - 4 else raw
                out[tag] = Entry(type, n, raw, dataOffset)
            }
            return out
        }
        fun typeSize(type: Int) = when(type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12 -> 8
            else -> 1
        }
        fun values(e: Entry): LongArray {
            val out = LongArray(e.count.toInt())
            file.seek(e.valueOffset)
            for (i in out.indices) {
                out[i] = when(e.type) {
                    1, 2, 6, 7 -> file.readUnsignedByte().toLong()
                    3 -> u16().toLong()
                    4 -> u32()
                    8 -> readS16().toLong()
                    9 -> readS32().toLong()
                    else -> error("Unsupported integer TIFF field type ${e.type}")
                }
            }
            return out
        }
        fun rational(e: Entry): Double {
            file.seek(e.valueOffset)
            val n = u32(); val d = u32()
            return if (d == 0L) 0.0 else n.toDouble() / d.toDouble()
        }
        fun signedRational(e: Entry): Double {
            file.seek(e.valueOffset)
            val n = readS32(); val d = readS32()
            return if (d == 0) 0.0 else n.toDouble() / d.toDouble()
        }
        fun readS16(): Int { val v=u16(); return if(v and 0x8000!=0) v-65536 else v }
        fun readS32(): Int { val v=u32().toInt(); return v }
        fun readU16At(pos: Long): Int { file.seek(pos); return u16() }
        override fun close() { file.close() }
    }


    private fun cfaCode(values: LongArray): Int {
        if (values.size < 4) return 0
        return when(values.take(4)) {
            listOf(0L,1L,1L,2L) -> 0 // RGGB
            listOf(1L,0L,2L,1L) -> 1 // GRBG
            listOf(1L,2L,0L,1L) -> 2 // GBRG
            listOf(2L,1L,1L,0L) -> 3 // BGGR
            else -> -1
        }
    }

    fun importDng(
        source: File,
        f32Target: File,
        propertiesTarget: File
    ): Result {
        require(source.isFile) { "RAW source is missing" }
        Tiff(RandomAccessFile(source, "r")).use { tiff ->
                val e = tiff.readEntryMap(tiff.firstIfd())
                val width = (e[256]?.let{tiff.values(it).firstOrNull()} ?: 0L).toInt()
                val height = (e[257]?.let{tiff.values(it).firstOrNull()} ?: 0L).toInt()
                require(width > 0 && height > 0) { "Invalid DNG dimensions" }
                val bits = (e[258]?.let{tiff.values(it).firstOrNull()} ?: 0L).toInt()
                val compression = (e[259]?.let{tiff.values(it).firstOrNull()} ?: 1L).toInt()
                val photometric = (e[262]?.let{tiff.values(it).firstOrNull()} ?: 0L).toInt()
                val samples = (e[277]?.let{tiff.values(it).firstOrNull()} ?: 1L).toInt()
                require(compression == 1) { "Compressed DNG is not supported by the safe importer" }
                require(photometric == 32803) { "DNG is not a CFA RAW" }
                require(samples == 1) { "Multi-sample DNG is not supported" }
                require(bits == 16) { "Only 16-bit uncompressed DNG is supported" }

                val offsets = e[273]?.let{tiff.values(it)} ?: error("Missing StripOffsets")
                val counts = e[279]?.let{tiff.values(it)} ?: error("Missing StripByteCounts")
                require(offsets.isNotEmpty() && offsets.size == counts.size) { "Invalid strip tables" }
                val rowsPerStrip = (e[278]?.let{tiff.values(it).firstOrNull()} ?: height.toLong()).toInt().coerceAtLeast(1)
                val black = e[50714]?.let { vals ->
                    if (vals.type == 5 || vals.type == 10) tiff.rational(vals).toFloat()
                    else tiff.values(vals).average().toFloat()
                } ?: 0f
                val white = e[50717]?.let{tiff.values(it).firstOrNull()?.toFloat()}?.takeIf{it>0}
                    ?: 65535f
                val cfa = cfaCode(e[33422]?.let{tiff.values(it)} ?: LongArray(0))
                require(cfa >= 0) { "Unsupported CFA pattern" }
                val iso = e[34855]?.let{tiff.values(it).firstOrNull()?.toInt()} ?: 0
                val exposureNs = e[33434]?.let{ (tiff.rational(it) * 1_000_000_000.0).toLong() } ?: 0L

                val denom = max(1f, white - black)
                val raf = RandomAccessFile(source, "r")
                raf.use { src ->
                    val out = BufferedOutputStream(FileOutputStream(f32Target), 128 * 1024)
                    out.use { os ->
                        val rowBytes = width * 2
                        val rowBuf = ByteArray(rowBytes)
                        val floatBuf = ByteBuffer.allocate(width * 4).order(ByteOrder.LITTLE_ENDIAN)
                        var yBase = 0
                        for (s in offsets.indices) {
                            val stripRows = minOf(rowsPerStrip, height - yBase)
                            require(counts[s] >= stripRows.toLong() * rowBytes) { "Truncated DNG strip" }
                            src.seek(offsets[s])
                            repeat(stripRows) {
                                src.readFully(rowBuf)
                                floatBuf.clear()
                                for (x in 0 until width) {
                                    val a = rowBuf[x * 2].toInt() and 0xff
                                    val b = rowBuf[x * 2 + 1].toInt() and 0xff
                                    val raw = (if (tiff.littleEndian) (a or (b shl 8)) else ((a shl 8) or b)).toFloat()
                                    floatBuf.putFloat(((raw - black) / denom).coerceIn(0f, 1f))
                                }
                                os.write(floatBuf.array())
                                yBase++
                            }
                        }
                        require(yBase == height) { "DNG strips do not cover the full image" }
                    }
                }
                propertiesTarget.parentFile?.mkdirs()
                propertiesTarget.printWriter().use { out ->
                    out.println("type=LIGHT")
                    out.println("width=$width")
                    out.println("height=$height")
                    out.println("cfa=$cfa")
                    out.println("iso=$iso")
                    out.println("exposureNs=$exposureNs")
                    out.println("blackLevel=$black")
                    out.println("whiteLevel=$white")
                    out.println("importedDng=1")
                }
                return Result(width,height,cfa,iso,exposureNs,black,white)
            }
        }
    }
