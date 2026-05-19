package com.jdcr.jdcrcameragesture

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.jdcr.jdcrcameragesture.data.JdcrGestureName
import com.jdcr.jdcrcameragesture.data.JdcrHandGestureResult
import com.jdcr.jdcrcameragesture.recognizer.CustomRecognizer
import com.jdcr.jdcrcameragesture.recognizer.JdcrGestureCustomRecognizer
import com.jdcr.jdcrcameragesture.recognizer.JdcrGestureRecognizer
import com.jdcr.jdcrcameragesture.util.JdcrGestureLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class JdcrGestureRecognizerHelper(
    private val context: Context,
    private val options: Options
) : GestureRecognizerHelper {

    @Volatile
    private var delegateGPU = false
    private val gestureRecognizer by lazy { initGestureRecognizer() }
    private val resultFlow = MutableSharedFlow<Result<JdcrHandGestureResult>>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val recognizer by lazy { JdcrGestureRecognizer(options) }

    private fun initGestureRecognizer(): GestureRecognizer {
        fun createFromOptions(baseOptions: BaseOptions): GestureRecognizer {
            val optionBuilder =
                GestureRecognizer.GestureRecognizerOptions.builder().setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { mpResult, _ ->
                        val gestureResult = recognizer.processRecognitionResult(mpResult)
                        if (gestureResult.isSuccess) {
                            JdcrGestureLog.i("异步识别结果:" + gestureResult.getOrNull())
                        }
                        resultFlow.tryEmit(gestureResult)
                    }
                    .setNumHands(options.maxHand)
                    .setErrorListener {
                        JdcrGestureLog.e("异步识别异常", it)
                    }
            JdcrGestureLog.i("创建手势识别处理器")
            return GestureRecognizer.createFromOptions(context, optionBuilder.build())
        }
        try {
            val baseBuilder =
                BaseOptions.builder().setModelAssetPath(options.modelAssetPath).apply {
                    if (options.delegate != null) {
                        JdcrGestureLog.i("指定解析器为:${options.delegate}")
                        setDelegate(options.delegate)
                        if (options.delegate == Delegate.GPU) {
                            delegateGPU = true
                        }
                    }
                }
            return createFromOptions(baseBuilder.build())
        } catch (e: Exception) {
            JdcrGestureLog.e("创建手势识别处理器异常,回退cpu解析器", e)
            val baseBuilder =
                BaseOptions.builder().setModelAssetPath(options.modelAssetPath).apply {
                    setDelegate(Delegate.CPU)
                    delegateGPU = false
                }
            return createFromOptions(baseBuilder.build())
        }
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

    fun isDelegateGPU(): Boolean {
        return delegateGPU
    }

    fun close() {
        gestureRecognizer.close()
    }

    class Options internal constructor(
        val modelAssetPath: String,
        val maxHand: Int,
        val modelMatchScore: Float,
        val allowGestures: Set<String>,
        val customRecognizer: CustomRecognizer?,
        val delegate: Delegate?,
    )

    class Builder {

        private var modelAssetPath: String? = null
        private var maxHand = 1
        private var modelMatchScore = 0.6f
        private var allowGestures = JdcrGestureName.CMT
        private var customRecognizer: CustomRecognizer? = JdcrGestureCustomRecognizer()
        private var delegate: Delegate? = null

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

        fun setDelegate(delegate: Delegate?): Builder {
            this.delegate = delegate
            return this
        }

        fun build(): Options {
            requireNotNull(modelAssetPath)
            return Options(
                modelAssetPath!!,
                maxHand,
                modelMatchScore,
                allowGestures,
                customRecognizer,
                delegate
            )
        }

    }

}