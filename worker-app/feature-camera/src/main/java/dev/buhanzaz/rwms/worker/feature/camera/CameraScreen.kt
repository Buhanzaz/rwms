package dev.buhanzaz.rwms.worker.feature.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.media.MediaActionSound
import android.net.Uri
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
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
import dev.buhanzaz.rwms.worker.core.media.EncryptedEvidenceFileStore
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import dev.buhanzaz.rwms.worker.core.ui.WorkerButton as Button
import dev.buhanzaz.rwms.worker.core.ui.decodeWorkerBitmapFile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val WorkerCameraBlue = Color(0xFF3B82F6)
private val WorkerCameraPanel = Color(0xE6191919)
private const val CAMERA_THUMBNAIL_MAX_PIXELS = 256_000L
private const val CAMERA_GALLERY_MAX_PIXELS = 2_000_000L

/** Captures an ordered CameraX batch and persists it once the worker continues. */
@Composable
fun CameraScreen(
    userId: String,
    entryId: String,
    routeIndex: Int,
    onBack: () -> Unit,
    onSaved: (evidenceIds: List<String>) -> Unit,
    requestSyncAfterSave: Boolean = true,
    completeAfterSave: Boolean = false,
    problemReportId: String? = null,
    maxPhotos: Int = Int.MAX_VALUE,
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
    LaunchedEffect(state.savedEvidenceIds) {
        state.savedEvidenceIds.takeIf { it.isNotEmpty() }?.let { evidenceIds ->
            viewModel.consumeSavedCaptures(evidenceIds)
            onSaved(evidenceIds)
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
        else -> WorkerCameraExperience(
            title = if (problemReportId == null) "Фото результата" else "Фото проблемы",
            maxPhotos = maxPhotos,
            saving = state.saving,
            saveError = state.error,
            persistedCapturePaths = state.persistedCapturePaths,
            persistedCaptureCount = state.persistedCameraCaptureCount,
            onBack = onBack,
            onPersistedCapturesConsumed = viewModel::consumePersistedCaptures,
            onConfirm = { files ->
                viewModel.confirmCaptures(
                    userId,
                    entryId,
                    routeIndex,
                    files,
                    requestSyncAfterSave,
                    completeAfterSave,
                    problemReportId,
                )
            },
        )
    }
}

/**
 * Opens Android's photo picker and imports up to ten selected images as encrypted task evidence.
 * Completion callers defer sync until the matching task action has entered the outbox.
 */
@Composable
fun GalleryImportScreen(
    userId: String,
    entryId: String,
    routeIndex: Int,
    onBack: () -> Unit,
    onSaved: (evidenceIds: List<String>) -> Unit,
    requestSyncAfterSave: Boolean = true,
    completeAfterSave: Boolean = false,
    problemReportId: String? = null,
    maxPhotos: Int = 10,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pickerOpened by rememberSaveable { mutableStateOf(false) }
    val selectionLimit = maxPhotos.coerceIn(1, 10)
    val importPhotos: (List<Uri>) -> Unit = { uris ->
        if (uris.isEmpty()) {
            onBack()
        } else {
            viewModel.confirmGallery(
                userId,
                entryId,
                routeIndex,
                uris,
                requestSyncAfterSave,
                completeAfterSave,
                problemReportId,
            )
        }
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(selectionLimit.coerceAtLeast(2)),
        onResult = importPhotos,
    )
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        importPhotos(listOfNotNull(uri))
    }
    fun openPicker() {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (selectionLimit == 1) singlePicker.launch(request) else picker.launch(request)
    }

    LaunchedEffect(Unit) {
        if (!pickerOpened) {
            pickerOpened = true
            openPicker()
        }
    }
    LaunchedEffect(state.savedEvidenceIds) {
        state.savedEvidenceIds.takeIf { it.isNotEmpty() }?.let { evidenceIds ->
            viewModel.consumeSavedCaptures(evidenceIds)
            onSaved(evidenceIds)
        }
    }

    WorkerScreenScaffold(title = "Фото из галереи", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = ::openPicker,
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Выбрать другие фото") }
            }
        }
    }
}

@Composable
private fun WorkerCameraExperience(
    title: String,
    maxPhotos: Int,
    saving: Boolean,
    saveError: String?,
    persistedCapturePaths: Set<String>,
    persistedCaptureCount: Int,
    onBack: () -> Unit,
    onPersistedCapturesConsumed: (Set<String>) -> Unit,
    onConfirm: (List<File>) -> Unit,
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
    val preferences = remember(context) { WorkerCameraPreferences(context) }
    var cameraSettings by remember { mutableStateOf(preferences.load()) }
    var cameraMode by remember { mutableStateOf(WorkerCameraMode.Photo) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var captureInProgress by remember { mutableStateOf(false) }
    var capturedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var galleryStartIndex by remember { mutableStateOf<Int?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var lightMode by remember { mutableStateOf(WorkerCameraLightMode.Off) }
    var message by remember { mutableStateOf<String?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var selectedZoom by remember { mutableFloatStateOf(1f) }
    var captureTargetRotation by remember {
        mutableIntStateOf(workerCaptureTargetRotation(previewView.display?.rotation))
    }
    var nightExtensionActive by remember { mutableStateOf(false) }
    val capturesForDisposal by rememberUpdatedState(capturedFiles)
    val shutterSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    val cameraExperienceDisposed = remember { AtomicBoolean(false) }

    DisposableEffect(shutterSound, photoFileExecutor, cameraExperienceDisposed) {
        cameraExperienceDisposed.set(false)
        onDispose {
            cameraExperienceDisposed.set(true)
            shutterSound.release()
            photoFileExecutor.shutdown()
            capturesForDisposal.forEach(File::delete)
        }
    }
    BackHandler(enabled = saving) {
        message = "Дождитесь сохранения фотографий"
    }
    DisposableEffect(context, previewView) {
        val listener = object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                captureTargetRotation = workerCaptureTargetRotationForOrientation(
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
    LaunchedEffect(persistedCapturePaths) {
        if (persistedCapturePaths.isNotEmpty()) {
            capturedFiles = remainingWorkerCameraCaptures(capturedFiles, persistedCapturePaths)
            galleryStartIndex = galleryStartIndex?.takeIf { capturedFiles.isNotEmpty() }
                ?.coerceAtMost(capturedFiles.lastIndex)
            onPersistedCapturesConsumed(persistedCapturePaths)
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
                val previewRotation = workerCaptureTargetRotation(previewView.display?.rotation)
                val preview = Preview.Builder().setTargetRotation(previewRotation).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val ratio = when (cameraSettings.aspectRatio) {
                    WorkerCameraAspectRatio.FourThree -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    WorkerCameraAspectRatio.SixteenNine -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                }
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(
                        if (cameraSettings.motionCaptureEnabled && cameraMode == WorkerCameraMode.Photo) {
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
                // Ultra HDR's gain map cannot survive the mandatory pixel rewrite below. CameraX
                // therefore emits a transient ordinary JPEG that is physically oriented before WebP.
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
                selectedZoom = workerCoerceCaptureZoom(
                    requested = selectedZoom,
                    hardwareMinimum = zoomState?.minZoomRatio,
                    hardwareMaximum = zoomState?.maxZoomRatio,
                )
                bound.cameraControl.setZoomRatio(selectedZoom)
            }

            if (cameraMode == WorkerCameraMode.Night) {
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
            bound.cameraInfo.hasFlashUnit() && cameraMode != WorkerCameraMode.Night
        } ?: WorkerCameraLightMode.Off
        imageCapture?.flashMode = effective.captureMode
        bound.cameraControl.enableTorch(effective == WorkerCameraLightMode.Torch)
        if (bound.cameraInfo.isLowLightBoostSupported) {
            bound.cameraControl.enableLowLightBoostAsync(
                cameraMode == WorkerCameraMode.Night && !nightExtensionActive,
            )
        }
    }

    val zoomState = rememberTransformableState { _, zoomChange, _, _ ->
        val current = camera?.cameraInfo?.zoomState?.value ?: return@rememberTransformableState
        selectedZoom = workerCoerceCaptureZoom(
            requested = selectedZoom * zoomChange,
            hardwareMinimum = current.minZoomRatio,
            hardwareMaximum = current.maxZoomRatio,
        )
        camera?.cameraControl?.setZoomRatio(selectedZoom)
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (captureInProgress || saving || galleryStartIndex != null) return
        if (capturedFiles.size + persistedCaptureCount >= maxPhotos) {
            message = "Можно добавить фотографий: $maxPhotos"
            return
        }
        captureInProgress = true
        settingsOpen = false
        if (cameraMode == WorkerCameraMode.Night) {
            message = if (nightExtensionActive) {
                "Ночной снимок — держите телефон неподвижно"
            } else {
                "Ночной снимок с автоматическим усилением света"
            }
        }
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        val target = File(context.cacheDir, "rwms-capture-${System.nanoTime()}.jpg")
        capture.targetRotation = captureTargetRotation
        try {
            capture.takePicture(
                photoFileExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        if (cameraExperienceDisposed.get()) {
                            image.close()
                            target.delete()
                            return
                        }
                        val normalized = persistWorkerCameraImageProxy(image, target)
                        mainExecutor.execute {
                            if (cameraExperienceDisposed.get()) {
                                normalized?.delete()
                                target.delete()
                                return@execute
                            }
                            captureInProgress = false
                            if (normalized == null) {
                                target.delete()
                                message = "Не удалось подготовить фотографию"
                                return@execute
                            }
                            if (normalized != target) target.delete()
                            when (val result = validateCameraXSave(normalized)) {
                                is CameraXSaveResult.Saved -> {
                                    capturedFiles = capturedFiles + result.file
                                    message = "Снято фотографий: ${capturedFiles.size}"
                                }
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
                            if (cameraExperienceDisposed.get()) return@execute
                            captureInProgress = false
                            message = "Не удалось сохранить фотографию"
                        }
                    }
                },
            )
        } catch (_: RuntimeException) {
            target.delete()
            captureInProgress = false
            message = "Не удалось запустить съёмку"
        }
    }

    val volumeShutterAction by rememberUpdatedState { takePhoto() }
    DisposableEffect(context) {
        val host = context.volumeShutterHost()
        host?.setVolumeShutterHandler { volumeShutterAction() }
        onDispose { host?.setVolumeShutterHandler(null) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            CameraTopBar(
                title = title,
                lightMode = lightMode,
                flashAvailable = camera?.cameraInfo?.hasFlashUnit() == true,
                settingsOpen = settingsOpen,
                onLight = {
                    if (cameraMode == WorkerCameraMode.Night) {
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
                saveError?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 62.dp, start = 16.dp, end = 16.dp),
                    ) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp, 9.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            CameraBottomControls(
                mode = cameraMode,
                captureInProgress = captureInProgress,
                saving = saving,
                lastCapture = capturedFiles.lastOrNull(),
                captureCount = capturedFiles.size,
                persistedCaptureCount = persistedCaptureCount,
                canSwitch = remember(camera) {
                    camera != null
                },
                onMode = { requested ->
                    val selection = selectWorkerCameraMode(requested)
                    cameraMode = selection.mode
                    message = selection.message
                    lightMode = WorkerCameraLightMode.Off
                },
                onThumbnail = {
                    if (capturedFiles.isNotEmpty()) galleryStartIndex = capturedFiles.lastIndex
                },
                onShutter = ::takePhoto,
                onSwitch = {
                    lightMode = WorkerCameraLightMode.Off
                    selectedZoom = 1f
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else CameraSelector.LENS_FACING_BACK
                },
                onDone = {
                    settingsOpen = false
                    galleryStartIndex = null
                    if (capturedFiles.isEmpty() && persistedCaptureCount == 0) {
                        onBack()
                    } else {
                        onConfirm(capturedFiles.toList())
                    }
                },
            )
        }
        if (settingsOpen) {
            CameraSettingsPanel(
                settings = cameraSettings,
                onSettings = { cameraSettings = normalizeWorkerCameraSettings(it) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp, 12.dp, 12.dp, 188.dp),
            )
        }
        galleryStartIndex?.let { initialIndex ->
            WorkerPendingCaptureGallery(
                files = capturedFiles,
                initialIndex = initialIndex,
                onRemove = { file ->
                    if (!saving) {
                        file.delete()
                        capturedFiles = capturedFiles.filterNot { it.absolutePath == file.absolutePath }
                        galleryStartIndex = galleryStartIndex?.takeIf { capturedFiles.isNotEmpty() }
                            ?.coerceAtMost(capturedFiles.lastIndex)
                    }
                },
                onDismiss = { galleryStartIndex = null },
            )
        }
    }
}

@Composable
private fun CameraTopBar(
    title: String,
    lightMode: WorkerCameraLightMode,
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
    mode: WorkerCameraMode,
    captureInProgress: Boolean,
    saving: Boolean,
    lastCapture: File?,
    captureCount: Int,
    persistedCaptureCount: Int,
    canSwitch: Boolean,
    onMode: (WorkerCameraMode) -> Unit,
    onThumbnail: () -> Unit,
    onShutter: () -> Unit,
    onSwitch: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().height(174.dp).padding(top = 4.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.Center) {
            WorkerCameraMode.entries.forEach { candidate ->
                Text(
                    candidate.label,
                    color = if (candidate == mode) WorkerCameraBlue else Color.White.copy(alpha = 0.72f),
                    fontSize = 15.sp,
                    modifier = Modifier.clip(CircleShape).clickable(
                        enabled = !captureInProgress && !saving,
                        role = Role.Tab,
                    ) { onMode(candidate) }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (lastCapture != null) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable(
                                enabled = !captureInProgress && !saving,
                                role = Role.Button,
                                onClick = onThumbnail,
                            )
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                contentDescription = "Открыть снятые фотографии, всего $captureCount"
                            },
                    ) {
                        WorkerCapturedPhotoPreview(
                            file = lastCapture,
                            maxPixels = CAMERA_THUMBNAIL_MAX_PIXELS,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Surface(
                            color = WorkerCameraBlue,
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.BottomEnd).size(22.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = captureCount.toString(),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier.size(86.dp).clip(CircleShape)
                    .clickable(
                        enabled = !captureInProgress && !saving,
                        role = Role.Button,
                        onClick = onShutter,
                    )
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = "Снять фотографию"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White, style = Stroke(3.dp.toPx()), radius = size.minDimension / 2 - 2.dp.toPx())
                    drawCircle(Color.White, radius = size.minDimension * 0.39f)
                    if (mode == WorkerCameraMode.Night) {
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
                            drawLine(WorkerCameraBlue, start, end, 1.5.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
            }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canSwitch && !saving) {
                    TextButton(
                        onClick = onSwitch,
                        enabled = !captureInProgress,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("↻", color = Color.White, fontSize = 26.sp) }
                    Spacer(Modifier.width(8.dp))
                }
                WorkerCameraDoneButton(
                    enabled = !captureInProgress && !saving,
                    captureCount = captureCount + persistedCaptureCount,
                    onClick = onDone,
                )
            }
        }
    }
}

/** Renders one transient CameraX file without retaining its decoded bitmap after disposal. */
@Composable
private fun WorkerCapturedPhotoPreview(
    file: File,
    maxPixels: Long,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(file.absolutePath, file.lastModified(), maxPixels) {
        runCatching { decodeWorkerBitmapFile(file, maxPixels) }.getOrNull()
    }
    DisposableEffect(bitmap) {
        onDispose {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }
    if (bitmap == null) {
        Box(modifier.background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Text("Фото", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

/** Shows the worker's transient camera batch only when its bottom-left thumbnail is opened. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkerPendingCaptureGallery(
    files: List<File>,
    initialIndex: Int,
    onRemove: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    if (files.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(files.indices),
        pageCount = { files.size },
    )
    val currentIndex = pagerState.currentPage.coerceIn(files.indices)
    BackHandler(onBack = onDismiss)
    LaunchedEffect(files.size) {
        if (pagerState.currentPage > files.lastIndex) {
            pagerState.scrollToPage(files.lastIndex)
        }
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                files.getOrNull(page)?.let { file ->
                    Box(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        WorkerCapturedPhotoPreview(
                            file = file,
                            maxPixels = CAMERA_GALLERY_MAX_PIXELS,
                            contentDescription = "Фотография ${page + 1} из ${files.size}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${currentIndex + 1} из ${files.size}",
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                files.getOrNull(currentIndex)?.let { current ->
                    TextButton(onClick = { onRemove(current) }) {
                        Text("Удалить", color = Color(0xFFFFB4AB))
                    }
                }
                TextButton(onClick = onDismiss) { Text("Готово", color = Color.White) }
            }
        }
    }
}

/** Continues with every captured photo or closes an empty camera session. */
@Composable
private fun WorkerCameraDoneButton(
    enabled: Boolean,
    captureCount: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (captureCount == 0) {
                    "Закрыть камеру"
                } else {
                    "Сохранить $captureCount фотографий"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(21.dp)) {
            val color = Color.White.copy(alpha = if (enabled) 1f else 0.35f)
            drawLine(
                color,
                Offset(size.width * 0.32f, size.height * 0.12f),
                Offset(size.width * 0.7f, size.height * 0.5f),
                2.2.dp.toPx(),
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(size.width * 0.7f, size.height * 0.5f),
                Offset(size.width * 0.32f, size.height * 0.88f),
                2.2.dp.toPx(),
                StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun CameraSettingsPanel(
    settings: WorkerCameraSettings,
    onSettings: (WorkerCameraSettings) -> Unit,
    modifier: Modifier,
) {
    Surface(color = WorkerCameraPanel, shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth()) {
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
        Text(value, color = WorkerCameraBlue, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CameraZoomStops(camera: Camera?, selectedZoom: Float, onZoom: (Float) -> Unit, modifier: Modifier) {
    val zoom = camera?.cameraInfo?.zoomState?.value
    val minimum = workerCaptureMinimumZoom(zoom?.minZoomRatio, zoom?.maxZoomRatio)
    val maximum = workerCaptureMaximumZoom(zoom?.minZoomRatio, zoom?.maxZoomRatio)
    val stops = workerSupportedZoomStops(minimum, maximum)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stops.forEach { stop ->
            Surface(
                color = if (kotlin.math.abs(stop - selectedZoom) < 0.05f) WorkerCameraBlue else Color.Black.copy(alpha = 0.55f),
                shape = CircleShape,
                modifier = Modifier.clickable { onZoom(stop) },
            ) { Text(workerZoomLabel(stop), color = Color.White, modifier = Modifier.padding(10.dp, 7.dp)) }
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
        drawCircle(WorkerCameraBlue, radius = 26.dp.toPx(), center = point, style = Stroke(2.dp.toPx()))
        drawCircle(WorkerCameraBlue, radius = 3.dp.toPx(), center = point)
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

private enum class WorkerCameraLightMode(val symbol: String) {
    Off("⚡̸"),
    Flash("⚡"),
    Torch("▰");

    val captureMode: Int
        get() = if (this == Flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF

    fun next(): WorkerCameraLightMode = when (this) {
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
internal fun persistWorkerCameraImageProxy(image: ImageProxy, target: File): File? = try {
    val rotationDegrees = image.imageInfo.rotationDegrees
    val jpegBytes = workerCameraJpegBytes(image) ?: return null
    persistWorkerCameraJpeg(
        jpegBytes = jpegBytes,
        rotationDegrees = rotationDegrees,
        target = target,
    )
} catch (_: Exception) {
    null
} finally {
    image.close()
}

private fun workerCameraJpegBytes(image: ImageProxy): ByteArray? {
    if (image.format != ImageFormat.JPEG) return null
    val plane = image.planes.singleOrNull() ?: return null
    val buffer = plane.buffer.duplicate()
    if (!workerCameraJpegSizeAllowed(buffer.remaining())) return null
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes.takeIf { it.isWorkerCameraJpeg() }
}

/** Rejects an oversized CameraX buffer before allocating a second byte array of the same size. */
internal fun workerCameraJpegSizeAllowed(byteCount: Int): Boolean =
    byteCount in 4..EncryptedEvidenceFileStore.MAX_SOURCE_IMAGE_BYTES.toInt()

private fun ByteArray.isWorkerCameraJpeg(): Boolean =
    size >= 4 &&
        this[0] == 0xFF.toByte() &&
        this[1] == 0xD8.toByte() &&
        this[size - 2] == 0xFF.toByte() &&
        this[size - 1] == 0xD9.toByte()

private fun persistWorkerCameraJpeg(
    jpegBytes: ByteArray,
    rotationDegrees: Int,
    target: File,
): File? = try {
    target.outputStream().buffered().use { output -> output.write(jpegBytes) }

    // Ignore any orientation embedded by the vendor. We deliberately seed the shared normalizer
    // with the physical transform reported by CameraX, then it writes final pixels with EXIF=1.
    val physicalOrientation = workerExifOrientationForRotationDegrees(rotationDegrees)
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
    normalizeWorkerCameraJpegOrientation(
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
