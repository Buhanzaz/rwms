package dev.buhanzaz.rwms.manager.ui.components

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.buhanzaz.rwms.manager.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val ManagerCameraBlue = Color(0xFF3B82F6)
private val ManagerCameraRed = Color(0xFFFF3B30)
private val ManagerCameraPanel = Color(0xE6191919)

@Composable
internal fun ManagerCameraExperience(
    title: String,
    photoUris: List<String>,
    onPhotoCaptured: (String) -> Unit,
    onRemovePhotoUri: (String) -> Unit,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    // CameraX invokes the file callback on this executor. Keep pixel normalization off the main
    // thread: a high-resolution JPEG can take noticeably longer than the shutter animation.
    val photoFileExecutor = remember(context) { Executors.newSingleThreadExecutor() }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val preferences = remember(context) { ManagerCameraPreferences(context) }
    var settings by remember { mutableStateOf(preferences.load()) }
    var cameraMode by remember { mutableStateOf(ManagerCameraMode.Photo) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var selectedCameraKey by remember { mutableStateOf<String?>(null) }
    var availableCameraLenses by remember { mutableStateOf<List<ManagerCameraLens>>(emptyList()) }
    var lightMode by remember { mutableStateOf(ManagerCameraLightMode.Off) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var videoState by remember { mutableStateOf(ManagerRecordingState.Idle) }
    var recordingStartedAtElapsedMillis by remember { mutableLongStateOf(0L) }
    var recordingDurationMillis by remember { mutableLongStateOf(0L) }
    var captureInProgress by remember { mutableStateOf(false) }
    var capabilities by remember { mutableStateOf(ManagerBoundCameraCapabilities()) }
    var effectiveVideoQuality by remember { mutableStateOf(ManagerVideoQuality.Fhd) }
    var effectiveVideoFps by remember { mutableIntStateOf(30) }
    var effectiveVideoHdrActive by remember { mutableStateOf(false) }
    var nightExtensionActive by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsDetail by remember { mutableStateOf(ManagerSettingsDetail.None) }
    var galleryStartIndex by remember { mutableStateOf<Int?>(null) }
    var cameraMessage by remember { mutableStateOf<String?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var selectedZoom by remember { mutableFloatStateOf(1f) }
    var captureTargetRotation by remember {
        mutableIntStateOf(managerCaptureTargetRotation(previewView.display?.rotation))
    }
    var manualZoomVisible by remember { mutableStateOf(false) }
    var manualZoomRevision by remember { mutableIntStateOf(0) }
    val shutterSound = remember {
        MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    }
    val lastMedia = photoUris.lastOrNull()

    DisposableEffect(shutterSound) {
        onDispose { shutterSound.release() }
    }

    DisposableEffect(photoFileExecutor) {
        onDispose { photoFileExecutor.shutdown() }
    }

    DisposableEffect(context, previewView) {
        val listener = object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                captureTargetRotation = managerCaptureTargetRotationForOrientation(
                    orientationDegrees = orientation,
                    fallbackRotation = previewView.display?.rotation ?: captureTargetRotation,
                )
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(captureTargetRotation, imageCapture, videoCapture) {
        imageCapture?.targetRotation = captureTargetRotation
        videoCapture?.targetRotation = captureTargetRotation
    }

    LaunchedEffect(settings) {
        preferences.save(settings)
    }

    LaunchedEffect(cameraMessage) {
        if (cameraMessage != null) {
            delay(2_600)
            cameraMessage = null
        }
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1_500)
            focusPoint = null
        }
    }

    LaunchedEffect(manualZoomRevision) {
        if (manualZoomRevision > 0) {
            delay(1_800)
            manualZoomVisible = false
        }
    }

    // Status callbacks can be delayed by a device while its encoder starts. Keep the timer
    // responsive from the first VideoRecordEvent.Start, then let CameraX's recorded duration
    // correct it whenever a status callback arrives.
    LaunchedEffect(videoState, recordingStartedAtElapsedMillis) {
        if (videoState != ManagerRecordingState.Recording || recordingStartedAtElapsedMillis == 0L) {
            return@LaunchedEffect
        }
        while (videoState == ManagerRecordingState.Recording) {
            recordingDurationMillis = maxOf(
                recordingDurationMillis,
                SystemClock.elapsedRealtime() - recordingStartedAtElapsedMillis,
            )
            delay(250)
        }
    }

    DisposableEffect(
        lifecycleOwner,
        previewView,
        lensFacing,
        selectedCameraKey,
        cameraMode,
        settings.aspectRatio,
        settings.requestedMegapixels,
        settings.ultraHdrEnabled,
        settings.videoHdrEnabled,
        settings.motionCaptureEnabled,
        settings.videoQuality,
        settings.videoFramesPerSecond,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var disposed = false

        fun applyBinding(
            provider: ProcessCameraProvider,
            selectedLens: ManagerCameraLens,
            availableLenses: List<ManagerCameraLens>,
            selector: CameraSelector,
            nightAvailable: Boolean,
            photoHdrExtensionAvailable: Boolean,
            activeExtensionMode: Int?,
        ) {
            if (disposed) return
            val baseSelector = selectedLens.selector
            val extensionActive = activeExtensionMode != null
            val nightActive = activeExtensionMode == ExtensionMode.NIGHT
            val photoHdrActive = activeExtensionMode == ExtensionMode.HDR
            val extensionLabel = if (nightActive) "Ночной" else "HDR"
            val conservativeSettings = settings.copy(
                aspectRatio = ManagerCameraAspectRatio.FourThree,
                ultraHdrEnabled = false,
                videoHdrEnabled = false,
                requestedMegapixels = null,
                motionCaptureEnabled = false,
                videoFramesPerSecond = 30,
                videoQuality = ManagerVideoQuality.Fhd,
            )
            var extensionProfileAdapted = false
            var extensionAbandoned = false
            var conservativeProfileUsed = false
            val binding = runCatching {
                bindManagerCamera(
                    provider = provider,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    selector = selector,
                    selectedLens = selectedLens,
                    availableLenses = availableLenses,
                    mode = cameraMode,
                    settings = settings,
                    nightExtensionAvailable = nightAvailable,
                    nightExtensionActive = nightActive,
                    photoHdrExtensionAvailable = photoHdrExtensionAvailable,
                    photoHdrExtensionActive = photoHdrActive,
                )
            }.recoverCatching { firstFailure ->
                if (!extensionActive) throw firstFailure
                extensionProfileAdapted = true
                Log.w("ManagerCamera", "Adapting requested $extensionLabel profile", firstFailure)
                provider.unbindAll()
                bindManagerCamera(
                    provider = provider,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    selector = selector,
                    selectedLens = selectedLens,
                    availableLenses = availableLenses,
                    mode = cameraMode,
                    settings = conservativeSettings,
                    nightExtensionAvailable = nightAvailable,
                    nightExtensionActive = nightActive,
                    photoHdrExtensionAvailable = photoHdrExtensionAvailable,
                    photoHdrExtensionActive = photoHdrActive,
                )
            }.recoverCatching { extensionFailure ->
                if (!extensionActive) throw extensionFailure
                extensionAbandoned = true
                Log.w("ManagerCamera", "$extensionLabel extension failed for this camera", extensionFailure)
                provider.unbindAll()
                bindManagerCamera(
                    provider = provider,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    selector = baseSelector,
                    selectedLens = selectedLens,
                    availableLenses = availableLenses,
                    mode = cameraMode,
                    settings = settings,
                    nightExtensionAvailable = nightAvailable && !nightActive,
                    nightExtensionActive = false,
                    photoHdrExtensionAvailable = photoHdrExtensionAvailable && !photoHdrActive,
                    photoHdrExtensionActive = false,
                )
            }.recoverCatching { requestedProfileFailure ->
                conservativeProfileUsed = true
                Log.w("ManagerCamera", "Requested camera profile is unavailable", requestedProfileFailure)
                provider.unbindAll()
                bindManagerCamera(
                    provider = provider,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    selector = baseSelector,
                    selectedLens = selectedLens,
                    availableLenses = availableLenses,
                    mode = cameraMode,
                    settings = conservativeSettings,
                    nightExtensionAvailable = nightAvailable && !(extensionAbandoned && nightActive),
                    nightExtensionActive = false,
                    photoHdrExtensionAvailable = photoHdrExtensionAvailable &&
                        !(extensionAbandoned && photoHdrActive),
                    photoHdrExtensionActive = false,
                )
            }.getOrElse { failure ->
                if (cameraMode != ManagerCameraMode.Video) {
                    Log.e("ManagerCamera", "Unable to bind camera", failure)
                    cameraMessage = "Не удалось запустить камеру"
                    return
                }
                // A few camera HALs reject a valid encoder profile after Preview has already
                // been detached. Retain a live viewfinder in that case, make recording
                // explicitly unavailable, and avoid presenting a black screen to the user.
                Log.w("ManagerCamera", "Video profile unavailable; restoring preview", failure)
                runCatching {
                    provider.unbindAll()
                    bindManagerCamera(
                        provider = provider,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        selector = selector,
                        selectedLens = selectedLens,
                        availableLenses = availableLenses,
                        mode = ManagerCameraMode.Photo,
                        settings = conservativeSettings,
                        nightExtensionAvailable = false,
                        nightExtensionActive = false,
                        photoHdrExtensionAvailable = false,
                        photoHdrExtensionActive = false,
                    ).copy(videoFallbackToPreview = true)
                }.getOrElse { previewFailure ->
                    Log.e("ManagerCamera", "Unable to restore camera preview", previewFailure)
                    cameraMessage = "Не удалось запустить камеру"
                    return
                }
            }
            if (disposed) {
                provider.unbindAll()
                return
            }
            camera = binding.camera
            imageCapture = binding.imageCapture
            videoCapture = binding.videoCapture
            capabilities = binding.capabilities
            effectiveVideoQuality = binding.videoQuality
            effectiveVideoFps = binding.videoFps
            effectiveVideoHdrActive = binding.videoHdrActive
            nightExtensionActive = binding.nightExtensionActive
            val supportedExposure = settings.exposureEvTenths.coerceIn(
                binding.capabilities.minimumExposureTenths,
                binding.capabilities.maximumExposureTenths,
            )
            if (supportedExposure != settings.exposureEvTenths) {
                settings = settings.copy(exposureEvTenths = supportedExposure)
            }
            if (extensionProfileAdapted &&
                (binding.nightExtensionActive || binding.photoHdrExtensionActive)
            ) {
                cameraMessage = "$extensionLabel профиль адаптирован к возможностям камеры"
            } else if (extensionAbandoned &&
                !binding.nightExtensionActive &&
                !binding.photoHdrExtensionActive
            ) {
                cameraMessage = "$extensionLabel недоступен для выбранной камеры"
            }
            if (conservativeProfileUsed && cameraMode == ManagerCameraMode.Photo) {
                cameraMessage = "Параметры фото адаптированы к возможностям камеры"
                settings = settings.copy(
                    aspectRatio = ManagerCameraAspectRatio.FourThree,
                    requestedMegapixels = null,
                    ultraHdrEnabled = false,
                    motionCaptureEnabled = false,
                )
            }
            if (cameraMode == ManagerCameraMode.Video &&
                (settings.videoQuality != binding.videoQuality ||
                    settings.videoFramesPerSecond != binding.videoFps ||
                    settings.videoHdrEnabled != binding.videoHdrActive ||
                    (conservativeProfileUsed && settings.motionCaptureEnabled))
            ) {
                cameraMessage = "Профиль видео адаптирован к возможностям камеры"
                settings = settings.copy(
                    videoQuality = binding.videoQuality,
                    videoFramesPerSecond = binding.videoFps,
                    videoHdrEnabled = binding.videoHdrActive,
                    motionCaptureEnabled = if (conservativeProfileUsed) {
                        false
                    } else {
                        settings.motionCaptureEnabled
                    },
                )
            }
            if (binding.videoFallbackToPreview) {
                cameraMessage = "Предпросмотр восстановлен, но этот профиль видео недоступен"
            }
            val normalizedZoom = managerCoerceZoom(
                selectedZoom,
                binding.capabilities.minimumZoom,
                binding.capabilities.maximumZoom,
            )
            selectedZoom = normalizedZoom
            binding.camera.cameraControl.setZoomRatio(normalizedZoom)
        }

        providerFuture.addListener(
            {
                if (disposed) return@addListener
                val provider = runCatching { providerFuture.get() }.getOrElse { failure ->
                    Log.e("ManagerCamera", "Unable to obtain camera provider", failure)
                    cameraMessage = "Камера недоступна"
                    return@addListener
                }
                val discoveredLenses = managerAvailableCameraLenses(context, provider)
                availableCameraLenses = discoveredLenses
                val backAvailable = discoveredLenses.any {
                    it.lensFacing == CameraSelector.LENS_FACING_BACK
                }
                val frontAvailable = discoveredLenses.any {
                    it.lensFacing == CameraSelector.LENS_FACING_FRONT
                }
                val actualLensFacing = when {
                    discoveredLenses.any { it.lensFacing == lensFacing } -> lensFacing
                    backAvailable -> CameraSelector.LENS_FACING_BACK
                    frontAvailable -> CameraSelector.LENS_FACING_FRONT
                    else -> {
                        cameraMessage = "На устройстве нет доступной камеры"
                        return@addListener
                    }
                }
                if (actualLensFacing != lensFacing) {
                    lensFacing = actualLensFacing
                    return@addListener
                }
                val facingLenses = discoveredLenses.filter { it.lensFacing == actualLensFacing }
                val currentLens = facingLenses.firstOrNull { it.key == selectedCameraKey }
                    ?: facingLenses.firstOrNull(ManagerCameraLens::isDefault)
                    ?: facingLenses.firstOrNull()
                    ?: run {
                        cameraMessage = "На устройстве нет доступной камеры"
                        return@addListener
                    }
                val selectedLens = managerPreferredLensForMegapixels(
                    lenses = facingLenses,
                    currentLens = currentLens,
                    requestedMegapixels = settings.requestedMegapixels,
                )
                if (selectedCameraKey != selectedLens.key) {
                    selectedCameraKey = selectedLens.key
                    selectedZoom = 1f
                    return@addListener
                }
                val baseSelector = selectedLens.selector
                val extensionFuture = ExtensionsManager.getInstanceAsync(context, provider)
                extensionFuture.addListener(
                    {
                        if (disposed) return@addListener
                        val extensionManager = runCatching { extensionFuture.get() }.getOrNull()
                        val nightAvailable = extensionManager?.let { manager ->
                            runCatching {
                                manager.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT)
                            }.getOrDefault(false)
                        } ?: false
                        val photoHdrExtensionAvailable = extensionManager?.let { manager ->
                            runCatching {
                                manager.isExtensionAvailable(baseSelector, ExtensionMode.HDR)
                            }.getOrDefault(false)
                        } ?: false
                        val requestedExtensionMode = when {
                            cameraMode == ManagerCameraMode.Night && nightAvailable ->
                                ExtensionMode.NIGHT
                            cameraMode == ManagerCameraMode.Photo &&
                                settings.ultraHdrEnabled &&
                                photoHdrExtensionAvailable -> ExtensionMode.HDR
                            else -> null
                        }
                        val extensionSelector = if (requestedExtensionMode != null) {
                            extensionManager?.let { manager ->
                                runCatching {
                                    manager.getExtensionEnabledCameraSelector(
                                        baseSelector,
                                        requestedExtensionMode,
                                    )
                                }.getOrNull()
                            }
                        } else {
                            null
                        }
                        applyBinding(
                            provider,
                            selectedLens,
                            discoveredLenses,
                            extensionSelector ?: baseSelector,
                            nightAvailable,
                            photoHdrExtensionAvailable,
                            requestedExtensionMode.takeIf { extensionSelector != null },
                        )
                    },
                    mainExecutor,
                )
            },
            mainExecutor,
        )

        onDispose {
            disposed = true
            runCatching {
                camera?.cameraControl?.enableTorch(false)
                if (camera?.cameraInfo?.isLowLightBoostSupported == true) {
                    camera?.cameraControl?.enableLowLightBoostAsync(false)
                }
                activeRecording?.stop()
                if (providerFuture.isDone) providerFuture.get().unbindAll()
            }
            activeRecording = null
            videoState = ManagerRecordingState.Idle
            recordingStartedAtElapsedMillis = 0L
            recordingDurationMillis = 0L
            captureInProgress = false
            camera = null
            imageCapture = null
            videoCapture = null
            effectiveVideoHdrActive = false
        }
    }

    LaunchedEffect(camera, settings.exposureEvTenths) {
        val boundCamera = camera ?: return@LaunchedEffect
        val state = boundCamera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return@LaunchedEffect
        val step = state.exposureCompensationStep.toFloat().takeIf { it > 0f }
            ?: return@LaunchedEffect
        val requestedIndex = (settings.exposureEvTenths / 10f / step).roundToInt()
            .coerceIn(
                state.exposureCompensationRange.lower,
                state.exposureCompensationRange.upper,
            )
        boundCamera.cameraControl.setExposureCompensationIndex(requestedIndex)
    }

    LaunchedEffect(camera, imageCapture, lightMode, cameraMode, nightExtensionActive) {
        val boundCamera = camera ?: return@LaunchedEffect
        val hasLight = boundCamera.cameraInfo.hasFlashUnit()
        val effectiveLight = lightMode.takeIf { hasLight } ?: ManagerCameraLightMode.Off
        imageCapture?.flashMode = effectiveLight.imageCaptureFlashMode
        boundCamera.cameraControl.enableTorch(effectiveLight == ManagerCameraLightMode.Torch)
        if (boundCamera.cameraInfo.isLowLightBoostSupported) {
            val enableBoost = cameraMode == ManagerCameraMode.Night &&
                !nightExtensionActive &&
                effectiveLight == ManagerCameraLightMode.Off
            boundCamera.cameraControl.enableLowLightBoostAsync(enableBoost)
        }
    }

    val zoomTransformState = rememberTransformableState { _, zoomChange, _, _ ->
        val nextZoom = managerCoerceZoom(
            selectedZoom * zoomChange,
            capabilities.minimumZoom,
            capabilities.maximumZoom,
        )
        selectedZoom = nextZoom
        camera?.cameraControl?.setZoomRatio(nextZoom)
        manualZoomVisible = true
        manualZoomRevision += 1
    }

    fun cycleLightMode() {
        if (!capabilities.hasFlashUnit) return
        if (cameraMode == ManagerCameraMode.Night) {
            lightMode = ManagerCameraLightMode.Off
            cameraMessage = "В ночном режиме вспышка и фонарь недоступны"
            return
        }
        val next = lightMode.next(cameraMode)
        lightMode = next
    }

    fun takePhoto() {
        if (captureInProgress || videoState != ManagerRecordingState.Idle) return
        val capture = imageCapture ?: return
        captureInProgress = true
        settingsOpen = false
        settingsDetail = ManagerSettingsDetail.None
        if (cameraMode == ManagerCameraMode.Night) {
            cameraMessage = if (nightExtensionActive) {
                "Ночной снимок — держите телефон неподвижно"
            } else if (capabilities.lowLightBoostAvailable) {
                "Ночной снимок с автоматическим усилением света"
            } else {
                "Ночное улучшение этой камерой не поддерживается"
            }
        }
        playManagerCameraFeedback(context, shutterSound)
        captureManagerCameraPhoto(
            imageCapture = capture,
            targetRotation = captureTargetRotation,
            cacheDir = context.cacheDir,
            ioExecutor = photoFileExecutor,
            mainExecutor = mainExecutor,
            onCaptured = { uri, resolutionReduced ->
                captureInProgress = false
                if (resolutionReduced) {
                    cameraMessage = "Фото сохранено с разрешением до 13 МП для стабильной загрузки"
                }
                onPhotoCaptured(uri)
            },
            onError = {
                captureInProgress = false
                cameraMessage = "Не удалось сохранить фотографию"
            },
        )
    }

    fun toggleVideoRecording() {
        val currentRecording = activeRecording
        if (currentRecording != null && videoState.isActive) {
            videoState = ManagerRecordingState.Stopping
            currentRecording.stop()
            return
        }
        if (videoState != ManagerRecordingState.Idle) return
        settingsOpen = false
        settingsDetail = ManagerSettingsDetail.None
        val capture = videoCapture ?: run {
            cameraMessage = "Запись видео недоступна"
            return
        }
        capture.targetRotation = captureTargetRotation
        val file = createManagerCameraMediaFile(context.cacheDir, "video", "mp4") ?: return
        val output = FileOutputOptions.Builder(file).build()
        var pending = capture.output.prepareRecording(context, output)
        if (audioPermissionGranted &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pending = try {
                pending.withAudioEnabled()
            } catch (security: SecurityException) {
                Log.w("ManagerCamera", "Audio permission was revoked", security)
                pending
            }
        }
        videoState = ManagerRecordingState.Starting
        recordingStartedAtElapsedMillis = 0L
        recordingDurationMillis = 0L
        activeRecording = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingStartedAtElapsedMillis = SystemClock.elapsedRealtime()
                    recordingDurationMillis = 0L
                    videoState = ManagerRecordingState.Recording
                }

                is VideoRecordEvent.Status -> {
                    recordingDurationMillis = maxOf(
                        recordingDurationMillis,
                        event.recordingStats.recordedDurationNanos / 1_000_000L,
                    )
                }

                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    videoState = ManagerRecordingState.Idle
                    recordingStartedAtElapsedMillis = 0L
                    recordingDurationMillis = 0L
                    if (event.hasError()) {
                        file.delete()
                        cameraMessage = "Не удалось сохранить видео"
                        Log.e("ManagerCamera", "Video finalize failed: ${event.error}")
                    } else {
                        playManagerCameraFeedback(context, shutterSound)
                        onPhotoCaptured(Uri.fromFile(file).toString())
                    }
                }

                else -> Unit
            }
        }
    }

    val volumeShutterAction by rememberUpdatedState<() -> Unit> {
        if (galleryStartIndex == null) {
            if (cameraMode == ManagerCameraMode.Video) toggleVideoRecording() else takePhoto()
        }
    }
    DisposableEffect(context, hostView) {
        val activity = context.managerMainActivity()
        val dialog = hostView.managerComposeDialog()
        activity?.setVolumeShutterHandler { volumeShutterAction() }
        dialog?.setOnKeyListener { _, keyCode, event ->
            managerHandleVolumeShutterKey(
                keyCode = keyCode,
                action = event.action,
                repeatCount = event.repeatCount,
            ) { volumeShutterAction() }
        }
        onDispose {
            dialog?.setOnKeyListener(null)
            activity?.setVolumeShutterHandler(null)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ManagerCameraTopBar(
                title = title,
                mode = cameraMode,
                videoQuality = effectiveVideoQuality,
                videoFps = effectiveVideoFps,
                videoHdrActive = effectiveVideoHdrActive,
                recordingState = videoState,
                recordingDurationMillis = recordingDurationMillis,
                lightMode = lightMode,
                flashAvailable = capabilities.hasFlashUnit,
                lightEnabled = capabilities.hasFlashUnit &&
                    cameraMode != ManagerCameraMode.Night &&
                    !videoState.isActive &&
                    !captureInProgress,
                settingsEnabled = !videoState.isActive && !captureInProgress,
                settingsOpen = settingsOpen,
                onLightClick = ::cycleLightMode,
                onSettingsClick = {
                    settingsOpen = !settingsOpen
                    settingsDetail = ManagerSettingsDetail.None
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .transformable(zoomTransformState)
                    .pointerInput(camera, previewView) {
                        detectTapGestures { point ->
                            val boundCamera = camera ?: return@detectTapGestures
                            val meteringPoint = previewView.meteringPointFactory.createPoint(
                                point.x,
                                point.y,
                                0.16f,
                            )
                            val action = FocusMeteringAction.Builder(
                                meteringPoint,
                                FocusMeteringAction.FLAG_AF or
                                    FocusMeteringAction.FLAG_AE or
                                    FocusMeteringAction.FLAG_AWB,
                            )
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                            focusPoint = point
                            boundCamera.cameraControl.startFocusAndMetering(action)
                        }
                    },
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                if (settings.gridEnabled) ManagerCameraGrid()
                focusPoint?.let { point -> ManagerFocusIndicator(point) }
                cameraMessage?.let { message ->
                    Surface(
                        color = Color.Black.copy(alpha = 0.68f),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp),
                    ) {
                        Text(
                            message,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                ManagerZoomControls(
                    minimumZoom = capabilities.minimumZoom,
                    maximumZoom = capabilities.maximumZoom,
                    selectedZoom = selectedZoom,
                    selectedLensZoom = capabilities.selectedLensZoom,
                    selectedCameraKey = capabilities.selectedCameraKey,
                    cameraLenses = capabilities.cameraLenses,
                    manualZoomVisible = manualZoomVisible,
                    onZoomChanged = { zoom ->
                        selectedZoom = managerCoerceZoom(
                            zoom,
                            capabilities.minimumZoom,
                            capabilities.maximumZoom,
                        )
                        camera?.cameraControl?.setZoomRatio(selectedZoom)
                        manualZoomVisible = true
                        manualZoomRevision += 1
                    },
                    onLensSelected = { cameraKey ->
                        if (cameraKey == selectedCameraKey) {
                            selectedZoom = 1f
                            camera?.cameraControl?.setZoomRatio(1f)
                        } else {
                            val targetLens = availableCameraLenses.firstOrNull {
                                lens -> lens.key == cameraKey
                            }
                            lightMode = ManagerCameraLightMode.Off
                            selectedZoom = 1f
                            selectedCameraKey = cameraKey
                            settings = settings.copy(requestedMegapixels = null)
                            targetLens?.let { lens ->
                                cameraMessage = "Камера ${managerZoomLabel(lens.displayZoom)}"
                            }
                        }
                        manualZoomVisible = false
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp),
                )
            }

            ManagerCameraBottomControls(
                mode = cameraMode,
                recordingState = videoState,
                captureInProgress = captureInProgress,
                lastMedia = lastMedia,
                canSwitchCamera = capabilities.canSwitchCamera,
                onModeSelected = { selectedMode ->
                    if (!videoState.isActive) {
                        if (selectedMode == ManagerCameraMode.Video && !audioPermissionGranted) {
                            onRequestAudioPermission()
                        }
                        if (selectedMode != cameraMode) lightMode = ManagerCameraLightMode.Off
                        cameraMode = selectedMode
                        settingsOpen = false
                        settingsDetail = ManagerSettingsDetail.None
                    }
                },
                onThumbnailClick = {
                    if (photoUris.isNotEmpty()) galleryStartIndex = photoUris.lastIndex
                },
                onShutterClick = {
                    if (cameraMode == ManagerCameraMode.Video) toggleVideoRecording() else takePhoto()
                },
                onSwitchCamera = {
                    if (!videoState.isActive) {
                        lightMode = ManagerCameraLightMode.Off
                        selectedCameraKey = null
                        selectedZoom = 1f
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    }
                },
                onClose = onClose,
            )
        }

        if (settingsOpen) {
            if (cameraMode == ManagerCameraMode.Video) {
                ManagerVideoSettingsPanel(
                    settings = settings,
                    capabilities = capabilities,
                    onSettingsChanged = { settings = normalizeManagerCameraSettings(it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 188.dp),
                )
            } else {
                ManagerPhotoSettingsPanel(
                    settings = settings,
                    detail = settingsDetail,
                    capabilities = capabilities,
                    lightMode = lightMode,
                    nightMode = cameraMode == ManagerCameraMode.Night,
                    onDetailChanged = { settingsDetail = it },
                    onLightClick = ::cycleLightMode,
                    onSettingsChanged = { settings = normalizeManagerCameraSettings(it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 188.dp),
                )
            }
        }
    }

    galleryStartIndex?.let { index ->
        ManagerPhotoGalleryDialog(
            photoUris = photoUris,
            initialIndex = index,
            onRemovePhotoUri = onRemovePhotoUri,
            onDismiss = { galleryStartIndex = null },
        )
    }
}

private tailrec fun Context.managerMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.managerMainActivity()
    else -> null
}

/** Resolves the Compose dialog that owns this camera composition, when one exists. */
private tailrec fun View.managerComposeDialog(): Dialog? {
    val window = (this as? DialogWindowProvider)?.window
    return (window?.callback as? Dialog)
        ?: (parent as? View)?.managerComposeDialog()
}

/**
 * Consumes both volume keys inside the active camera window. This is required when the camera is
 * hosted by a Compose dialog, whose window dispatches keys without calling [MainActivity].
 */
internal fun managerHandleVolumeShutterKey(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    onShutter: () -> Unit,
): Boolean {
    if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
        return false
    }
    if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) onShutter()
    return true
}

private const val MANAGER_CAPTURE_WIDE_ZOOM_TARGET = 0.6f

/**
 * Keeps the shared 0.6× stop available on logical cameras that really support it. A physical
 * lens is already an optical selection, so its local 1× baseline remains the appropriate stop.
 * Invalid or narrower hardware ranges never receive a made-up zoom request.
 */
internal fun managerCaptureMinimumZoom(
    hardwareMinimum: Float?,
    hardwareMaximum: Float?,
    selectedLensIsPhysical: Boolean,
): Float {
    val minimum = managerCaptureSupportedMinimumZoom(hardwareMinimum, hardwareMaximum)
    val maximum = managerCaptureMaximumZoom(hardwareMinimum, hardwareMaximum)
    val target = if (selectedLensIsPhysical) 1f else MANAGER_CAPTURE_WIDE_ZOOM_TARGET
    return target.takeIf { zoom -> zoom in minimum..maximum } ?: minimum
}

internal fun managerCaptureMaximumZoom(
    hardwareMinimum: Float?,
    hardwareMaximum: Float?,
): Float {
    val minimum = managerCaptureSupportedMinimumZoom(hardwareMinimum, hardwareMaximum)
    return hardwareMaximum
        ?.takeIf { zoom -> zoom.isFinite() && zoom >= minimum }
        ?: minimum
}

private fun managerCaptureSupportedMinimumZoom(
    hardwareMinimum: Float?,
    hardwareMaximum: Float?,
): Float {
    val reportedMinimum = hardwareMinimum?.takeIf { zoom -> zoom.isFinite() && zoom > 0f }
    val reportedMaximum = hardwareMaximum?.takeIf { zoom -> zoom.isFinite() && zoom > 0f }
    return when {
        reportedMinimum != null && reportedMaximum != null && reportedMaximum >= reportedMinimum ->
            reportedMinimum
        reportedMinimum != null -> reportedMinimum
        // When a vendor reports only a usable maximum, that maximum is the one value known to
        // be safe. Do not invent a 1× request outside the published range.
        reportedMaximum != null -> reportedMaximum
        else -> 1f
    }
}

private data class ManagerBoundCameraCapabilities(
    val hasFlashUnit: Boolean = false,
    val canSwitchCamera: Boolean = false,
    val nightExtensionAvailable: Boolean = false,
    val lowLightBoostAvailable: Boolean = false,
    val photoHdrAvailable: Boolean = false,
    val videoHdrAvailable: Boolean = false,
    val videoStabilizationAvailable: Boolean = false,
    val photoMegapixels: List<Int> = emptyList(),
    val cameraLenses: List<ManagerCameraLensOption> = emptyList(),
    val selectedCameraKey: String? = null,
    val selectedLensZoom: Float = 1f,
    val videoQualities: Set<ManagerVideoQuality> = emptySet(),
    val videoFramesPerSecond: Set<Int> = setOf(30),
    val minimumZoom: Float = 1f,
    val maximumZoom: Float = 1f,
    val minimumExposureTenths: Int = 0,
    val maximumExposureTenths: Int = 0,
)

private data class ManagerCameraLens(
    val key: String,
    val cameraId: String,
    val parentCameraId: String,
    val lensFacing: Int,
    val selector: CameraSelector,
    val cameraInfo: CameraInfo,
    val metadataCameraInfo: CameraInfo,
    val photoSizes: List<Size>,
    val opticalScale: Float?,
    val focalLength: Float?,
    val displayZoom: Float,
    val isPhysical: Boolean,
    val isDefault: Boolean,
) {
    val photoPixelCounts: List<Long>
        get() = photoSizes.map { size -> size.width.toLong() * size.height }
}

private data class ManagerCameraLensOption(
    val key: String,
    val displayZoom: Float,
    val maximumMegapixels: Int?,
)

private data class ManagerCameraBinding(
    val camera: Camera,
    val imageCapture: ImageCapture?,
    val videoCapture: VideoCapture<Recorder>?,
    val capabilities: ManagerBoundCameraCapabilities,
    val nightExtensionActive: Boolean,
    val photoHdrExtensionActive: Boolean,
    val videoQuality: ManagerVideoQuality,
    val videoFps: Int,
    val videoHdrActive: Boolean,
    val videoFallbackToPreview: Boolean = false,
)

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun bindManagerCamera(
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    selector: CameraSelector,
    selectedLens: ManagerCameraLens,
    availableLenses: List<ManagerCameraLens>,
    mode: ManagerCameraMode,
    settings: ManagerCameraSettings,
    nightExtensionAvailable: Boolean,
    nightExtensionActive: Boolean,
    photoHdrExtensionAvailable: Boolean,
    photoHdrExtensionActive: Boolean,
): ManagerCameraBinding {
    provider.unbindAll()
    val baseCameraInfo = selectedLens.cameraInfo
    val videoCapabilities = Recorder.getVideoCapabilities(baseCameraInfo)
    val sdrQualities = videoCapabilities.getSupportedQualities(DynamicRange.SDR)
        .mapNotNull(::managerVideoQualityFromCameraX)
        .toSet()
    val hlgQualities = videoCapabilities.getSupportedQualities(DynamicRange.HLG_10_BIT)
        .mapNotNull(::managerVideoQualityFromCameraX)
        .toSet()
    // Some HALs advertise HLG in the dynamic-range list but publish no usable encoder quality.
    // Treat HDR as available only when Recorder can create an actual HLG profile.
    val videoHdrAvailable = hlgQualities.isNotEmpty()
    val targetDynamicRange = if (
        mode == ManagerCameraMode.Video &&
        settings.videoHdrEnabled &&
        videoHdrAvailable &&
        settings.videoFramesPerSecond <= 30
    ) {
        DynamicRange.HLG_10_BIT
    } else {
        DynamicRange.SDR
    }
    val dynamicRangeQualities = if (targetDynamicRange == DynamicRange.HLG_10_BIT) {
        hlgQualities
    } else {
        sdrQualities
    }
    val actualQuality = managerEffectiveVideoQuality(settings.videoQuality, dynamicRangeQualities)
        ?: ManagerVideoQuality.Hd
    val supportedFps = managerSupportedFrameRates(baseCameraInfo)
    val actualFps = settings.videoFramesPerSecond
        .takeIf { it in supportedFps && !(targetDynamicRange != DynamicRange.SDR && it > 30) }
        ?: 30
    val facingPhotoPixelCounts = availableLenses
        .filter { lens -> lens.lensFacing == selectedLens.lensFacing }
        .flatMap(ManagerCameraLens::photoPixelCounts)
    val zoomState = baseCameraInfo.zoomState.value
    val exposure = baseCameraInfo.exposureState
    val exposureStep = exposure.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 0f
    val supportedMinimumZoom = zoomState?.minZoomRatio
    val supportedMaximumZoom = zoomState?.maxZoomRatio
    val capabilities = ManagerBoundCameraCapabilities(
        hasFlashUnit = baseCameraInfo.hasFlashUnit(),
        canSwitchCamera = provider.hasManagerCamera(CameraSelector.LENS_FACING_BACK) &&
            provider.hasManagerCamera(CameraSelector.LENS_FACING_FRONT),
        nightExtensionAvailable = nightExtensionAvailable,
        lowLightBoostAvailable = baseCameraInfo.isLowLightBoostSupported,
        // Every still capture is normalized into an app-owned SDR JPEG before it can be
        // uploaded. JPEG-R gain maps cannot survive that deterministic pixel transform, so do
        // not advertise a selectable Ultra HDR still format.
        photoHdrAvailable = photoHdrExtensionAvailable,
        videoHdrAvailable = videoHdrAvailable,
        videoStabilizationAvailable = videoCapabilities.isStabilizationSupported,
        photoMegapixels = managerPhotoMegapixelOptions(facingPhotoPixelCounts),
        cameraLenses = managerVisibleCameraLensOptions(availableLenses, selectedLens),
        selectedCameraKey = selectedLens.key,
        selectedLensZoom = selectedLens.displayZoom,
        videoQualities = dynamicRangeQualities,
        videoFramesPerSecond = supportedFps,
        minimumZoom = managerCaptureMinimumZoom(
            hardwareMinimum = supportedMinimumZoom,
            hardwareMaximum = supportedMaximumZoom,
            selectedLensIsPhysical = selectedLens.isPhysical,
        ),
        maximumZoom = managerCaptureMaximumZoom(
            hardwareMinimum = supportedMinimumZoom,
            hardwareMaximum = supportedMaximumZoom,
        ),
        minimumExposureTenths = if (exposureStep > 0f) {
            (exposure.exposureCompensationRange.lower * exposureStep * 10).roundToInt()
        } else {
            0
        },
        maximumExposureTenths = if (exposureStep > 0f) {
            (exposure.exposureCompensationRange.upper * exposureStep * 10).roundToInt()
        } else {
            0
        },
    )
    val previewBuilder = Preview.Builder()
        .setTargetRotation(managerCaptureTargetRotation(previewView.display?.rotation))
    val extensionActive = nightExtensionActive || photoHdrExtensionActive
    if (selectedLens.isPhysical && !extensionActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(selectedLens.cameraId)
    }
    // Preview and recorder must negotiate the same dynamic range. Leaving Preview at SDR
    // while VideoCapture requests HLG causes a black viewfinder on several Camera2 HALs.
    if (mode == ManagerCameraMode.Video) {
        previewBuilder.setDynamicRange(targetDynamicRange)
    }
    val preview = previewBuilder
        .build()
        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

    return if (mode == ManagerCameraMode.Video) {
        val qualitySelector = QualitySelector.from(
            actualQuality.toCameraXQuality(),
            FallbackStrategy.higherQualityOrLowerThan(actualQuality.toCameraXQuality()),
        )
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
        val videoBuilder = VideoCapture.Builder(recorder)
            .setDynamicRange(targetDynamicRange)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
        // 30 fps is the recorder's normal negotiated profile. Forcing Range(30, 30) makes
        // some vendor HALs reject an otherwise supported Preview + VideoCapture session.
        if (actualFps > 30) {
            videoBuilder.setTargetFrameRate(Range(actualFps, actualFps))
        }
        if (settings.motionCaptureEnabled && capabilities.videoStabilizationAvailable) {
            videoBuilder.setVideoStabilizationEnabled(true)
        }
        if (selectedLens.isPhysical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Camera2Interop.Extender(videoBuilder).setPhysicalCameraId(selectedLens.cameraId)
        }
        val video = videoBuilder.build()
        val boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview, video)
        ManagerCameraBinding(
            camera = boundCamera,
            imageCapture = null,
            videoCapture = video,
            capabilities = capabilities,
            nightExtensionActive = false,
            photoHdrExtensionActive = false,
            videoQuality = actualQuality,
            videoFps = actualFps,
            videoHdrActive = targetDynamicRange != DynamicRange.SDR,
        )
    } else {
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(
                managerStillCaptureMode(
                    mode = mode,
                    photoHdrExtensionActive = photoHdrExtensionActive,
                ),
            )
            .setTargetRotation(managerCaptureTargetRotation(previewView.display?.rotation))
            .setResolutionSelector(managerPhotoResolutionSelector(settings, selectedLens.photoSizes))
        if (selectedLens.isPhysical && !extensionActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(selectedLens.cameraId)
        }
        // The app rotates the captured pixels locally and writes Orientation=1. Use an ordinary
        // JPEG from CameraX so the final upload is one deterministic, widely supported format.
        captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
        val capture = captureBuilder.build()
        val boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        ManagerCameraBinding(
            camera = boundCamera,
            imageCapture = capture,
            videoCapture = null,
            capabilities = capabilities,
            nightExtensionActive = nightExtensionActive,
            photoHdrExtensionActive = photoHdrExtensionActive,
            videoQuality = actualQuality,
            videoFps = actualFps,
            videoHdrActive = false,
        )
    }
}

/**
 * Prioritizes shutter latency for ordinary photos while preserving quality processing on opt-in
 * Night and HDR modes.
 */
internal fun managerStillCaptureMode(
    mode: ManagerCameraMode,
    photoHdrExtensionActive: Boolean,
): Int = if (mode == ManagerCameraMode.Photo && !photoHdrExtensionActive) {
    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
} else {
    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
}

private fun managerPhotoResolutionSelector(
    settings: ManagerCameraSettings,
    photoSizes: List<Size>,
): ResolutionSelector {
    val aspectRatioStrategy = when (settings.aspectRatio) {
        ManagerCameraAspectRatio.FourThree ->
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        ManagerCameraAspectRatio.SixteenNine ->
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    }
    val builder = ResolutionSelector.Builder()
        .setAspectRatioStrategy(aspectRatioStrategy)
        .setAllowedResolutionMode(
            managerPhotoAllowedResolutionMode(settings.requestedMegapixels),
        )
    val effectiveMegapixels = managerEffectivePhotoMegapixels(settings.requestedMegapixels)
    val requestedPixels = effectiveMegapixels * 1_000_000L
    managerPreferredPhotoSize(photoSizes, effectiveMegapixels)?.let { preferredSize ->
        builder.setResolutionStrategy(
            ResolutionStrategy(
                preferredSize,
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
        )
    }
    builder.setResolutionFilter(
        ResolutionFilter { supportedSizes, _ ->
            supportedSizes.sortedWith(
                compareBy<Size> { size ->
                    abs(size.width.toLong() * size.height - requestedPixels)
                }.thenByDescending { size -> size.width.toLong() * size.height },
            )
        },
    )
    return builder.build()
}

/**
 * Keeps the automatic profile responsive while explicit high-resolution choices stay available.
 */
internal fun managerPhotoAllowedResolutionMode(requestedMegapixels: Int?): Int =
    if (requestedMegapixels == null) {
        ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
    } else {
        ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
    }

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun managerSupportedPhotoSizes(cameraInfo: CameraInfo): List<Size> = runCatching {
    val camera2Info = Camera2CameraInfo.from(cameraInfo)
    val streamMap = camera2Info
        .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val maximumResolutionStreamMap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION,
        )
    } else {
        null
    }
    managerSupportedJpegOutputSizes(streamMap, maximumResolutionStreamMap)
}.getOrDefault(emptyList())

private fun managerSupportedPhotoSizes(
    characteristics: CameraCharacteristics,
): List<Size> = runCatching {
    val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val maximumResolutionStreamMap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
    } else {
        null
    }
    managerSupportedJpegOutputSizes(streamMap, maximumResolutionStreamMap)
}.getOrDefault(emptyList())

private fun managerSupportedJpegOutputSizes(
    streamMap: android.hardware.camera2.params.StreamConfigurationMap?,
    maximumResolutionStreamMap: android.hardware.camera2.params.StreamConfigurationMap?,
): List<Size> = buildList {
    listOfNotNull(streamMap, maximumResolutionStreamMap).forEach { outputMap ->
        addAll(
            runCatching { outputMap.getOutputSizes(ImageFormat.JPEG).orEmpty().toList() }
                .getOrDefault(emptyList()),
        )
        // Some vendor HALs throw for the high-resolution query even though their normal
        // JPEG table is valid. Keep the valid table instead of losing every advertised size.
        addAll(
            runCatching {
                outputMap.getHighResolutionOutputSizes(ImageFormat.JPEG).orEmpty().toList()
            }.getOrDefault(emptyList()),
        )
    }
}
    .distinctBy { size -> size.width to size.height }
    .sortedByDescending { size -> size.width.toLong() * size.height }

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun managerAvailableCameraLenses(
    context: Context,
    provider: ProcessCameraProvider,
): List<ManagerCameraLens> {
    val topLevelInfos = provider.availableCameraInfos
    val topLevelCameraIds = topLevelInfos.mapNotNull(::managerCameraId).toSet()
    val cameraManager = context.applicationContext.getSystemService(CameraManager::class.java)
    val defaultCameraIds = listOf(
        CameraSelector.LENS_FACING_BACK,
        CameraSelector.LENS_FACING_FRONT,
    ).associateWith { facing ->
        runCatching {
            CameraSelector.Builder()
                .requireLensFacing(facing)
                .build()
                .filter(topLevelInfos)
                .firstOrNull()
        }.getOrNull()?.let(::managerCameraId)
    }

    val discovered = buildList {
        topLevelInfos.forEach { cameraInfo ->
            val lensFacing = runCatching { cameraInfo.lensFacing }.getOrNull()
                ?: return@forEach
            val cameraId = managerCameraId(cameraInfo) ?: return@forEach
            val selector = runCatching { cameraInfo.cameraSelector }.getOrNull()
                ?: return@forEach
            val parentPhotoSizes = managerSupportedPhotoSizes(cameraInfo)
            val physicalFallbackPhotoSizes = parentPhotoSizes
                .filter { size -> size.width.toLong() * size.height < 40_000_000L }
                .ifEmpty { parentPhotoSizes }
            val declaredPhysicalIds = managerDeclaredPhysicalCameraIds(cameraManager, cameraId)
            Log.i(
                "ManagerCamera",
                "Camera $cameraId facing=$lensFacing physical=$declaredPhysicalIds " +
                    "jpeg=${parentPhotoSizes.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "none"}",
            )
            add(
                ManagerCameraLens(
                    key = "camera:$cameraId",
                    cameraId = cameraId,
                    parentCameraId = cameraId,
                    lensFacing = lensFacing,
                    selector = selector,
                    cameraInfo = cameraInfo,
                    metadataCameraInfo = cameraInfo,
                    photoSizes = parentPhotoSizes,
                    opticalScale = managerOpticalScale(cameraInfo),
                    focalLength = managerMinimumFocalLength(cameraInfo),
                    displayZoom = 1f,
                    isPhysical = false,
                    isDefault = defaultCameraIds[lensFacing] == cameraId,
                ),
            )

            val cameraXPhysicalIds = mutableSetOf<String>()
            val physicalInfos = runCatching { cameraInfo.physicalCameraInfos }
                .getOrDefault(emptySet())
            physicalInfos.forEach physicalLoop@{ physicalInfo ->
                val physicalCameraId = managerCameraId(physicalInfo) ?: return@physicalLoop
                if (physicalCameraId == cameraId || physicalCameraId in topLevelCameraIds) {
                    return@physicalLoop
                }
                val physicalPhotoSizes = managerSupportedPhotoSizes(physicalInfo)
                val photoSizes = managerPhysicalLensOutputs(
                    physicalPhotoSizes,
                    physicalFallbackPhotoSizes,
                )
                if (photoSizes.isEmpty()) return@physicalLoop
                if (physicalPhotoSizes.isEmpty()) {
                    Log.i(
                        "ManagerCamera",
                        "Physical camera $physicalCameraId has no separate JPEG table; " +
                            "using logical camera $cameraId output sizes",
                    )
                }
                val physicalSelector = managerPhysicalCameraSelector(selector, physicalCameraId)
                add(
                    ManagerCameraLens(
                        key = "physical:$cameraId:$physicalCameraId",
                        cameraId = physicalCameraId,
                        parentCameraId = cameraId,
                        lensFacing = lensFacing,
                        selector = physicalSelector,
                        cameraInfo = cameraInfo,
                        metadataCameraInfo = physicalInfo,
                        photoSizes = photoSizes,
                        opticalScale = managerOpticalScale(physicalInfo),
                        focalLength = managerMinimumFocalLength(physicalInfo),
                        displayZoom = 1f,
                        isPhysical = true,
                        isDefault = false,
                    ),
                )
                cameraXPhysicalIds += physicalCameraId
            }

            // CameraX does not expose every physical CameraInfo on all vendor HALs. Nothing
            // Phone (3a), for example, can publish its wide/tele sensors only through the
            // logical rear camera. Camera2's declared physical ids are still bindable with
            // CameraSelector.setPhysicalCameraId, so use them as a second discovery source.
            declaredPhysicalIds
                .asSequence()
                .filter { physicalCameraId ->
                    physicalCameraId != cameraId &&
                        physicalCameraId !in topLevelCameraIds &&
                        physicalCameraId !in cameraXPhysicalIds
                }
                .forEach physicalLoop@{ physicalCameraId ->
                    val characteristics = managerPhysicalCameraCharacteristics(
                        cameraManager,
                        physicalCameraId,
                    ) ?: return@physicalLoop
                    val physicalPhotoSizes = managerSupportedPhotoSizes(characteristics)
                    val photoSizes = managerPhysicalLensOutputs(
                        physicalPhotoSizes,
                        physicalFallbackPhotoSizes,
                    )
                    if (photoSizes.isEmpty()) return@physicalLoop
                    if (physicalPhotoSizes.isEmpty()) {
                        Log.i(
                            "ManagerCamera",
                            "Physical camera $physicalCameraId has no direct JPEG table; " +
                                "using logical camera $cameraId output sizes",
                        )
                    }
                    add(
                        ManagerCameraLens(
                            key = "physical:$cameraId:$physicalCameraId",
                            cameraId = physicalCameraId,
                            parentCameraId = cameraId,
                            lensFacing = lensFacing,
                            selector = managerPhysicalCameraSelector(selector, physicalCameraId),
                            cameraInfo = cameraInfo,
                            // CameraX needs the logical parent for binding; the direct Camera2
                            // characteristics above supply the actual sensor metadata below.
                            metadataCameraInfo = cameraInfo,
                            photoSizes = photoSizes,
                            opticalScale = managerOpticalScale(characteristics),
                            focalLength = managerMinimumFocalLength(characteristics),
                            displayZoom = 1f,
                            isPhysical = true,
                            isDefault = false,
                        ),
                    )
                }
        }
    }

    val opticalReferences = discovered.groupBy(ManagerCameraLens::lensFacing)
        .mapValues { (_, facingLenses) -> managerOpticalReference(facingLenses) }
    val lenses = discovered.map { lens ->
        val reference = opticalReferences[lens.lensFacing]
        val opticalScale = lens.opticalScale
        val focalLength = lens.focalLength
        val opticalRatio = if (
            reference?.opticalScale != null &&
            opticalScale != null &&
            reference.opticalScale > 0f
        ) {
            opticalScale / reference.opticalScale
        } else if (
            reference?.focalLength != null &&
            focalLength != null &&
            reference.focalLength > 0f
        ) {
            focalLength / reference.focalLength
        } else {
            null
        }
        val intrinsicRatio = if (!lens.isPhysical) {
            runCatching { lens.metadataCameraInfo.intrinsicZoomRatio }
                .getOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
        } else {
            null
        }
        lens.copy(
            displayZoom = if (lens.isDefault) {
                1f
            } else {
                val preferredRatio = if (
                    !lens.isPhysical && intrinsicRatio != null && abs(intrinsicRatio - 1f) > 0.05f
                ) {
                    intrinsicRatio
                } else {
                    opticalRatio ?: intrinsicRatio ?: 1f
                }
                managerNormalizedLensZoomRatio(preferredRatio)
            },
        )
    }.sortedWith(
        compareBy<ManagerCameraLens> { it.lensFacing }
            .thenBy(ManagerCameraLens::displayZoom)
            .thenBy { !it.isDefault }
            .thenBy(ManagerCameraLens::key),
    )

    Log.i(
        "ManagerCamera",
        lenses.joinToString(prefix = "Available lenses: ") { lens ->
            val maximumMegapixels = managerPhotoMegapixelOptions(lens.photoPixelCounts)
                .firstOrNull()
            "${lens.key}=${managerZoomLabel(lens.displayZoom)}/${maximumMegapixels ?: 0}MP"
        },
    )
    return lenses
}

private fun managerPhysicalCameraSelector(
    parentSelector: CameraSelector,
    physicalCameraId: String,
): CameraSelector = CameraSelector.Builder()
    .addCameraFilter { cameraInfos -> parentSelector.filter(cameraInfos) }
    .setPhysicalCameraId(physicalCameraId)
    .build()

private fun managerDeclaredPhysicalCameraIds(
    cameraManager: CameraManager?,
    cameraId: String,
): Set<String> {
    if (cameraManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
    return runCatching {
        cameraManager.getCameraCharacteristics(cameraId).physicalCameraIds
    }.getOrDefault(emptySet())
}

private fun managerPhysicalCameraCharacteristics(
    cameraManager: CameraManager?,
    physicalCameraId: String,
): CameraCharacteristics? {
    if (cameraManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
        cameraManager.getCameraCharacteristics(physicalCameraId)
    }.getOrNull()
}

private data class ManagerOpticalReference(
    val opticalScale: Float?,
    val focalLength: Float?,
)

private fun managerOpticalReference(
    lenses: List<ManagerCameraLens>,
): ManagerOpticalReference {
    val defaultLens = lenses.firstOrNull(ManagerCameraLens::isDefault)
    // A logical default camera represents the native 1× reference. Prefer its reported
    // optics whenever present; otherwise a telephoto 50 MP sensor could accidentally become
    // the reference merely because it has the same resolution as the main sensor.
    if (defaultLens?.opticalScale != null || defaultLens?.focalLength != null) {
        return ManagerOpticalReference(
            opticalScale = defaultLens.opticalScale,
            focalLength = defaultLens.focalLength,
        )
    }
    val physicalLenses = lenses.filter(ManagerCameraLens::isPhysical)
    if (physicalLenses.isEmpty()) {
        return ManagerOpticalReference(null, null)
    }

    val minimumLogicalZoom = defaultLens?.cameraInfo?.zoomState?.value?.minZoomRatio
        ?.takeIf { zoom -> zoom.isFinite() && zoom in 0.1f..<0.99f }
    if (minimumLogicalZoom != null) {
        val widestOpticalScale = physicalLenses.mapNotNull { lens ->
            lens.opticalScale
        }.minOrNull()
        val widestFocalLength = physicalLenses.mapNotNull { lens ->
            lens.focalLength
        }.minOrNull()
        return ManagerOpticalReference(
            opticalScale = widestOpticalScale?.div(minimumLogicalZoom),
            focalLength = widestFocalLength?.div(minimumLogicalZoom),
        )
    }

    val maximumPixelCount = physicalLenses.maxOfOrNull { lens ->
        lens.photoPixelCounts.maxOrNull() ?: 0L
    } ?: 0L
    val highestResolutionLenses = physicalLenses.filter { lens ->
        (lens.photoPixelCounts.maxOrNull() ?: 0L) == maximumPixelCount
    }
    val mainLens = if (highestResolutionLenses.size == 1) {
        highestResolutionLenses.first()
    } else {
        highestResolutionLenses.sortedBy { lens ->
            lens.opticalScale
                ?: lens.focalLength
                ?: Float.MAX_VALUE
        }[highestResolutionLenses.size / 2]
    }
    return ManagerOpticalReference(
        opticalScale = mainLens.opticalScale,
        focalLength = mainLens.focalLength,
    )
}

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun managerCameraId(cameraInfo: CameraInfo): String? = runCatching {
    Camera2CameraInfo.from(cameraInfo).cameraId
}.getOrNull()

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun managerOpticalScale(cameraInfo: CameraInfo): Float? = runCatching {
    val camera2Info = Camera2CameraInfo.from(cameraInfo)
    val focalLength = managerMinimumFocalLength(cameraInfo)
        ?: return@runCatching null
    val sensorSize = camera2Info
        .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        ?: return@runCatching null
    sensorSize.width.takeIf { width -> width.isFinite() && width > 0f }
        ?.let { width -> focalLength / width }
}.getOrNull()

private fun managerOpticalScale(characteristics: CameraCharacteristics): Float? = runCatching {
    val focalLength = managerMinimumFocalLength(characteristics)
        ?: return@runCatching null
    val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        ?: return@runCatching null
    sensorSize.width.takeIf { width -> width.isFinite() && width > 0f }
        ?.let { width -> focalLength / width }
}.getOrNull()

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun managerMinimumFocalLength(cameraInfo: CameraInfo): Float? = runCatching {
    Camera2CameraInfo.from(cameraInfo)
        .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.filter { focal -> focal.isFinite() && focal > 0f }
        ?.minOrNull()
}.getOrNull()

private fun managerMinimumFocalLength(characteristics: CameraCharacteristics): Float? =
    characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.filter { focal -> focal.isFinite() && focal > 0f }
        ?.minOrNull()

private fun managerPreferredLensForMegapixels(
    lenses: List<ManagerCameraLens>,
    currentLens: ManagerCameraLens,
    requestedMegapixels: Int?,
): ManagerCameraLens {
    val preferredKey = managerPreferredCameraLensKey(
        lenses = lenses.map { lens ->
            ManagerCameraLensProfile(
                key = lens.key,
                displayZoom = lens.displayZoom,
                pixelCounts = lens.photoPixelCounts,
                isDefault = lens.isDefault,
            )
        },
        currentKey = currentLens.key,
        requestedMegapixels = requestedMegapixels,
    )
    return lenses.firstOrNull { lens -> lens.key == preferredKey } ?: currentLens
}

private fun managerVisibleCameraLensOptions(
    availableLenses: List<ManagerCameraLens>,
    selectedLens: ManagerCameraLens,
): List<ManagerCameraLensOption> = availableLenses
    .filter { lens ->
        lens.lensFacing == selectedLens.lensFacing && lens.photoSizes.isNotEmpty()
    }
    .groupBy { lens -> (lens.displayZoom * 10f).roundToInt() }
    .values
    .map { equivalentLenses ->
        val visibleLens = equivalentLenses.firstOrNull { it.key == selectedLens.key }
            ?: equivalentLenses.firstOrNull(ManagerCameraLens::isDefault)
            ?: equivalentLenses.maxBy { lens -> lens.photoPixelCounts.maxOrNull() ?: 0L }
        ManagerCameraLensOption(
            key = visibleLens.key,
            displayZoom = visibleLens.displayZoom,
            maximumMegapixels = managerPhotoMegapixelOptions(visibleLens.photoPixelCounts)
                .firstOrNull(),
        )
    }
    .sortedBy(ManagerCameraLensOption::displayZoom)

private fun managerSupportedFrameRates(cameraInfo: CameraInfo): Set<Int> = runCatching {
    val ranges = cameraInfo.supportedFrameRateRanges
    buildSet {
        add(30)
        if (ranges.any { range -> range.lower <= 60 && range.upper >= 60 }) add(60)
    }
}.getOrDefault(setOf(30))

private fun ProcessCameraProvider.hasManagerCamera(lensFacing: Int): Boolean = runCatching {
    hasCamera(CameraSelector.Builder().requireLensFacing(lensFacing).build())
}.getOrDefault(false)

private fun managerVideoQualityFromCameraX(quality: Quality): ManagerVideoQuality? = when (quality) {
    Quality.UHD -> ManagerVideoQuality.Uhd
    Quality.FHD -> ManagerVideoQuality.Fhd
    Quality.HD -> ManagerVideoQuality.Hd
    else -> null
}

private fun ManagerVideoQuality.toCameraXQuality(): Quality = when (this) {
    ManagerVideoQuality.Uhd -> Quality.UHD
    ManagerVideoQuality.Fhd -> Quality.FHD
    ManagerVideoQuality.Hd -> Quality.HD
}

private enum class ManagerCameraLightMode {
    Off,
    Flash,
    Torch,
    ;

    val imageCaptureFlashMode: Int
        get() = if (this == Flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF

    fun next(mode: ManagerCameraMode): ManagerCameraLightMode = when {
        mode == ManagerCameraMode.Video && this == Off -> Torch
        mode == ManagerCameraMode.Video -> Off
        this == Off -> Flash
        this == Flash -> Torch
        else -> Off
    }
}

private enum class ManagerRecordingState {
    Idle,
    Starting,
    Recording,
    Stopping,
    ;

    val isActive: Boolean
        get() = this != Idle
}

private enum class ManagerSettingsDetail {
    None,
    Exposure,
    Quality,
}

@Composable
private fun ManagerCameraTopBar(
    title: String,
    mode: ManagerCameraMode,
    videoQuality: ManagerVideoQuality,
    videoFps: Int,
    videoHdrActive: Boolean,
    recordingState: ManagerRecordingState,
    recordingDurationMillis: Long,
    lightMode: ManagerCameraLightMode,
    flashAvailable: Boolean,
    lightEnabled: Boolean,
    settingsEnabled: Boolean,
    settingsOpen: Boolean,
    onLightClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(76.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ManagerLightButton(
            mode = lightMode,
            available = flashAvailable,
            enabled = lightEnabled,
            onClick = onLightClick,
        )
        if (mode == ManagerCameraMode.Video && recordingState.isActive) {
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(9.dp)) {
                    drawCircle(ManagerCameraRed)
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "REC ${managerRecordingTimerLabel(recordingDurationMillis)}",
                    color = ManagerCameraRed,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    modifier = Modifier.semantics {
                        contentDescription = "Длительность записи ${managerRecordingTimerLabel(recordingDurationMillis)}"
                    },
                )
            }
        } else {
            Text(
                text = if (mode == ManagerCameraMode.Video) {
                    managerVideoSettingLabel(videoQuality, videoFps, videoHdrActive)
                } else {
                    title
                },
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
        }
        ManagerChevronButton(
            pointsUp = settingsOpen,
            description = if (settingsOpen) "Закрыть настройки камеры" else "Открыть настройки камеры",
            enabled = settingsEnabled,
            onClick = onSettingsClick,
        )
    }
}

@Composable
private fun ManagerCameraBottomControls(
    mode: ManagerCameraMode,
    recordingState: ManagerRecordingState,
    captureInProgress: Boolean,
    lastMedia: String?,
    canSwitchCamera: Boolean,
    onModeSelected: (ManagerCameraMode) -> Unit,
    onThumbnailClick: () -> Unit,
    onShutterClick: () -> Unit,
    onSwitchCamera: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(174.dp)
            .padding(top = 4.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManagerCameraMode.entries.forEach { candidate ->
                Text(
                    text = candidate.label,
                    color = if (candidate == mode) ManagerCameraBlue else Color.White.copy(alpha = 0.72f),
                    fontSize = 15.sp,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            enabled = !recordingState.isActive,
                            role = Role.Tab,
                        ) { onModeSelected(candidate) }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (lastMedia != null) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable(
                                enabled = !recordingState.isActive && !captureInProgress,
                                role = Role.Button,
                                onClick = onThumbnailClick,
                            )
                            .semantics { contentDescription = "Открыть последнее фото или видео" },
                    ) {
                        ManagerPhotoPreview(lastMedia, Modifier.fillMaxSize())
                    }
                }
            }
            ManagerShutterButton(
                mode = mode,
                recordingState = recordingState,
                captureInProgress = captureInProgress,
                onClick = onShutterClick,
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canSwitchCamera && !recordingState.isActive) {
                    ManagerCameraSwitchButton(onClick = onSwitchCamera)
                    Spacer(Modifier.width(8.dp))
                }
                ManagerNextButton(
                    enabled = !recordingState.isActive && !captureInProgress,
                    onClick = onClose,
                )
            }
        }
    }
}

@Composable
private fun ManagerShutterButton(
    mode: ManagerCameraMode,
    recordingState: ManagerRecordingState,
    captureInProgress: Boolean,
    onClick: () -> Unit,
) {
    val video = mode == ManagerCameraMode.Video
    Box(
        modifier = Modifier
            .size(86.dp)
            .clip(CircleShape)
            .clickable(
                enabled = !captureInProgress && recordingState != ManagerRecordingState.Stopping,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = when {
                    recordingState.isActive -> "Остановить запись видео"
                    video -> "Начать запись видео"
                    mode == ManagerCameraMode.Night -> "Снять ночную фотографию"
                    else -> "Снять фотографию"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White,
                style = Stroke(width = 3.dp.toPx()),
                radius = size.minDimension / 2f - 2.dp.toPx(),
            )
            if (recordingState.isActive) {
                drawRoundRect(
                    color = ManagerCameraRed,
                    topLeft = Offset(size.width * 0.34f, size.height * 0.34f),
                    size = ComposeSize(size.width * 0.32f, size.height * 0.32f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                )
            } else {
                drawCircle(
                    color = if (video) ManagerCameraRed else Color.White,
                    radius = size.minDimension * 0.39f,
                )
                if (mode == ManagerCameraMode.Night) {
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
                        drawLine(ManagerCameraBlue, start, end, 1.5.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
        if (captureInProgress) {
            CircularProgressIndicator(
                color = ManagerCameraBlue,
                strokeWidth = 3.dp,
                modifier = Modifier.size(78.dp),
            )
        }
    }
}

@Composable
private fun ManagerZoomControls(
    minimumZoom: Float,
    maximumZoom: Float,
    selectedZoom: Float,
    selectedLensZoom: Float,
    selectedCameraKey: String?,
    cameraLenses: List<ManagerCameraLensOption>,
    manualZoomVisible: Boolean,
    onZoomChanged: (Float) -> Unit,
    onLensSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedDisplayZoom = selectedLensZoom * selectedZoom
    val lensChoices = cameraLenses.map { lens ->
        ManagerZoomChoice(
            cameraKey = lens.key,
            localZoom = null,
            displayZoom = lens.displayZoom,
            maximumMegapixels = lens.maximumMegapixels,
            isLens = true,
        )
    }
    val digitalChoices = managerSupportedZoomStops(
        minimumZoom * selectedLensZoom,
        maximumZoom * selectedLensZoom,
    ).map { displayZoom ->
        ManagerZoomChoice(
            cameraKey = selectedCameraKey,
            localZoom = displayZoom / selectedLensZoom,
            displayZoom = displayZoom,
            maximumMegapixels = null,
            isLens = false,
        )
    }
    val choices = (lensChoices + digitalChoices)
        .groupBy { choice -> (choice.displayZoom * 20f).roundToInt() }
        .values
        .map { equivalentChoices ->
            equivalentChoices.firstOrNull { choice ->
                choice.isLens && choice.cameraKey == selectedCameraKey
            } ?: equivalentChoices.firstOrNull(ManagerZoomChoice::isLens)
                ?: equivalentChoices.first()
        }
        .sortedBy(ManagerZoomChoice::displayZoom)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (manualZoomVisible && maximumZoom - minimumZoom >= 0.1f) {
            Surface(
                color = Color.Black.copy(alpha = 0.58f),
                shape = CircleShape,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        managerZoomLabel(selectedDisplayZoom),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(44.dp),
                    )
                    Slider(
                        value = selectedZoom,
                        onValueChange = onZoomChanged,
                        valueRange = minimumZoom..maximumZoom,
                        modifier = Modifier.width(190.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.52f))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 5.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            choices.forEach { choice ->
                val selected = abs(selectedDisplayZoom - choice.displayZoom) < 0.04f &&
                    if (choice.isLens) {
                        choice.cameraKey == selectedCameraKey && abs(selectedZoom - 1f) < 0.04f
                    } else {
                        choice.cameraKey == selectedCameraKey &&
                            choice.localZoom?.let { zoom -> abs(selectedZoom - zoom) < 0.04f } == true
                    }
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .widthIn(min = 44.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected) {
                                Modifier.background(Color.White.copy(alpha = 0.13f))
                            } else {
                                Modifier
                            },
                        )
                        .clickable(role = Role.Button) {
                            val cameraKey = choice.cameraKey
                            if (choice.isLens && cameraKey != null) {
                                onLensSelected(cameraKey)
                            } else {
                                choice.localZoom?.let(onZoomChanged)
                            }
                        }
                        .padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            managerZoomLabel(choice.displayZoom),
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                        )
                        choice.maximumMegapixels?.takeIf { it >= 40 }?.let { megapixels ->
                            Text(
                                "${megapixels}MP",
                                color = ManagerCameraBlue,
                                fontSize = 8.sp,
                                lineHeight = 8.sp,
                            )
                        }
                    }
                    if (selected) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(Color.White, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}

private data class ManagerZoomChoice(
    val cameraKey: String?,
    val localZoom: Float?,
    val displayZoom: Float,
    val maximumMegapixels: Int?,
    val isLens: Boolean,
)

@Composable
private fun ManagerPhotoSettingsPanel(
    settings: ManagerCameraSettings,
    detail: ManagerSettingsDetail,
    capabilities: ManagerBoundCameraCapabilities,
    lightMode: ManagerCameraLightMode,
    nightMode: Boolean,
    onDetailChanged: (ManagerSettingsDetail) -> Unit,
    onLightClick: () -> Unit,
    onSettingsChanged: (ManagerCameraSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = ManagerCameraPanel,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth().heightIn(max = 300.dp),
    ) {
        when (detail) {
            ManagerSettingsDetail.Exposure -> ManagerExposureSettings(
                settings = settings,
                capabilities = capabilities,
                onBack = { onDetailChanged(ManagerSettingsDetail.None) },
                onSettingsChanged = onSettingsChanged,
            )

            ManagerSettingsDetail.Quality -> ManagerQualitySettings(
                settings = settings,
                capabilities = capabilities,
                onBack = { onDetailChanged(ManagerSettingsDetail.None) },
                onSettingsChanged = onSettingsChanged,
            )

            ManagerSettingsDetail.None -> {
                val selectedMegapixels = settings.requestedMegapixels
                val tiles = listOf(
                    ManagerSettingTileModel(
                        symbol = lightMode.settingsSymbol,
                        lightGlyph = lightMode,
                        title = "Вспышка",
                        value = lightMode.settingsLabel,
                        activeColor = lightMode.settingsColor,
                        enabled = capabilities.hasFlashUnit && !nightMode,
                        onClick = onLightClick,
                    ),
                    ManagerSettingTileModel(
                        symbol = "HDR",
                        title = "HDR",
                        value = when {
                            nightMode && (
                                capabilities.nightExtensionAvailable ||
                                    capabilities.lowLightBoostAvailable
                                ) -> "Авто"
                            nightMode -> "Нет"
                            !capabilities.photoHdrAvailable -> "Нет"
                            settings.ultraHdrEnabled -> "Авто"
                            else -> "Выкл"
                        },
                        activeColor = ManagerCameraBlue.takeIf {
                            if (nightMode) {
                                capabilities.nightExtensionAvailable ||
                                    capabilities.lowLightBoostAvailable
                            } else {
                                settings.ultraHdrEnabled && capabilities.photoHdrAvailable
                            }
                        },
                        enabled = capabilities.photoHdrAvailable && !nightMode,
                        onClick = {
                            onSettingsChanged(settings.copy(ultraHdrEnabled = !settings.ultraHdrEnabled))
                        },
                    ),
                    ManagerSettingTileModel(
                        symbol = "☼",
                        title = "Экспозиция",
                        value = if (settings.exposureEvTenths == 0) {
                            "Авто"
                        } else {
                            String.format(Locale.US, "%+.1f", settings.exposureEvTenths / 10f)
                        },
                        activeColor = ManagerCameraBlue.takeIf { settings.exposureEvTenths != 0 },
                        enabled = capabilities.minimumExposureTenths != capabilities.maximumExposureTenths,
                        onClick = { onDetailChanged(ManagerSettingsDetail.Exposure) },
                    ),
                    ManagerSettingTileModel(
                        symbol = "◉",
                        title = "Движение",
                        value = when {
                            nightMode -> "Авто"
                            settings.motionCaptureEnabled -> "Вкл"
                            else -> "Выкл"
                        },
                        activeColor = ManagerCameraBlue.takeIf {
                            settings.motionCaptureEnabled && !nightMode
                        },
                        enabled = !nightMode,
                        onClick = {
                            onSettingsChanged(
                                settings.copy(motionCaptureEnabled = !settings.motionCaptureEnabled),
                            )
                        },
                    ),
                    ManagerSettingTileModel(
                        symbol = selectedMegapixels?.toString() ?: "A",
                        title = "Качество",
                        value = selectedMegapixels?.let { "${it}MP" } ?: "Авто",
                        onClick = { onDetailChanged(ManagerSettingsDetail.Quality) },
                    ),
                    ManagerSettingTileModel(
                        symbol = "▦",
                        title = "Сетка",
                        value = if (settings.gridEnabled) "Вкл" else "Выкл",
                        activeColor = ManagerCameraBlue.takeIf { settings.gridEnabled },
                        onClick = {
                            onSettingsChanged(settings.copy(gridEnabled = !settings.gridEnabled))
                        },
                    ),
                    ManagerSettingTileModel(
                        symbol = settings.aspectRatio.label,
                        title = "Кадр",
                        value = settings.aspectRatio.label,
                        activeColor = ManagerCameraBlue,
                        onClick = {
                            onSettingsChanged(settings.copy(aspectRatio = settings.aspectRatio.next()))
                        },
                    ),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(tiles) { tile -> ManagerCameraSettingTile(tile) }
                }
            }
        }
    }
}

@Composable
private fun ManagerVideoSettingsPanel(
    settings: ManagerCameraSettings,
    capabilities: ManagerBoundCameraCapabilities,
    onSettingsChanged: (ManagerCameraSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = ManagerCameraPanel,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Качество видео", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagerVideoQuality.entries.forEach { quality ->
                    val enabled = quality in capabilities.videoQualities
                    CameraChoiceChip(
                        text = quality.label,
                        selected = settings.videoQuality == quality,
                        enabled = enabled,
                        onClick = { onSettingsChanged(settings.copy(videoQuality = quality)) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Частота кадров", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60).forEach { fps ->
                    CameraChoiceChip(
                        text = "$fps FPS",
                        selected = settings.videoFramesPerSecond == fps,
                        enabled = fps in capabilities.videoFramesPerSecond,
                        onClick = {
                            onSettingsChanged(
                                settings.copy(
                                    videoFramesPerSecond = fps,
                                    videoHdrEnabled = settings.videoHdrEnabled && fps <= 30,
                                ),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraChoiceChip(
                    text = if (settings.videoHdrEnabled) "HDR вкл" else "HDR выкл",
                    selected = settings.videoHdrEnabled,
                    enabled = capabilities.videoHdrAvailable && settings.videoFramesPerSecond <= 30,
                    onClick = {
                        onSettingsChanged(settings.copy(videoHdrEnabled = !settings.videoHdrEnabled))
                    },
                )
                CameraChoiceChip(
                    text = if (settings.motionCaptureEnabled) "Стабилизация вкл" else "Стабилизация",
                    selected = settings.motionCaptureEnabled,
                    enabled = capabilities.videoStabilizationAvailable,
                    onClick = {
                        onSettingsChanged(
                            settings.copy(motionCaptureEnabled = !settings.motionCaptureEnabled),
                        )
                    },
                )
            }
        }
    }
}

private data class ManagerSettingTileModel(
    val symbol: String,
    val title: String,
    val value: String,
    val lightGlyph: ManagerCameraLightMode? = null,
    val activeColor: Color? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun ManagerCameraSettingTile(tile: ManagerSettingTileModel) {
    val contentColor = when {
        !tile.enabled -> Color.White.copy(alpha = 0.28f)
        tile.activeColor != null -> tile.activeColor
        else -> Color.White
    }
    Column(
        modifier = Modifier
            .height(108.dp)
            .clickable(enabled = tile.enabled, role = Role.Button, onClick = tile.onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val lightGlyph = tile.lightGlyph
        if (lightGlyph == null) {
            Text(
                tile.symbol,
                color = contentColor,
                fontWeight = FontWeight.Bold,
                fontSize = if (tile.symbol.length > 4) 13.sp else 20.sp,
                maxLines = 1,
            )
        } else if (lightGlyph == ManagerCameraLightMode.Torch) {
            ManagerTorchGlyph(contentColor, Modifier.size(24.dp))
        } else {
            ManagerFlashGlyph(
                color = contentColor,
                crossed = lightGlyph == ManagerCameraLightMode.Off,
                modifier = Modifier.size(25.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(tile.title, color = Color.White.copy(alpha = if (tile.enabled) 0.9f else 0.3f), fontSize = 12.sp)
        Text(tile.value, color = contentColor.copy(alpha = 0.92f), fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun ManagerExposureSettings(
    settings: ManagerCameraSettings,
    capabilities: ManagerBoundCameraCapabilities,
    onBack: () -> Unit,
    onSettingsChanged: (ManagerCameraSettings) -> Unit,
) {
    val minimum = capabilities.minimumExposureTenths.coerceAtMost(-1)
    val maximum = capabilities.maximumExposureTenths.coerceAtLeast(1)
    Column(modifier = Modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", color = Color.White, fontSize = 28.sp) }
            Text("Экспозиция", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onSettingsChanged(settings.copy(exposureEvTenths = 0)) },
            ) { Text("Авто", color = ManagerCameraBlue) }
        }
        Text(
            String.format(Locale.US, "%+.1f EV", settings.exposureEvTenths / 10f),
            color = Color.White,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Slider(
            value = settings.exposureEvTenths.toFloat().coerceIn(minimum.toFloat(), maximum.toFloat()),
            onValueChange = { value ->
                onSettingsChanged(settings.copy(exposureEvTenths = value.roundToInt()))
            },
            valueRange = minimum.toFloat()..maximum.toFloat(),
            steps = (maximum - minimum - 1).coerceAtLeast(0),
        )
        Text(
            "Автоэкспозиция продолжает работать; ползунок задаёт только поправку яркости.",
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ManagerQualitySettings(
    settings: ManagerCameraSettings,
    capabilities: ManagerBoundCameraCapabilities,
    onBack: () -> Unit,
    onSettingsChanged: (ManagerCameraSettings) -> Unit,
) {
    Column(modifier = Modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", color = Color.White, fontSize = 28.sp) }
            Text("Качество фотографии", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Text("Мегапиксели", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraChoiceChip(
                text = "Авто",
                selected = settings.requestedMegapixels == null,
                onClick = { onSettingsChanged(settings.copy(requestedMegapixels = null)) },
            )
            capabilities.photoMegapixels.take(4).forEach { megapixels ->
                CameraChoiceChip(
                    text = "${megapixels}MP",
                    selected = settings.requestedMegapixels == megapixels,
                    onClick = {
                        onSettingsChanged(
                            settings.copy(
                                requestedMegapixels = megapixels,
                                aspectRatio = if (megapixels >= 40) {
                                    ManagerCameraAspectRatio.FourThree
                                } else {
                                    settings.aspectRatio
                                },
                            ),
                        )
                    },
                )
            }
        }
        if (capabilities.photoMegapixels.any { it >= 40 }) {
            Text(
                "Авто выбирает около 12 MP для быстрого снимка. Полное разрешение 40+ MP " +
                    "снимается в формате 4:3 и может сохраняться дольше.",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CameraChoiceChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) ManagerCameraBlue else Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.05f),
            disabledContentColor = Color.White.copy(alpha = 0.3f),
        ),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
    ) {
        Text(text, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ManagerCameraGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.42f)
        drawLine(color, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), 1f)
        drawLine(color, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), 1f)
        drawLine(color, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), 1f)
        drawLine(color, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), 1f)
    }
}

@Composable
private fun ManagerFocusIndicator(point: Offset) {
    val sizeDp = 62.dp
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }
    Canvas(
        modifier = Modifier
            .offset {
                IntOffset(
                    (point.x - sizePx / 2f).roundToInt(),
                    (point.y - sizePx / 2f).roundToInt(),
                )
            }
            .size(sizeDp),
    ) {
        val corner = size.minDimension * 0.27f
        val stroke = 1.6.dp.toPx()
        listOf(
            Offset(0f, 0f) to listOf(Offset(corner, 0f), Offset(0f, corner)),
            Offset(size.width, 0f) to listOf(Offset(size.width - corner, 0f), Offset(size.width, corner)),
            Offset(0f, size.height) to listOf(Offset(corner, size.height), Offset(0f, size.height - corner)),
            Offset(size.width, size.height) to listOf(
                Offset(size.width - corner, size.height),
                Offset(size.width, size.height - corner),
            ),
        ).forEach { (origin, ends) ->
            ends.forEach { end -> drawLine(ManagerCameraBlue, origin, end, stroke, StrokeCap.Round) }
        }
        drawCircle(ManagerCameraBlue, radius = 3.dp.toPx(), center = center)
    }
}

@Composable
private fun ManagerLightButton(
    mode: ManagerCameraLightMode,
    available: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = when {
        !available -> Color.White.copy(alpha = 0.25f)
        !enabled -> Color.White.copy(alpha = 0.42f)
        mode == ManagerCameraLightMode.Flash -> ManagerCameraBlue
        mode == ManagerCameraLightMode.Torch -> ManagerCameraRed
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = when (mode) {
                    ManagerCameraLightMode.Off -> "Вспышка выключена"
                    ManagerCameraLightMode.Flash -> "Вспышка включена"
                    ManagerCameraLightMode.Torch -> "Фонарик включён"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (mode == ManagerCameraLightMode.Torch) {
            ManagerTorchGlyph(color, Modifier.size(27.dp))
        } else {
            ManagerFlashGlyph(
                color = color,
                crossed = mode == ManagerCameraLightMode.Off,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun ManagerFlashGlyph(color: Color, crossed: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.58f, 0f)
            lineTo(size.width * 0.18f, size.height * 0.56f)
            lineTo(size.width * 0.45f, size.height * 0.56f)
            lineTo(size.width * 0.34f, size.height)
            lineTo(size.width * 0.83f, size.height * 0.39f)
            lineTo(size.width * 0.56f, size.height * 0.39f)
            close()
        }
        drawPath(path, color = color)
        if (crossed) {
            drawLine(
                color,
                Offset(size.width * 0.08f, size.height * 0.1f),
                Offset(size.width * 0.9f, size.height * 0.92f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ManagerTorchGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.2f, 0f)
            lineTo(size.width * 0.8f, 0f)
            lineTo(size.width * 0.72f, size.height * 0.25f)
            lineTo(size.width * 0.64f, size.height * 0.34f)
            lineTo(size.width * 0.64f, size.height)
            lineTo(size.width * 0.36f, size.height)
            lineTo(size.width * 0.36f, size.height * 0.34f)
            lineTo(size.width * 0.28f, size.height * 0.25f)
            close()
        }
        drawPath(path, color)
        drawCircle(Color.Black.copy(alpha = 0.42f), radius = size.width * 0.08f, center = Offset(center.x, size.height * 0.55f))
    }
}

@Composable
private fun ManagerChevronButton(
    pointsUp: Boolean,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val left = if (pointsUp) Offset(2f, size.height * 0.68f) else Offset(2f, size.height * 0.32f)
            val middle = if (pointsUp) Offset(size.width / 2f, size.height * 0.3f) else Offset(size.width / 2f, size.height * 0.7f)
            val right = if (pointsUp) Offset(size.width - 2f, size.height * 0.68f) else Offset(size.width - 2f, size.height * 0.32f)
            val color = Color.White.copy(alpha = if (enabled) 1f else 0.35f)
            drawLine(color, left, middle, 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, middle, right, 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun ManagerCameraSwitchButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Переключить камеру" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(27.dp)) {
            val stroke = 1.8.dp.toPx()
            drawArc(
                Color.White,
                startAngle = 205f,
                sweepAngle = 205f,
                useCenter = false,
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = ComposeSize(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                Color.White,
                startAngle = 25f,
                sweepAngle = 205f,
                useCenter = false,
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = ComposeSize(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawCircle(Color.White, radius = 3.dp.toPx(), center = center)
        }
    }
}

@Composable
private fun ManagerNextButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Продолжить" },
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

private val ManagerCameraLightMode.settingsLabel: String
    get() = when (this) {
        ManagerCameraLightMode.Off -> "Выкл"
        ManagerCameraLightMode.Flash -> "Вспышка"
        ManagerCameraLightMode.Torch -> "Фонарик"
    }

private val ManagerCameraLightMode.settingsSymbol: String
    get() = when (this) {
        ManagerCameraLightMode.Off -> "⚡̸"
        ManagerCameraLightMode.Flash -> "⚡"
        ManagerCameraLightMode.Torch -> "▰"
    }

private val ManagerCameraLightMode.settingsColor: Color?
    get() = when (this) {
        ManagerCameraLightMode.Off -> null
        ManagerCameraLightMode.Flash -> ManagerCameraBlue
        ManagerCameraLightMode.Torch -> ManagerCameraRed
    }

private fun captureManagerCameraPhoto(
    imageCapture: ImageCapture,
    targetRotation: Int,
    cacheDir: File,
    ioExecutor: Executor,
    mainExecutor: Executor,
    onCaptured: (String, Boolean) -> Unit,
    onError: () -> Unit,
) {
    // File output lets a vendor choose whether to rotate the pixels or only write EXIF. Some
    // devices incorrectly write Orientation=1 while leaving the JPEG buffer sideways. The in-memory
    // callback gives CameraX's explicit rotation for the unrotated JPEG, so normalize from that
    // authoritative value instead of guessing from vendor EXIF.
    imageCapture.targetRotation = targetRotation
    imageCapture.takePicture(
        ioExecutor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val capturedFile = createManagerCameraMediaFile(cacheDir, "capture", "jpg")
                val normalized = if (capturedFile == null) {
                    image.close()
                    null
                } else {
                    persistManagerCameraImageProxy(image, capturedFile)
                }
                if (normalized != null && capturedFile != null) {
                    if (normalized.file != capturedFile && !capturedFile.delete()) {
                        Log.w(
                            "ManagerCamera",
                            "Unable to remove pre-normalized capture ${capturedFile.name}",
                        )
                    }
                    mainExecutor.execute {
                        onCaptured(
                            Uri.fromFile(normalized.file).toString(),
                            normalized.resolutionReduced,
                        )
                    }
                } else {
                    capturedFile?.delete()
                    mainExecutor.execute { onError() }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("ManagerCamera", "Unable to capture photo", exception)
                mainExecutor.execute { onError() }
            }
        },
    )
}

/**
 * Persists one in-memory CameraX JPEG and applies the authoritative clockwise transform carried
 * by [ImageProxy.getImageInfo]. The proxy is always closed, including unsupported or malformed
 * output, so a failed normalization cannot stall the camera pipeline.
 */
internal fun persistManagerCameraImageProxy(
    image: ImageProxy,
    target: File,
): ManagerNormalizedCameraPhoto? = try {
    val rotationDegrees = image.imageInfo.rotationDegrees
    writeManagerCameraJpeg(image, target)
    normalizeManagerCameraCapturedJpegOrientation(
        source = target,
        rotationDegrees = rotationDegrees,
    )
} catch (failure: Exception) {
    Log.e("ManagerCamera", "Unable to persist captured JPEG", failure)
    null
} finally {
    image.close()
}

/** Writes the unrotated standard-JPEG buffer returned by CameraX without depending on EXIF. */
private fun writeManagerCameraJpeg(image: ImageProxy, target: File) {
    check(image.format == ImageFormat.JPEG) {
        "CameraX returned image format ${image.format}, expected JPEG"
    }
    val jpegBuffer = image.planes.singleOrNull()?.buffer?.duplicate()
        ?: error("CameraX returned a JPEG without exactly one plane")
    check(jpegBuffer.remaining() >= 4) { "CameraX returned a truncated JPEG" }
    val firstByte = jpegBuffer.get(jpegBuffer.position())
    val secondByte = jpegBuffer.get(jpegBuffer.position() + 1)
    val penultimateByte = jpegBuffer.get(jpegBuffer.limit() - 2)
    val lastByte = jpegBuffer.get(jpegBuffer.limit() - 1)
    check(
        firstByte == 0xff.toByte() &&
            secondByte == 0xd8.toByte() &&
            penultimateByte == 0xff.toByte() &&
            lastByte == 0xd9.toByte()
    ) { "CameraX returned malformed JPEG bytes" }
    FileOutputStream(target).channel.use { channel ->
        while (jpegBuffer.hasRemaining()) channel.write(jpegBuffer)
    }
    check(target.length() > 0L) { "CameraX returned an empty JPEG" }
}

/**
 * Produces an app-owned JPEG whose pixels already have the orientation declared by the camera.
 * A normal source stays in place; transformed sources are written beside it so a failed rewrite
 * never destroys the captured original. The caller removes that original only after success.
 */
internal fun normalizeManagerCameraJpegOrientation(
    source: File,
    fallbackOrientation: Int,
): File? = normalizeManagerCameraPhotoOrientation(
    source = source,
    fallbackOrientation = fallbackOrientation,
    trustRecordedExif = true,
)?.file

/**
 * CameraX delivers unrotated JPEG pixels with the exact clockwise transform in [rotationDegrees].
 * That value must win over a vendor's file EXIF, which is precisely what protects landscape
 * captures from HALs that report `Orientation=1` incorrectly.
 */
internal fun normalizeManagerCameraCapturedJpegOrientation(
    source: File,
    rotationDegrees: Int,
): ManagerNormalizedCameraPhoto? = normalizeManagerCameraPhotoOrientation(
    source = source,
    fallbackOrientation = managerExifOrientationForRotationDegrees(rotationDegrees),
    trustRecordedExif = false,
)

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal data class ManagerNormalizedCameraPhoto(
    val file: File,
    val resolutionReduced: Boolean,
)

private fun normalizeManagerCameraPhotoOrientation(
    source: File,
    fallbackOrientation: Int,
    trustRecordedExif: Boolean,
): ManagerNormalizedCameraPhoto? = try {
    val sourceExif = ExifInterface(source)
    val recordedOrientation = sourceExif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_UNDEFINED,
    )
    val orientation = if (trustRecordedExif) {
        managerResolvedCameraExifOrientation(
            recordedOrientation = recordedOrientation,
            fallbackOrientation = fallbackOrientation,
        )
    } else {
        fallbackOrientation.takeIf { !managerExifOrientationNeedsRepair(it) }
            ?: ExifInterface.ORIENTATION_NORMAL
    }
    if (orientation == ExifInterface.ORIENTATION_NORMAL) {
        // The source pixels already match the requested target rotation. Still persist the
        // explicit normal value because several devices use Orientation=0 instead of omitting
        // the tag.
        if (recordedOrientation != ExifInterface.ORIENTATION_NORMAL) {
            managerWriteCameraExifOrientation(source, ExifInterface.ORIENTATION_NORMAL)
        }
        ManagerNormalizedCameraPhoto(source, resolutionReduced = false)
    } else {
        val decodeInput = File.createTempFile(
            "decode-unoriented-",
            ".image",
            requireNotNull(source.parentFile) { "Capture file has no parent directory" },
        )
        try {
            source.inputStream().use { input ->
                decodeInput.outputStream().use { output -> input.copyTo(output) }
            }
            // BitmapFactory implementations differ in whether they honor source EXIF. Decode a
            // disposable copy with Orientation=1, then apply the source transform exactly once.
            managerWriteCameraExifOrientation(decodeInput, ExifInterface.ORIENTATION_NORMAL)
            val decodePlan = managerCameraOrientationDecodePlan(decodeInput)
            val sourceBitmap = BitmapFactory.decodeFile(decodeInput.path, decodePlan.options)
                ?: error("Unable to decode JPEG")
            try {
                val orientedBitmap = Bitmap.createBitmap(
                    sourceBitmap,
                    0,
                    0,
                    sourceBitmap.width,
                    sourceBitmap.height,
                    managerExifOrientationMatrix(orientation),
                    true,
                )
                try {
                    val parent = requireNotNull(source.parentFile) {
                        "Capture file has no parent directory"
                    }
                    val output = File.createTempFile("upright-", ".jpg", parent)
                    var outputReady = false
                    try {
                        output.outputStream().buffered().use { stream ->
                            check(
                                orientedBitmap.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    MANAGER_NORMALIZED_JPEG_QUALITY,
                                    stream,
                                ),
                            ) { "Unable to encode normalized JPEG" }
                        }
                        managerWriteCameraExifOrientation(output, ExifInterface.ORIENTATION_NORMAL)
                        outputReady = true
                        ManagerNormalizedCameraPhoto(
                            file = output,
                            resolutionReduced = decodePlan.resolutionReduced,
                        )
                    } finally {
                        if (!outputReady) output.delete()
                    }
                } finally {
                    if (orientedBitmap !== sourceBitmap) orientedBitmap.recycle()
                }
            } finally {
                sourceBitmap.recycle()
            }
        } finally {
            decodeInput.delete()
        }
    }
} catch (failure: Exception) {
    Log.e("ManagerCamera", "Unable to normalize capture orientation", failure)
    null
}

internal fun managerResolvedCameraExifOrientation(
    recordedOrientation: Int,
    fallbackOrientation: Int,
): Int = when {
    !managerExifOrientationNeedsRepair(recordedOrientation) -> recordedOrientation
    !managerExifOrientationNeedsRepair(fallbackOrientation) -> fallbackOrientation
    else -> ExifInterface.ORIENTATION_NORMAL
}

internal fun managerExifOrientationMatrix(orientation: Int): Matrix = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            setRotate(180f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            setRotate(90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            setRotate(-90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        else -> Unit
    }
}

private data class ManagerCameraOrientationDecodePlan(
    val options: BitmapFactory.Options,
    val resolutionReduced: Boolean,
)

/**
 * Rotation needs both the decoded source and a destination bitmap at once. Cap each bitmap at
 * roughly 13 MP (about 52 MB ARGB_8888) so a selected 48–50 MP photo cannot exhaust the app
 * heap while it is being made upload-safe. CameraX captures below this limit retain full size.
 */
private fun managerCameraOrientationDecodePlan(file: File): ManagerCameraOrientationDecodePlan {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    check(width > 0 && height > 0) { "Unable to read JPEG dimensions" }
    var sampleSize = 1
    while (managerSampledPixelCount(width, height, sampleSize) >
        MANAGER_MAX_ORIENTATION_NORMALIZATION_PIXELS
    ) {
        sampleSize *= 2
    }
    return ManagerCameraOrientationDecodePlan(
        options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
        resolutionReduced = sampleSize > 1,
    )
}

private fun managerSampledPixelCount(width: Int, height: Int, sampleSize: Int): Long {
    val sampledWidth = (width.toLong() + sampleSize - 1L) / sampleSize
    val sampledHeight = (height.toLong() + sampleSize - 1L) / sampleSize
    return sampledWidth * sampledHeight
}

private fun managerWriteCameraExifOrientation(file: File, orientation: Int) {
    ExifInterface(file).run {
        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        saveAttributes()
    }
    val persisted = ExifInterface(file).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_UNDEFINED,
    )
    check(persisted == orientation) { "EXIF orientation was not persisted" }
}

private const val MANAGER_NORMALIZED_JPEG_QUALITY = 95
private const val MANAGER_MAX_ORIENTATION_NORMALIZATION_PIXELS = 13_000_000L

private fun createManagerCameraMediaFile(
    cacheDir: File,
    prefix: String,
    extension: String,
): File? = runCatching {
    val directory = File(cacheDir, "manager-photos").apply { mkdirs() }
    File(directory, "$prefix-${UUID.randomUUID()}.$extension").apply { createNewFile() }
}.getOrNull()

private fun playManagerCameraFeedback(context: Context, shutterSound: MediaActionSound) {
    runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return@runCatching
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(45L)
        }
    }
}
