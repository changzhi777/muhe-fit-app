package com.muhe.fit.app.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraXPreview   // 与 @Preview 注解同名，起别名
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.muhe.fit.app.R
import com.muhe.fit.app.ui.theme.muheExtended
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// ── 扫码共享组件（V1.2 自 CheckInScreen 抽出；打卡「扫码添加」与登录「扫码配对」共用） ──
// 纪律：解码读多种制式，存不存/怎么用由调用面把关（打卡按 canRenderFmt 拒非 QR，
// 配对面 clean 成 8 位码——checkin 域纪律「读得了多种、只存能重绘的」照搬）。

/**
 * 全屏扫码覆盖层：相机预览 + 取景提示 + 权限态（请求中 / 被拒 → 去授权跳系统设置）。
 * [title]/[hint] 由调用面传入（打卡=扫码添加文案，登录=扫码配对文案）；被拒时回调
 * [onDenied]（登录屏借此点亮认证区 inline 无权限行，login-design.md §五）。
 * 识别成功经 [onFound] 回传一次后自行停止分析；[onFound] 参数 = (码值, zxing 制式名)。
 */
@Composable
internal fun ScanOverlay(
    title: String,
    hint: String,
    onFound: (String, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onDenied: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        if (!ok) onDenied?.invoke()
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    // 去系统设置授权后返回的恢复路径（2026-11-18 修复）：该路径不经 ActivityResult，
    // granted/denied 停在旧值、覆盖层永远停在「被拒」态——ON_RESUME 重查权限
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                granted = now
                if (now) denied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f))
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.scan_close))
                }
            }
            if (granted) {
                CameraPreview(onFound = onFound, modifier = Modifier.weight(1f))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.muheExtended.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // 权限请求中 / 被拒：占位居中呈现被拒态引导
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (denied) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.scan_camera_denied),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            TextButton(onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null))
                                context.startActivity(intent)
                            }) {
                                Text(stringResource(R.string.scan_camera_grant))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * CameraX 预览 + ImageAnalysis 取流：PreviewView 经 AndroidView 承载，背压 KEEP_ONLY_LATEST，
 * 解码跑单线程 executor；一次性成功即置位 delivered 停止后续回调；onDispose 解绑相机 +
 * 关 executor（ProcessCameraProvider 与生命周期绑定，随覆盖层退场释放）。
 */
@Composable
internal fun CameraPreview(onFound: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val providerRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    // 覆盖层是否已退场（2026-11-18 修复初始化竞态）：冷启动/低端机上 Future 回调晚于
    // onDispose 时，无此守卫会 bind 到已消失的覆盖层（相机真开着）且 executor 已 shutdown
    // （帧派发 RejectedExecutionException）
    val disposed = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.value = true
            providerRef.value?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        factory = { ctx ->
            PreviewView(ctx).also { previewView ->
                ProcessCameraProvider.getInstance(ctx).addListener({
                    // 退场后才到的初始化回调：只解绑不清场（unbindAll 幂等），不 bind、不再用 executor
                    if (disposed.value) {
                        try { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } catch (_: Exception) {}
                        return@addListener
                    }
                    val provider = ProcessCameraProvider.getInstance(ctx).get()
                    val preview = CameraXPreview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(executor, BarcodeAnalyzer(delivered) { value, fmt ->
                                mainHandler.post { onFound(value, fmt) }
                            })
                        }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    providerRef.value = provider
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
    )
}

/**
 * CameraX YUV 帧 → zxing MultiFormatReader 解码（scan.js 的 Android 半；多种制式都读，
 * 存不存由 canRenderFmt 把关）。帧数据先按 rotationDegrees 转正再解码；
 * 无码帧（NotFoundException 等）静默吞掉等下一帧。
 */
internal class BarcodeAnalyzer(
    private val delivered: AtomicBoolean,
    private val onFound: (String, String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to BarcodeFormat.values().toList()))
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (delivered.get()) return
            val (data, w, h) = uprightLuminance(image)
            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            if (result.text.isNotEmpty() && delivered.compareAndSet(false, true)) {
                onFound(result.text, result.barcodeFormat.toString())
            }
        } catch (_: Exception) {
            // 本帧无码：静默取下一帧（scan.js「found 才回」语义）
        } finally {
            image.close()
        }
    }

    /** Y 平面抽亮度 + 按 rotationDegrees 转正：返回 (数据, 转正宽, 转正高)。 */
    private fun uprightLuminance(image: ImageProxy): Triple<ByteArray, Int, Int> {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val src = ByteArray(w * h)
        if (pixelStride == 1 && rowStride == w) {
            buffer.get(src)
        } else {
            var pos = 0
            for (row in 0 until h) {
                if (pixelStride == 1) {
                    buffer.position(row * rowStride)
                    buffer.get(src, pos, w)
                    pos += w
                } else {
                    for (col in 0 until w) {
                        src[pos++] = buffer.get(row * rowStride + col * pixelStride)
                    }
                }
            }
        }
        return when (image.imageInfo.rotationDegrees) {
            90 -> rotate90(src, w, h)
            180 -> rotate180(src, w, h)
            270 -> rotate270(src, w, h)
            else -> Triple(src, w, h)
        }
    }

    /** 顺时针 90°：新宽 = 原高；out[c0 * newW + (newW-1-r0)] = src[r0 * w + c0]。 */
    private fun rotate90(src: ByteArray, w: Int, h: Int): Triple<ByteArray, Int, Int> {
        val out = ByteArray(src.size)
        for (r0 in 0 until h) {
            for (c0 in 0 until w) {
                out[c0 * h + (h - 1 - r0)] = src[r0 * w + c0]
            }
        }
        return Triple(out, h, w)
    }

    /** 180°：中心对称。 */
    private fun rotate180(src: ByteArray, w: Int, h: Int): Triple<ByteArray, Int, Int> {
        val out = ByteArray(src.size)
        for (r0 in 0 until h) {
            for (c0 in 0 until w) {
                out[(h - 1 - r0) * w + (w - 1 - c0)] = src[r0 * w + c0]
            }
        }
        return Triple(out, w, h)
    }

    /** 顺时针 270°（= 逆时针 90°）：新宽 = 原高。 */
    private fun rotate270(src: ByteArray, w: Int, h: Int): Triple<ByteArray, Int, Int> {
        val out = ByteArray(src.size)
        for (r0 in 0 until w) {
            for (c0 in 0 until h) {
                out[r0 * h + c0] = src[c0 * w + (w - 1 - r0)]
            }
        }
        return Triple(out, h, w)
    }
}
