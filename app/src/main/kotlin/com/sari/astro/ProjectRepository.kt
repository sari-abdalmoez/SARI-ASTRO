package com.sari.astro

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProjectRepository {
    enum class FrameType { LIGHT, DARK, FLAT, BIAS, IMPORTED }

    data class FrameInfo(
        val path: String,
        val type: FrameType,
        val width: Int,
        val height: Int,
        val cfa: Int,
        val iso: Int,
        val exposureNs: Long,
        val sensorTimestamp: Long = 0L,
        val name: String,
        val sizeBytes: Long,
        val sourcePath: String? = null,
        val temperatureC: Float? = null,
        val stackable: Boolean = type == FrameType.LIGHT
    )

    data class Session(
        val dir: File,
        val width: Int,
        val height: Int,
        val cfa: Int,
        val neutral: FloatArray?,
        val title: String
    )

    data class ProjectInfo(
        val id: String,
        val dir: File,
        val title: String,
        val width: Int,
        val height: Int,
        val lightCount: Int,
        val calibrationCount: Int,
        val importedCount: Int,
        val totalBytes: Long,
        val firstLight: FrameInfo?,
        val lastModified: Long
    )

    data class ImportSummary(val imported: Int, val skipped: Int, val errors: List<String>)

    private fun projectsRoot(ctx: Context) = File(ctx.filesDir, "projects").apply { mkdirs() }

    private fun sessionDirs(ctx: Context) = projectsRoot(ctx)
        .listFiles { f -> f.isDirectory && f.name.startsWith("session-") }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    private fun defaultTitle(): String = "Astro " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    fun newSession(
        ctx: Context,
        width: Int,
        height: Int,
        cfa: Int,
        neutral: FloatArray?,
        title: String = defaultTitle()
    ): Session {
        val dir = File(projectsRoot(ctx), "session-${System.currentTimeMillis()}")
        File(dir, "frames").mkdirs()
        File(dir, "calibration").mkdirs()
        File(dir, "results").mkdirs()
        File(dir, "imports").mkdirs()
        File(dir, "originals").mkdirs()
        val s = Session(dir, width, height, cfa, neutral, title)
        writeSessionMetadata(s)
        return s
    }

    fun latestSession(ctx: Context): Session? = sessionDirs(ctx).firstOrNull()?.let(::readSession)

    fun sessionForDir(dir: File): Session = readSession(dir)

    fun sessionForId(ctx: Context, id: String): Session? = sessionDirs(ctx).firstOrNull { it.name == id }?.let(::readSession)

    fun ensureSession(ctx: Context, width: Int, height: Int, cfa: Int, neutral: FloatArray?): Session {
        val old = latestSession(ctx)
        return if (old != null && old.width == width && old.height == height && old.cfa == cfa) old
        else newSession(ctx, width, height, cfa, neutral)
    }

    fun renameProject(sessionDir: File, title: String): Boolean {
        val clean = title.trim().replace(Regex("[\\r\\n]+"), " ").take(64)
        if (clean.isBlank()) return false
        return runCatching {
            val s = readSession(sessionDir)
            writeSessionMetadata(s.copy(title = clean))
            true
        }.getOrDefault(false)
    }

    private fun writeSessionMetadata(s: Session) {
        File(s.dir, "session.properties").printWriter().use { out ->
            out.println("title=${s.title.replace("\n", " ")}")
            out.println("width=${s.width}")
            out.println("height=${s.height}")
            out.println("cfa=${s.cfa}")
            if (s.neutral != null && s.neutral.size >= 3) out.println("neutral=${s.neutral[0]},${s.neutral[1]},${s.neutral[2]}")
        }
    }

    private fun readSession(dir: File): Session {
        val p = File(dir, "session.properties")
        val m = mutableMapOf<String, String>()
        if (p.isFile) p.forEachLine { line ->
            val i = line.indexOf('=')
            if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
        }
        val neutral = m["neutral"]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size >= 3 }?.toFloatArray()
        val title = m["title"]?.takeIf { it.isNotBlank() } ?: "Astro Session"
        return Session(
            dir,
            m["width"]?.toIntOrNull() ?: 0,
            m["height"]?.toIntOrNull() ?: 0,
            m["cfa"]?.toIntOrNull() ?: 0,
            neutral,
            title
        )
    }

    fun listProjects(ctx: Context): List<ProjectInfo> = sessionDirs(ctx).mapNotNull { dir ->
        runCatching {
            val s = readSession(dir)
            val frames = scan(dir)
            val light = frames.filter { it.type == FrameType.LIGHT && it.stackable }
            val cal = frames.count { it.type == FrameType.DARK || it.type == FrameType.FLAT || it.type == FrameType.BIAS }
            val imported = frames.count { it.type == FrameType.IMPORTED }
            ProjectInfo(
                id = dir.name,
                dir = dir,
                title = s.title,
                width = s.width,
                height = s.height,
                lightCount = light.size,
                calibrationCount = cal,
                importedCount = imported,
                totalBytes = directoryBytes(dir),
                firstLight = light.maxByOrNull { File(it.path).lastModified() },
                lastModified = dir.lastModified()
            )
        }.getOrNull()
    }

    private fun directoryBytes(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun saveRawFrame(
        ctx: Context,
        image: Image,
        result: TotalCaptureResult,
        chars: CameraCharacteristics,
        type: FrameType,
        sessionDir: File? = null
    ): FrameInfo? {
        val w = image.width
        val h = image.height
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toFloat() }?.toFloatArray()
        val session = if (sessionDir != null && sessionDir.isDirectory) {
            val existing = readSession(sessionDir)
            val compatible = (existing.width <= 0 || existing.width == w) &&
                (existing.height <= 0 || existing.height == h) &&
                (existing.cfa == 0 || existing.cfa == cfa)
            if (!compatible) return null
            if (existing.width <= 0 || existing.height <= 0 || existing.cfa == 0) {
                val updated = existing.copy(
                    width = if (existing.width > 0) existing.width else w,
                    height = if (existing.height > 0) existing.height else h,
                    cfa = if (existing.cfa != 0) existing.cfa else cfa,
                    neutral = existing.neutral ?: neutral
                )
                writeSessionMetadata(updated)
                updated
            } else existing
        } else {
            ensureSession(ctx, w, h, cfa, neutral)
        }
        val sub = if (type == FrameType.LIGHT) File(session.dir, "frames")
        else File(File(session.dir, "calibration"), type.name.lowercase(Locale.US))
        sub.mkdirs()
        val idx = (sub.listFiles()?.count { it.extension == "f32" } ?: 0) + 1
        val file = File(sub, String.format(Locale.US, "%s_%04d.f32", type.name.lowercase(Locale.US), idx))
        val ok = writeRawF32(image, result, chars, file)
        if (!ok) { file.delete(); return null }
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val temp: Float? = null
        val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: image.timestamp
        val duplicate = sub.listFiles { f -> f.isFile && f.extension.equals("properties", true) }
            ?.any { pf ->
                pf.readLines().any { it == "sensorTimestamp=$sensorTimestamp" }
            } == true
        if (duplicate) {
            file.delete()
            return null
        }
        File(file.parentFile, file.nameWithoutExtension + ".properties").printWriter().use { out ->
            out.println("type=${type.name}")
            out.println("width=$w")
            out.println("height=$h")
            out.println("cfa=$cfa")
            out.println("iso=$iso")
            out.println("exposureNs=$exp")
            out.println("sensorTimestamp=$sensorTimestamp")
            if (temp != null) out.println("temperatureC=$temp")
        }
        return FrameInfo(file.absolutePath, type, w, h, cfa, iso, exp, sensorTimestamp = sensorTimestamp, name = file.name, sizeBytes = file.length(), temperatureC = temp?.toFloat(), stackable = type == FrameType.LIGHT)
    }

    private fun writeRawF32(image: Image, result: TotalCaptureResult, chars: CameraCharacteristics, file: File): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 2 || rowStride < image.width * 2) return false
        val staticWhite = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat() ?: 65535f
        val dynWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat()
        val white = (dynWhite ?: staticWhite).coerceAtLeast(1f)
        val dynBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val black = if (dynBlack != null && dynBlack.isNotEmpty()) dynBlack.average().toFloat() else 0f
        val rowFloats = FloatArray(image.width)
        val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
        val bb = ByteBuffer.allocate(image.width * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.use { o ->
            for (y in 0 until image.height) {
                val rowBase = y * rowStride
                for (x in 0 until image.width) {
                    val pos = rowBase + x * pixelStride
                    if (pos + 1 >= buf.limit()) return false
                    val raw = buf.getShort(pos).toInt() and 0xffff
                    rowFloats[x] = ((raw - black) / (white - black).coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
                bb.clear()
                for (v in rowFloats) bb.putFloat(v)
                o.write(bb.array(), 0, bb.position())
            }
        }
        return true
    }

    /**
     * Dedicated public MediaStore folder for one Astro project.
     * The session id is included so two projects with the same title never share an album.
     */
    fun mediaStoreRelativePath(sessionDir: File, bucket: String): String {
        val title = safeName(readSession(sessionDir).title).ifBlank { "Astro" }
        val id = sessionDir.name.removePrefix("session-")
        val cleanBucket = safeName(bucket).ifBlank { "RAW" }
        return "Pictures/SARI Astro/$title-$id/$cleanBucket"
    }

    fun listFrames(ctx: Context): List<FrameInfo> = latestSession(ctx)?.let { scan(it.dir) } ?: emptyList()

    fun scan(sessionDir: File): List<FrameInfo> {
        val s = readSession(sessionDir)
        val out = mutableListOf<FrameInfo>()
        fun scanDir(dir: File, type: FrameType, stackable: Boolean = type == FrameType.LIGHT) {
            dir.listFiles { f -> f.extension.equals("f32", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    val m = readProps(File(f.parentFile, f.nameWithoutExtension + ".properties"))
                    out += FrameInfo(
                        f.absolutePath, type, m["width"]?.toIntOrNull() ?: s.width, m["height"]?.toIntOrNull() ?: s.height, m["cfa"]?.toIntOrNull() ?: s.cfa,
                        m["iso"]?.toIntOrNull() ?: 0,
                        m["exposureNs"]?.toLongOrNull() ?: 0L,
                        m["sensorTimestamp"]?.toLongOrNull() ?: 0L,
                        name = f.name,
                        sizeBytes = f.length(),
                        sourcePath = m["sourcePath"],
                        temperatureC = m["temperatureC"]?.toFloatOrNull(),
                        stackable = stackable
                    )
                }
        }
        scanDir(File(s.dir, "frames"), FrameType.LIGHT, true)
        for (t in listOf(FrameType.DARK, FrameType.FLAT, FrameType.BIAS)) {
            scanDir(File(File(s.dir, "calibration"), t.name.lowercase(Locale.US)), t, false)
        }
        File(s.dir, "imports").listFiles { f ->
            f.isFile && f.extension.lowercase(Locale.US) in setOf("jpg", "jpeg", "png", "webp")
        }?.sortedBy { it.name }?.forEach { f ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            out += FrameInfo(f.absolutePath, FrameType.IMPORTED, bounds.outWidth, bounds.outHeight, 0, 0, 0L, name = f.name, sizeBytes = f.length(), stackable = false)
        }
        return out
    }

    fun latestCalibration(ctx: Context, type: FrameType): FrameInfo? = listFrames(ctx).filter { it.type == type }.maxByOrNull { File(it.path).lastModified() }

    fun latestCalibrationInProject(sessionDir: File, type: FrameType): FrameInfo? = scan(sessionDir)
        .filter { it.type == type }
        .maxByOrNull { File(it.path).lastModified() }

    fun importUris(ctx: Context, sessionDir: File, uris: List<Uri>): ImportSummary {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val originals = File(sessionDir, "originals").apply { mkdirs() }
        val imports = File(sessionDir, "imports").apply { mkdirs() }
        val frames = File(sessionDir, "frames").apply { mkdirs() }
        val session = readSession(sessionDir)
        for ((index, uri) in uris.withIndex()) {
            try {
                val name = displayName(ctx, uri) ?: "import_${System.currentTimeMillis()}_$index"
                val lower = name.lowercase(Locale.US)
                if (lower.endsWith(".dng") || ctx.contentResolver.getType(uri)?.contains("dng", true) == true) {
                    val safe = safeName(name.substringBeforeLast('.'))
                    val original = uniqueFile(originals, safe + ".dng")
                    require(copyUri(ctx, uri, original)) { "Unable to copy DNG" }
                    val f32 = uniqueFile(frames, safe + ".f32")
                    val props = File(frames, f32.nameWithoutExtension + ".properties")
                    val dng = runCatching { DngImporter.importDng(original, f32, props) }.getOrElse {
                        f32.delete(); props.delete(); throw it
                    }
                    if (session.width <= 0 || session.height <= 0) writeSessionMetadata(session.copy(width = dng.width, height = dng.height, cfa = dng.cfa))
                    imported++
                } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || ctx.contentResolver.getType(uri)?.startsWith("image/") == true) {
                    val ext = lower.substringAfterLast('.', "jpg").take(5)
                    require(copyUri(ctx, uri, uniqueFile(imports, safeName(name.removeSuffix(".$ext")) + ".$ext"))) { "Unable to copy image" }
                    imported++
                } else {
                    skipped++
                }
            } catch (t: Throwable) {
                errors += (t.message ?: "Import failed")
            }
        }
        // Touch the project so it naturally bubbles to the top of the album grid.
        session.dir.setLastModified(System.currentTimeMillis())
        return ImportSummary(imported, skipped, errors)
    }

    private fun uniqueFile(dir: File, preferred: String): File {
        var candidate = File(dir, preferred)
        var i = 2
        while (candidate.exists()) {
            val base = candidate.nameWithoutExtension
            val ext = candidate.extension
            candidate = File(dir, "$base-$i${if (ext.isBlank()) "" else ".${ext}"}")
            i++
        }
        return candidate
    }

    private fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(80).ifBlank { "import" }

    private fun displayName(ctx: Context, uri: Uri): String? {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun copyUri(ctx: Context, uri: Uri, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val input = ctx.contentResolver.openInputStream(uri) ?: return@runCatching false
        input.use { i -> FileOutputStream(target).use { o -> i.copyTo(o, 128 * 1024) } }
        true
    }.getOrDefault(false)

    fun copyFileToUri(ctx: Context, source: File, uri: Uri): Boolean = runCatching {
        val out = ctx.contentResolver.openOutputStream(uri) ?: return@runCatching false
        out.use { o -> BufferedInputStream(FileInputStream(source), 64 * 1024).use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; o.write(buf, 0, n) }
        }}
        true
    }.getOrDefault(false)

    private fun readProps(f: File): Map<String, String> {
        val m = mutableMapOf<String, String>()
        if (f.isFile) f.forEachLine { line ->
            val i = line.indexOf('=')
            if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
        }
        return m
    }
}
