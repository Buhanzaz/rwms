package dev.buhanzaz.rwms.manager.ui.components

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.view.Surface as AndroidSurface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File
import java.util.Locale
import java.util.UUID
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
            onRequestAudioPermission = {
                permissionLauncher.launch(managerVideoAudioPermissions())
            },
            onClose = onBack,
        )
    } else {
        CameraPermissionScreen(
            title = title,
            onRequestPermission = {
                permissionLauncher.launch(managerPhotoCapturePermissions())
            },
            onBack = onBack,
        )
    }
}

internal fun managerPhotoCapturePermissions(): Array<String> =
    arrayOf(Manifest.permission.CAMERA)

internal fun managerVideoAudioPermissions(): Array<String> =
    arrayOf(Manifest.permission.RECORD_AUDIO)

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
    onRequestAudioPermission: () -> Unit,
    onClose: () -> Unit,
) {
    ManagerCameraExperience(
        title = title,
        photoUris = photoUris,
        onPhotoCaptured = onPhotoCaptured,
        onRemovePhotoUri = onRemovePhotoUri,
        audioPermissionGranted = audioPermissionGranted,
        onRequestAudioPermission = onRequestAudioPermission,
        onClose = onClose,
    )
}

@Composable
fun ManagerPhotoPreview(
    photoUri: String,
    modifier: Modifier = Modifier,
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
            .background(MaterialTheme.colorScheme.surfaceVariant),
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

/** Returns a valid CameraX target rotation even while the preview is attaching to the window. */
internal fun managerCaptureTargetRotation(displayRotation: Int?): Int = when (displayRotation) {
    AndroidSurface.ROTATION_0,
    AndroidSurface.ROTATION_90,
    AndroidSurface.ROTATION_180,
    AndroidSurface.ROTATION_270,
    -> displayRotation

    else -> AndroidSurface.ROTATION_0
}

/** Maps the physical device position to CameraX rotation even when the activity stays portrait. */
internal fun managerCaptureTargetRotationForOrientation(
    orientationDegrees: Int,
    fallbackRotation: Int,
): Int = when (orientationDegrees) {
    in 45 until 135 -> AndroidSurface.ROTATION_270
    in 135 until 225 -> AndroidSurface.ROTATION_180
    in 225 until 315 -> AndroidSurface.ROTATION_90
    in 0 until 360 -> AndroidSurface.ROTATION_0
    else -> managerCaptureTargetRotation(fallbackRotation)
}

/** EXIF rotation values used when a vendor HAL writes Orientation=0. */
internal fun managerExifOrientationForRotationDegrees(rotationDegrees: Int): Int =
    when (((rotationDegrees % 360) + 360) % 360) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

internal fun managerExifOrientationNeedsRepair(orientation: Int): Boolean = orientation !in 1..8

/**
 * Copies a gallery original to app-owned storage and resolves EXIF rotation before it reaches
 * the durable upload outbox. PNG/WebP bytes are retained unchanged unless their EXIF says that
 * their pixels need a transform; then the normalized app-owned result is an upright JPEG.
 */
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
    val normalized = if (extension == "jpg" || managerPhotoNeedsPixelNormalization(target)) {
        normalizeManagerCameraJpegOrientation(
            source = target,
            fallbackOrientation = ExifInterface.ORIENTATION_NORMAL,
        ) ?: run {
            target.delete()
            return@runCatching null
        }
    } else {
        target
    }
    if (normalized != target && !target.delete()) {
        // The normalized file is the only URI handed to the upload outbox; this stale cache file
        // has no reachable reference and Android can reclaim it with the rest of the cache.
        android.util.Log.w("ManagerPhotos", "Unable to remove pre-normalized gallery image")
    }
    Uri.fromFile(normalized).toString()
}.getOrNull()

private fun managerPhotoNeedsPixelNormalization(file: File): Boolean = runCatching {
    ExifInterface(file).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    ) in setOf(
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
        ExifInterface.ORIENTATION_ROTATE_180,
        ExifInterface.ORIENTATION_FLIP_VERTICAL,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270,
    )
}.getOrDefault(false)

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
