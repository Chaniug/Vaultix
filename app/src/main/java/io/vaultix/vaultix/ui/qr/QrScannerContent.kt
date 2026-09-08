package io.vaultix.vaultix.ui.qr

import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.vaultix.vaultix.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 二维码扫描内容（用于录入 TOTP 的 `otpauth://` 二维码）。
 *
 * 选型：**CameraX**（Google 现行推荐的相机 API，生命周期感知）+ **ZXing core**（解码）。
 * 选 ZXing 而非 ML Kit：轻量（~500KB vs unbundled 2-3MB）、零 GMS 依赖
 * （ML Kit bundled 依赖 Google Play Services，国内机型不可用）。
 *
 * 隐私：只读取预览帧做本地识别，**不拍照、不落盘、不上传**；识别到第一条结果后
 * 立即停止解码并回调（[QrAnalyzer.consumed] 保证只回调一次）。
 */
@Composable
fun QrScannerContent(
    onResult: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted -> permissionGranted = isGranted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (permissionGranted) {
            CameraPreview(onResult = onResult)
            ScannerTopBar(onBack = onBack)
        } else {
            PermissionRationale(
                onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun ScannerTopBar(onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
        Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Text(
                text = stringResource(R.string.qr_scan_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun PermissionRationale(onRequest: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.qr_camera_permission_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.qr_camera_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.qr_grant_permission))
        }
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/** CameraX 预览 + 逐帧分析；识别到结果即回调一次。 */
@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analyzerExecutor, QrAnalyzer(onResult)) }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            runCatching { future.get().unbindAll() }
            analyzerExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * 逐帧 QR 解码。
 *
 * [consumed] 保证只回调一次——否则连续多帧识别到同一个码会重复触发导航/回填。
 * 未识别到二维码是常态（几乎每帧都会抛 NotFoundException），故静默忽略。
 */
private class QrAnalyzer(
    private val onResult: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader()
    private val consumed = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (consumed.get()) {
            image.close()
            return
        }
        try {
            val text = decode(image)
            if (text != null && consumed.compareAndSet(false, true)) {
                onResult(text)
            }
        } catch (ignored: NotFoundException) {
            // 未识别到二维码是**常态**（几乎每一帧都会走到这里），静默忽略。
            // 变量名用 ignored：Detekt 的 allowedExceptionNameRegex 放行。
        } catch (ignored: Exception) {
            // 其余异常（如个别机型的图像格式异常）同样不应中断预览流——
            // 扫码是非关键路径，失败后用户仍可手动输入 TOTP 密钥。
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val luma = image.lumaNv21()
        val source = PlanarYUVLuminanceSource(
            luma,
            image.width,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false,
        )
        return reader.decode(BinaryBitmap(HybridBinarizer(source)))?.text
    }
}

/**
 * 取 Y 平面拼成 NV21 缓冲。
 *
 * ZXing 的 [PlanarYUVLuminanceSource] 只读前 `width * height` 字节（Y 平面），
 * UV 部分不参与黑白二维码识别，因此这里只填 Y、UV 留 0——既够用也省一次拷贝。
 */
private fun ImageProxy.lumaNv21(): ByteArray {
    val yPlane = planes[0]
    val buffer = yPlane.buffer
    val ySize = width * height
    val out = ByteArray(ySize + ySize / 2)
    var outPos = 0
    for (row in 0 until height) {
        val rowStart = row * yPlane.rowStride
        for (col in 0 until width) {
            out[outPos] = buffer.get(rowStart + col * yPlane.pixelStride)
            outPos++
        }
    }
    return out
}
