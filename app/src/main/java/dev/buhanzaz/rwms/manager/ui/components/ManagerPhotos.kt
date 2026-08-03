package dev.buhanzaz.rwms.manager.ui.components

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Surface as AndroidSurface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.roundToInt

/** Full-screen camera route. Picking an existing image stays on the preceding photo step. */
@Composable
fun ManagerPhotoCaptureScreen(
    title: String,
    photoUris: List<String>,
    onPhotoCaptured: (String) -> Unit,
    onRemovePhotoUri: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var audioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        cameraPermissionGranted = permissions[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        audioPermissionGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    BackHandler(onBack = onBack)

    if (cameraPermissionGranted) {
        ManagerPhotoCamera(
            title = title,
            photoUris = photoUris,
            onPhotoCaptured = onPhotoCaptured,
            onRemovePhotoUri = onRemovePhotoUri,
            audioPermissionGranted = audioPermissionGranted,
            onClose = onBack,
        )
    } else {
        CameraPermissionScreen(
            title = title,
            onRequestPermission = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                    ),
                )
            },
            onBack = onBack,
        )
    }
}

@Composable
private fun CameraPermissionScreen(
    title: String,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "Разрешите доступ к камере, чтобы снять фото осмотра.",
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequestPermission) { Text("Разрешить камеру") }
        TextButton(onClick = onBack) { Text("Назад", color = Color.White) }
    }
}

@Composable
private fun ManagerPhotoCamera(
    title: String,
    photoUris: List<String>,
    onPhotoCaptured: (String) -> Unit,
    onRemovePhotoUri: (String) -> Unit,
    audioPermissionGranted: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var videoState by remember { mutableStateOf(ManagerVideoCaptureState.Idle) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var selectedZoom by remember { mutableFloatStateOf(1f) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    var lightMode by remember { mutableStateOf(ManagerPhotoLightMode.Off) }
    var galleryStartIndex by remember { mutableStateOf<Int?>(null) }
    val shutterSound = remember {
        MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    }
    val lastPhoto = photoUris.lastOrNull()

    DisposableEffect(shutterSound) {
        onDispose { shutterSound.release() }
    }

    DisposableEffect(lifecycleOwner, previewView, lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var disposed = false
        cameraProviderFuture.addListener(
            {
                if (disposed) return@addListener
                val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrElse {
                    Log.e("ManagerPhotoCamera", "Unable to obtain camera provider", it)
                    return@addListener
                }
                val backAvailable = cameraProvider.hasManagerLens(CameraSelector.LENS_FACING_BACK)
                val frontAvailable = cameraProvider.hasManagerLens(CameraSelector.LENS_FACING_FRONT)
                val boundLensFacing = when {
                    cameraProvider.hasManagerLens(lensFacing) -> lensFacing
                    backAvailable -> CameraSelector.LENS_FACING_BACK
                    frontAvailable -> CameraSelector.LENS_FACING_FRONT
                    else -> {
                        Log.e("ManagerPhotoCamera", "No supported camera lens is available")
                        return@addListener
                    }
                }
                if (boundLensFacing != lensFacing) lensFacing = boundLensFacing
                canSwitchCamera = backAvailable && frontAvailable
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                    .apply { flashMode = lightMode.toFlashMode() }
                val recorder = Recorder.Builder().build()
                val video = VideoCapture.withOutput(recorder)

                var videoBound = false
                val boundCamera = runCatching {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.Builder().requireLensFacing(boundLensFacing).build(),
                        preview,
                        capture,
                        video,
                    )
                        .also { videoBound = true }
                }.recoverCatching { videoFailure ->
                    // Some devices cannot bind photo and video use cases together. Keep photo
                    // capture usable instead of offering a recording that cannot be saved.
                    Log.w("ManagerPhotoCamera", "Video capture is unavailable for this lens", videoFailure)
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.Builder().requireLensFacing(boundLensFacing).build(),
                        preview,
                        capture,
                    )
                }.getOrElse {
                    Log.e("ManagerPhotoCamera", "Unable to bind photo camera", it)
                    null
                }
                if (disposed || boundCamera == null) return@addListener
                imageCapture = capture
                videoCapture = video.takeIf { videoBound }
                camera = boundCamera
                boundCamera.cameraInfo.zoomState.value?.let { zoomState ->
                    val normalizedZoom = managerCoerceZoom(
                        selectedZoom,
                        zoomState.minZoomRatio,
                        zoomState.maxZoomRatio,
                    )
                    selectedZoom = normalizedZoom
                    boundCamera.cameraControl.setZoomRatio(normalizedZoom)
                }
                if (boundCamera.cameraInfo.hasFlashUnit()) {
                    boundCamera.cameraControl.enableTorch(lightMode == ManagerPhotoLightMode.Torch)
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed = true
            runCatching {
                camera?.cameraControl?.enableTorch(false)
                activeRecording?.stop()
                if (cameraProviderFuture.isDone) cameraProviderFuture.get().unbindAll()
            }
            activeRecording = null
            videoState = ManagerVideoCaptureState.Idle
            camera = null
            imageCapture = null
            videoCapture = null
        }
    }

    val takePhotoAction: State<() -> Unit> = rememberUpdatedState(
        newValue = {
            if (videoState == ManagerVideoCaptureState.Idle) {
                playCaptureFeedback(context, shutterSound)
                captureDirectPhoto(
                    imageCapture = imageCapture,
                    previewView = previewView,
                    cacheDir = context.cacheDir,
                    mainExecutor = mainExecutor,
                    onCaptured = onPhotoCaptured,
                )
            }
        },
    )
    val startVideoAction: State<() -> Unit> = rememberUpdatedState(
        newValue = startVideo@{
            if (videoState != ManagerVideoCaptureState.Idle || activeRecording != null) {
                return@startVideo
            }
            val capture = videoCapture ?: run {
                Log.w("ManagerPhotoCamera", "Video capture was requested without a bound video use case")
                return@startVideo
            }
            val file = createManagerMediaFile(context.cacheDir, prefix = "video", extension = "mp4")
                ?: return@startVideo
            val outputOptions = FileOutputOptions.Builder(file).build()
            var pendingRecording = capture.output.prepareRecording(context, outputOptions)
            if (audioPermissionGranted &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                // PendingRecording is immutable: retain the audio-enabled instance.
                pendingRecording = try {
                    pendingRecording.withAudioEnabled()
                } catch (security: SecurityException) {
                    // Permission can still be revoked between the check and CameraX call.
                    Log.w("ManagerPhotoCamera", "Audio permission was revoked", security)
                    pendingRecording
                }
            }
            videoState = ManagerVideoCaptureState.Starting
            activeRecording = pendingRecording.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        if (videoState == ManagerVideoCaptureState.Starting) {
                            videoState = ManagerVideoCaptureState.Recording
                        }
                    }

                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        videoState = ManagerVideoCaptureState.Idle
                        if (event.hasError()) {
                            file.delete()
                            Log.e(
                                "ManagerPhotoCamera",
                                "Unable to finalize video (${event.error})",
                            )
                        } else {
                            // The app-owned MP4 is verified by MediaUploader before upload.
                            onPhotoCaptured(Uri.fromFile(file).toString())
                            playCaptureFeedback(context, shutterSound)
                        }
                    }

                    else -> Unit
                }
            }
        },
    )
    val lockVideoAction: State<() -> Unit> = rememberUpdatedState(
        newValue = {
            if (activeRecording != null && videoState.isVideoActive) {
                videoState = ManagerVideoCaptureState.Locked
            }
        },
    )
    val stopVideoAction: State<() -> Unit> = rememberUpdatedState(
        newValue = {
            val recording = activeRecording
            if (recording != null && videoState.isVideoActive) {
                videoState = ManagerVideoCaptureState.Stopping
                recording.stop()
            }
        },
    )
    val captureState = rememberUpdatedState(videoState)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(108.dp),
        ) {
            ManagerLightToggleButton(
                lightMode = lightMode,
                onClick = {
                    val next = lightMode.next()
                    lightMode = next
                    imageCapture?.flashMode = next.toFlashMode()
                    camera?.cameraControl?.enableTorch(next == ManagerPhotoLightMode.Torch)
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp, bottom = 12.dp),
            )
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 26.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            ManagerZoomSelector(
                camera = camera,
                selectedZoom = selectedZoom,
                onZoomSelected = { zoom ->
                    selectedZoom = zoom
                    camera?.cameraControl?.setZoomRatio(zoom)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .padding(start = 28.dp, end = 28.dp, bottom = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lastPhoto != null) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray)
                        .clickable { galleryStartIndex = photoUris.lastIndex },
                ) {
                    if (isManagerVideoUri(lastPhoto)) {
                        Text("▶", color = Color.White, fontSize = 24.sp)
                    } else {
                        ManagerPhotoPreview(lastPhoto, Modifier.fillMaxSize())
                    }
                }
            } else {
                Spacer(Modifier.size(64.dp))
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .managerCaptureGesture(
                        videoState = captureState,
                        onTakePhoto = takePhotoAction,
                        onStartVideo = startVideoAction,
                        onLockVideo = lockVideoAction,
                        onStopVideo = stopVideoAction,
                    )
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = if (videoState.isVideoActive) {
                            "Остановить запись видео"
                        } else {
                            "Снять фото. Удерживайте кнопку для записи видео"
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (videoState.isVideoActive) {
                    Box(
                        modifier = Modifier
                            .size(if (videoState == ManagerVideoCaptureState.Locked) 34.dp else 28.dp)
                            .clip(
                                if (videoState == ManagerVideoCaptureState.Locked) {
                                    RoundedCornerShape(8.dp)
                                } else {
                                    CircleShape
                                },
                            )
                            .background(Color(0xFFB3261E)),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canSwitchCamera && !videoState.isVideoActive) {
                    CameraRoundControl(
                        label = "⇄",
                        description = "Переключить камеру",
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                    )
                }
                CameraRoundControl(
                    label = "→",
                    description = "Выйти из камеры",
                    onClick = onClose,
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

@Composable
private fun ManagerZoomSelector(
    camera: Camera?,
    selectedZoom: Float,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoomState = camera?.cameraInfo?.zoomState?.value
    val minZoom = zoomState?.minZoomRatio ?: 1f
    val maxZoom = zoomState?.maxZoomRatio ?: 1f
    val zoomStops = managerSupportedZoomStops(minZoom, maxZoom)
    if (zoomStops.size < 2) return
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        zoomStops.forEach { zoom ->
            FilledTonalButton(
                onClick = { onZoomSelected(zoom) },
                shape = CircleShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (abs(selectedZoom - zoom) < 0.01f) {
                        Color.White
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (abs(selectedZoom - zoom) < 0.01f) Color.Black else Color.White,
                ),
                modifier = Modifier.size(48.dp),
            ) { Text(managerZoomLabel(zoom)) }
        }
    }
}

/** Only show zoom positions exposed by the currently bound CameraInfo range. */
internal fun managerSupportedZoomStops(minZoom: Float, maxZoom: Float): List<Float> {
    val minimum = normalizedManagerZoomMinimum(minZoom)
    val maximum = normalizedManagerZoomMaximum(minimum, maxZoom)
    val stops = mutableListOf(minimum, maximum)
    var lowerStop = 1f
    while (lowerStop >= minimum) {
        if (lowerStop <= maximum) stops += lowerStop
        lowerStop /= 2f
    }
    var upperStop = 2f
    while (upperStop <= maximum) {
        if (upperStop >= minimum) stops += upperStop
        upperStop *= 2f
    }
    return stops
        .distinctBy { (it * 1_000f).roundToInt() }
        .sorted()
}

internal fun managerCoerceZoom(requestedZoom: Float, minZoom: Float, maxZoom: Float): Float {
    val minimum = normalizedManagerZoomMinimum(minZoom)
    val maximum = normalizedManagerZoomMaximum(minimum, maxZoom)
    return requestedZoom
        .takeIf { it.isFinite() }
        ?.coerceIn(minimum, maximum)
        ?: minimum
}

private fun normalizedManagerZoomMinimum(value: Float): Float =
    value.takeIf { it.isFinite() && it > 0f } ?: 1f

private fun normalizedManagerZoomMaximum(minimum: Float, value: Float): Float =
    value.takeIf { it.isFinite() && it >= minimum } ?: minimum

private fun managerZoomLabel(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.01f) {
        "${value.roundToInt()}×"
    } else {
        String.format(Locale.US, "%.1f×", value)
    }

private fun ProcessCameraProvider.hasManagerLens(lensFacing: Int): Boolean = runCatching {
    hasCamera(CameraSelector.Builder().requireLensFacing(lensFacing).build())
}.getOrDefault(false)

private enum class ManagerVideoCaptureState {
    Idle,
    Starting,
    Recording,
    Locked,
    Stopping,
    ;

    val isVideoActive: Boolean
        get() = this != Idle
}

/**
 * A tap takes a photo. Holding the same shutter begins recording; swiping up while
 * still holding it locks the recording, and a later tap ends it.
 */
private fun Modifier.managerCaptureGesture(
    videoState: State<ManagerVideoCaptureState>,
    onTakePhoto: State<() -> Unit>,
    onStartVideo: State<() -> Unit>,
    onLockVideo: State<() -> Unit>,
    onStopVideo: State<() -> Unit>,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (videoState.value.isVideoActive) {
            if (waitForUpOrCancellation() != null) onStopVideo.value.invoke()
            return@awaitEachGesture
        }
        val releasedBeforeVideo = try {
            withTimeout(MANAGER_VIDEO_HOLD_MILLIS) { waitForUpOrCancellation() }
        } catch (_: TimeoutCancellationException) {
            null
        }
        if (releasedBeforeVideo != null) {
            onTakePhoto.value.invoke()
            return@awaitEachGesture
        }

        onStartVideo.value.invoke()
        var locked = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
            if (!locked &&
                change.position.y <= down.position.y - MANAGER_VIDEO_LOCK_DISTANCE_DP.dp.toPx()
            ) {
                locked = true
                onLockVideo.value.invoke()
            }
            if (change.changedToUpIgnoreConsumed()) {
                if (!locked) onStopVideo.value.invoke()
                break
            }
        }
    }
}

private const val MANAGER_VIDEO_HOLD_MILLIS = 500L
private const val MANAGER_VIDEO_LOCK_DISTANCE_DP = 72

@Composable
private fun CameraRoundControl(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 28.sp)
    }
}

@Composable
private fun ManagerLightToggleButton(
    lightMode: ManagerPhotoLightMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(lightMode.buttonColor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = lightMode.icon,
            color = lightMode.contentColor,
            fontSize = 22.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ManagerPhotoPreview(
    photoUri: String,
    modifier: Modifier = Modifier,
    rotationDegrees: Int = 0,
) {
    if (isManagerVideoUri(photoUri)) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = "Видео" },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▶", style = MaterialTheme.typography.titleMedium)
                Text("Видео", style = MaterialTheme.typography.labelSmall)
            }
        }
        return
    }
    AsyncImage(
        model = managerPhotoImageModel(photoUri),
        contentDescription = "Фотография",
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .graphicsLayer { rotationZ = rotationDegrees.toFloat() },
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun managerPhotoImageModel(
    photoUri: String,
): ImageRequest {
    val context = LocalContext.current
    val cacheRevision = managerLocalPhotoFile(photoUri)
        ?.lastModified()
        ?.takeIf { it > 0L }
        ?: 0L
    return remember(context, photoUri, cacheRevision) {
        ImageRequest.Builder(context)
            .data(photoUri)
            .memoryCacheKey("$photoUri#$cacheRevision")
            .build()
    }
}

@Composable
private fun ManagerZoomablePhoto(
    photoUri: String,
    rotationDegrees: Int,
    contentDescription: String,
    onZoomStateChanged: (Boolean) -> Unit,
) {
    var scale by remember(photoUri) { mutableFloatStateOf(1f) }
    var translationX by remember(photoUri) { mutableFloatStateOf(0f) }
    var translationY by remember(photoUri) { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(MANAGER_PHOTO_MIN_SCALE, MANAGER_PHOTO_MAX_SCALE)
        if (nextScale <= MANAGER_PHOTO_MIN_SCALE) {
            translationX = 0f
            translationY = 0f
        } else {
            translationX += panChange.x
            translationY += panChange.y
        }
        scale = nextScale
        onZoomStateChanged(nextScale > MANAGER_PHOTO_MIN_SCALE)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            // Let the pager own one-finger swipes until the user has zoomed in.
            // A zoomed image still keeps its natural pan gesture.
            .transformable(
                state = transformState,
                canPan = { scale > MANAGER_PHOTO_MIN_SCALE },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = managerPhotoImageModel(photoUri),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = translationX
                    translationY = translationY
                    rotationZ = rotationDegrees.toFloat()
                },
            contentScale = ContentScale.Fit,
        )
    }
}

private const val MANAGER_PHOTO_MIN_SCALE = 1f
private const val MANAGER_PHOTO_MAX_SCALE = 5f
private const val MANAGER_PHOTO_DISMISS_SWIPE_DP = 96

/** Maps a virtual infinite pager page to an index in the current photo list. */
internal fun managerPhotoGalleryLogicalIndex(page: Int, photoCount: Int): Int {
    require(photoCount > 0) { "Photo count must be positive" }
    return Math.floorMod(page, photoCount)
}

/** Starts an infinite pager close to its center while preserving the requested photo. */
internal fun managerPhotoGalleryInitialPage(initialIndex: Int, photoCount: Int): Int {
    require(photoCount > 0) { "Photo count must be positive" }
    if (photoCount == 1) return 0
    val centerPage = Int.MAX_VALUE / 2
    val normalizedInitialIndex = initialIndex.coerceIn(0, photoCount - 1)
    return centerPage - managerPhotoGalleryLogicalIndex(centerPage, photoCount) + normalizedInitialIndex
}

private fun Modifier.managerPhotoSwipeDownToDismiss(
    enabled: Boolean,
    dismissThresholdPx: Float,
    onDismiss: () -> Unit,
): Modifier = pointerInput(enabled, dismissThresholdPx, onDismiss) {
    if (!enabled) return@pointerInput
    var verticalDrag = 0f
    detectVerticalDragGestures(
        onDragStart = { verticalDrag = 0f },
        onVerticalDrag = { _, dragAmount -> verticalDrag += dragAmount },
        onDragEnd = {
            if (verticalDrag >= dismissThresholdPx) onDismiss()
            verticalDrag = 0f
        },
        onDragCancel = { verticalDrag = 0f },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ManagerPhotoGalleryDialog(
    photoUris: List<String>,
    initialIndex: Int,
    title: String? = null,
    onRemovePhotoUri: ((String) -> Unit)? = null,
    photoRotationDegrees: (String) -> Int = { 0 },
    onRotatePhotoUri: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    if (photoUris.isEmpty()) return
    val photoCount = photoUris.size
    val pagerState = rememberPagerState(
        initialPage = managerPhotoGalleryInitialPage(initialIndex, photoCount),
        // A virtual page range keeps the carousel circular without copying photo data.
        pageCount = { if (photoUris.size > 1) Int.MAX_VALUE else 1 },
    )
    var currentPhotoZoomed by remember { mutableStateOf(false) }
    val currentLogicalIndex = managerPhotoGalleryLogicalIndex(
        pagerState.currentPage,
        photoUris.size,
    )
    val dismissThresholdPx = with(LocalDensity.current) {
        MANAGER_PHOTO_DISMISS_SWIPE_DP.dp.toPx()
    }

    LaunchedEffect(currentLogicalIndex) {
        currentPhotoZoomed = false
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !currentPhotoZoomed,
                    modifier = Modifier
                        .weight(1f)
                        .managerPhotoSwipeDownToDismiss(
                            enabled = !currentPhotoZoomed,
                            dismissThresholdPx = dismissThresholdPx,
                            onDismiss = onDismiss,
                        ),
                ) { page ->
                    val logicalIndex = managerPhotoGalleryLogicalIndex(page, photoUris.size)
                    Box(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val uri = photoUris[logicalIndex]
                        if (isManagerVideoUri(uri)) {
                            Text(
                                text = "Видео будет доступно после загрузки",
                                color = Color.White,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            ManagerZoomablePhoto(
                                photoUri = uri,
                                rotationDegrees = photoRotationDegrees(uri),
                                contentDescription = "Фотография ${logicalIndex + 1}",
                                onZoomStateChanged = { zoomed ->
                                    if (logicalIndex == currentLogicalIndex) {
                                        currentPhotoZoomed = zoomed
                                    }
                                },
                            )
                        }
                    }
                }
                // Keep the slide indicator just above the persistent bottom actions. The preview
                // then remains unobscured and its controls stay in the same place in inventory,
                // estimates and repairs.
                if (photoUris.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        photoUris.indices.forEach { index ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(if (index == currentLogicalIndex) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == currentLogicalIndex) {
                                            Color.White
                                        } else {
                                            Color.White.copy(alpha = 0.42f)
                                        },
                                    )
                                    .semantics {
                                        contentDescription = "Фотография ${index + 1} из ${photoUris.size}"
                                    },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${currentLogicalIndex + 1} из ${photoUris.size}",
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = listOfNotNull(
                                    title,
                                    "${currentLogicalIndex + 1} из ${photoUris.size}",
                                ).joinToString(" · ")
                            },
                    )
                    val current = photoUris.getOrNull(currentLogicalIndex)
                    if (current != null && !isManagerVideoUri(current) && onRotatePhotoUri != null) {
                        TextButton(
                            onClick = { onRotatePhotoUri(current) },
                        ) {
                            Text("Повернуть", color = Color.White)
                        }
                    }
                    if (current != null && onRemovePhotoUri != null) {
                        TextButton(onClick = { onRemovePhotoUri(current) }) {
                            Text("Удалить", color = Color(0xFFFFB4AB))
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("Готово", color = Color.White) }
                }
            }
        }
    }
}

private fun captureDirectPhoto(
    imageCapture: ImageCapture?,
    previewView: PreviewView,
    cacheDir: File,
    mainExecutor: java.util.concurrent.Executor,
    onCaptured: (String) -> Unit,
) {
    val capture = imageCapture ?: return
    // CameraX stores this orientation in the JPEG's EXIF metadata.  Keep the original bytes
    // untouched afterwards; media-service is the sole canonical image processor.
    capture.targetRotation = managerCaptureTargetRotation(previewView.display?.rotation)
    val file = createManagerMediaFile(cacheDir, "capture") ?: return
    val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
    capture.takePicture(
        outputOptions,
        mainExecutor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // Keep CameraX's original JPEG and its EXIF orientation intact.
                onCaptured(Uri.fromFile(file).toString())
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                Log.e("ManagerPhotoCamera", "Unable to save photo", exception)
            }
        },
    )
}

/** Returns a valid CameraX target rotation even while the preview is attaching to the window. */
internal fun managerCaptureTargetRotation(displayRotation: Int?): Int = when (displayRotation) {
    AndroidSurface.ROTATION_0,
    AndroidSurface.ROTATION_90,
    AndroidSurface.ROTATION_180,
    AndroidSurface.ROTATION_270,
    -> displayRotation

    else -> AndroidSurface.ROTATION_0
}

/** Copies a gallery original to an app-owned cache path that MediaUploader can read later. */
fun copyManagerPhotoToAppCache(context: Context, source: Uri): String? = runCatching {
    val extension = when (context.contentResolver.getType(source)?.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
    val target = createManagerMediaFile(context.cacheDir, "picked", extension)
        ?: return@runCatching null
    context.contentResolver.openInputStream(source)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    // Byte-for-byte copy only: source EXIF remains available to media-service.
    Uri.fromFile(target).toString()
}.getOrNull()

private fun createManagerMediaFile(cacheDir: File, prefix: String, extension: String = "jpg"): File? =
    runCatching {
        val directory = File(cacheDir, "manager-photos").apply { mkdirs() }
        File(directory, "$prefix-${UUID.randomUUID()}.$extension").apply { createNewFile() }
    }.getOrNull()

private fun managerLocalPhotoFile(uriText: String): File? = runCatching {
    val uri = Uri.parse(uriText)
    if (uri.scheme != ContentResolver.SCHEME_FILE) return@runCatching null
    uri.path?.let(::File)?.takeIf(File::isFile)
}.getOrNull()

private fun isManagerVideoUri(uriText: String): Boolean = Uri.parse(uriText)
    .path
    ?.substringAfterLast('.', missingDelimiterValue = "")
    ?.lowercase(Locale.ROOT)
    ?.let { it == "mp4" || it == "webm" }
    ?: false

private fun playCaptureFeedback(context: Context, shutterSound: MediaActionSound) {
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

private enum class ManagerPhotoLightMode(
    val icon: String,
    val buttonColor: Color,
    val contentColor: Color,
) {
    Off("⚡", Color.White.copy(alpha = 0.12f), Color.White),
    Flash("⚡", Color(0xFF9AD99A), Color.Black),
    Torch("☀", Color(0xFF9AD99A), Color.Black),
    ;

    fun next(): ManagerPhotoLightMode = when (this) {
        Off -> Flash
        Flash -> Torch
        Torch -> Off
    }

    fun toFlashMode(): Int = when (this) {
        Flash -> ImageCapture.FLASH_MODE_ON
        Off,
        Torch -> ImageCapture.FLASH_MODE_OFF
    }
}
