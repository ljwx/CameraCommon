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
            return Result.failure(JdcrGestureException(404, "没有手"))
        }
        val internalResult = internalGesture(gesture, result.landmarks())
        if (internalResult.isSuccess) {
            return internalResult
        }
        if (!enableInnerRecognizer && customRecognizer == null) {
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
                        val mapped = getGestureResultName(gestureName)
                        if (mapped != UNKONWN) {
                            return Pair(mapped, index)
                        }
                    }
                }
            }
            return null
        }

        val result = gestureResult()
        if (result != null) {
            val position =
                runCatching { handCenterNormalized(landmarks[result.second]) }.getOrNull()
            return Result.success(JdcrHandGestureResult(result.first, position))
        }

        return Result.failure(JdcrUnrecognizedException(301, "内置手势识别未匹配"))
    }

    private fun customGesture(
        landmarks: List<List<NormalizedLandmark>>,
    ): Result<JdcrHandGestureResult> {
        if (landmarks.isEmpty()) {
            return Result.failure(JdcrUnrecognizedException(401, "未检测到关节"))
        }
        landmarks.forEachIndexed { index, oneLandmarks ->
            if (index < maxHand) {
                val result = handleOneHand(oneLandmarks)
                if (result.isSuccess) {
                    return result
                }
            }
        }
        return Result.failure(JdcrGestureException(500, "手势检测结束,没有结果"))
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