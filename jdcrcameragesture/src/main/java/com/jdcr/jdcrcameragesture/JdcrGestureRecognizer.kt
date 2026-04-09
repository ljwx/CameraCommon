package com.jdcr.jdcrcameragesture

import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

class JdcrGestureRecognizer(val enableInnerRecognizer: Boolean = true) {

    companion object {
        //内置
        const val OpenPalm = "Open_Palm"
        const val ClosedFist = "Closed_Fist"
        const val PointingUp = "Pointing_Up"
        const val Victory = "Victory"
        const val ThumbUp = "Thumb_Up"
        const val ThumbDown = "Thumb_Down"
        const val ILoveYou = "ILoveYou"

        const val UNKONWN = "unknown"
    }

    private var internalScore = 0.6f
    private var maxHand = 1

    private val internalGestureNames = setOf(
        OpenPalm,
        ClosedFist,
        PointingUp,
        Victory,
        ThumbUp,
        ThumbDown,
        ILoveYou,
    )

    private var customRecognizer: ((List<NormalizedLandmark>) -> Result<String>)? = null

    private var bizGestureNames = listOf(
        OpenPalm,
        ClosedFist,
        ThumbUp,
        Victory,
        PointingUp,
    )

    fun processRecognitionResult(
        result: GestureRecognizerResult,
    ): Result<JdcrHandGestureResult> {
        val gesture = result.gestures()
        if (gesture.isNullOrEmpty() || gesture[0].isNullOrEmpty()) {
            val message = "没有检测到手"
            HandGestureLog.r(message)
            return Result.failure(JdcrGestureException(404, message))
        }
        val internalResult = internalGesture(gesture, result.landmarks())
        if (internalResult.isSuccess) {
            return internalResult
        }
        if (!enableInnerRecognizer && customRecognizer == null) {
            HandGestureLog.r("未开启库自定义识别,且外部自定义手势识别为空,直接返回")
            return internalResult
        }
        return customGesture(result.landmarks())
    }

    fun setCustomRecognizer(recognizer: ((List<NormalizedLandmark>) -> Result<String>)?) {
        this.customRecognizer = recognizer
    }

    fun setBizGesture(gestures: List<String>) {
        this.bizGestureNames = gestures
    }

    fun changeConfig(internalScore: Float? = null, maxHand: Int? = null) {
        internalScore?.let { this.internalScore = it }
        maxHand?.let { this.maxHand = it }
    }

    private fun internalGesture(
        gestures: List<List<Category>>,
        landmarks: List<List<NormalizedLandmark>>
    ): Result<JdcrHandGestureResult> {
        fun gestureResult(): Pair<String, Int>? {
            gestures.forEachIndexed { index, categories ->
                if (index < maxHand) {
                    val category = categories[0]
                    val gestureName = category.categoryName()
                    val score = category.score()
                    if (score > internalScore && gestureName in internalGestureNames) {
                        HandGestureLog.r("模型内置手势识别成功,$gestureName,相似度:$score")
                        val mapped = getGestureResultName(gestureName)
                        if (mapped != UNKONWN) {
                            return Pair(mapped, index)
                        } else {
                            HandGestureLog.r("模型内置手势不在业务手势范围内")
                        }
                    }
                }
            }
            HandGestureLog.r("模型内置手势识别未匹配")
            return null
        }

        val result = gestureResult()
        if (result != null) {
            val position =
                runCatching { handCenterNormalized(landmarks[result.second]) }.getOrNull()
            return Result.success(JdcrHandGestureResult(result.first, position))
        }

        return Result.failure(JdcrUnrecognizedException(301, "模型内置手势识别未匹配"))
    }

    private fun customGesture(
        landmarks: List<List<NormalizedLandmark>>,
    ): Result<JdcrHandGestureResult> {
        if (landmarks.isEmpty()) {
            val message = "自定义识别未检测到关节"
            HandGestureLog.r(message)
            return Result.failure(JdcrUnrecognizedException(401, message))
        }
        HandGestureLog.r("开始自定义手势识别")
        landmarks.forEachIndexed { index, oneLandmarks ->
            if (index < maxHand) {
                val result = handleOneHand(oneLandmarks)
                if (result.isSuccess) {
                    HandGestureLog.r("自定义手势识别成功")
                    return result
                }
            }
        }
        val msg = "自定义手势检测结束,没有结果"
        HandGestureLog.r(msg)
        return Result.failure(JdcrGestureException(500, msg))
    }

    private fun handleOneHand(hand: List<NormalizedLandmark>): Result<JdcrHandGestureResult> {

        fun isMatch(result: Result<String>): Boolean {
            return result.isSuccess && result.getOrElse { UNKONWN } == UNKONWN
        }

        val gesture =
            if (enableInnerRecognizer) JdcrGestureCustomRecognizer.custom(hand) else customRecognizer?.invoke(
                hand
            )
        if (gesture?.isSuccess == true) {
            HandGestureLog.r("自定义手势检测成功,开始检测手的位置")
            val position = handCenterNormalized(hand)
            return Result.success(JdcrHandGestureResult(gesture.getOrDefault(UNKONWN), position))
        } else {
            return Result.failure(JdcrGestureException(302, "当前手未识别到手势"))
        }
    }

    private fun getGestureResultName(name: String): String {
        return if (name in bizGestureNames) name else UNKONWN
    }

    fun handCenterNormalized(hand: List<NormalizedLandmark>): JdcrHandGesturePosition? {
        if (hand.isEmpty()) return null
        var sx = 0f
        var sy = 0f
        for (lm in hand) {
            sx += lm.x()
            sy += lm.y()
        }
        val n = hand.size
        var nx = sx / n
        val ny = sy / n
        return JdcrHandGesturePosition(nx, ny)
    }

}