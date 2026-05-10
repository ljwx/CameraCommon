package com.jdcr.jdcrcamerabase.config

import androidx.camera.view.PreviewView.ScaleType

data class CameraPreviewConfig(
    var enable: Boolean = true,
    var scaleType: ScaleType = ScaleType.FILL_CENTER
)

data class CameraAnalysisConfig(
    var enable: Boolean = false,
    var throttler: Long = 90,
    var targetAspectRatio: Float? = null
)

data class CameraCaptureConfig(val enable: Boolean = true)

data class JdcrCameraStartConfig(
    var lensFacingBack: Boolean = true,
    var previewConfig: CameraPreviewConfig = CameraPreviewConfig(),
    var captureConfig: CameraCaptureConfig = CameraCaptureConfig(),
    var analysisConfig: CameraAnalysisConfig = CameraAnalysisConfig(),
) {
    companion object {
        val Test = JdcrCameraStartConfig(
            lensFacingBack = false,
            captureConfig = CameraCaptureConfig(true),
            analysisConfig = CameraAnalysisConfig(true, throttler = 2000)
        )
        val Capture = JdcrCameraStartConfig()
        val Analysis =
            JdcrCameraStartConfig(
                lensFacingBack = false,
                captureConfig = CameraCaptureConfig(false),
                analysisConfig = CameraAnalysisConfig(true)
            )
        val AnalysisNoPreview =
            JdcrCameraStartConfig(
                lensFacingBack = false,
                captureConfig = CameraCaptureConfig(false),
                analysisConfig = CameraAnalysisConfig(true),
                previewConfig = CameraPreviewConfig(false)
            )
        val QRCode = JdcrCameraStartConfig(
            lensFacingBack = true,
            captureConfig = CameraCaptureConfig(false),
            analysisConfig = CameraAnalysisConfig(true)
        )
    }
}