package com.jdcr.jdcrcamerabase.config

import android.graphics.Color

data class JdcrPreviewOuterBorder(val color: Int = Color.WHITE, val width: Float = 3f)

data class JdcrPreviewUpperMaskInnerStroke(
    val strokeColor: Int = Color.WHITE, //内圈边框颜色
    val strokeWidth: Float = 2f, //内圈边框宽度
)

data class JdcrPreviewUpperMask(
    val maskAlpha: Float = 0.5f,
    val marginHorizontal: Float = 30f,// 遮罩左右间距
    val marginVertical: Float = 30f,// 遮罩上下间距
    val innerRadius: Float = 12f,//遮罩弧度
    val innerStroke: JdcrPreviewUpperMaskInnerStroke? = JdcrPreviewUpperMaskInnerStroke()
)

data class JdcrCameraPreviewConfig(
    val w: Float,
    val h: Float,
    val x: Float,
    val y: Float,
    val outerRadiusTop: Float = 12f,// 顶部弧度
    val outerRadiusBottom: Float = 12f,// 底部弧度
    val outerBorder: JdcrPreviewOuterBorder? = null,//外边框
    val upperMask: JdcrPreviewUpperMask? = null,// 是否显示遮罩
) {

    companion object {
        fun getTest(): JdcrCameraPreviewConfig {
            return JdcrCameraPreviewConfig(
                330f,
                264f,
                208f,
                18f,
                outerBorder = JdcrPreviewOuterBorder(),
                upperMask = JdcrPreviewUpperMask()
            )
        }

    }

}