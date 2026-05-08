package com.jdcr.jdcrcamerabase.config

import androidx.camera.view.PreviewView.ScaleType

data class CameraPreviewConfig(
    val enable: Boolean = true,
    val scaleType: ScaleType = ScaleType.FILL_CENTER
)

data class CameraAnalysisConfig(
    val enable: Boolean = false,
    val throttler: Long = 90,
    val targetAspectRatio: Float? = null
)

data class CameraCaptureConfig(val enable: Boolean = true)

data class JdcrCameraStartConfig(
    val lensFacingBack: Boolean = true,
    val previewConfig: CameraPreviewConfig = CameraPreviewConfig(),
    val captureConfig: CameraCaptureConfig = CameraCaptureConfig(),
    val analysisConfig: CameraAnalysisConfig = CameraAnalysisConfig(),
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