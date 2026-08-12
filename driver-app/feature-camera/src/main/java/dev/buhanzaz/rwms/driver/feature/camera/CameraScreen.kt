package dev.buhanzaz.rwms.driver.feature.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.MediaActionSound
import android.net.Uri
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val DriverCameraBlue = Color(0xFF3B82F6)
private val DriverCameraPanel = Color(0xE6191919)

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
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionGate = cameraPermissionGate(
        granted = granted,
        requestedAtLeastOnce = permissionRequested,
        shouldShowRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } ?: true,
    )

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.hasCameraPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(hasCameraHardware, granted, permissionRequested) {
        if (hasCameraHardware && !granted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(state.savedEvidenceId) {
        state.savedEvidenceId?.let { evidenceId ->
            viewModel.consumeSavedCapture(evidenceId)
            onSaved()
        }
    }

    when {
        !hasCameraHardware -> CameraGate("На этом устройстве нет доступной камеры", onBack = onBack)
        !granted && permissionGate == CameraPermissionGate.PERMANENTLY_DENIED -> CameraGate(
            message = "Доступ к камере отключён. Разрешите его в настройках приложения.",
            primaryLabel = "Открыть настройки",
            onPrimary = context::openApplicationSettings,
            onBack = onBack,
        )
        !granted -> CameraGate(
            message = "Для подтверждения результата нужен доступ к камере.",
            primaryLabel = "Разрешить камеру",
            onPrimary = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onBack = onBack,
        )
        else -> DriverCameraExperience(
            title = "Фото результата",
            saving = state.saving,
            saveError = state.error,
            onBack = onBack,
            onConfirm = { file -> viewModel.confirmCapture(userId, entryId, routeIndex, file) },
        )
    }
}

@Composable
private fun DriverCameraExperience(
    title: String,
    saving: Boolean,
    saveError: String?,
    onBack: () -> Unit,
    onConfirm: (File) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val photoFileExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val preferences = remember(context) { DriverCameraPreferences(context) }
    var cameraSettings by remember { mutableStateOf(preferences.load()) }
    var cameraMode by remember { mutableStateOf(DriverCameraMode.Photo) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var captureInProgress by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var lightMode by remember { mutableStateOf(DriverCameraLightMode.Off) }
    var message by remember { mutableStateOf<String?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var selectedZoom by remember { mutableFloatStateOf(1f) }
    var captureTargetRotation by remember {
        mutableIntStateOf(driverCaptureTargetRotation(previewView.display?.rotation))
    }
    var nightExtensionActive by remember { mutableStateOf(false) }
    val pendingForDisposal by rememberUpdatedState(pendingFile)
    val shutterSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    DisposableEffect(shutterSound, photoFileExecutor) {
        onDispose {
            shutterSound.release()
            photoFileExecutor.shutdown()
            pendingForDisposal?.delete()
        }
    }
    DisposableEffect(context, previewView) {
        val listener = object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                captureTargetRotation = driverCaptureTargetRotationForOrientation(
                    orientationDegrees = orientation,
                    fallbackRotation = previewView.display?.rotation ?: captureTargetRotation,
                )
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    LaunchedEffect(captureTargetRotation, imageCapture) {
        imageCapture?.targetRotation = captureTargetRotation
    }
    LaunchedEffect(cameraSettings) { preferences.save(cameraSettings) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_600)
            message = null
        }
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1_500)
            focusPoint = null
        }
    }

    DisposableEffect(
        lifecycleOwner,
        previewView,
        lensFacing,
        cameraMode,
        cameraSettings.aspectRatio,
        cameraSettings.motionCaptureEnabled,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            val provider = runCatching { providerFuture.get() }.getOrElse {
                message = "Камера недоступна"
                return@addListener
            }
            val baseSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            if (!runCatching { provider.hasCamera(baseSelector) }.getOrDefault(false)) {
                val fallback = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                if (runCatching { provider.hasCamera(fallback) }.getOrDefault(false)) {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                } else message = "На устройстве нет доступной камеры"
                return@addListener
            }

            fun bind(selector: CameraSelector, nightActive: Boolean) {
                if (disposed) return
                val previewRotation = driverCaptureTargetRotation(previewView.display?.rotation)
                val preview = Preview.Builder().setTargetRotation(previewRotation).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val ratio = when (cameraSettings.aspectRatio) {
                    DriverCameraAspectRatio.FourThree -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    DriverCameraAspectRatio.SixteenNine -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                }
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(
                        if (cameraSettings.motionCaptureEnabled && cameraMode == DriverCameraMode.Photo) {
                            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        } else {
                            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        },
                    )
                    .setTargetRotation(captureTargetRotation)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(ratio)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build(),
                    )
                // Ultra HDR's gain map cannot survive the mandatory pixel rewrite below. Driver
                // evidence is deliberately one ordinary JPEG with Orientation=1.
                captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
                val capture = captureBuilder.build()
                val bound = runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                }.recoverCatching {
                    provider.unbindAll()
                    nightExtensionActive = false
                    provider.bindToLifecycle(lifecycleOwner, baseSelector, preview, capture)
                }.getOrElse {
                    message = "Не удалось запустить камеру"
                    return
                }
                camera = bound
                imageCapture = capture
                nightExtensionActive = nightActive
                val zoomState = bound.cameraInfo.zoomState.value
                selectedZoom = driverCoerceCaptureZoom(
                    requested = selectedZoom,
                    hardwareMinimum = zoomState?.minZoomRatio,
                    hardwareMaximum = zoomState?.maxZoomRatio,
                )
                bound.cameraControl.setZoomRatio(selectedZoom)
            }

            if (cameraMode == DriverCameraMode.Night) {
                val extensionFuture = ExtensionsManager.getInstanceAsync(context, provider)
                extensionFuture.addListener({
                    if (disposed) return@addListener
                    val manager = runCatching { extensionFuture.get() }.getOrNull()
                    val available = manager?.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT) == true
                    val selector = if (available) {
                        runCatching {
                            manager.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)
                        }.getOrNull()
                    } else null
                    bind(selector ?: baseSelector, selector != null)
                }, mainExecutor)
            } else {
                bind(baseSelector, false)
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            runCatching {
                camera?.cameraControl?.enableTorch(false)
                if (providerFuture.isDone) providerFuture.get().unbindAll()
            }
            camera = null
            imageCapture = null
        }
    }

    LaunchedEffect(camera, cameraSettings.exposureEvTenths) {
        val bound = camera ?: return@LaunchedEffect
        val exposure = bound.cameraInfo.exposureState
        if (!exposure.isExposureCompensationSupported) return@LaunchedEffect
        val step = exposure.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: return@LaunchedEffect
        val index = (cameraSettings.exposureEvTenths / 10f / step).roundToInt()
            .coerceIn(exposure.exposureCompensationRange.lower, exposure.exposureCompensationRange.upper)
        bound.cameraControl.setExposureCompensationIndex(index)
    }
    LaunchedEffect(camera, imageCapture, lightMode, cameraMode, nightExtensionActive) {
        val bound = camera ?: return@LaunchedEffect
        val effective = lightMode.takeIf {
            bound.cameraInfo.hasFlashUnit() && cameraMode != DriverCameraMode.Night
        } ?: DriverCameraLightMode.Off
        imageCapture?.flashMode = effective.captureMode
        bound.cameraControl.enableTorch(effective == DriverCameraLightMode.Torch)
        if (bound.cameraInfo.isLowLightBoostSupported) {
            bound.cameraControl.enableLowLightBoostAsync(
                cameraMode == DriverCameraMode.Night && !nightExtensionActive,
            )
        }
    }

    val zoomState = rememberTransformableState { _, zoomChange, _, _ ->
        val current = camera?.cameraInfo?.zoomState?.value ?: return@rememberTransformableState
        selectedZoom = driverCoerceCaptureZoom(
            requested = selectedZoom * zoomChange,
            hardwareMinimum = current.minZoomRatio,
            hardwareMaximum = current.maxZoomRatio,
        )
        camera?.cameraControl?.setZoomRatio(selectedZoom)
    }

    fun takePhoto() {
        val capture = imageCapture ?: run {
            message = "Камера ещё запускается"
            return
        }
        if (captureInProgress || pendingFile != null) return
        captureInProgress = true
        settingsOpen = false
        if (cameraMode == DriverCameraMode.Night) {
            message = if (nightExtensionActive) {
                "Ночной снимок — держите телефон неподвижно"
            } else {
                "Ночной снимок с автоматическим усилением света"
            }
        }
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        val target = File(context.cacheDir, "rwms-capture-${System.nanoTime()}.jpg")
        capture.targetRotation = captureTargetRotation
        capture.takePicture(
            photoFileExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val normalized = persistDriverCameraImageProxy(image, target)
                    mainExecutor.execute {
                        captureInProgress = false
                        if (normalized == null) {
                            target.delete()
                            message = "Не удалось подготовить фотографию"
                            return@execute
                        }
                        if (normalized != target) target.delete()
                        when (val result = validateCameraXSave(normalized)) {
                            is CameraXSaveResult.Saved -> pendingFile = result.file
                            is CameraXSaveResult.Failed -> {
                                normalized.delete()
                                message = result.message
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    target.delete()
                    mainExecutor.execute {
                        captureInProgress = false
                        message = "Не удалось сохранить фотографию"
                    }
                }
            },
        )
    }

    val volumeShutterAction by rememberUpdatedState { takePhoto() }
    DisposableEffect(context) {
        val host = context.volumeShutterHost()
        host?.setVolumeShutterHandler { volumeShutterAction() }
        onDispose { host?.setVolumeShutterHandler(null) }
    }

    if (pendingFile != null) {
        CaptureConfirmation(
            file = requireNotNull(pendingFile),
            saving = saving,
            error = saveError,
            onRetake = {
                pendingFile?.delete()
                pendingFile = null
            },
            onConfirm = { onConfirm(requireNotNull(pendingFile)) },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            CameraTopBar(
                title = title,
                lightMode = lightMode,
                flashAvailable = camera?.cameraInfo?.hasFlashUnit() == true,
                settingsOpen = settingsOpen,
                onLight = {
                    if (cameraMode == DriverCameraMode.Night) {
                        message = "В ночном режиме вспышка и фонарь недоступны"
                    } else {
                        lightMode = lightMode.next()
                    }
                },
                onSettings = { settingsOpen = !settingsOpen },
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)
                    .transformable(zoomState)
                    .pointerInput(camera, previewView) {
                        detectTapGestures { point ->
                            val bound = camera ?: return@detectTapGestures
                            val metering = previewView.meteringPointFactory.createPoint(point.x, point.y, 0.16f)
                            val action = FocusMeteringAction.Builder(
                                metering,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or
                                    FocusMeteringAction.FLAG_AWB,
                            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                            focusPoint = point
                            bound.cameraControl.startFocusAndMetering(action)
                        }
                    },
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                if (cameraSettings.gridEnabled) CameraGrid()
                focusPoint?.let { FocusIndicator(it) }
                CameraZoomStops(
                    camera = camera,
                    selectedZoom = selectedZoom,
                    onZoom = { zoom ->
                        selectedZoom = zoom
                        camera?.cameraControl?.setZoomRatio(zoom)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                )
                message?.let {
                    Surface(
                        color = Color.Black.copy(alpha = 0.68f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                    ) { Text(it, color = Color.White, modifier = Modifier.padding(14.dp, 8.dp)) }
                }
            }
            CameraBottomControls(
                mode = cameraMode,
                captureInProgress = captureInProgress,
                canSwitch = remember(camera) {
                    camera != null
                },
                onMode = { requested ->
                    val selection = selectDriverCameraMode(requested)
                    cameraMode = selection.mode
                    message = selection.message
                    lightMode = DriverCameraLightMode.Off
                },
                onShutter = ::takePhoto,
                onSwitch = {
                    lightMode = DriverCameraLightMode.Off
                    selectedZoom = 1f
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else CameraSelector.LENS_FACING_BACK
                },
                onClose = onBack,
            )
        }
        if (settingsOpen) {
            CameraSettingsPanel(
                settings = cameraSettings,
                onSettings = { cameraSettings = normalizeDriverCameraSettings(it) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp, 12.dp, 12.dp, 188.dp),
            )
        }
    }
}

@Composable
private fun CameraTopBar(
    title: String,
    lightMode: DriverCameraLightMode,
    flashAvailable: Boolean,
    settingsOpen: Boolean,
    onLight: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(76.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onLight, enabled = flashAvailable) {
            Text(lightMode.symbol, color = if (flashAvailable) Color.White else Color.Gray, fontSize = 20.sp)
        }
        Text(
            title,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSettings) {
            Text(if (settingsOpen) "⌃" else "⌄", color = Color.White, fontSize = 24.sp)
        }
    }
}

@Composable
private fun CameraBottomControls(
    mode: DriverCameraMode,
    captureInProgress: Boolean,
    canSwitch: Boolean,
    onMode: (DriverCameraMode) -> Unit,
    onShutter: () -> Unit,
    onSwitch: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().height(174.dp).padding(top = 4.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.Center) {
            DriverCameraMode.entries.forEach { candidate ->
                Text(
                    candidate.label,
                    color = if (candidate == mode) DriverCameraBlue else Color.White.copy(alpha = 0.72f),
                    fontSize = 15.sp,
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Tab) { onMode(candidate) }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f))
            Box(
                modifier = Modifier.size(86.dp).clip(CircleShape)
                    .clickable(enabled = !captureInProgress, role = Role.Button, onClick = onShutter)
                    .semantics { contentDescription = "Снять фотографию" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White, style = Stroke(3.dp.toPx()), radius = size.minDimension / 2 - 2.dp.toPx())
                    drawCircle(Color.White, radius = size.minDimension * 0.39f)
                    if (mode == DriverCameraMode.Night) {
                        repeat(20) { index ->
                            val angle = index * Math.PI.toFloat() / 10f
                            val start = Offset(
                                center.x + kotlin.math.cos(angle) * size.minDimension * 0.43f,
                                center.y + kotlin.math.sin(angle) * size.minDimension * 0.43f,
                            )
                            val end = Offset(
                                center.x + kotlin.math.cos(angle) * size.minDimension * 0.48f,
                                center.y + kotlin.math.sin(angle) * size.minDimension * 0.48f,
                            )
                            drawLine(DriverCameraBlue, start, end, 1.5.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
                if (captureInProgress) CircularProgressIndicator(color = DriverCameraBlue, modifier = Modifier.size(78.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                if (canSwitch) {
                    TextButton(
                        onClick = onSwitch,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("↻", color = Color.White, fontSize = 26.sp) }
                }
                TextButton(
                    onClick = onClose,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text("Готово", color = DriverCameraBlue, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun CameraSettingsPanel(
    settings: DriverCameraSettings,
    onSettings: (DriverCameraSettings) -> Unit,
    modifier: Modifier,
) {
    Surface(color = DriverCameraPanel, shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CameraSetting("Движение", if (settings.motionCaptureEnabled) "Вкл" else "Выкл") {
                    onSettings(settings.copy(motionCaptureEnabled = !settings.motionCaptureEnabled))
                }
                CameraSetting("Сетка", if (settings.gridEnabled) "Вкл" else "Выкл") {
                    onSettings(settings.copy(gridEnabled = !settings.gridEnabled))
                }
                CameraSetting("Кадр", settings.aspectRatio.label) {
                    onSettings(settings.copy(aspectRatio = settings.aspectRatio.next()))
                }
            }
            Text("Экспозиция ${String.format(java.util.Locale.US, "%+.1f", settings.exposureEvTenths / 10f)}", color = Color.White)
            Slider(
                value = settings.exposureEvTenths.toFloat(),
                onValueChange = { onSettings(settings.copy(exposureEvTenths = it.roundToInt())) },
                valueRange = -20f..20f,
            )
        }
    }
}

@Composable
private fun CameraSetting(title: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Text(value, color = DriverCameraBlue, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CameraZoomStops(camera: Camera?, selectedZoom: Float, onZoom: (Float) -> Unit, modifier: Modifier) {
    val zoom = camera?.cameraInfo?.zoomState?.value
    val minimum = driverCaptureMinimumZoom(zoom?.minZoomRatio, zoom?.maxZoomRatio)
    val maximum = driverCaptureMaximumZoom(zoom?.minZoomRatio, zoom?.maxZoomRatio)
    val stops = driverSupportedZoomStops(minimum, maximum)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stops.forEach { stop ->
            Surface(
                color = if (kotlin.math.abs(stop - selectedZoom) < 0.05f) DriverCameraBlue else Color.Black.copy(alpha = 0.55f),
                shape = CircleShape,
                modifier = Modifier.clickable { onZoom(stop) },
            ) { Text(driverZoomLabel(stop), color = Color.White, modifier = Modifier.padding(10.dp, 7.dp)) }
        }
    }
}

@Composable
private fun CameraGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.45f)
        drawLine(color, Offset(size.width / 3, 0f), Offset(size.width / 3, size.height), 1.dp.toPx())
        drawLine(color, Offset(size.width * 2 / 3, 0f), Offset(size.width * 2 / 3, size.height), 1.dp.toPx())
        drawLine(color, Offset(0f, size.height / 3), Offset(size.width, size.height / 3), 1.dp.toPx())
        drawLine(color, Offset(0f, size.height * 2 / 3), Offset(size.width, size.height * 2 / 3), 1.dp.toPx())
    }
}

@Composable
private fun FocusIndicator(point: Offset) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(DriverCameraBlue, radius = 26.dp.toPx(), center = point, style = Stroke(2.dp.toPx()))
        drawCircle(DriverCameraBlue, radius = 3.dp.toPx(), center = point)
    }
}

@Composable
private fun CaptureConfirmation(
    file: File,
    saving: Boolean,
    error: String?,
    onRetake: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bitmap = remember(file) {
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Проверьте фото", color = Color.White, style = MaterialTheme.typography.titleLarge)
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Предпросмотр фотографии",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRetake,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) { Text("Переснять", maxLines = 1) }
        }
        Button(
            onClick = onConfirm,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saving) "Сохраняем…" else "Использовать", maxLines = 1) }
    }
}

@Composable
private fun CameraGate(
    message: String,
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        primaryLabel?.let { Button(onClick = onPrimary) { Text(it) } }
        TextButton(onClick = onBack) { Text("Назад", color = Color.White) }
    }
}

/** Cycles the supported still-capture flash and continuous torch modes. */
private enum class DriverCameraLightMode(val symbol: String) {
    Off("⚡̸"),
    Flash("⚡"),
    Torch("▰");

    val captureMode: Int
        get() = if (this == Flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF

    fun next(): DriverCameraLightMode = when (this) {
        Off -> Flash
        Flash -> Torch
        Torch -> Off
    }
}

/**
 * Writes the in-memory CameraX capture as an ordinary JPEG with pixels already in their display
 * orientation. `ImageProxy.imageInfo.rotationDegrees` is authoritative here: some camera HALs
 * return raw sideways pixels while incorrectly marking their file-output JPEG as Orientation=1.
 */
internal fun persistDriverCameraImageProxy(image: ImageProxy, target: File): File? = try {
    val rotationDegrees = image.imageInfo.rotationDegrees
    val jpegBytes = driverCameraJpegBytes(image) ?: return null
    persistDriverCameraJpeg(
        jpegBytes = jpegBytes,
        rotationDegrees = rotationDegrees,
        target = target,
    )
} catch (_: Exception) {
    null
} finally {
    image.close()
}

private fun driverCameraJpegBytes(image: ImageProxy): ByteArray? {
    if (image.format != ImageFormat.JPEG) return null
    val plane = image.planes.singleOrNull() ?: return null
    val buffer = plane.buffer.duplicate()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes.takeIf { it.isDriverCameraJpeg() }
}

private fun ByteArray.isDriverCameraJpeg(): Boolean =
    size >= 4 &&
        this[0] == 0xFF.toByte() &&
        this[1] == 0xD8.toByte() &&
        this[size - 2] == 0xFF.toByte() &&
        this[size - 1] == 0xD9.toByte()

private fun persistDriverCameraJpeg(
    jpegBytes: ByteArray,
    rotationDegrees: Int,
    target: File,
): File? = try {
    target.outputStream().buffered().use { output -> output.write(jpegBytes) }

    // Ignore any orientation embedded by the vendor. We deliberately seed the shared normalizer
    // with the physical transform reported by CameraX, then it writes final pixels with EXIF=1.
    val physicalOrientation = driverExifOrientationForRotationDegrees(rotationDegrees)
    ExifInterface(target).run {
        setAttribute(ExifInterface.TAG_ORIENTATION, physicalOrientation.toString())
        saveAttributes()
    }
    check(
        ExifInterface(target).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        ) == physicalOrientation,
    ) { "Unable to persist CameraX image rotation" }
    normalizeDriverCameraJpegOrientation(
        source = target,
        fallbackOrientation = physicalOrientation,
    )
} catch (_: Exception) {
    null
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private tailrec fun Context.volumeShutterHost(): VolumeShutterHost? = when (this) {
    is VolumeShutterHost -> this
    is ContextWrapper -> baseContext.volumeShutterHost()
    else -> null
}

private fun Context.openApplicationSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        },
    )
}
