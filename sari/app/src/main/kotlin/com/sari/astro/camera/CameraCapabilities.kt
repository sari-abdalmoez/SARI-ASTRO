package com.sari.astro.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager

/** Runtime Camera2 capability probe. Nothing is assumed; unsupported features are reported as such. */
data class CameraCaps(
    val id: String, val facingBack: Boolean, val rawSupported: Boolean, val manualSensor: Boolean,
    val rawSizes: List<String>, val exposureNs: LongRange?, val isoRange: IntRange?, val manualFocus: Boolean,
    val minFocusDiopters: Float, val focalLengthsMm: List<Float>, val activeArray: String?, val sensorOrientation: Int?,
    val maxFrameDurationNs: Long?,
) {
    val usableForRawAstro get() = rawSupported && manualSensor && exposureNs != null && isoRange != null
    fun describe(): String = buildString {
        append("Camera $id (${if (facingBack) "back" else "other"})\n")
        append(" RAW_SENSOR: ${if (rawSupported) "yes " + rawSizes.joinToString() else "NOT AVAILABLE"}\n")
        append(" Manual sensor: $manualSensor\n")
        append(" Exposure: ${exposureNs?.let { "${it.first / 1000} us - ${"%.1f".format(it.last / 1e9)} s" } ?: "n/a"}\n")
        append(" ISO: ${isoRange?.let { "${it.first}-${it.last}" } ?: "n/a"}\n")
        append(" Manual focus: $manualFocus  focal: ${focalLengthsMm.joinToString { "%.1f mm".format(it) }.ifEmpty { "n/a" }}\n")
        append(" Active array: ${activeArray ?: "n/a"}\n")
        if (!usableForRawAstro) append(" -> RAW astro capture unavailable on this camera.\n")
    }
}

object CameraProbe {
    fun probe(ctx: Context): List<CameraCaps> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        val ids = try { mgr.cameraIdList } catch (t: Throwable) { return emptyList() }
        return ids.mapNotNull { id ->
            try {
                val c = mgr.getCameraCharacteristics(id)
                val caps = c.get(CC.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
                val map = c.get(CC.SCALER_STREAM_CONFIGURATION_MAP)
                val raw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { "${it.width}x${it.height}" } ?: emptyList()
                val exp = c.get(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val iso = c.get(CC.SENSOR_INFO_SENSITIVITY_RANGE)
                val minFocus = c.get(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                CameraCaps(
                    id, c.get(CC.LENS_FACING) == CC.LENS_FACING_BACK,
                    CC.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps && raw.isNotEmpty(),
                    CC.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
                    raw, exp?.let { it.lower..it.upper }, iso?.let { it.lower..it.upper },
                    minFocus > 0f, minFocus,
                    c.get(CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
                    c.get(CC.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { "${it.width()}x${it.height()}" },
                    c.get(CC.SENSOR_ORIENTATION), c.get(CC.SENSOR_INFO_MAX_FRAME_DURATION),
                )
            } catch (t: Throwable) { null }  // a misbehaving camera must never crash the probe
        }
    }
}
