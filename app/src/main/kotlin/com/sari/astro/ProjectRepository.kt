package com.sari.astro

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object ProjectRepository {
    enum class FrameType { LIGHT, DARK, FLAT, BIAS }
    data class FrameInfo(
        val path: String,
        val type: FrameType,
        val width: Int,
        val height: Int,
        val cfa: Int,
        val iso: Int,
        val exposureNs: Long,
        val name: String,
        val sizeBytes: Long
    )
    data class Session(val dir: File, val width: Int, val height: Int, val cfa: Int, val neutral: FloatArray?)

    private fun projectsRoot(ctx: android.content.Context) = File(ctx.filesDir, "projects").apply { mkdirs() }
    private fun sessionDirs(ctx: android.content.Context) = projectsRoot(ctx).listFiles { f -> f.isDirectory && f.name.startsWith("session-") }
        ?.sortedByDescending { it.lastModified() }.orEmpty()

    fun newSession(ctx: android.content.Context, width: Int, height: Int, cfa: Int, neutral: FloatArray?): Session {
        val dir = File(projectsRoot(ctx), "session-${System.currentTimeMillis()}")
        File(dir, "frames").mkdirs(); File(dir, "calibration").mkdirs(); File(dir, "results").mkdirs()
        val s = Session(dir,width,height,cfa,neutral)
        writeSessionMetadata(s)
        return s
    }

    fun latestSession(ctx: android.content.Context): Session? {
        val d = sessionDirs(ctx).firstOrNull() ?: return null
        return readSession(d)
    }

    fun ensureSession(ctx: android.content.Context, width: Int, height: Int, cfa: Int, neutral: FloatArray?): Session {
        val old = latestSession(ctx)
        return if (old != null && old.width == width && old.height == height && old.cfa == cfa) old else newSession(ctx,width,height,cfa,neutral)
    }

    private fun writeSessionMetadata(s: Session) {
        File(s.dir,"session.properties").printWriter().use { out ->
            out.println("width=${s.width}"); out.println("height=${s.height}"); out.println("cfa=${s.cfa}")
            if (s.neutral != null && s.neutral.size >= 3) out.println("neutral=${s.neutral[0]},${s.neutral[1]},${s.neutral[2]}")
        }
    }

    private fun readSession(dir: File): Session {
        val p=File(dir,"session.properties"); val m=mutableMapOf<String,String>()
        if(p.isFile)p.forEachLine{line->val i=line.indexOf('=');if(i>0)m[line.substring(0,i)]=line.substring(i+1)}
        val neutral=m["neutral"]?.split(',')?.mapNotNull{it.toFloatOrNull()}?.takeIf{it.size>=3}?.toFloatArray()
        return Session(dir,m["width"]?.toIntOrNull()?:0,m["height"]?.toIntOrNull()?:0,m["cfa"]?.toIntOrNull()?:0,neutral)
    }

    fun saveRawFrame(
        ctx: android.content.Context,
        image: Image,
        result: TotalCaptureResult,
        chars: CameraCharacteristics,
        type: FrameType
    ): FrameInfo? {
        val w=image.width; val h=image.height
        val cfa=chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val neutral=result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toFloat() }?.toFloatArray()
        val session=ensureSession(ctx,w,h,cfa,neutral)
        val sub=if(type==FrameType.LIGHT) File(session.dir,"frames") else File(File(session.dir,"calibration"),type.name.lowercase(Locale.US))
        sub.mkdirs()
        val idx=(sub.listFiles()?.count{it.extension=="f32"}?:0)+1
        val file=File(sub,String.format(Locale.US,"%s_%04d.f32",type.name.lowercase(Locale.US),idx))
        val ok=writeRawF32(image,result,chars,file)
        if(!ok) { file.delete(); return null }
        val iso=result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val exp=result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        File(file.parentFile,file.nameWithoutExtension+".properties").printWriter().use { out ->
            out.println("type=${type.name}");out.println("width=$w");out.println("height=$h");out.println("cfa=$cfa");out.println("iso=$iso");out.println("exposureNs=$exp")
        }
        return FrameInfo(file.absolutePath,type,w,h,cfa,iso,exp,file.name,file.length())
    }

    private fun writeRawF32(image:Image,result:TotalCaptureResult,chars:CameraCharacteristics,file:File):Boolean {
        val plane=image.planes.firstOrNull()?:return false
        val buf=plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val pixelStride=plane.pixelStride
        val rowStride=plane.rowStride
        if(pixelStride<2||rowStride<image.width*2)return false
        val staticWhite=chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat() ?: 65535f
        val dynWhite=result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat()
        val white=(dynWhite?:staticWhite).coerceAtLeast(1f)
        val dynBlack=result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val black=if(dynBlack!=null&&dynBlack.isNotEmpty()) dynBlack.average().toFloat() else 0f
        val rowFloats=FloatArray(image.width)
        val out=BufferedOutputStream(FileOutputStream(file),64*1024)
        val bb=ByteBuffer.allocate(image.width*4).order(ByteOrder.LITTLE_ENDIAN)
        out.use { o ->
            for(y in 0 until image.height){
                val rowBase=y*rowStride
                for(x in 0 until image.width){
                    val pos=rowBase+x*pixelStride
                    if(pos+1>=buf.limit()) return false
                    val raw=buf.getShort(pos).toInt() and 0xffff
                    rowFloats[x]=((raw-black)/(white-black).coerceAtLeast(1f)).coerceIn(0f,1f)
                }
                bb.clear()
                for(v in rowFloats)bb.putFloat(v)
                o.write(bb.array(),0,bb.position())
            }
        }
        return true
    }

    fun listFrames(ctx: android.content.Context): List<FrameInfo> {
        val s=latestSession(ctx)?:return emptyList(); return scan(s.dir)
    }
    fun scan(sessionDir:File):List<FrameInfo>{
        val s=readSession(sessionDir); val out=mutableListOf<FrameInfo>()
        fun scanDir(dir:File,type:FrameType){dir.listFiles{f->f.extension=="f32"}?.sortedBy{it.name}?.forEach{f->
            val m=readProps(File(f.parentFile,f.nameWithoutExtension+".properties"));out+=FrameInfo(f.absolutePath,type,s.width,s.height,s.cfa,m["iso"]?.toIntOrNull()?:0,m["exposureNs"]?.toLongOrNull()?:0L,f.name,f.length())
        }}
        scanDir(File(s.dir,"frames"),FrameType.LIGHT)
        for(t in listOf(FrameType.DARK,FrameType.FLAT,FrameType.BIAS))scanDir(File(File(s.dir,"calibration"),t.name.lowercase(Locale.US)),t)
        return out
    }
    fun latestCalibration(ctx: android.content.Context,type:FrameType):FrameInfo?=listFrames(ctx).filter{it.type==type}.maxByOrNull{File(it.path).lastModified()}

    private fun readProps(f:File):Map<String,String>{val m=mutableMapOf<String,String>();if(f.isFile)f.forEachLine{line->val i=line.indexOf('=');if(i>0)m[line.substring(0,i)]=line.substring(i+1)};return m}

    fun copyFileToUri(ctx: android.content.Context, source:File, uri:android.net.Uri):Boolean=runCatching{
        val out=ctx.contentResolver.openOutputStream(uri) ?: return@runCatching false
        out.use { o -> BufferedInputStream(FileInputStream(source),64*1024).use { inp -> val buf=ByteArray(64*1024);while(true){val n=inp.read(buf);if(n<0)break;o.write(buf,0,n)}} }
        true
    }.getOrDefault(false)
}
