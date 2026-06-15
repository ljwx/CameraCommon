# 手势识别性能优化方案

## 1. 背景分析

当前手势识别库在处理相机实时图像流时，存在显著的性能瓶颈。主要表现为：
1. CameraX 输出了远超模型需要的**高分辨率原图**。
2. 图像在输入到 MediaPipe 前，通过 CPU 进行了**高强度的 YUV 到 Bitmap 转换**。
3. 当 MediaPipe 开启 GPU 加速 (`Delegate.GPU`) 时，仍然存在 **CPU 内存 (Bitmap) 到 GPU 显存**的数据冗余拷贝。

以下方案旨在通过**底层相机配置**与**内存直通 (Zero-Copy)** 彻底解决上述性能损耗。

---

## 2. 优化点一：相机流直接降采样 (Downsampling Optimization)

所有 MediaPipe 的图像模型（包括手势识别）底层 Tensor 的输入尺寸都是固定的（通常为 256x256 左右）。向模型输入 1080P 等几兆大的高清图像不仅无法提升识别率，反而会造成极大的预处理损耗。

### 📌 改动说明
**禁止在代码中手动使用 Matrix 或 Bitmap 去压缩图片！**
修改 `JdcrCameraHelper.kt` 中 `ImageAnalysis` 的配置，直接命令相机硬件（ISP）输出低分辨率图像流。

```kotlin
// 修改前
imageAnalysis = imageAnalysis ?: ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()

// 修改后
import android.util.Size

imageAnalysis = imageAnalysis ?: ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setTargetResolution(Size(640, 480)) // 👉 新增：强制降低输出分辨率
    .build()
```

### 📈 收益
- **极大幅降低 CPU 算力消耗**：废除了大图的像素级遍历。
- **成倍降低内存占用及 GC 频率**：每帧图像的内存从几 MB 骤降到几百 KB。
- **提升帧率与降低发热**：MediaPipe 内部无需再对大尺寸图像进行复杂的 Resize 操作。

### ⚠️ 风险
- **宽高等比缩放问题**：强制设置分辨率后，需要注意相机输出图像的长宽比是否与预览画面的长宽比匹配（一般建议保持 4:3 比例的低分辨率输出，如 640x480）。如果比例差异过大，有极小概率引起识别区域框的不对应（需要注意你的裁剪或坐标映射逻辑）。

---

## 3. 优化点二：GPU 显存直通 (VRAM Zero-Copy Optimization)

目前你在获取到相机数据后，先在 CPU 侧将其转换为了 `Bitmap`，然后塞给 MediaPipe 再次送入 GPU，这会受到内存总线带宽的极大限制。

### 📌 改动说明
利用 CameraX 结合 MediaPipe 原生支持的 `HardwareBuffer`，实现从相机硬件直通 GPU。

**步骤 1: 开启 RGBA 格式流输出**
在 `JdcrCameraHelper.kt` 中修改格式。在 Android 10+ 平台上，RGBA_8888 格式的 Image 会通过底层的 HardwareBuffer 分配，这使得它能直接被映射为 OpenGL 纹理。
```kotlin
imageAnalysis = imageAnalysis ?: ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setTargetResolution(Size(640, 480))
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // 👉 新增：指定输出格式
    .build()
```

**步骤 2: 废弃 Bitmap 转换逻辑**
不要再调用 `toJdcrBitmap()`。暴露一个新的 Flow，直接把原始的 `ImageProxy` 传出去（或者直接在回调内部对接识别器）。
```kotlin
// 新增或修改你的回调流类型，抛弃 Bitmap，发射 ImageProxy
private val analysisImageProxyFlow = MutableSharedFlow<ImageProxy>(...) 

setAnalyzer(cameraExecutor) { imageProxy ->
    if (isThrottlePass()) {
        // 直接暴露 ImageProxy 对象
        analysisImageProxyFlow.tryEmit(imageProxy) 
    } else {
        imageProxy.close() // 抛弃的帧必须手动回收
    }
}
```

**步骤 3: 改造 MediaPipe 的接收端**
修改 `JdcrGestureRecognizerHelper.kt`，不要使用 `BitmapImageBuilder`，替换为原生能够对接硬件缓冲的 `MediaImageBuilder`。
```kotlin
import com.google.mediapipe.framework.image.MediaImageBuilder

fun recognizeAsyncImageProxy(imageProxy: ImageProxy) {
    val image = imageProxy.image
    if (image != null) {
        // 👉 核心：MediaImageBuilder 会利用底层的 HardwareBuffer 映射显存
        val mpImage = MediaImageBuilder(image).build()
        gestureRecognizer.recognizeAsync(mpImage, android.os.SystemClock.uptimeMillis())
    }
    // 👉 关键：MediaPipe 同步复制/映射完数据流后，必须立即 close 相机对象释放缓存
    imageProxy.close()
}
```

### 📈 收益
- **真正实现 0 次 CPU 像素拷贝**：完全告别缓慢且发热严重的 CPU YUV 转 RGB/Bitmap 操作。
- **打破显存瓶颈**：数据几乎是瞬间在底层通过纹理或硬件缓冲送入 GPU 的模型中，是当前移动端视觉推理的最高效做法。

### ⚠️ 风险
- **对象泄漏与相机卡死**：`ImageProxy` 对象来自 CameraX 内部的对象池。如果你在转交过程中没有正确地执行 `imageProxy.close()`，当对象池耗尽（通常只缓存 2-3 帧），相机流将会彻底冻结无法产出新画面。
- **Android 系统版本差异**：完全享受硬件缓冲零拷贝的特性通常依赖 `Android 10 (API 29)` 及以上的系统支持。在极低版本的 Android 机型上，`MediaImageBuilder` 可能依然会内部回退为 CPU 转换。但即便如此，仍比你用纯 Kotlin 自己算 Bitmap 快得多。

---

## 4. 总结方案

如果你想花最少的精力拿到 **90% 的收益**：请只做**优化点一**（加一行 `setTargetResolution`），立马能体验到脱胎换骨的流畅度。
如果你追求极致的**工业级性能**，并解决设备发热瓶颈：请在优化一的基础上，将**优化点二（废除 Bitmap 转直通 ImageProxy）**彻底重构完成。
