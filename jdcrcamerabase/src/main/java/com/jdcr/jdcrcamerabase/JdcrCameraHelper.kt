package com.jdcr.jdcrcamerabase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
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
import androidx.core.view.doOnNextLayout
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
    private var boundUseCases: List<UseCase> = emptyList()
    private var currentUseCases: List<UseCase> = emptyList()

    @Volatile
    private var uiRotationDegrees: JdcrCameraUIRotation = JdcrCameraUIRotation.DEGREES_0 //ui旋转角度

    @Volatile
    private var requestedUiRotationDegrees: JdcrCameraUIRotation = JdcrCameraUIRotation.DEGREES_0

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
        val rotation = resolveDisplayRotation(previewView)
        JdcrCameraLog.d(
            "创建Preview,display.rotation=" + rotationName(rotation) +
                ",viewAttached=" + previewView.isAttachedToWindow +
                ",implMode=" + previewView.implementationMode
        )
        preview = preview ?: Preview.Builder()
            .setTargetRotation(rotation) // 关键：自动适配方向
            .build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        return preview!!
    }

    /**
     * display 在 view 尚未 attach 时可能为空(尤其浮窗预览),回退到 ROTATION_0 并记录,避免 NPE 与拿到错误基准。
     */
    private fun resolveDisplayRotation(previewView: PreviewView): Int {
        return previewView.display?.rotation ?: run {
            JdcrCameraLog.e(
                "预览视图display为空(可能尚未attach),方向基准回退ROTATION_0",
                IllegalStateException("previewView.display == null")
            )
            Surface.ROTATION_0
        }
    }

    private fun rotationName(rotation: Int): String {
        return when (rotation) {
            Surface.ROTATION_0 -> "ROTATION_0(0°)"
            Surface.ROTATION_90 -> "ROTATION_90(90°)"
            Surface.ROTATION_180 -> "ROTATION_180(180°)"
            Surface.ROTATION_270 -> "ROTATION_270(270°)"
            else -> "UNKNOWN($rotation)"
        }
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

        // 基础宽高比在创建时(主线程)取一次,与旧实现完全一致,避免在分析线程读 View 尺寸。
        val previewBaseRatio: Float? =
            if (config.previewConfig.enable && displayView.width > 0 && displayView.height > 0) {
                displayView.width.toFloat() / displayView.height.toFloat()
            } else {
                null
            }
        // 仅 FILL_CENTER(中心裁剪铺满)下,“分析裁剪=预览可视区”的交换推导才成立;同样创建时取一次。
        val centerCrop = config.previewConfig.scaleType == PreviewView.ScaleType.FILL_CENTER

        // 每帧调用:非旋转时返回值与旧实现逐字节一致;仅当旋转 90/270 且中心裁剪时交换比例(1/base),
        // 让识别裁剪跟随预览旋转后的可视区(见 relayoutPreviewView)。
        fun resolveAnalysisRatio(rotation: JdcrCameraUIRotation): Float? {
            val base = previewBaseRatio ?: return config.analysisConfig.targetAspectRatio
            val swap = centerCrop && (rotation == JdcrCameraUIRotation.DEGREES_90 ||
                rotation == JdcrCameraUIRotation.DEGREES_270)
            return if (swap) 1f / base else base
        }

        fun applyUiRotation(bitmap: Bitmap, rotation: JdcrCameraUIRotation): Bitmap {
            if (rotation != JdcrCameraUIRotation.DEGREES_0) {
                val matrix = Matrix().apply {
                    postRotate(rotation.value, bitmap.width / 2f, bitmap.height / 2f)
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

        var lastLoggedRotation = Int.MIN_VALUE
        var lastLoggedRatio = Float.NaN
        imageAnalysis = imageAnalysis ?: ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            //.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build().apply {
                JdcrCameraLog.i("ImageAnalysis的图片采样间隔ms:$throttler")
                setAnalyzer(cameraExecutor) {
                    val rotationDegrees = it.imageInfo.rotationDegrees
                    if (rotationDegrees != lastLoggedRotation) {
                        lastLoggedRotation = rotationDegrees
                        JdcrCameraLog.i(
                            "分析帧基础方向:" +
                                "raw=${it.width}x${it.height}" +
                                ",rawCrop=${it.cropRect.toShortString()}" +
                                ",imageRotation=${rotationDegrees}°" +
                                ",analysisTarget=${imageAnalysis?.targetRotation?.let(::rotationName)}" +
                                ",uiRotation=${uiRotationDegrees.value}°" +
                                ",后置=$currentLensFacingBack"
                        )
                    }
                    if (isThrottlePass()) {
                        val frameUiRotation = uiRotationDegrees
                        // 每帧按当前 uiRotation 计算裁剪比例,确保旋转后识别裁剪跟随预览可视区。
                        val ratio = resolveAnalysisRatio(frameUiRotation)
                        val ratioValue = ratio ?: Float.NEGATIVE_INFINITY
                        if (ratioValue != lastLoggedRatio) {
                            lastLoggedRatio = ratioValue
                            JdcrCameraLog.i(
                                "分析裁剪比例:$ratio," +
                                    "uiRotation=${frameUiRotation.value}°," +
                                    "previewBaseRatio=$previewBaseRatio,centerCrop=$centerCrop"
                            )
                        }
                        var bitmap = it.toJdcrBitmap(ratio, !currentLensFacingBack)
                        bitmap = applyUiRotation(bitmap, frameUiRotation)
                        if (frameUiRotation == uiRotationDegrees) {
                            analysisImageFlow.tryEmit(bitmap)
                        } else {
                            bitmap.recycle()
                        }
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
//                    if (state is JdcrCameraState.Opened) {
//                        if (lastOperation is JdcrCameraOperation.Close) {
//                            JdcrCameraLog.d("相机已开启,但最后的操作是关闭相机,现在执行关闭")
//                            internalClose()
//                        }
//                    }
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
                unbind(*boundUseCases.toTypedArray())
                camera =
                    bindToLifecycle(
                        lifecycleOwner,
                        getCameraSelector(),
                        *list.toTypedArray()
                    ).apply {
                        observeState(this.cameraInfo)
                    }
                boundUseCases = list
                logBindDiagnostics(camera)
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
        return isClosed()
    }

    fun isOpened(): Boolean {
        return lastOperation is JdcrCameraOperation.Open && (stateFlow.value is JdcrCameraState.Opened)
    }

    fun isClosed(): Boolean {
        return lastOperation is JdcrCameraOperation.Close && (stateFlow.value is JdcrCameraState.Closed || stateFlow.value == JdcrCameraState.IDLE) && (camera == null && boundUseCases.isEmpty())
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

    fun changeRotationClockwise() {
        runMain {
            val nextRotation = when (requestedUiRotationDegrees) {
                JdcrCameraUIRotation.DEGREES_0 -> JdcrCameraUIRotation.DEGREES_90
                JdcrCameraUIRotation.DEGREES_90 -> JdcrCameraUIRotation.DEGREES_180
                JdcrCameraUIRotation.DEGREES_180 -> JdcrCameraUIRotation.DEGREES_270
                JdcrCameraUIRotation.DEGREES_270 -> JdcrCameraUIRotation.DEGREES_0
            }
            changeRotation(nextRotation)
        }
    }

    fun changeRotation(viewRotation: JdcrCameraUIRotation) {
        runMain {
            val oldRotation = requestedUiRotationDegrees
            JdcrCameraLog.i("触发手动旋转:old=${oldRotation.value}°,new=${viewRotation.value}°")
            if (viewRotation == requestedUiRotationDegrees) {
                JdcrCameraLog.i("忽略重复旋转请求:当前=${requestedUiRotationDegrees.value}°")
                return@runMain
            }
            requestedUiRotationDegrees = viewRotation
            val previewView = getPreviewView()
            val parent = previewView.parent as? ViewGroup

            // 先完成目标宽高布局,再提交角度。否则会短暂出现“旧尺寸 + 新角度”的黑边。
            previewView.doOnNextLayout {
                if (requestedUiRotationDegrees != viewRotation) return@doOnNextLayout
                previewView.scaleType = config.previewConfig.scaleType
                previewView.rotation = viewRotation.value
                uiRotationDegrees = viewRotation
                JdcrCameraLog.i(
                    "手动旋转已应用:" +
                        "old=${oldRotation.value}°,new=${viewRotation.value}°" +
                        ",viewRotation=${previewView.rotation}°" +
                        ",viewSize=${previewView.width}x${previewView.height}" +
                        ",layoutSize=${previewView.layoutParams.width}x${previewView.layoutParams.height}" +
                        ",parentSize=${parent?.width}x${parent?.height}" +
                        ",${useCaseDirectionDescription()}"
                )
            }
            JdcrCameraUtils.relayoutPreviewView(previewView, viewRotation)
            updateUseCaseRotation()
        }
    }

    /**
     * 将 UseCase 的 targetRotation 重新对齐到当前物理 display 方向。
     * 暂时保持既有行为,仅更新 Preview/Capture,不改 ImageAnalysis。
     * 方向诊断日志会明确记录两者是否一致,待问题设备验证后再决定是否同步 Analysis。
     */
    private fun updateUseCaseRotation() {
        val rotation = resolveDisplayRotation(getPreviewView())
        preview?.targetRotation = rotation
        capture?.targetRotation = rotation
        JdcrCameraLog.d("同步UseCase的targetRotation=" + rotationName(rotation) + "(仅Preview/Capture,跳过ImageAnalysis)")
    }

    private fun useCaseDirectionDescription(): String {
        val previewInfo = preview?.resolutionInfo
        val analysisInfo = imageAnalysis?.resolutionInfo
        return "preview(target=${preview?.targetRotation?.let(::rotationName)}," +
            "resolution=${previewInfo?.resolution},crop=${previewInfo?.cropRect}," +
            "rotation=${previewInfo?.rotationDegrees})," +
            "analysis(target=${imageAnalysis?.targetRotation?.let(::rotationName)}," +
            "resolution=${analysisInfo?.resolution},crop=${analysisInfo?.cropRect}," +
            "rotation=${analysisInfo?.rotationDegrees})," +
            "uiRotation=${uiRotationDegrees.value}°,后置=$currentLensFacingBack"
    }

    private fun logBindDiagnostics(camera: Camera?) {
        runCatching {
            val previewView = getPreviewView()
            val sensorRotation = camera?.cameraInfo?.sensorRotationDegrees

            // 多来源方向对比:定位是哪一层错位(view 所在 display / Activity 的 display / 配置方向)
            val viewDisplayRotation = previewView.display?.rotation
            val activityDisplayRotation =
                (context as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation
            val viewContextDisplayRotation =
                (previewView.context as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation
            val orientation = context.resources.configuration.orientation
            val orientationName = when (orientation) {
                android.content.res.Configuration.ORIENTATION_PORTRAIT -> "竖屏"
                android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "横屏"
                else -> "未知($orientation)"
            }
            val previewTarget = preview?.targetRotation
            val analysisTarget = imageAnalysis?.targetRotation

            JdcrCameraLog.i(
                "相机绑定:设备=" + Build.MANUFACTURER + "/" + Build.MODEL +
                    ",sensorRotationDegrees=" + sensorRotation +
                    ",view.display.rotation=" + (viewDisplayRotation?.let { rotationName(it) } ?: "null") +
                    ",activity.display.rotation=" + (activityDisplayRotation?.let { rotationName(it) } ?: "null") +
                    ",viewCtx.display.rotation=" + (viewContextDisplayRotation?.let { rotationName(it) } ?: "null") +
                    ",configuration=" + orientationName +
                    ",后置=" + currentLensFacingBack +
                    ",绑定用例数=" + boundUseCases.size +
                    ",previewView=" + previewView.width + "x" + previewView.height +
                    ",layout=" + previewView.layoutParams.width + "x" + previewView.layoutParams.height +
                    ",${useCaseDirectionDescription()}"
            )
            if (previewTarget != null && analysisTarget != null && previewTarget != analysisTarget) {
                JdcrCameraLog.w(
                    "[方向诊断]绑定后Preview与ImageAnalysis的targetRotation不一致:" +
                        "preview=${rotationName(previewTarget)},analysis=${rotationName(analysisTarget)}"
                )
            }
            logViewHierarchyTransforms(previewView)
            // 渲染层的最终变换要等 surface 就绪+布局后才生效,多采样几次,一次跑够
            previewView.postDelayed({ logPreviewRenderTransform(previewView, "0.8s") }, 800)
            previewView.postDelayed({ logPreviewRenderTransform(previewView, "2s") }, 2000)
            previewView.postDelayed({ logPreviewRenderTransform(previewView, "4s") }, 4000)
        }.onFailure {
            JdcrCameraLog.e("打印相机绑定方向诊断失败", it)
        }
    }

    /**
     * 一次打够:Preview 选定分辨率 + 需要的旋转(ResolutionInfo)、TextureView 实际变换矩阵、
     * PreviewView 当前显示帧的尺寸。这是“画面到底转没转、谁转的”的最终证据。
     */
    private fun logPreviewRenderTransform(previewView: PreviewView, tag: String) {
        runCatching {
            // 1. Preview 用例自己选定的分辨率 + 它判断需要旋转多少度
            val resInfo = preview?.resolutionInfo
            val resStr = if (resInfo != null) {
                "resolution=" + resInfo.resolution + ",rotationDegrees=" + resInfo.rotationDegrees
            } else {
                "resolutionInfo=null"
            }

            // 2. PreviewView 当前实际显示出来的帧(经过所有变换后用户看到的)
            val shown = previewView.bitmap
            val shownStr = if (shown != null) {
                val orient = if (shown.width >= shown.height) "横(正常)" else "竖(疑似被转)"
                "显示帧=" + shown.width + "x" + shown.height + "(" + orient + ")"
            } else {
                "显示帧=null"
            }

            // 3. 承载画面的 TextureView 的变换矩阵
            val texture =
                findChild(previewView) { it is android.view.TextureView } as? android.view.TextureView
            val textureStr = if (texture != null) {
                val m = android.graphics.Matrix()
                texture.getTransform(m)
                val v = FloatArray(9)
                m.getValues(v)
                val angle = Math.round(
                    Math.toDegrees(
                        Math.atan2(v[Matrix.MSKEW_X].toDouble(), v[Matrix.MSCALE_X].toDouble())
                    )
                )
                "TextureView=" + texture.width + "x" + texture.height +
                    ",估算旋转=" + angle + "°,scaleX=" + v[Matrix.MSCALE_X] +
                    ",scaleY=" + v[Matrix.MSCALE_Y] + ",skewX=" + v[Matrix.MSKEW_X] +
                    ",skewY=" + v[Matrix.MSKEW_Y]
            } else {
                val sv = findChild(previewView) { it is android.view.SurfaceView }
                if (sv != null) "渲染层=SurfaceView(无法取transform)" else "未找到Texture/SurfaceView"
            }

            JdcrCameraLog.i("预览渲染层诊断[$tag]:$resStr | $shownStr | $textureStr")
        }.onFailure {
            JdcrCameraLog.e("打印预览渲染层诊断失败[$tag]", it)
        }
    }

    private fun findChild(root: android.view.View, predicate: (android.view.View) -> Boolean): android.view.View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findChild(root.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    /**
     * 从 previewView 向上逐级检查父 View 是否带 rotation/scale 变换。
     * COMPATIBLE(TextureView)预览会继承祖先 View 的变换,宿主容器若被旋转会导致预览整体歪。
     */
    private fun logViewHierarchyTransforms(start: android.view.View) {
        runCatching {
            val sb = StringBuilder("预览视图祖先transform诊断(从预览往上):")
            var v: android.view.View? = start
            var depth = 0
            var foundTransform = false
            while (v != null && depth < 15) {
                val hasTransform = v.rotation != 0f || v.rotationX != 0f || v.rotationY != 0f ||
                    v.scaleX != 1f || v.scaleY != 1f
                if (hasTransform) foundTransform = true
                if (hasTransform || depth == 0) {
                    sb.append("\n  [").append(depth).append("] ")
                        .append(v.javaClass.simpleName)
                        .append(" size=").append(v.width).append("x").append(v.height)
                        .append(" rotation=").append(v.rotation)
                        .append(" rotationX=").append(v.rotationX)
                        .append(" rotationY=").append(v.rotationY)
                        .append(" scaleX=").append(v.scaleX)
                        .append(" scaleY=").append(v.scaleY)
                        .append(if (hasTransform) "  <== 有变换!" else "")
                }
                v = v.parent as? android.view.View
                depth++
            }
            if (!foundTransform) sb.append("\n  (祖先链未发现 rotation/scale 变换)")
            JdcrCameraLog.i(sb.toString())
        }.onFailure {
            JdcrCameraLog.e("打印预览视图祖先transform失败", it)
        }
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
        requestedUiRotationDegrees = JdcrCameraUIRotation.DEGREES_0
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
            camera?.cameraInfo?.cameraState?.removeObservers(lifecycleOwner)
            if (boundUseCases.isNotEmpty()) {
                cameraProvider?.unbind(*boundUseCases.toTypedArray())
                boundUseCases = emptyList()
            }
            camera = null
            updateState(JdcrCameraState.Closed)
            Result.success(true)
        }
    }

    fun isBackFacing(): Boolean {
        return currentLensFacingBack
    }

    fun getUIRotationDegrees(): JdcrCameraUIRotation {
        return requestedUiRotationDegrees
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
