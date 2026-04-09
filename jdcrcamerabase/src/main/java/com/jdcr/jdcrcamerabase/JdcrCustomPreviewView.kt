package com.jdcr.jdcrcamerabase

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import kotlin.math.max
import androidx.core.graphics.toColorInt
import com.jdcr.jdcrcamerabase.config.JdcrCameraPreviewConfig
import com.jdcr.jdcrcamerabase.util.JdcrCameraLog

private fun Float?.dp2Px(defaultPx: Int, context: Context): Int {
    if (this == null) {
        return defaultPx
    }
    context.resources?.displayMetrics?.density?.let {
        return ((this * it) + 0.5).toInt()
    }
    return defaultPx
}

class JdcrCustomPreviewView(
    private val context: Context,
    private val option: JdcrCameraPreviewConfig,
) : FrameLayout(context) {

    init {
        id = R.id.jdcrcamerabase_custom_preview_container
        addPreviewView()
    }

    override fun dispatchDraw(canvas: Canvas) {
        try {
            val save = canvas.save()
            drawClip(canvas)
            super.dispatchDraw(canvas)
            if (option.showOuterBorder == true) {
                canvas.restoreToCount(save)
                drawWhiteBorder(canvas)
            }
            if (option.showMask == true) {
                drawMaskAndBorder(canvas)
            }
        } catch (e: Exception) {
            JdcrCameraLog.d("绘制圆角失败：${e.message}")
            super.dispatchDraw(canvas)
        }
    }

    private fun drawClip(canvas: Canvas) {
        val path = Path()
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radiusTopPx = option.outerRadiusTop.dp2Px(48, context).toFloat()
        val radiusBottomPx = option.outerRadiusBottom.dp2Px(48, context).toFloat()
        val radii = floatArrayOf(
            radiusTopPx, radiusTopPx,
            radiusTopPx, radiusTopPx,
            radiusBottomPx, radiusBottomPx,
            radiusBottomPx, radiusBottomPx
        )
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.clipPath(path)
    }

    private fun drawWhiteBorder(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val strokePx = 4f.dp2Px(4, context).toFloat()
        if (width < strokePx || height < strokePx) return
        val half = strokePx / 2f
        val rect = RectF(half, half, width - half, height - half)
        val radiusTopPx = option.outerRadiusTop.dp2Px(48, context).toFloat()
        val radiusBottomPx = option.outerRadiusBottom.dp2Px(48, context).toFloat()
        val radiusTopInner = max(0f, radiusTopPx - half)
        val radiusBottomInner = max(0f, radiusBottomPx - half)
        val radii = floatArrayOf(
            radiusTopInner, radiusTopInner,
            radiusTopInner, radiusTopInner,
            radiusBottomInner, radiusBottomInner,
            radiusBottomInner, radiusBottomInner
        )
        val path = Path().apply { addRoundRect(rect, radii, Path.Direction.CW) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = Color.WHITE
        }
        canvas.drawPath(path, paint)
    }

    private fun drawMaskAndBorder(canvas: Canvas) {
        try {
            // 获取虚线边框的位置和尺寸
            val borderRect = option.getOverlayRectF(context)
            val strokeRect = option.getOverlayRectFStroke(context)

            val layerId = canvas.saveLayer(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                null,
                Canvas.ALL_SAVE_FLAG
            )

            // 1. 先绘制整个区域的半透明遮罩
            val maskPaint = Paint().apply {
                color = "#00000000".toColorInt()
                style = android.graphics.Paint.Style.FILL
                alpha = (256 * (option.maskAlpha ?: 0.5f)).toInt()
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

            // 2. 在虚线框区域"挖掉"遮罩，使其完全透明
            val clearPaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            val radiusMaskPx = option.maskRadius.dp2Px(36, context).toFloat()
            if (radiusMaskPx > 0) {
                canvas.drawRoundRect(borderRect, radiusMaskPx, radiusMaskPx, clearPaint)
            } else {
                canvas.drawRect(borderRect, clearPaint)
            }
            canvas.restoreToCount(layerId)

            // 3. 最后绘制虚线边框
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f // 4px宽度
                color = Color.WHITE // 白色虚线
                pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f) // 虚线模式
            }

            if (radiusMaskPx > 0) {
                canvas.drawRoundRect(strokeRect, radiusMaskPx, radiusMaskPx, borderPaint)
            } else {
                canvas.drawRect(strokeRect, borderPaint)
            }
            JdcrCameraLog.d("绘制遮罩和虚线边框完成")
        } catch (e: Exception) {
            JdcrCameraLog.d("绘制遮罩和虚线边框失败：${e.message}")
        }
    }

    private fun addPreviewView() {
        val width = option.w.dp2Px(900, context)
        val height = option.h.dp2Px(792, context)
        val marginStart = option.x.dp2Px(624, context)
        val marginTop = option.y.dp2Px(18, context)
        val layout = LayoutParams(width, height)
        layout.marginStart = marginStart
        layout.topMargin = marginTop
        JdcrCameraLog.d("预览画面左上间距:$marginStart,$marginTop,大小:$width,$height")
        layoutParams = layout
        val previewView = PreviewView(context).apply {
            id = R.id.jdcrcamerabase_preview_view
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun getPreviewView(): PreviewView {
        return findViewById(R.id.jdcrcamerabase_preview_view)
    }

}