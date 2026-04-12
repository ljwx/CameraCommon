package com.jdcr.jdcrcameragesture.recognizer

import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.jdcr.jdcrcameragesture.JdcrGestureRecognizerHelper
import com.jdcr.jdcrcameragesture.data.GestureName
import com.jdcr.jdcrcameragesture.data.JdcrGestureName
import com.jdcr.jdcrcameragesture.util.HandGestureLog
import com.jdcr.jdcrcameragesture.exception.JdcrGestureException
import com.jdcr.jdcrcameragesture.data.GesturePosition
import com.jdcr.jdcrcameragesture.data.JdcrHandGestureResult
import com.jdcr.jdcrcameragesture.exception.JdcrNoneHandException
import com.jdcr.jdcrcameragesture.exception.JdcrUnrecognizedException

internal class JdcrGestureRecognizer(private val options: JdcrGestureRecognizerHelper.Options) {

    private val modelScore = options.modelMatchScore

    private val customRecognizer = options.customRecognizer

    private val maxHand = options.maxHand

    fun processRecognitionResult(
        result: GestureRecognizerResult,
    ): Result<JdcrHandGestureResult> {
        val gesture = result.gestures()
        if (gesture.isNullOrEmpty() || gesture[0].isNullOrEmpty()) {
            val message = "没有检测到手"
            HandGestureLog.r(message)
            return Result.failure(JdcrNoneHandException(404, message))
        }
        val internalResult = internalGesture(gesture, result.landmarks())
        if (internalResult.isSuccess) {
            return internalResult
        }
        if (customRecognizer == null) {
            HandGestureLog.r("自定义手势识别为空,直接返回")
            return internalResult
        }
        return customGesture(result.landmarks())
    }

    private fun internalGesture(
        gestures: List<List<Category>>,
        landmarks: List<List<NormalizedLandmark>>
    ): Result<JdcrHandGestureResult> {

        fun judgeGesture(score: Float, name: String): Result<GestureName> {
            if (score >= modelScore) {
                HandGestureLog.r("模型内置手势识别成功,$name,相似度:$score")
                if (name in options.allowGestures) {
                    return Result.success(GestureName(name))
                } else {
                    HandGestureLog.r("模型内置手势不在目标手势范围内")
                }
            }
            return Result.failure(JdcrUnrecognizedException(0, ""))
        }

        gestures.forEachIndexed { index, categories ->
            if (index < maxHand) {
                val category = categories[0]
                val gestureName = category.categoryName()
                val score = category.score()
                val name = judgeGesture(score, gestureName)
                if (name.isSuccess) {
                    val position = handCenterNormalized(landmarks[index])
                    return Result.success(JdcrHandGestureResult(name.getOrThrow(), position))
                }
            }
        }

        if (customRecognizer == null) {
            val defaultHand = 0
            val name = GestureName(JdcrGestureName.UNKNOWN)
            val position = handCenterNormalized(landmarks[defaultHand])
            HandGestureLog.r("内置手势全部未匹配,且没有自定义识别器,返回第一只手识别结果")
            return Result.success(JdcrHandGestureResult(name, position))
        }

        val message = "模型内置手势识别未匹配"
        return Result.failure(JdcrUnrecognizedException(301, message))
    }

    private fun customGesture(
        landmarks: List<List<NormalizedLandmark>>,
    ): Result<JdcrHandGestureResult> {
        if (landmarks.isEmpty()) {
            val message = "自定义识别器未检测到关节"
            HandGestureLog.r(message)
            return Result.failure(JdcrUnrecognizedException(401, message))
        }
        HandGestureLog.r("开始自定义手势识别")
        var firstHandResult: JdcrHandGestureResult? = null
        landmarks.forEachIndexed { index, oneLandmarks ->
            if (index < maxHand) {
                val result = handleOneHand(oneLandmarks)
                if (index == 0) {
                    firstHandResult = result.getOrNull()
                }
                result.onSuccess {
                    if (it.name.name != JdcrGestureName.UNKNOWN) {
                        return result
                    }
                }
            }
        }
        if (firstHandResult != null) {
            HandGestureLog.r("自定义手势识别没有检测到手势,返回第一只手的信息")
            return Result.success(firstHandResult!!)
        }
        val msg = "自定义手势检测结束,没有结果"
        HandGestureLog.r(msg)
        return Result.failure(JdcrGestureException(500, msg))
    }

    private fun handleOneHand(hand: List<NormalizedLandmark>): Result<JdcrHandGestureResult> {
        val gesture = customRecognizer?.recognize(hand)
        if (gesture?.isSuccess == true) {
            val name = gesture.getOrDefault(GestureName(JdcrGestureName.UNKNOWN))
            HandGestureLog.r("自定义手势检测成功,开始检测手的位置")
            val position = handCenterNormalized(hand)
            return Result.success(JdcrHandGestureResult(name, position))
        } else {
            return Result.failure(JdcrGestureException(302, "自定义手势识别失败"))
        }
    }

    private fun handCenterNormalized(hand: List<NormalizedLandmark>): GesturePosition? {
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
        return GesturePosition(nx, ny)
    }

}