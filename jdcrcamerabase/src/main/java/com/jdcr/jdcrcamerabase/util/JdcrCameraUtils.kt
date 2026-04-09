package com.jdcr.jdcrcamerabase.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.view.ViewGroup
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import java.io.ByteArrayOutputStream
import java.io.File

fun ImageProxy.toJdcrBitmap(targetRatio: Float? = null, mirrorHorizontal: Boolean = false): Bitmap {

    fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val planeY = image.planes[0]
        val planeU = image.planes[1]
        val planeV = image.planes[2]
        val yBuffer = planeY.buffer
        val uBuffer = planeU.buffer
        val vBuffer = planeV.buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + (ySize / 2))
        val yRowStride = planeY.rowStride
        val uvRowStride = planeV.rowStride
        val uvPixelStride = planeV.pixelStride
        var pos = 0
        // 处理 Y 平面 (考虑 Row Stride)
        if (yRowStride == image.width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until image.height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, image.width)
                pos += image.width
            }
        }
        // 处理 UV 平面 (交错存储，考虑 Pixel Stride)
        // NV21 格式是 V-U-V-U 这样排布的
        for (row in 0 until image.height / 2) {
            for (col in 0 until image.width / 2) {
                val vPos = row * uvRowStride + col * uvPixelStride
                val uPos = row * uvRowStride + col * uvPixelStride
                nv21[pos++] = vBuffer.get(vPos)
                nv21[pos++] = uBuffer.get(uPos)
            }
        }

        return nv21
    }

    // 1. 将 YUV_420_888 转换为 NV21 字节数组
    val nv21 = yuv420ToNv21(this)
    val rotationDegrees = this.imageInfo.rotationDegrees

    // 2. 核心优化：在转 Jpeg 之前，直接计算出裁剪的 Rect
    var cropX = 0
    var cropY = 0
    var cropW = this.width
    var cropH = this.height

    if (targetRatio != null) {
        // 【极其关键】：Sensor 画面通常是横向的，而 TargetRatio 通常是竖屏 UI 的比例
        // 如果画面需要旋转 90 度或 270 度，我们在原始横向画面上裁剪时，比例必须倒过来算
        val isFlipped = rotationDegrees == 90 || rotationDegrees == 270
        val activeRatio = if (isFlipped) 1f / targetRatio else targetRatio

        val currentRatio = cropW.toFloat() / cropH.toFloat()

        // 容差判断
        if (kotlin.math.abs(currentRatio - activeRatio) > 0.005f) {
            if (currentRatio > activeRatio) {
                // 原图太宽，裁剪两边
                cropW = (cropH * activeRatio).toInt()
                cropX = (this.width - cropW) / 2
            } else {
                // 原图太高，裁剪上下
                cropH = (this.width / activeRatio).toInt()
                cropY = (this.height - cropH) / 2
            }
        }
    }

    // 生成目标区域的 Rect
    val cropRect = Rect(cropX, cropY, cropX + cropW, cropY + cropH)

    // 3. 使用 YuvImage 进行 JPEG 压缩 (只压缩需要的区域，大幅节省 CPU 和内存)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(cropRect, 100, out)

    val imageBytes = out.toByteArray()
    var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

    // 4. 处理画面旋转 (此时的 Bitmap 已经是裁剪后的小图了，旋转成本更低)
    if (rotationDegrees != 0 || mirrorHorizontal) {
        val matrix = Matrix()
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        if (mirrorHorizontal) {
            matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        val newBitmap =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (newBitmap !== bitmap) {
            bitmap.recycle()
        }
        bitmap = newBitmap
    }

    return bitmap
}

enum class JdcrCameraUIRotation(val value: Float) {
    DEGREES_0(0f),
    DEGREES_90(90f),
    DEGREES_180(180f),
    DEGREES_270(270f),
}

object JdcrCameraUtils {

    private fun getCacheDir(context: Context): String {
        val path = context.cacheDir.path + "/kn_add_material"
        if (!File(path).exists()) {
            File(path).mkdirs()
        }
        return path
    }

    fun getCacheOptions(context: Context, isFront: Boolean): ImageCapture.OutputFileOptions {
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = isFront // 前置使成片镜像，和预览一致
        }
        return ImageCapture.OutputFileOptions.Builder(
            File(
                getCacheDir(context),
                "material_" + System.currentTimeMillis() + ".jpg"
            )
        ).setMetadata(metadata).build()
    }

    fun relayoutPreviewView(previewView: PreviewView, viewRotation: JdcrCameraUIRotation) {
        val parent = (previewView.parent as? ViewGroup) ?: return
        val parentWidth = parent.width
        val parentHeight = parent.width
        val isSwap =
            (viewRotation == JdcrCameraUIRotation.DEGREES_90 || viewRotation == JdcrCameraUIRotation.DEGREES_270)
        val maxDimen = maxOf(parent.width, parent.height)
        if (isSwap) {
            previewView.layoutParams.width = maxDimen
            previewView.layoutParams.height = maxDimen
        } else {
            previewView.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            previewView.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        previewView.invalidate()
        previewView.requestLayout()
    }

}