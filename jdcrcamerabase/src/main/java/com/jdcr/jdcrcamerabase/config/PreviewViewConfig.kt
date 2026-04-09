package com.jdcr.jdcrcamerabase.config

import android.content.Context
import android.graphics.RectF

data class JdcrCameraPreviewConfig(
    val w: Float?,
    val h: Float?,
    val x: Float?,
    val y: Float?,
    val outerRadiusTop: Float?,// 顶部弧度
    val outerRadiusBottom: Float?,// 边框弧度(上菜)
    val showOuterBorder: Boolean?,
    val showMask: Boolean?,// 是否显示遮罩
    val maskAlpha: Float?,
    val maskMarginHorizontal: Float?,// 遮罩左右间距
    val maskMarginVertical: Float?,// 遮罩上下间距
    val maskRadius: Float?,//遮罩弧度
) {

    companion object {
        fun getTest(): JdcrCameraPreviewConfig {
            return JdcrCameraPreviewConfig(
                330f,
                264f,
                208f,
                18f,
                20f,
                20f,
                false,
                false,
                0.2f,
                30f,
                30f,
                8f
            )
        }

    }

    fun getOverlayRectF(context: Context): RectF {
        val diff = 2
        val left = dp2px(context, (maskMarginHorizontal ?: 0f) + diff).toFloat()
        val top = dp2px(context, (maskMarginVertical ?: 0f) + diff).toFloat()
        val right = dp2px(context, (w ?: 0f) - (maskMarginHorizontal ?: 0f) - diff).toFloat()
        val bottom = dp2px(context, (h ?: 0f) - (maskMarginVertical ?: 0f) - diff).toFloat()
        return RectF(left, top, right, bottom)
    }

    fun getOverlayRectFStroke(context: Context): RectF {
        val left = dp2px(context, (maskMarginHorizontal ?: 0f)).toFloat()
        val top = dp2px(context, (maskMarginVertical ?: 0f)).toFloat()
        val right = dp2px(context, (w ?: 0f) - (maskMarginHorizontal ?: 0f)).toFloat()
        val bottom = dp2px(context, (h ?: 0f) - (maskMarginVertical ?: 0f)).toFloat()
        return RectF(left, top, right, bottom)
    }

    fun dp2px(context: Context, dp: Float): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }

}