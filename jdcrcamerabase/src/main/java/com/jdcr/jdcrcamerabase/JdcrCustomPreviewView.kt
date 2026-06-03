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
import android.util.TypedValue
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import kotlin.math.max
import androidx.core.graphics.toColorInt
import com.jdcr.jdcrcamerabase.config.JdcrCameraPreviewConfig
import com.jdcr.jdcrcamerabase.config.JdcrPreviewOuterBorder
import com.jdcr.jdcrcamerabase.config.JdcrPreviewUpperMask
import com.jdcr.jdcrcamerabase.config.JdcrPreviewUpperMaskInnerStroke
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

private fun Float.dp2Px(context: Context): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this,
        context.resources.displayMetrics
    )
}

class JdcrCustomPreviewView(
    private val context: Context,
    private val option: JdcrCameraPreviewConfig,
) : FrameLayout(context) {

    init {
        id = R.id.jdcrcamerabase_custom_preview_container
        addPreviewView()
        isClickable = true
    }

    override fun dispatchDraw(canvas: Canvas) {
        try {
            val save = canvas.save()
            drawClip(canvas)
            super.dispatchDraw(canvas)
            if (option.outerBorder != null) {
                canvas.restoreToCount(save)
                drawOuterBorder(canvas, option.outerBorder)
            }
            if (option.upperMask != null) {
                drawUpperMask(canvas, option.upperMask)
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

    private fun drawOuterBorder(canvas: Canvas, outerBorder: JdcrPreviewOuterBorder) {
        if (width <= 0 || height <= 0) return
        val strokePx = outerBorder.width.dp2Px(context)
        if (width < strokePx || height < strokePx) return
        val half = strokePx / 2f
        val rect = RectF(half, half, width - half, height - half)
        val radiusTopPx = option.outerRadiusTop.dp2Px(context)
        val radiusBottomPx = option.outerRadiusBottom.dp2Px(context)
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
            color = outerBorder.color
        }
        canvas.drawPath(path, paint)
    }

    private fun drawUpperMask(canvas: Canvas, params: JdcrPreviewUpperMask) {
        try {

            fun getOverlayRectF(context: Context, diff: Int = 0): RectF {
                val left = (params.marginHorizontal + diff).dp2Px(context)
                val top = (params.marginVertical + diff).dp2Px(context)
                val right = (option.w - params.marginHorizontal - diff).dp2Px(context)
                val bottom = (option.h - params.marginVertical - diff).dp2Px(context)
                return RectF(left, top, right, bottom)
            }

            // 获取虚线边框的位置和尺寸
            val borderRect = getOverlayRectF(context, 1)
            val strokeRect = getOverlayRectF(context)

            val fullScreenRect = RectF(
                0f,
                0f,
                width.toFloat(),
                height.toFloat()
            )
            val layerId = canvas.saveLayer(fullScreenRect, null)

            // 1. 先绘制整个区域的半透明遮罩
            val maskPaint = Paint().apply {
                color = "#00000000".toColorInt()
                style = android.graphics.Paint.Style.FILL
                alpha = (256 * (params.maskAlpha)).toInt()
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

            // 2. 在虚线框区域"挖掉"遮罩，使其完全透明
            val clearPaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            val radiusMaskPx = params.innerRadius.dp2Px(context)
            if (radiusMaskPx > 0) {
                canvas.drawRoundRect(borderRect, radiusMaskPx, radiusMaskPx, clearPaint)
            } else {
                canvas.drawRect(borderRect, clearPaint)
            }
            canvas.restoreToCount(layerId)

            // 3. 最后绘制虚线边框
            val borderPaint = getUpperInnerBorderPaint(params.innerStroke)

            if (radiusMaskPx > 0) {
                canvas.drawRoundRect(strokeRect, radiusMaskPx, radiusMaskPx, borderPaint)
            } else {
                canvas.drawRect(strokeRect, borderPaint)
            }
            JdcrCameraLog.d("绘制遮罩完成")
        } catch (e: Exception) {
            JdcrCameraLog.d("绘制遮罩失败：${e.message}")
        }
    }

    private fun getUpperInnerBorderPaint(stroke: JdcrPreviewUpperMaskInnerStroke?): Paint {
        stroke ?: return Paint().apply { color = Color.TRANSPARENT }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke.strokeWidth.dp2Px(context)
            color = stroke.strokeColor
            pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f) // 虚线模式
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
            implementationMode = option.previewConfig.implementationMode
        }
        JdcrCameraLog.i("预览模式: ${previewView.implementationMode}")
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun getPreviewView(): PreviewView {
        return findViewById(R.id.jdcrcamerabase_preview_view)
    }

    fun capturePreviewBitmap(): android.graphics.Bitmap? {
        val previewView = getPreviewView()

        // 1. 获取 CameraX 渲染的当前帧图片
        val cameraBitmap = previewView.bitmap ?: return null

        // 2. 拷贝为可修改的 ARGB_8888 格式（支持透明叠加）
        val resultBitmap = cameraBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        // 3. 暂时隐藏 PreviewView，避免它在自身的 draw 流程中绘制黑色占位图覆盖已获取的相机画面
        val originalVisibility = previewView.visibility
        previewView.visibility = INVISIBLE

        // 4. 将 JdcrCustomPreviewView 上的圆角、边框、半透明遮罩等绘制叠加在相机画面之上
        draw(canvas)

        // 5. 恢复 PreviewView 的可见性
        previewView.visibility = originalVisibility

        return resultBitmap
    }

}