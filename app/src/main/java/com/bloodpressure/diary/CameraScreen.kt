package com.bloodpressure.diary

import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import java.time.OffsetDateTime
import kotlinx.coroutines.launch

@Composable
internal fun CameraScreen(
    onCancel: () -> Unit,
    onManual: () -> Unit,
    onResult: (OffsetDateTime, Readings) -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentOnResult by rememberUpdatedState(onResult)
    val active = remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().build() }

    DisposableEffect(owner, previewView) {
        active.value = true
        ready = false
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build()
        future.addListener({
            if (active.value) {
                try {
                    provider = future.get().also {
                        preview.surfaceProvider = previewView.surfaceProvider
                        it.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    }
                    ready = true
                } catch (_: Exception) {
                    error = "無法啟動相機，可改用手動輸入。"
                }
            }
        }, executor)
        onDispose {
            active.value = false
            provider?.unbind(preview, capture)
        }
    }

    BackHandler { onCancel() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("拍攝血壓計", style = MaterialTheme.typography.headlineMedium)
        Text(error ?: "請讓三組數字及旁邊的標籤完整入鏡，並避開反光。")
        AndroidView({ previewView }, Modifier.weight(1f).fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = ready && !busy,
                onClick = {
                    busy = true
                    error = null
                    val takenAt = OffsetDateTime.now()
                    previewView.display?.let { capture.targetRotation = it.rotation }
                    capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            if (!active.value) {
                                image.close()
                                return
                            }
                            val rotation = image.imageInfo.rotationDegrees
                            val bitmap = try {
                                image.toBitmap()
                            } catch (_: Exception) {
                                null
                            } finally {
                                // 複製像素後立即歸還相機緩衝區。
                                image.close()
                            }
                            scope.launch {
                                val readings = if (bitmap == null) Readings(null, null, null) else try {
                                    recognizePhoto(InputImage.fromBitmap(bitmap, rotation))
                                } finally {
                                    bitmap.recycle()
                                }
                                if (active.value) currentOnResult(takenAt, readings)
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (active.value) {
                                busy = false
                                error = "拍攝失敗，請重拍或手動輸入。"
                            }
                        }
                    })
                }
            ) { Text(if (busy) "辨識中…" else "拍照辨識") }
            OutlinedButton(onClick = onManual) { Text("手動輸入") }
        }
        TextButton(onClick = onCancel) { Text("取消") }
    }
}
