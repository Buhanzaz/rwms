package dev.buhanzaz.rwms.worker

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.exifinterface.media.ExifInterface
import dev.buhanzaz.rwms.worker.core.ui.WorkerButton
import androidx.core.view.WindowCompat
import java.io.IOException
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Circular crop editor matching the customer photo flow on a black edge-to-edge surface. */
@Composable
fun AvatarEditorScreen(
    initialUri: String,
    isSaving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
    onSave: (Bitmap) -> Unit,
) {
    BackHandler(onBack = onBack)
    var selectedUri by remember(initialUri) { mutableStateOf(Uri.parse(initialUri)) }
    var crop by remember(initialUri) { mutableStateOf(WorkerAvatarCrop()) }
    var isPreparing by remember(initialUri) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context as? Activity
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller?.isAppearanceLightNavigationBars
        val previousNavigationContrast = if (Build.VERSION.SDK_INT >= 29) {
            activity?.window?.isNavigationBarContrastEnforced
        } else {
            null
        }
        controller?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= 29) activity?.window?.isNavigationBarContrastEnforced = false
        onDispose {
            controller?.apply {
                isAppearanceLightStatusBars = previousLightStatusBars ?: true
                isAppearanceLightNavigationBars = previousLightNavigationBars ?: true
            }
            if (Build.VERSION.SDK_INT >= 29 && previousNavigationContrast != null) {
                activity?.window?.isNavigationBarContrastEnforced = previousNavigationContrast
            }
        }
    }
    var sourceState by remember(selectedUri) { mutableStateOf<AvatarSourceState>(AvatarSourceState.Loading) }
    LaunchedEffect(selectedUri) {
        sourceState = withContext(Dispatchers.IO) {
            runCatching { decodeAvatarBitmap(context, selectedUri) }
                .fold(
                    onSuccess = AvatarSourceState::Ready,
                    onFailure = { AvatarSourceState.Failed },
                )
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            onDismissError()
            crop = WorkerAvatarCrop()
            selectedUri = uri
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = Color.White,
                    )
                }
                Text(
                    text = "Фото профиля",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                "Двигайте и масштабируйте изображение",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.78f),
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when (val source = sourceState) {
                    AvatarSourceState.Loading -> CircularProgressIndicator(color = Color.White)
                    AvatarSourceState.Failed -> Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Не удалось открыть изображение", color = Color.White, textAlign = TextAlign.Center)
                    }
                    is AvatarSourceState.Ready -> AvatarCropViewport(
                        bitmap = source.bitmap,
                        enabled = !isSaving && !isPreparing,
                        crop = crop,
                        onCropChanged = { crop = it },
                    )
                }
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WorkerButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !isSaving && !isPreparing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    Text("Другое")
                }
                AvatarSaveButton(
                    enabled = sourceState is AvatarSourceState.Ready,
                    isSaving = isSaving || isPreparing,
                    onClick = {
                        val source = (sourceState as? AvatarSourceState.Ready)?.bitmap
                            ?: return@AvatarSaveButton
                        val selectedCrop = crop
                        isPreparing = true
                        coroutineScope.launch {
                            val cropped = withContext(Dispatchers.Default) {
                                renderWorkerAvatar(source, selectedCrop)
                            }
                            onSave(cropped)
                            isPreparing = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AvatarCropViewport(
    bitmap: Bitmap,
    enabled: Boolean,
    crop: WorkerAvatarCrop,
    onCropChanged: (WorkerAvatarCrop) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var viewport by remember(bitmap) { mutableStateOf(Size.Zero) }
    Column(Modifier.fillMaxSize()) {
      Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(bitmap, enabled, crop) {
                if (enabled) detectTransformGestures { centroid, pan, zoomChange, _ ->
                    onCropChanged(crop.transformed(bitmap.width, bitmap.height, Size(size.width.toFloat(), size.height.toFloat()), centroid, pan, zoomChange))
                }
            }
            .semantics { contentDescription = "Предпросмотр аватара" }
            .testTag("avatar-crop-preview"),
      ) {
        val diameter = min(size.width, size.height) * 0.9f
        val source = crop.sourceRect(bitmap.width, bitmap.height)
        val imageScale = diameter / source.width
        withTransform({
            translate(center.x, center.y)
            scale(imageScale, imageScale, pivot = Offset.Zero)
            translate(-source.center.x, -source.center.y)
        }) {
            drawRect(
                Color.White,
                size = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
            )
            drawImage(image)
        }
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addOval(Rect(center = center, radius = diameter / 2f))
        }
        drawPath(outside, Color.Black.copy(alpha = 0.55f))
        drawCircle(Color.White, radius = diameter / 2f, style = Stroke(3.dp.toPx()))
      }
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(Icons.Filled.ZoomOut, contentDescription = null, tint = Color.White)
        Slider(
            value = crop.zoom,
            onValueChange = { value ->
                onCropChanged(crop.transformed(bitmap.width, bitmap.height, viewport, Offset(viewport.width / 2f, viewport.height / 2f), Offset.Zero, value / crop.zoom))
            },
            valueRange = 1f..MAX_AVATAR_ZOOM,
            enabled = enabled,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Масштаб фото" },
        )
        Icon(Icons.Filled.ZoomIn, contentDescription = null, tint = Color.White)
      }
    }
}

@Composable
private fun AvatarSaveButton(
    enabled: Boolean,
    isSaving: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkerButton(
        onClick = onClick,
        enabled = enabled && !isSaving,
        modifier = modifier,
    ) {
        Text("Готово")
    }
}

internal data class WorkerAvatarCrop(
    val zoom: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
)

internal fun WorkerAvatarCrop.sourceRect(width: Int, height: Int): Rect {
    val edge = min(width, height) / zoom.coerceIn(1f, MAX_AVATAR_ZOOM)
    val half = edge / 2f
    val x = (centerX * width).coerceIn(half, width - half)
    val y = (centerY * height).coerceIn(half, height - half)
    return Rect(x - half, y - half, x + half, y + half)
}

internal fun WorkerAvatarCrop.transformed(
    width: Int,
    height: Int,
    viewport: Size,
    centroid: Offset,
    pan: Offset,
    zoomChange: Float,
): WorkerAvatarCrop {
    val diameter = min(viewport.width, viewport.height) * 0.9f
    if (diameter <= 0f) return this
    val before = sourceRect(width, height)
    val nextZoom = (zoom * zoomChange).coerceIn(1f, MAX_AVATAR_ZOOM)
    val nextEdge = min(width, height) / nextZoom
    val anchor = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
    val center = before.center + anchor * ((before.width - nextEdge) / diameter) - pan * (nextEdge / diameter)
    val candidate = WorkerAvatarCrop(nextZoom, center.x / width, center.y / height)
    val bounded = candidate.sourceRect(width, height)
    return candidate.copy(centerX = bounded.center.x / width, centerY = bounded.center.y / height)
}

internal fun renderWorkerAvatar(source: Bitmap, crop: WorkerAvatarCrop): Bitmap {
    val selected = crop.sourceRect(source.width, source.height)
    val output = Bitmap.createBitmap(AVATAR_OUTPUT_SIZE, AVATAR_OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(output).apply {
        drawColor(android.graphics.Color.WHITE)
        val scale = AVATAR_OUTPUT_SIZE / selected.width
        scale(scale, scale)
        translate(-selected.left, -selected.top)
        drawBitmap(source, 0f, 0f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
    }
    return output
}

internal fun decodeAvatarBitmap(context: Context, uri: Uri): Bitmap {
    if (Build.VERSION.SDK_INT >= 28) {
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            val factor = min(1f, MAX_EDITOR_EDGE.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * factor).toInt().coerceAtLeast(1),
                (info.size.height * factor).toInt().coerceAtLeast(1),
            )
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Selected image is unavailable")
    boundsStream.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid image bounds")
    var sample = 1
    while (bounds.outWidth / sample > MAX_EDITOR_EDGE || bounds.outHeight / sample > MAX_EDITOR_EDGE) {
        sample *= 2
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: throw IOException("Selected image is unavailable")
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    return orientAvatarBitmap(decoded, orientation)
}

private fun orientAvatarBitmap(source: Bitmap, orientation: Int): Bitmap {
    if (orientation == ExifInterface.ORIENTATION_NORMAL ||
        orientation == ExifInterface.ORIENTATION_UNDEFINED
    ) {
        return source
    }
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
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
        }
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        .also { if (it !== source) source.recycle() }
}

private sealed interface AvatarSourceState {
    data object Loading : AvatarSourceState
    data object Failed : AvatarSourceState
    data class Ready(val bitmap: Bitmap) : AvatarSourceState
}

private const val MAX_EDITOR_EDGE = 2_048
private const val AVATAR_OUTPUT_SIZE = 1_024
private const val MAX_AVATAR_ZOOM = 6f
