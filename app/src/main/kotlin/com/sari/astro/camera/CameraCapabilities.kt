package com.sari.astro.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager

data class CameraCaps(
    val id: String,
    val facingBack: Boolean,
    val rawSupported: Boolean,
    val manualSensor: Boolean,
    val rawSizes: List<String>,
    val exposureNs: LongRange?,
    val isoRange: IntRange?,
    val manualFocus: Boolean,
    val minFocusDiopters: Float,
    val focalLengthsMm: List<Float>,
    val activeArray: String?,
    val sensorOrientation: Int?,
    val maxFrameDurationNs: Long?,
    val maxAfRegions: Int,
    val maxAeRegions: Int
) {
    val usableForRawAstro: Boolean
        get() = rawSupported && manualSensor && exposureNs != null && isoRange != null
}

object CameraProbe {
    fun probe(ctx: Context): List<CameraCaps> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        return try {
            mgr.cameraIdList.mapNotNull { id ->
                runCatching {
                    val c = mgr.getCameraCharacteristics(id)
                    val caps = c.get(CC.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
                    val map = c.get(CC.SCALER_STREAM_CONFIGURATION_MAP)
                    val raw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { "${it.width}x${it.height}" }.orEmpty()
                    val exp = c.get(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val iso = c.get(CC.SENSOR_INFO_SENSITIVITY_RANGE)
                    val minFocus = c.get(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    val afModes = c.get(CC.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                    CameraCaps(
                        id = id,
                        facingBack = c.get(CC.LENS_FACING) == CC.LENS_FACING_BACK,
                        rawSupported = CC.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps && raw.isNotEmpty(),
                        manualSensor = CC.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
                        rawSizes = raw,
                        exposureNs = exp?.let { it.lower..it.upper },
                        isoRange = iso?.let { it.lower..it.upper },
                        manualFocus = minFocus > 0f && CC.CONTROL_AF_MODE_OFF in afModes && CC.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
                        minFocusDiopters = minFocus,
                        focalLengthsMm = c.get(CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
                        activeArray = c.get(CC.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { "${it.width()}x${it.height()}" },
                        sensorOrientation = c.get(CC.SENSOR_ORIENTATION),
                        maxFrameDurationNs = c.get(CC.SENSOR_INFO_MAX_FRAME_DURATION),
                        maxAfRegions = c.get(CC.CONTROL_MAX_REGIONS_AF) ?: 0,
                        maxAeRegions = c.get(CC.CONTROL_MAX_REGIONS_AE) ?: 0
                    )
                }.getOrNull()
            }
        } catch (_: Throwable) { emptyList() }
    }
}
