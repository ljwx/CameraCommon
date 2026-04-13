package com.jdcr.jdcrcamerabase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Looper
import android.view.Surface
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.jdcr.jdcrcamerabase.config.JdcrCameraStartConfig
import com.jdcr.jdcrcamerabase.exception.JdcrCameraException
import com.jdcr.jdcrcamerabase.state.JdcrCameraOperation
import com.jdcr.jdcrcamerabase.state.JdcrCameraState
import com.jdcr.jdcrcamerabase.state.JdcrCameraStateError
import com.jdcr.jdcrcamerabase.util.JdcrCameraLog
import com.jdcr.jdcrcamerabase.util.JdcrCameraUIRotation
import com.jdcr.jdcrcamerabase.util.JdcrCameraUtils
import com.jdcr.jdcrcamerabase.util.toJdcrBitmap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

class JdcrCameraHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val displayView: JdcrCustomPreviewView,
) {

    val coroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
        e.printStackTrace()
        JdcrCameraLog.e("JdcrCameraHelper协程收到异常", e)
    }
    private val rootJob = SupervisorJob()
    private var _scope: CoroutineScope =
        CoroutineScope(Dispatchers.Main.immediate + rootJob + coroutineExceptionHandler)
    private val cameraMutex = Mutex()

    private var cameraProvider: ProcessCameraProvider? = null
    private var config = JdcrCameraStartConfig.Capture
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile
    private var currentLensFacingBack = true
    private var currentUseCases: List<UseCase> = emptyList()

    @Volatile
    private var uiRotationDegrees: JdcrCameraUIRotation = JdcrCameraUIRotation.DEGREES_0 //ui旋转角度

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val stateFlow = MutableStateFlow<JdcrCameraState>(JdcrCameraState.IDLE)
    private val analysisImageFlow = MutableSharedFlow<Bitmap>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var lastOperation: JdcrCameraOperation = JdcrCameraOperation.Close

    init {
        context.applicationContext.let {
            initCamera(it)
        }
    }

    private fun initCamera(context: Context) {
        _scope.launch(Dispatchers.Default) {
            getProvider(context)
        }
    }

    private suspend fun getProvider(context: Context): Result<ProcessCameraProvider> {
        cameraProvider?.let { return Result.success(it) }
        cameraMutex.withLock {
            cameraProvider?.let { return Result.success(it) }
            return suspendCancellableCoroutine { conti ->
                val future = ProcessCameraProvider.getInstance(context.applicationContext)
                runCatching {
                    future.addListener({
                        cameraProvider = future.get()
                        JdcrCameraLog.i("相机初始化成功")
                        conti.resume(Result.success(cameraProvider!!), null)
                    }, ContextCompat.getMainExecutor(context))
                    conti.invokeOnCancellation {
                        JdcrCameraLog.e("相机初始化被取消", it)
                        future.cancel(true)
                    }
                }.onFailure {
                    JdcrCameraLog.e("相机初始化异常", it)
                    conti.resume(Result.failure(it), null)
                }
            }
        }
    }

    private inline fun <T> runMain(crossinline block: () -> T): T {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            runBlocking(Dispatchers.Main.immediate) {
                block()
            }
        }
    }

    private fun getPreviewView(): PreviewView {
        return displayView.getPreviewView()
    }

    private fun getPreview(): Preview {
        val previewView = getPreviewView()
        previewView.scaleType = config.previewConfig.scaleType
        preview = preview ?: Preview.Builder()
            .setTargetRotation(previewView.display.rotation) // 关键：自动适配方向
            .build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        return preview!!
    }

    private fun getCameraSelector(): CameraSelector {
        val lensFacing =
            if (currentLensFacingBack) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        return CameraSelector.Builder().requireLensFacing(lensFacing).build()
    }

    private fun getCapture(): ImageCapture {
        capture = capture ?: ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        return capture!!
    }

    private fun getImageAnalysis(): ImageAnalysis {
        val throttler = config.analysisConfig.throttler
        var lastMs = 0L
        fun isThrottlePass(): Boolean {
            if (System.currentTimeMillis() - throttler > lastMs) {
                lastMs = System.currentTimeMillis()
                return true
            }
            return false
        }

        fun getAspectRatio(): Float? {
            if (config.previewConfig.enable) {
                if (displayView.width > 0 && displayView.height > 0) {
                    return displayView.width.toFloat() / displayView.height.toFloat()
                }
            }
            return config.analysisConfig.targetAspectRatio
        }

        fun applyUiRotation(bitmap: Bitmap): Bitmap {
            if (uiRotationDegrees != JdcrCameraUIRotation.DEGREES_0) {
                val matrix = Matrix().apply {
                    postRotate(uiRotationDegrees.value, bitmap.width / 2f, bitmap.height / 2f)
                }
                val rotated =
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                }
                return rotated
            }
            return bitmap
        }

        val ratio =
            getAspectRatio().apply { JdcrCameraLog.d("ImageAnalysis的Bitmap的宽高比:$this") }
        imageAnalysis = imageAnalysis ?: ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            //.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build().apply {
                JdcrCameraLog.i("ImageAnalysis的图片采样间隔ms:$throttler")
                setAnalyzer(cameraExecutor) {
                    if (isThrottlePass()) {
                        var bitmap = it.toJdcrBitmap(ratio, !currentLensFacingBack)
                        bitmap = applyUiRotation(bitmap)
                        analysisImageFlow.tryEmit(bitmap)
                    }
                    it.close()
                }
            }
        return imageAnalysis!!
    }

    private fun setupUseCases(config: JdcrCameraStartConfig): List<UseCase> {
        return mutableListOf<UseCase>().apply {
            config.apply {
                currentLensFacingBack = lensFacingBack
                if (previewConfig.enable) {
                    add(getPreview())
                    JdcrCameraLog.i("添加预览case")
                }
                if (captureConfig.enable) {
                    add(getCapture())
                    JdcrCameraLog.i("添加拍照case")
                }
                if (analysisConfig.enable) {
                    add(getImageAnalysis())
                    JdcrCameraLog.i("添加分析case")
                }
            }
        }
    }

    private fun startCameraInternal(list: List<UseCase>): Result<Boolean> {

        lastOperation = JdcrCameraOperation.Open

        fun observeState(cameraInfo: CameraInfo) {
            JdcrCameraLog.i("camera添加状态监听")
            cameraInfo.cameraState.observe(lifecycleOwner) { state ->
                state.type.apply {
                    val stateType = when (this) {
                        CameraState.Type.PENDING_OPEN -> JdcrCameraState.Ready
                        CameraState.Type.OPENING -> {
                            JdcrCameraState.Opening
                        }

                        CameraState.Type.OPEN -> {
                            if (lastOperation is JdcrCameraOperation.Close) {
                                JdcrCameraLog.d("相机已开启,但最后的操作是关闭相机,现在执行关闭")
                                internalClose()
                            }
                            JdcrCameraState.Opened
                        }

                        CameraState.Type.CLOSING -> JdcrCameraState.Closing
                        CameraState.Type.CLOSED -> {
//                            if (lastOperation is CameraOperation.Open) {
//                                startCameraInternal(currentUseCases)
//                            }
                            JdcrCameraState.Closed
                        }
                    }
                    JdcrCameraLog.d("收到camera状态变化:$stateType")
                    updateState(stateType)
                }
                state.error?.apply {
                    val stateError = when (this.code) {
                        CameraState.ERROR_STREAM_CONFIG -> JdcrCameraStateError.StreamConfigError()
                        CameraState.ERROR_CAMERA_IN_USE -> JdcrCameraStateError.CameraInUse()
                        CameraState.ERROR_MAX_CAMERAS_IN_USE -> JdcrCameraStateError.MaxCamerasInUse()
                        CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> JdcrCameraStateError.OtherRecoverableError()
                        CameraState.ERROR_CAMERA_DISABLED -> JdcrCameraStateError.CameraDisabled()
                        CameraState.ERROR_CAMERA_FATAL_ERROR -> JdcrCameraStateError.CameraFatalError()
                        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> JdcrCameraStateError.DoNotDisturbModeEnabled()
                        else -> JdcrCameraStateError.Unknown()
                    }
                    JdcrCameraLog.e("收到camera状态错误", IllegalStateException(stateError.message))
                    updateState(JdcrCameraState.Error(stateError))
                }
            }
        }

        return runCatching {
            cameraProvider!!.apply {
                JdcrCameraLog.i("移除camera状态监听")
                camera?.cameraInfo?.cameraState?.removeObservers(lifecycleOwner)
                unbindAll()
                camera =
                    bindToLifecycle(
                        lifecycleOwner,
                        getCameraSelector(),
                        *list.toTypedArray()
                    ).apply {
                        observeState(this.cameraInfo)
                    }
            }
            JdcrCameraLog.i("启动相机,执行完成")
            true
        }.onFailure {
            val message = "启动相机,执行失败"
            JdcrCameraLog.e(message, it)
            updateState(JdcrCameraState.Error(JdcrCameraStateError.Unknown(message)))
        }
    }

    private fun invertRotation(rotation: Int): Int {
        return when (rotation) {
            Surface.ROTATION_0 -> Surface.ROTATION_0
            Surface.ROTATION_90 -> Surface.ROTATION_270
            Surface.ROTATION_180 -> Surface.ROTATION_180
            else -> Surface.ROTATION_90
        }
    }

    private fun isLastOperationOpen(): Boolean {
        return lastOperation == JdcrCameraOperation.Open
    }

    private fun isOpenedInternal(): Boolean {
        return isOpened()
    }

    private fun checkStarted(config: JdcrCameraStartConfig): Boolean {
        if (isOpenedInternal() && config == this.config) {
            JdcrCameraLog.d("相机已启动,且配置相同,不执行启动操作")
            return true
        }
        return false
    }

    fun start(config: JdcrCameraStartConfig = JdcrCameraStartConfig.Capture): Result<Boolean> {
        if (checkStarted(config)) return Result.success(true)
        if (cameraProvider == null) {
            val message = "相机未初始化完成"
            JdcrCameraLog.d(message)
            return Result.failure(JdcrCameraException(message))
        }
        this.config = config
        JdcrCameraLog.i("调用相机启动,是否后置:" + config.lensFacingBack + ",将相机状态置空")
        updateState(JdcrCameraState.IDLE)
        currentUseCases = setupUseCases(config)
        return runMain {
            clearPreviewViewState()
            startCameraInternal(currentUseCases)
        }
    }

    suspend fun startAndWait(config: JdcrCameraStartConfig = JdcrCameraStartConfig.Capture): Result<Boolean> {
        if (checkStarted(config)) return Result.success(true)
        if (cameraProvider == null) {
            withTimeoutOrNull(3000) {
                JdcrCameraLog.d("相机未初始化完成,等待初始化完成,直至三秒超时")
                while (true) {
                    if (cameraProvider == null) {
                        delay(50)
                    } else {
                        return@withTimeoutOrNull
                    }
                }
            }
        }
        val result = start(config)
        if (result.isFailure) {
            return result
        }
        return stateFlow.mapNotNull { getOpenResult() }.first()
    }

    fun capture(result: (Result<ImageProxy>) -> Unit) {
        JdcrCameraLog.i("执行拍照,返回ImageProxy")
        getCapture().takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                super.onCaptureSuccess(image)
                JdcrCameraLog.d("拍照成功,结果:" + image.imageInfo + ",format:" + image.format)
                result(Result.success(image))
            }

            override fun onError(exception: ImageCaptureException) {
                super.onError(exception)
                JdcrCameraLog.e("拍照失败", exception)
                result(Result.failure(exception))
            }

        })
    }

    fun captureFile(
        context: Context,
        callback: (Result<Uri?>) -> Unit
    ) {
        val option = JdcrCameraUtils.getCacheOptions(context, !isBackFacing())
        JdcrCameraLog.i("执行拍照，返回Uri")
        getCapture().takePicture(
            option,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    JdcrCameraLog.d("拍照成功,结果:" + outputFileResults.savedUri.toString())
                    callback.invoke(Result.success(outputFileResults.savedUri))
                }

                override fun onError(exception: ImageCaptureException) {
                    JdcrCameraLog.e("拍照失败", exception)
                    callback.invoke(Result.failure(exception))
                }

            })
    }

    private fun openedSuccess(): Boolean {
        return lastOperation is JdcrCameraOperation.Open && (stateFlow.value is JdcrCameraState.Opened)
    }

    private fun closedSuccess(): Boolean {
        return lastOperation is JdcrCameraOperation.Close && (stateFlow.value is JdcrCameraState.Closed || stateFlow.value == JdcrCameraState.IDLE)
    }

    fun isOpened(): Boolean {
        return lastOperation is JdcrCameraOperation.Open && (stateFlow.value is JdcrCameraState.Opened)
    }

    fun isClosed(): Boolean {
        return lastOperation is JdcrCameraOperation.Close && (stateFlow.value is JdcrCameraState.Closed || stateFlow.value == JdcrCameraState.IDLE)
    }

    suspend fun switchAndWait(facingBack: Boolean? = null): Result<Boolean> {
        JdcrCameraLog.i("触发切换前后摄像头:$facingBack")
        if (currentLensFacingBack == facingBack) {
            JdcrCameraLog.d("不执行切换后置,当前就是:$facingBack")
            return Result.success(true)
        }
        lastOperation = JdcrCameraOperation.Switch
        currentLensFacingBack = facingBack ?: !currentLensFacingBack
        val result = runMain { startCameraInternal(currentUseCases) }
        if (result.isFailure) {
            return result
        }
        return stateFlow.mapNotNull { getOpenResult() }.first()
    }

    fun changeRotation(viewRotation: JdcrCameraUIRotation) {
        JdcrCameraLog.d("触发旋转ui角度:" + viewRotation.value)
        if (viewRotation.value == uiRotationDegrees.value) {
            JdcrCameraLog.d("不执行旋转,当前就是:" + uiRotationDegrees.value)
            return
        }
        uiRotationDegrees = viewRotation
        runMain {
            val previewView = getPreviewView()
            previewView.rotation = viewRotation.value
            JdcrCameraLog.d("更新预览视图角度:" + viewRotation.value)
            JdcrCameraUtils.relayoutPreviewView(previewView, viewRotation)
        }
    }

    private fun updateUseCaseRotation(viewRotation: Float) {
//        val surfaceRotation = CameraOrientationUtil.degreesToSurfaceRotation(viewRotation)
//        preview?.targetRotation = surfaceRotation
//        capture?.targetRotation = surfaceRotation
//        imageAnalysis?.targetRotation = surfaceRotation
    }

    private fun clearPreviewViewState() {
        JdcrCameraLog.i("清扫预览视图状态")
        getPreviewView().apply {
            rotation = 0f
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            requestLayout()
        }
        uiRotationDegrees = JdcrCameraUIRotation.DEGREES_0
    }

    fun close(): Result<Boolean> {
        JdcrCameraLog.i("触发关闭相机")
        lastOperation = JdcrCameraOperation.Close
        return internalClose()
    }

    suspend fun closeAndWait(): Result<Boolean> {
        val result = close()
        if (result.isFailure) {
            return result
        }
        return stateFlow.mapNotNull { getCloseResult() }.first()
    }

    private fun internalClose(): Result<Boolean> {
        if (isClosed()) {
            JdcrCameraLog.i("已经是关闭状态")
            return Result.success(true)
        }
        return runMain {
            JdcrCameraLog.d("执行关闭相机(解绑)")
            cameraProvider?.unbindAll()
            camera = null
            Result.success(true)
        }
    }

    fun isBackFacing(): Boolean {
        return currentLensFacingBack
    }

    fun getUIRotationDegrees(): JdcrCameraUIRotation {
        return uiRotationDegrees
    }

    private fun updateState(state: JdcrCameraState) {
        if (stateFlow.value == state) return
        stateFlow.tryEmit(state)
    }

    fun getStateFlow(): StateFlow<JdcrCameraState> {
        return stateFlow
    }

    fun getOpenResult(): Result<Boolean>? {
        val value = stateFlow.value
        return when {
            openedSuccess() -> Result.success(true)
            closedSuccess() -> Result.failure(JdcrCameraException("最后的操作是关闭"))
            value is JdcrCameraState.Error -> Result.failure(JdcrCameraException(value.error.toString()))
            else -> null
        }
    }

    fun getCloseResult(): Result<Boolean>? {
        val value = stateFlow.value
        return when {
            closedSuccess() -> Result.success(true)
            openedSuccess() -> Result.failure(JdcrCameraException("最后的操作是开启"))
            value is JdcrCameraState.Error -> Result.failure(JdcrCameraException(value.error.toString()))
            else -> null
        }
    }

    fun getImageAnalysisBitmapFlow(): SharedFlow<Bitmap> {
        return analysisImageFlow
    }

    fun onDestroy() {
        JdcrCameraLog.i("触发onDestroy")
        close()
        cameraProvider = null
        camera = null
        capture = null
        rootJob.cancelChildren()
        cameraExecutor.shutdown()
    }

}