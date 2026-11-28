package com.qzxy.lite.ui.water

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 天猫校园饮水机二维码扫描页（CameraX + ZXing 纯 Java 解码，不依赖 GMS）。
 */
@Composable
fun TmallQrScannerScreen(
    onDetected: (String) -> Unit,
    onManualInput: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    val found = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(granted, lifecycleOwner) {
        if (!granted) return@DisposableEffect onDispose { }
        val disposed = AtomicBoolean(false)
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed.get()) return@addListener
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                if (found.get()) {
                    proxy.close()
                    return@setAnalyzer
                }
                val text = decodeQr(proxy)
                proxy.close()
                if (text != null && found.compareAndSet(false, true)) {
                    main.execute { if (!disposed.get()) onDetected(text) }
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, main)

        onDispose {
            disposed.set(true)
            runCatching {
                if (future.isDone) future.get().unbindAll()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            found.set(true)
            executor.shutdownNow()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            // 取景提示框
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(236.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)),
            )
            Text(
                "将饮水机上的二维码放进框内",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("需要相机权限以扫描饮水机二维码", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限")
                }
                OutlinedButton(onClick = onManualInput) {
                    Text("手动输入设备号")
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(onClick = onManualInput) {
                Text("手动输入", color = Color.White)
            }
            Button(onClick = onClose) {
                Text("取消")
            }
        }
    }
}

private fun decodeQr(proxy: ImageProxy): String? = runCatching {
    val y = proxy.planes[0]
    val buffer = y.buffer
    val w = proxy.width
    val h = proxy.height
    val rowStride = y.rowStride
    val pixelStride = y.pixelStride

    val data = ByteArray(w * h)
    if (pixelStride == 1 && rowStride == w && buffer.remaining() >= w * h) {
        buffer.get(data, 0, w * h)
    } else {
        val row = ByteArray(rowStride)
        var out = 0
        for (r in 0 until h) {
            val rowPos = r * rowStride
            if (rowPos >= buffer.limit()) break
            buffer.position(rowPos)
            val len = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, len)
            var c = 0
            for (x in 0 until w) {
                if (c >= len || out >= data.size) break
                data[out++] = row[c]
                c += pixelStride
            }
        }
    }

    val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
    reader.decodeWithState(bitmap).text
}.getOrNull()
