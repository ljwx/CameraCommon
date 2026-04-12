package com.jdcr.jdcrcameragesture

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.jdcr.jdcrcameragesture.data.JdcrGestureName
import com.jdcr.jdcrcameragesture.data.JdcrHandGestureResult
import com.jdcr.jdcrcameragesture.recognizer.CustomRecognizer
import com.jdcr.jdcrcameragesture.recognizer.JdcrGestureCustomRecognizer
import com.jdcr.jdcrcameragesture.recognizer.JdcrGestureRecognizer
import com.jdcr.jdcrcameragesture.util.HandGestureLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class JdcrGestureRecognizerHelper(
    private val context: Context,
    private val options: Options
) : GestureRecognizerHelper {

    private val gestureRecognizer by lazy { initGestureRecognizer() }
    private val resultFlow = MutableSharedFlow<Result<JdcrHandGestureResult>>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val recognizer by lazy { JdcrGestureRecognizer(options) }

    private fun initGestureRecognizer(): GestureRecognizer {
        val baseBuilder =
            BaseOptions.builder().setModelAssetPath(options.modelAssetPath)
        val optionBuilder =
            GestureRecognizer.GestureRecognizerOptions.builder().setBaseOptions(baseBuilder.build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { mpResult, _ ->
                    val gestureResult = recognizer.processRecognitionResult(mpResult)
                    if (gestureResult.isSuccess) {
                        HandGestureLog.i("异步识别结果:" + gestureResult.getOrNull())
                    }
                    resultFlow.tryEmit(gestureResult)
                }
                .setNumHands(options.maxHand)
                .setErrorListener {
                    HandGestureLog.e("异步识别异常:$it")
                }
        HandGestureLog.i("创建手势识别处理器")
        return GestureRecognizer.createFromOptions(context, optionBuilder.build())
    }

    private fun recognizeLiveStream(bitmap: Bitmap) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        gestureRecognizer.recognizeAsync(mpImage, SystemClock.uptimeMillis())
    }

    override fun recognizeAsyncBitmap(bitmap: Bitmap) {
        recognizeLiveStream(bitmap)
    }

    override suspend fun recognizeBitmap(bitmap: Bitmap): Result<JdcrHandGestureResult> {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = gestureRecognizer.recognize(mpImage)
        return recognizer.processRecognitionResult(result)
    }

    fun getResultFlow(): SharedFlow<Result<JdcrHandGestureResult>> {
        return resultFlow
    }

    class Options internal constructor(
        val modelAssetPath: String,
        val maxHand: Int,
        val modelMatchScore: Float,
        val allowGestures: Set<String>,
        val customRecognizer: CustomRecognizer?
    )

    class Builder {

        private var modelAssetPath: String? = null
        private var maxHand = 1
        private var modelMatchScore = 0.6f
        private var allowGestures = JdcrGestureName.CMT
        private var customRecognizer: CustomRecognizer? = JdcrGestureCustomRecognizer()

        fun setModelAssetPath(path: String): Builder {
            this.modelAssetPath = path
            return this
        }

        fun setMaxHand(maxHand: Int): Builder {
            this.maxHand = maxHand
            return this
        }

        fun modelMatchScore(score: Float): Builder {
            this.modelMatchScore = score
            return this
        }

        fun setAllowGestures(gestures: Set<String>): Builder {
            this.allowGestures = gestures
            return this
        }

        fun setCustomRecognizer(recognizer: CustomRecognizer?): Builder {
            this.customRecognizer = recognizer
            return this
        }

        fun build(): Options {
            requireNotNull(modelAssetPath)
            return Options(
                modelAssetPath!!,
                maxHand,
                modelMatchScore,
                allowGestures,
                customRecognizer
            )
        }

    }

}