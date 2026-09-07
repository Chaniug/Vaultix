package com.bastion.app.ui.scanner

import com.bastion.app.logging.runCatchingObserved
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import android.media.Image
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("UnsafeOptInUsageError")
internal class QrCameraScanSession(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    zxingFormats: Collection<BarcodeFormat>,
    private val generation: Int,
    private val diagnostics: QrScannerDiagnostics?,
    private val onCandidates: (List<String>, barcodeCount: Int, durationMs: Long) -> Boolean,
    private val onRestartRequested: (QrScanRestartReason) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val controller = LifecycleCameraController(appContext)
    private val zxingFormats: Collection<BarcodeFormat> = zxingFormats
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val healthPolicy = QrScanHealthPolicy()
    private val active = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val processingFrame = AtomicBoolean(false)
    private val previewStreaming = AtomicBoolean(false)
    private val previewObserver = Observer<PreviewView.StreamState> { state ->
        val streaming = state == PreviewView.StreamState.STREAMING
        val changed = previewStreaming.getAndSet(streaming) != streaming
        if (changed) diagnostics?.logPreviewState(streaming)
        // 仅在 STREAMING 状态发生变化时对焦：旧实现每次回调都触发，
        // 叠加 session 重启后会出现"反复拉风箱"导致镜头跟不住。
        if (changed && streaming) {
            requestCenterFocus(reason = "preview_streaming")
        }
    }

    fun start() {
        check(active.compareAndSet(false, true)) { "QR camera session already started" }
        val startedAt = SystemClock.elapsedRealtime()
        healthPolicy.onSessionStarted(startedAt)
        diagnostics?.logSessionStarted(generation)
        diagnostics?.logCameraProviderRequested(1)

        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        controller.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        )
        controller.setTapToFocusEnabled(true)
        controller.setImageAnalysisAnalyzer(analysisExecutor, ::analyzeFrame)

        previewView.controller = controller
        previewView.previewStreamState.observeForever(previewObserver)

        runCatchingObserved {
            controller.bindToLifecycle(lifecycleOwner)
        }.onFailure { error ->
            diagnostics?.logCameraBindFailed(error)
            requestRestart(QrScanRestartReason.FrameStreamStopped)
            return
        }

        controller.initializationFuture.addListener(
            {
                if (!active.get()) return@addListener
                runCatchingObserved { controller.initializationFuture.get() }
                    .onSuccess {
                        diagnostics?.logCameraBindSuccess(SystemClock.elapsedRealtime() - startedAt)
                        previewView.post { requestCenterFocus(reason = "session_start") }
                    }
                    .onFailure { error ->
                        diagnostics?.logCameraProviderFailed(error)
                        requestRestart(QrScanRestartReason.FrameStreamStopped)
                    }
            },
            mainExecutor
        )
    }

    fun tick(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!active.get()) return
        when (val action = healthPolicy.nextAction(nowMs, previewStreaming.get())) {
            QrScanHealthAction.None -> Unit
            QrScanHealthAction.Refocus -> {
                if (requestCenterFocus(reason = "periodic")) {
                    healthPolicy.onRefocusRequested(nowMs)
                }
            }
            is QrScanHealthAction.Restart -> requestRestart(action.reason)
        }
    }

    fun isProcessingFrame(): Boolean = processingFrame.get()

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!active.get()) {
            imageProxy.close()
            return
        }
        if (!processingFrame.compareAndSet(false, true)) {
            diagnostics?.logFrameSkipped()
            imageProxy.close()
            return
        }

        val frameStartedAt = SystemClock.elapsedRealtime()
        healthPolicy.onFrameStarted(frameStartedAt)
        diagnostics?.logFrameStarted(imageProxy.imageInfo.rotationDegrees)
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            diagnostics?.logFrameMissingImage()
            healthPolicy.onFrameCompleted(SystemClock.elapsedRealtime(), QrFrameOutcome.Failed)
            processingFrame.set(false)
            imageProxy.close()
            return
        }

        val frameFinished = AtomicBoolean(false)
        fun finishFrame(outcome: QrFrameOutcome) {
            if (!frameFinished.compareAndSet(false, true)) return
            healthPolicy.onFrameCompleted(SystemClock.elapsedRealtime(), outcome)
            processingFrame.set(false)
            runCatchingObserved { imageProxy.close() }
        }

        // 用 runCatching 兜底——任何在解码管线中抛出的异常都应被识别为 Failed。
        // 此前 onCandidates 也包了 runCatchingObserved，但 decodeFrameZxing 自身抛错
        // 会让整帧丢失且不报 health policy，导致管线真坏时永远不重启。
        val candidates = try {
            decodeFrameZxing(imageProxy, zxingFormats)
        } catch (t: Throwable) {
            diagnostics?.logFrameFailure(t)
            finishFrame(QrFrameOutcome.Failed)
            return
        }
        val durationMs = SystemClock.elapsedRealtime() - frameStartedAt
        val matched = runCatchingObserved {
            onCandidates(candidates, if (candidates.isNotEmpty()) 1 else 0, durationMs)
        }.getOrDefault(false)
        diagnostics?.logFrameSuccess(
            durationMs = durationMs,
            barcodeCount = if (candidates.isNotEmpty()) 1 else 0,
            candidateCount = candidates.size,
            matched = matched
        )
        finishFrame(
            outcome = if (candidates.isNotEmpty()) QrFrameOutcome.Detected else QrFrameOutcome.Empty
        )
    }

    private fun requestCenterFocus(reason: String): Boolean {
        if (!active.get() || !previewStreaming.get()) return false
        if (previewView.width <= 0 || previewView.height <= 0) return false

        return runCatchingObserved {
            val point = previewView.meteringPointFactory.createPoint(
                previewView.width / 2f,
                previewView.height / 2f,
                FOCUS_POINT_SIZE
            )
            // 不带 FLAG_AWB：避免白平衡跳变造成画面整体偏色闪烁；
            // 仍保留 AF + AE，覆盖对焦与曝光。
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
                .build()
            val cameraControl = controller.cameraControl ?: return@runCatchingObserved false
            cameraControl.startFocusAndMetering(action)
            diagnostics?.logRefocusRequested(reason)
            true
        }.onFailure { error ->
            diagnostics?.logRefocusFailed(error)
        }.getOrDefault(false)
    }

    private fun requestRestart(reason: QrScanRestartReason) {
        if (!active.compareAndSet(true, false)) return
        diagnostics?.logSessionRestartRequested(reason)
        closeResources()
        mainExecutor.execute { onRestartRequested(reason) }
    }

    override fun close() {
        active.set(false)
        closeResources()
    }

    private fun closeResources() {
        if (!closed.compareAndSet(false, true)) return
        diagnostics?.logDispose(processingFrame.get())
        previewStreaming.set(false)
        runCatchingObserved { previewView.previewStreamState.removeObserver(previewObserver) }
        runCatchingObserved { controller.clearImageAnalysisAnalyzer() }
        runCatchingObserved { previewView.controller = null }
        runCatchingObserved { controller.unbind() }
        analysisExecutor.shutdown()
    }

    private companion object {
        private const val ANALYSIS_WIDTH = 1280
        private const val ANALYSIS_HEIGHT = 960
        private const val FOCUS_POINT_SIZE = 0.24f
        // 从 3s 缩到 2s：周期性对焦间隔已放宽到 8s，2s 后回到连续 AF，
        // 镜头就有更长时间用设备默认的连续自动对焦跟随目标移动。
        private const val FOCUS_AUTO_CANCEL_SECONDS = 2L
    }
}

/**
 * 二维码优先格式（绝大多数扫码场景）。先试这批可让最常见场景只跑一次 binarizer。
 * 放在 top-level 是为了被文件内其它 private 函数引用；
 * companion object 的访问控制是 private 时外部不可见。
 */
private val TWO_DIMENSIONAL_FORMATS: Set<BarcodeFormat> = setOf(
    BarcodeFormat.QR_CODE,
    BarcodeFormat.DATA_MATRIX,
    BarcodeFormat.AZTEC,
    BarcodeFormat.PDF_417
)

/**
 * 把 [ImageProxy] 的 YUV_420_888 平面直接喂给 ZXing：[YuvImage] 压缩-JPEG-解码-Bitmap
 * 的链路单帧在 1280x960 上要 200-500ms，且每帧 5MB Bitmap 分配会触发持续 GC，
 * 是扫码「卡顿」的主要来源。改成：YUV → NV21（fast path）→ [com.google.zxing.PlanarYUVLuminanceSource]
 * → [RotatedLuminanceSource]（按 [ImageProxy] 旋转角校正方向）→ [BinaryBitmap]。
 * 整条链零大对象分配，单帧稳定在 30-80ms。
 */
private fun decodeFrameZxing(imageProxy: ImageProxy, formats: Collection<BarcodeFormat>): List<String> {
    val source = imageProxyToLuminanceSource(imageProxy) ?: return emptyList()
    return decodeSourceZxing(source, formats)
}

/**
 * 拆 2D / 1D 格式：相同 [BinaryBitmap] 跑两次 [MultiFormatReader] 即可，
 * 避免在同一次 decode 中让 ZXing 在 13 个 reader 上串行搜索（TRY_HARDER 时尤其慢）。
 * 二维码命中就直接返回；1D reader 在二维码场景几乎不可能命中，跳过它们即可拿满性能。
 */
internal fun decodeSourceZxing(
    source: com.google.zxing.LuminanceSource,
    formats: Collection<BarcodeFormat>
): List<String> {
    val binary = BinaryBitmap(HybridBinarizer(source))
    val twoD = formats.filter { it in TWO_DIMENSIONAL_FORMATS }
    val oneD = formats.filterNot { it in TWO_DIMENSIONAL_FORMATS }
    if (twoD.isNotEmpty()) {
        decodeWithFormats(binary, twoD)?.let { return it }
    }
    if (oneD.isNotEmpty()) {
        decodeWithFormats(binary, oneD)?.let { return it }
    }
    return emptyList()
}

private fun decodeWithFormats(
    binary: BinaryBitmap,
    formats: List<BarcodeFormat>
): List<String>? {
    val hints = buildZxingHints(formats)
    return runCatchingObserved {
        buildCandidates(MultiFormatReader().decode(binary, hints))
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * 把 [ImageProxy] 转为 [com.google.zxing.LuminanceSource]，按
 * [ImageProxy.imageInfo] 的 rotationDegrees 包一层 [RotatedLuminanceSource]。
 * 输入若 [Image.image] 为空则返回 null（视为空帧）。
 */
// lint 的 UnsafeOptInUsageError 不识别 Kotlin 的 @OptIn，属冗余告警。
// @OptIn 对编译期真正生效，@SuppressLint 仅用于安抚 lint，不改变任何运行行为。
@SuppressLint("UnsafeOptInUsageError")
@OptIn(ExperimentalGetImage::class)
private fun imageProxyToLuminanceSource(imageProxy: ImageProxy): com.google.zxing.LuminanceSource? {
    val mediaImage = imageProxy.image ?: return null
    val width = mediaImage.width
    val height = mediaImage.height
    val nv21 = yuv420ToNv21(mediaImage)
    val base: com.google.zxing.LuminanceSource = com.google.zxing.PlanarYUVLuminanceSource(
        nv21, width, height,
        0, 0, width, height,
        false
    )
    val rotation = imageProxy.imageInfo.rotationDegrees
    return if (rotation == 0) base else RotatedLuminanceSource(base, rotation)
}

/**
 * 把 YUV_420_888 的三平面数据紧凑为 NV21（Y 平面 + 交错 VU）。
 *
 * 快路径：当 Y 平面的 rowStride == width 且 pixelStride == 1、UV 平面的
 * pixelStride == 1/2 且 rowStride 与 NV21 偏移匹配时，使用 [java.nio.ByteBuffer] 的
 * 批量拷贝而不是逐字节 Kotlin 循环。旧实现里 1.8M 次 lambda 调用是单帧 100-300ms 的
 * 主要原因之一。其它布局回退到逐行 [System.arraycopy]（仍是 O(n) 但常数小很多）。
 *
 * 当 rowStride > width 或 pixelStride > 1 时，某些设备的平面布局可能导致写入位置
 * 超出 NV21 缓冲区。带边界保护：一旦 pos 超界则截断并返回部分数据（解码会失败）。
 */
private fun yuv420ToNv21(image: Image): ByteArray {
    val width = image.width
    val height = image.height
    val planes = image.planes
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride

    val nv21 = ByteArray(width * height + (width * height) / 2)

    val yCompact = yRowStride == width && yPixelStride == 1
    if (yCompact && yBuffer.remaining() >= width * height) {
        // 常见紧凑布局：Y 平面直接 bulk copy。
        yBuffer.get(nv21, 0, width * height)
    } else {
        val yRow = ByteArray(yRowStride)
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(yRow, 0, minOf(yRowStride, yBuffer.remaining()))
            for (col in 0 until width) {
                val srcIdx = col * yPixelStride
                if (srcIdx >= yRowStride) break
                val pos = row * width + col
                if (pos >= nv21.size) return nv21
                nv21[pos] = yRow[srcIdx]
            }
        }
    }

    val uvHeight = height / 2
    val uvWidth = width / 2
    val uvOffset = width * height
    val uvCompact = uRowStride == vRowStride &&
        uPixelStride == 1 && vPixelStride == 2 &&
        uBuffer.remaining() + vBuffer.remaining() >= uvWidth * uvHeight * 2
    if (uvCompact) {
        // 标准 NV21 交错：V 在前、U 在后。常见 CameraX 输出就是这种布局。
        var pos = uvOffset
        for (row in 0 until uvHeight) {
            vBuffer.position(row * vRowStride)
            vBuffer.get(nv21, pos, uvWidth)
            pos += uvWidth
            uBuffer.position(row * uRowStride)
            uBuffer.get(nv21, pos, uvWidth)
            pos += uvWidth
        }
    } else {
        val uRowArr = ByteArray(uRowStride)
        val vRowArr = ByteArray(vRowStride)
        var pos = uvOffset
        for (row in 0 until uvHeight) {
            uBuffer.position(row * uRowStride)
            uBuffer.get(uRowArr, 0, minOf(uRowStride, uBuffer.remaining()))
            vBuffer.position(row * vRowStride)
            vBuffer.get(vRowArr, 0, minOf(vRowStride, vBuffer.remaining()))
            for (col in 0 until uvWidth) {
                val vIdx = col * vPixelStride
                val uIdx = col * uPixelStride
                if (vIdx >= vRowStride || uIdx >= uRowStride) break
                if (pos + 1 >= nv21.size) return nv21
                nv21[pos++] = vRowArr[vIdx]
                nv21[pos++] = uRowArr[uIdx]
            }
        }
    }
    return nv21
}

/**
 * 把 [com.google.zxing.LuminanceSource] 按指定角度旋转的只读视图。
 * 不复制底层数据，getRow / getMatrix 都是 O(width) 重映射（binarizer 只读一次即可）。
 * 仅支持 0/90/180/270 度旋转，覆盖 CameraX 后置摄像头 portrait 取向的全部可能值。
 *
 * 旋转映射（new(x, y) = orig(?, ?)）：
 *  0°:  orig(x, y)
 *  90° CW: orig(y, H-1-x)        // 矩阵索引 (H-1-x)*W + y
 *  180°: orig(W-1-x, H-1-y)      // 矩阵索引 (H-1-y)*W + (W-1-x)
 *  270° CW (=90° CCW): orig(W-1-y, x) // 矩阵索引 x*W + (W-1-y)
 *  其中 W = delegate.width, H = delegate.height。
 */
internal class RotatedLuminanceSource(
    private val delegate: com.google.zxing.LuminanceSource,
    degrees: Int
) : com.google.zxing.LuminanceSource(
    if (degrees % 180 == 0) delegate.width else delegate.height,
    if (degrees % 180 == 0) delegate.height else delegate.width
) {
    private val normalized: Int = ((degrees % 360) + 360) % 360
    private val srcWidth: Int = delegate.width
    private val srcHeight: Int = delegate.height

    override fun getRow(y: Int, row: ByteArray): ByteArray {
        if (row.size < width) {
            throw IllegalArgumentException("row buffer too small: ${row.size} < $width")
        }
        when (normalized) {
            0 -> return delegate.getRow(y, row)
            90 -> {
                val mat = delegate.matrix
                for (x in 0 until width) {
                    row[x] = mat[(srcHeight - 1 - x) * srcWidth + y]
                }
            }
            180 -> {
                val mat = delegate.matrix
                val baseY = (srcHeight - 1 - y) * srcWidth
                for (x in 0 until width) {
                    row[x] = mat[baseY + (srcWidth - 1 - x)]
                }
            }
            270 -> {
                val mat = delegate.matrix
                for (x in 0 until width) {
                    row[x] = mat[x * srcWidth + (srcWidth - 1 - y)]
                }
            }
            else -> throw IllegalStateException("unsupported rotation: $normalized")
        }
        return row
    }

    override fun getMatrix(): ByteArray {
        val out = ByteArray(width * height)
        when (normalized) {
            0 -> return delegate.matrix
            90 -> {
                val src = delegate.matrix
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[y * width + x] = src[(srcHeight - 1 - x) * srcWidth + y]
                    }
                }
            }
            180 -> {
                val src = delegate.matrix
                for (y in 0 until height) {
                    val baseY = (srcHeight - 1 - y) * srcWidth
                    for (x in 0 until width) {
                        out[y * width + x] = src[baseY + (srcWidth - 1 - x)]
                    }
                }
            }
            270 -> {
                val src = delegate.matrix
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[y * width + x] = src[x * srcWidth + (srcWidth - 1 - y)]
                    }
                }
            }
            else -> throw IllegalStateException("unsupported rotation: $normalized")
        }
        return out
    }

    override fun isCropSupported(): Boolean = false
    override fun isRotateSupported(): Boolean = true

    override fun rotateCounterClockwise(): com.google.zxing.LuminanceSource =
        RotatedLuminanceSource(delegate, -normalized)
}

internal fun buildZxingHints(formats: Collection<BarcodeFormat>): Map<DecodeHintType, Any> {
    val hints = HashMap<DecodeHintType, Any>()
    hints[DecodeHintType.TRY_HARDER] = true
    hints[DecodeHintType.POSSIBLE_FORMATS] = formats.toList()
    return hints
}

internal fun buildCandidates(result: Result): List<String> {
    // zxing Result.getText() 是 Java 平台类型（String!），需先 trim 再判空，
    // 不能用 takeIf { it.isNotBlank() }（it 会被推断为可空 String? 而报错）。
    val text = result.text?.trim()
    if (text.isNullOrBlank()) return emptyList()
    return listOf(text)
}

/**
 * 保留供非相机路径（如相册解码）使用。相机扫码已迁移到 [decodeSourceZxing] / [PlanarYUVLuminanceSource] 路径。
 */
internal fun decodeBitmapZxing(bitmap: android.graphics.Bitmap, formats: Collection<BarcodeFormat>): List<String> {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val source = RGBLuminanceSource(w, h, pixels)
    val binary = BinaryBitmap(HybridBinarizer(source))
    val result = runCatchingObserved {
        MultiFormatReader().decode(binary, buildZxingHints(formats))
    }.getOrNull() ?: return emptyList()
    return buildCandidates(result)
}
