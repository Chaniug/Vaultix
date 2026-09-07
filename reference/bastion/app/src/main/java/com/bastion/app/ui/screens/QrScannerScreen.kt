@file:Suppress("LocalContextGetResourceValueCall")
package com.bastion.app.ui.screens

import com.bastion.app.logging.runCatchingObserved
import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import kotlinx.coroutines.delay
import com.bastion.app.R
import com.bastion.app.ui.scanner.QrCameraScanSession
import com.bastion.app.ui.scanner.QrScanRestartReason
import com.bastion.app.ui.scanner.QrScannerDiagnostics
import com.bastion.app.ui.scanner.decodeBitmapZxing
import java.util.concurrent.atomic.AtomicBoolean

private val DEFAULT_SCANNER_FORMATS = listOf(
    BarcodeFormat.QR_CODE,
    BarcodeFormat.CODE_128,
    BarcodeFormat.CODE_39,
    BarcodeFormat.CODE_93,
    BarcodeFormat.EAN_13,
    BarcodeFormat.EAN_8,
    BarcodeFormat.UPC_A,
    BarcodeFormat.UPC_E,
    BarcodeFormat.ITF,
    BarcodeFormat.CODABAR,
    BarcodeFormat.DATA_MATRIX,
    BarcodeFormat.AZTEC,
    BarcodeFormat.PDF_417
)

/**
 * QR码扫描屏幕
 * 用于扫描TOTP密钥的QR码
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun QrScannerScreen(
    onQrCodeScanned: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    allowedFormats: Collection<BarcodeFormat> = DEFAULT_SCANNER_FORMATS,
    resultValidator: (String) -> Boolean = { true },
    invalidResultMessage: String? = null,
    diagnosticLabel: String? = null,
    onDiagnostic: ((String) -> Unit)? = null,
    bottomContent: @Composable (launchGallery: () -> Unit) -> Unit = { launchGallery ->
        DefaultQrScannerBottomContent(launchGallery = launchGallery)
    }
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
    // 添加权限状态监听，在页面显示时自动检查权限
    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }
    
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        when {
            cameraPermissionState.status.isGranted -> {
                QrCodeScanner(
                    onQrCodeScanned = onQrCodeScanned,
                    onNavigateBack = onNavigateBack,
                    title = title ?: stringResource(R.string.scan_qr_code_title),
                    subtitle = subtitle ?: stringResource(R.string.qr_align_hint),
                    allowedFormats = allowedFormats,
                    resultValidator = resultValidator,
                    invalidResultMessage = invalidResultMessage,
                    diagnosticLabel = diagnosticLabel,
                    onDiagnostic = onDiagnostic,
                    bottomContent = bottomContent
                )
            }
            else -> {
                CameraPermissionRequest(
                    permissionState = cameraPermissionState,
                    onNavigateBack = onNavigateBack
                )
            }
        }
    }
}

/**
 * 请求相机权限界面
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CameraPermissionRequest(
    permissionState: PermissionState,
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.qr_camera_permission_title),
            style = MaterialTheme.typography.headlineSmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = stringResource(R.string.camera_permission_required),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = { permissionState.launchPermissionRequest() }
        ) {
            Text(stringResource(R.string.grant_permission))
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TextButton(onClick = onNavigateBack) {
            Text(stringResource(R.string.go_back))
        }
    }
}

@Composable
private fun QrCodeScanner(
    onQrCodeScanned: (String) -> Unit,
    onNavigateBack: () -> Unit,
    title: String,
    subtitle: String,
    allowedFormats: Collection<BarcodeFormat>,
    resultValidator: (String) -> Boolean,
    invalidResultMessage: String?,
    diagnosticLabel: String?,
    onDiagnostic: ((String) -> Unit)?,
    bottomContent: @Composable (launchGallery: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanConsumed = remember { AtomicBoolean(false) }
    val diagnostics = remember(diagnosticLabel, onDiagnostic) {
        if (diagnosticLabel != null && onDiagnostic != null) {
            QrScannerDiagnostics(diagnosticLabel, onDiagnostic)
        } else {
            null
        }
    }
    val currentValidator = rememberUpdatedState(resultValidator)
    val currentOnQrCodeScanned = rememberUpdatedState(onQrCodeScanned)
    val currentInvalidResultMessage = rememberUpdatedState(invalidResultMessage)
    var scanGeneration by remember { mutableIntStateOf(0) }
    var pendingRestartReason by remember { mutableStateOf<QrScanRestartReason?>(null) }
    var lastRestartAtMs by remember { mutableLongStateOf(0L) }
    // PreviewView 故意不把 scanGeneration 放进 key —— 重启只重建 QrCameraScanSession，
    // Surface/TextureView 复用，避免画面反复销毁/创建造成「卡顿 + 镜头跟不住」。
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var showOverlay by remember { mutableStateOf(false) }

    val acceptResult: (String?) -> Unit = acceptResult@{ raw ->
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return@acceptResult
        if (currentValidator.value(value) && scanConsumed.compareAndSet(false, true)) {
            diagnostics?.logResultAccepted()
            currentOnQrCodeScanned.value(value)
        }
    }

    val cameraSession = remember(
        context,
        lifecycleOwner,
        previewView,
        allowedFormats,
        scanGeneration,
        diagnostics
    ) {
        QrCameraScanSession(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            zxingFormats = allowedFormats,
            generation = scanGeneration,
            diagnostics = diagnostics,
            onCandidates = { candidates, _, _ ->
                val value = candidates.firstOrNull(currentValidator.value)
                value?.let(acceptResult)
                value != null
            },
            onRestartRequested = { reason ->
                val now = SystemClock.elapsedRealtime()
                if (!scanConsumed.get() &&
                    pendingRestartReason == null &&
                    now - lastRestartAtMs >= QR_SCAN_SESSION_RESTART_COOLDOWN_MS
                ) {
                    pendingRestartReason = reason
                }
            }
        )
    }
    val currentCameraSession = rememberUpdatedState(cameraSession)

    // 图片选择器 - 使用 GetContent 以兼容所有设备
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            diagnostics?.logGalleryStart()
            processImageWithZxing(
                context = context,
                uri = uri,
                formats = allowedFormats,
                diagnostics = diagnostics,
                resultValidator = currentValidator.value,
                onResult = acceptResult,
                onInvalid = {
                    Toast.makeText(
                        context,
                        currentInvalidResultMessage.value ?: context.getString(R.string.qr_not_found),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onNotFound = {
                    Toast.makeText(context, context.getString(R.string.qr_not_found), Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    DisposableEffect(cameraSession) {
        cameraSession.start()
        onDispose { cameraSession.close() }
    }

    LaunchedEffect(cameraSession, scanConsumed) {
        while (!scanConsumed.get()) {
            delay(QR_SCAN_HEALTH_TICK_MS)
            cameraSession.tick()
        }
    }

    LaunchedEffect(pendingRestartReason) {
        pendingRestartReason ?: return@LaunchedEffect
        delay(QR_SCAN_SESSION_RESTART_DELAY_MS)
        lastRestartAtMs = SystemClock.elapsedRealtime()
        scanGeneration += 1
        pendingRestartReason = null
    }

    LaunchedEffect(diagnostics) {
        val activeDiagnostics = diagnostics ?: return@LaunchedEffect
        activeDiagnostics.logScannerStarted(
            requestedFormats = allowedFormats.size,
            formatCount = allowedFormats.size
        )
        while (!scanConsumed.get()) {
            delay(QR_SCAN_DIAG_HEARTBEAT_MS)
            activeDiagnostics.logHeartbeat(currentCameraSession.value.isProcessingFrame())
        }
    }

    LaunchedEffect(Unit) {
        showOverlay = true
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.46f),
                            Color.Transparent,
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.66f)
                        )
                    )
                )
        )

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(animationSpec = tween(280)) + slideInVertically(
                animationSpec = tween(280),
                initialOffsetY = { -it / 8 }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                    Column(
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        ScannerFrame(
            modifier = Modifier.align(Alignment.Center)
        )

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(animationSpec = tween(320)) + slideInVertically(
                animationSpec = tween(320),
                initialOffsetY = { it / 6 }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    bottomContent { photoPickerLauncher.launch("image/*") }
                }
            }
        }
    }
}

@Composable
private fun DefaultQrScannerBottomContent(
    launchGallery: () -> Unit
) {
    Text(
        text = stringResource(R.string.qr_align_hint),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    FilledTonalButton(
        onClick = launchGallery,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Image,
            contentDescription = stringResource(R.string.qr_pick_from_gallery)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(stringResource(R.string.qr_pick_from_gallery))
    }
}

@Composable
private fun ScannerFrame(
    modifier: Modifier = Modifier
) {
    val cornerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)

    Box(
        modifier = modifier
            .size(268.dp)
            .clip(RoundedCornerShape(28.dp))
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                shape = RoundedCornerShape(28.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.08f))
        )

        ScannerCorner(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            color = cornerColor,
            position = ScannerCornerPosition.TopStart
        )
        ScannerCorner(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            color = cornerColor,
            position = ScannerCornerPosition.TopEnd
        )
        ScannerCorner(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            color = cornerColor,
            position = ScannerCornerPosition.BottomStart
        )
        ScannerCorner(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            color = cornerColor,
            position = ScannerCornerPosition.BottomEnd
        )
    }
}

private enum class ScannerCornerPosition {
    TopStart,
    TopEnd,
    BottomStart,
    BottomEnd
}

@Composable
private fun ScannerCorner(
    modifier: Modifier = Modifier,
    color: Color,
    position: ScannerCornerPosition
) {
    val horizontalAlignment = when (position) {
        ScannerCornerPosition.TopStart, ScannerCornerPosition.BottomStart -> Alignment.CenterStart
        ScannerCornerPosition.TopEnd, ScannerCornerPosition.BottomEnd -> Alignment.CenterEnd
    }
    val verticalAlignment = when (position) {
        ScannerCornerPosition.TopStart, ScannerCornerPosition.TopEnd -> Alignment.TopCenter
        ScannerCornerPosition.BottomStart, ScannerCornerPosition.BottomEnd -> Alignment.BottomCenter
    }
    val cornerAlignment = when (position) {
        ScannerCornerPosition.TopStart -> Alignment.TopStart
        ScannerCornerPosition.TopEnd -> Alignment.TopEnd
        ScannerCornerPosition.BottomStart -> Alignment.BottomStart
        ScannerCornerPosition.BottomEnd -> Alignment.BottomEnd
    }

    Box(modifier = modifier.size(30.dp)) {
        Box(
            modifier = Modifier
                .align(verticalAlignment)
                .height(5.dp)
                .width(30.dp)
                .clip(CircleShape)
                .background(color)
        )
        Box(
            modifier = Modifier
                .align(horizontalAlignment)
                .width(5.dp)
                .height(30.dp)
                .clip(CircleShape)
                .background(color)
        )
        Box(
            modifier = Modifier
                .align(cornerAlignment)
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

private fun processImageWithZxing(
    context: Context,
    uri: Uri,
    formats: Collection<BarcodeFormat>,
    diagnostics: QrScannerDiagnostics?,
    resultValidator: (String) -> Boolean,
    onResult: (String) -> Unit,
    onInvalid: () -> Unit,
    onNotFound: () -> Unit
) {
    val startedAt = SystemClock.elapsedRealtime()
    val bitmap = runCatchingObserved {
        // 先探测尺寸，按目标边长(2048)采样，避免相册大图全尺寸解码造成 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { s -> BitmapFactory.decodeStream(s, null, bounds) }
        var sampleSize = 1
        var bw = bounds.outWidth
        var bh = bounds.outHeight
        while (bw > 2048 || bh > 2048) {
            sampleSize *= 2
            bw /= 2
            bh /= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
        context.contentResolver.openInputStream(uri)?.use { s2 ->
            BitmapFactory.decodeStream(s2, null, options)
        }
    }.getOrNull()

    val candidates = if (bitmap != null) {
        try {
            decodeBitmapZxing(bitmap, formats)
        } finally {
            bitmap.recycle()
        }
    } else {
        diagnostics?.logGalleryDecodeFailed(Exception("bitmap decode returned null"))
        onNotFound()
        return
    }

    when (val value = candidates.firstOrNull(resultValidator)) {
        null -> {
            if (candidates.isEmpty()) {
                diagnostics?.logGalleryResult(
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                    barcodeCount = 0,
                    candidateCount = 0,
                    matched = false,
                    invalid = false
                )
                onNotFound()
            } else {
                diagnostics?.logGalleryResult(
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                    barcodeCount = 0,
                    candidateCount = candidates.size,
                    matched = false,
                    invalid = true
                )
                onInvalid()
            }
        }
        else -> {
            diagnostics?.logGalleryResult(
                durationMs = SystemClock.elapsedRealtime() - startedAt,
                barcodeCount = 0,
                candidateCount = candidates.size,
                matched = true,
                invalid = false
            )
            onResult(value)
        }
    }
}

private const val QR_SCAN_DIAG_HEARTBEAT_MS = 30_000L
private const val QR_SCAN_HEALTH_TICK_MS = 500L
private const val QR_SCAN_SESSION_RESTART_DELAY_MS = 450L
private const val QR_SCAN_SESSION_RESTART_COOLDOWN_MS = 3_000L
