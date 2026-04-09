package com.jdcr.jdcrcamerabase.config

import androidx.camera.view.PreviewView.ScaleType

data class CameraPreviewConfig(
    val enable: Boolean = true,
    val scaleType: ScaleType = ScaleType.FILL_CENTER
)

data class CameraAnalysisConfig(
    val enable: Boolean = false,
    val throttler: Long = 200,
    val targetAspectRatio: Float? = null
)

data class CameraCaptureConfig(val enable: Boolean = true)

data class CameraStartConfig(
    val lensFacingBack: Boolean = true,
    val previewConfig: CameraPreviewConfig = CameraPreviewConfig(),
    val captureConfig: CameraCaptureConfig = CameraCaptureConfig(),
    val analysisConfig: CameraAnalysisConfig = CameraAnalysisConfig(),
) {
    companion object {
        val Capture = CameraStartConfig()
        val Analysis =
            CameraStartConfig(
                lensFacingBack = false,
                captureConfig = CameraCaptureConfig(false),
                analysisConfig = CameraAnalysisConfig(true)
            )
        val AnalysisNoPreview =
            CameraStartConfig(
                lensFacingBack = false,
                captureConfig = CameraCaptureConfig(false),
                analysisConfig = CameraAnalysisConfig(true),
                previewConfig = CameraPreviewConfig(false)
            )
    }
}