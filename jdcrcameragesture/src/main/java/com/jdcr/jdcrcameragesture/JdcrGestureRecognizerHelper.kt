package com.jdcr.jdcrcameragesture

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class JdcrGestureRecognizerHelper(
    private val context: Context,
    private val modelAssetPath: String,
    val enableInnerRecognizer: Boolean = true
) {

    private var gestureRecognizer: GestureRecognizer? = null
    private val stateFlow = MutableStateFlow(Result.success(false))
    private val resultFlow = MutableSharedFlow<Result<JdcrHandGestureResult>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val recognizer by lazy { JdcrGestureRecognizer(enableInnerRecognizer) }

    init {
        setupGestureRecognizer()
    }

    private fun setupGestureRecognizer() {
        val baseBuilder =
            BaseOptions.builder().setModelAssetPath(modelAssetPath)
        val optionBuilder =
            GestureRecognizer.GestureRecognizerOptions.builder().setBaseOptions(baseBuilder.build())
                .setRunningMode(RunningMode.LIVE_STREAM).setResultListener { mpResult, _ ->
                    val gestureResult = recognizer.processRecognitionResult(mpResult)
                    if (gestureResult.isSuccess) {
                        HandGestureLog.i("识别结果:" + gestureResult.getOrNull())
                    }
                    resultFlow.tryEmit(gestureResult)
                }
                .setErrorListener {
                    HandGestureLog.d("识别异常:$it")
                }
        gestureRecognizer = GestureRecognizer.createFromOptions(context, optionBuilder.build())
        HandGestureLog.i("创建手势识别处理器")
    }

    private fun recognizeLiveStream(bitmap: Bitmap) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        gestureRecognizer?.recognizeAsync(mpImage, SystemClock.uptimeMillis())
    }

    fun setCustomRecognizer(recognizer: ((List<NormalizedLandmark>) -> Result<String>)?) {
        this.recognizer.setCustomRecognizer(recognizer)
    }

    fun setBizGesture(gestures: List<String>) {
        this.recognizer.setBizGesture(gestures)
    }

    fun changeConfig(internalScore: Float? = null, maxHand: Int? = null) {
        recognizer.changeConfig(internalScore, maxHand)
    }

    fun recognizeBitmap(bitmap: Bitmap) {
        recognizeLiveStream(bitmap)
    }

    fun getResultFlow(): SharedFlow<Result<JdcrHandGestureResult>> {
        return resultFlow
    }

    fun getStateFlow(): StateFlow<Result<Boolean>> {
        return stateFlow
    }

}