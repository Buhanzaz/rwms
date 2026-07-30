package dev.buhanzaz.rwms.worker.feature.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import java.io.File

@Composable
fun CameraScreen(
    userId: String,
    entryId: String,
    routeIndex: Int,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val hasCameraHardware = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionRequested = true
        granted = allowed
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var rotation by remember { mutableIntStateOf(0) }
    var bindingAttempt by rememberSaveable { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fileForDisposal by rememberUpdatedState(pendingFile)
    val permissionGate = cameraPermissionGate(
        granted = granted,
        requestedAtLeastOnce = permissionRequested,
        shouldShowRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } ?: true,
    )

    DisposableEffect(Unit) {
        onDispose { fileForDisposal?.delete() }
    }

    // Returning from application settings does not trigger the permission
    // launcher callback, so refresh the grant every time this route resumes.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.hasCameraPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A tap on "Добавить фото" should immediately lead to the system camera
    // permission request on first use; no separate hidden second step.
    LaunchedEffect(hasCameraHardware, granted, permissionRequested) {
        if (hasCameraHardware && !granted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(state.savedEvidenceId) {
        state.savedEvidenceId?.let { evidenceId ->
            // Consume first. Together with the Navigation 3 entry ViewModel
            // scope this guarantees a fresh camera screen when the same task
            // is opened again after a previous saved image.
            viewModel.consumeSavedCapture(evidenceId)
            onSaved()
        }
    }

    DisposableEffect(hasCameraHardware, granted, lifecycleOwner, previewView, pendingFile, bindingAttempt) {
        if (!hasCameraHardware || !granted || previewView == null || pendingFile != null) {
            imageCapture = null
            capturing = false
            return@DisposableEffect onDispose {}
        }
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            runCatching {
                val provider = providerFuture.get().also { cameraProvider = it }
                val view = requireNotNull(previewView)
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> error("На устройстве не найдена доступная камера")
                }
                val targetRotation = view.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder()
                    .setTargetRotation(targetRotation)
                    .build()
                    .also { it.setSurfaceProvider(view.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(targetRotation)
                    .setJpegQuality(85)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                if (!disposed) {
                    imageCapture = capture
                    captureError = null
                }
            }.onFailure {
                imageCapture = null
                captureError = it.message ?: "Камера недоступна"
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            cameraProvider?.unbindAll()
        }
    }

    WorkerScreenScaffold(title = "Фото результата", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !hasCameraHardware -> {
                    Text("На этом устройстве нет доступной камеры.")
                }

                !granted -> when (permissionGate) {
                    CameraPermissionGate.PERMANENTLY_DENIED -> {
                        Text("Доступ к камере отключён. Разрешите его в настройках приложения.")
                        Button(onClick = { context.openApplicationSettings() }) { Text("Открыть настройки") }
                    }

                    CameraPermissionGate.REQUESTABLE -> {
                        Text("Для подтверждения результата нужен доступ к камере.")
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(if (permissionRequested) "Разрешить камеру" else "Запрашиваем камеру…")
                        }
                    }

                    CameraPermissionGate.GRANTED -> Unit
                }

                pendingFile == null -> {
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = {
                            PreviewView(it).also { view ->
                                // TextureView avoids SurfaceView visibility failures seen
                                // on several inexpensive API 23/28 devices in Compose.
                                view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                previewView = view
                            }
                        },
                    )
                    Button(
                        onClick = {
                            val capture = imageCapture
                            if (capture == null) {
                                captureError = "Камера ещё запускается. Попробуйте через секунду."
                                return@Button
                            }
                            val target = File(context.cacheDir, "rwms-capture-${System.nanoTime()}.jpg")
                            capturing = true
                            captureError = null
                            capture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(target).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        capturing = false
                                        when (val result = validateCameraXSave(target)) {
                                            is CameraXSaveResult.Saved -> {
                                                pendingFile = result.file
                                                rotation = 0
                                            }

                                            is CameraXSaveResult.Failed -> {
                                                target.delete()
                                                captureError = result.message
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        capturing = false
                                        target.delete()
                                        captureError = exception.message ?: "Не удалось снять фотографию"
                                    }
                                },
                            )
                        },
                        enabled = imageCapture != null && !capturing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null)
                        Text(if (capturing) "Снимаем…" else "Снять")
                    }
                    if (imageCapture == null && captureError != null) {
                        OutlinedButton(
                            onClick = {
                                captureError = null
                                bindingAttempt++
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Повторить запуск камеры") }
                    }
                }

                else -> CaptureConfirmation(
                    file = requireNotNull(pendingFile),
                    rotation = rotation,
                    saving = state.saving,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRotate = { rotation = (rotation + 90) % 360 },
                    onRetake = {
                        pendingFile?.delete()
                        pendingFile = null
                        captureError = null
                    },
                    onConfirm = {
                        viewModel.confirmCapture(userId, entryId, routeIndex, requireNotNull(pendingFile))
                    },
                )
            }
            (captureError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openApplicationSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        },
    )
}

@Composable
private fun CaptureConfirmation(
    file: File,
    rotation: Int,
    saving: Boolean,
    modifier: Modifier = Modifier,
    onRotate: () -> Unit,
    onRetake: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bitmap = remember(file) {
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Предпросмотр фотографии",
                modifier = Modifier.weight(1f).fillMaxWidth().rotate(rotation.toFloat()),
            )
        }
        Text("Поворот меняет только локальный просмотр; исходный JPEG не изменяется.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRotate, enabled = !saving) { Text("Повернуть 90°") }
            OutlinedButton(onClick = onRetake, enabled = !saving) { Text("Переснять") }
            Button(onClick = onConfirm, enabled = !saving) { Text(if (saving) "Сохраняем…" else "Подтвердить") }
        }
    }
}
