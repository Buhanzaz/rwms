package dev.buhanzaz.rwms.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import dev.buhanzaz.rwms.worker.core.ui.WorkerButton
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Telegram-like circular crop editor drawn over the application's moving water background. */
@Composable
fun AvatarEditorScreen(
    initialUri: String,
    isSaving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
    onSave: (Bitmap) -> Unit,
) {
    var selectedUri by remember(initialUri) { mutableStateOf(Uri.parse(initialUri)) }
    var cropRequest by remember(initialUri) { mutableStateOf<AvatarCropRequest?>(null) }
    var isPreparing by remember(initialUri) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val sourceState by produceState<AvatarSourceState>(AvatarSourceState.Loading, selectedUri) {
        value = withContext(Dispatchers.IO) {
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
            cropRequest = null
            selectedUri = uri
        }
    }
    WorkerScreenScaffold(title = "Фото профиля", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Разведите пальцы для масштаба и переместите фото внутри круга",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(28.dp),
                color = Color.White.copy(alpha = 0.34f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.62f)),
            ) {
                when (val source = sourceState) {
                    AvatarSourceState.Loading -> Unit
                    AvatarSourceState.Failed -> Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Не удалось открыть изображение", textAlign = TextAlign.Center)
                    }
                    is AvatarSourceState.Ready -> AvatarCropViewport(
                        bitmap = source.bitmap,
                        enabled = !isSaving,
                        onCropChanged = { cropRequest = it },
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
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    Text("Другое")
                }
                AvatarSaveButton(
                    enabled = sourceState is AvatarSourceState.Ready && cropRequest != null,
                    isSaving = isSaving || isPreparing,
                    onClick = {
                        val request = cropRequest ?: return@AvatarSaveButton
                        isPreparing = true
                        coroutineScope.launch {
                            val cropped = withContext(Dispatchers.Default) {
                                cropAvatarBitmap(
                                    request.bitmap,
                                    request.zoom,
                                    request.offset,
                                    request.viewportPixels,
                                )
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
    onCropChanged: (AvatarCropRequest?) -> Unit,
) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewportPixels by remember(bitmap) { mutableFloatStateOf(0f) }
    val currentOnCropChanged by rememberUpdatedState(onCropChanged)
    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 5f)
        val nextOffset = offset + panChange
        val bounds = avatarPanBounds(ratio, viewportPixels, nextZoom)
        zoom = nextZoom
        offset = Offset(
            x = nextOffset.x.coerceIn(-bounds.x, bounds.x),
            y = nextOffset.y.coerceIn(-bounds.y, bounds.y),
        )
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth - 32.dp, maxHeight - 32.dp, 340.dp).coerceAtLeast(80.dp)
        val baseWidth: Dp = if (ratio >= 1f) diameter * ratio else diameter
        val baseHeight: Dp = if (ratio >= 1f) diameter else diameter / ratio
        Box(
            modifier = Modifier
                .size(diameter)
                .onSizeChanged { viewportPixels = it.width.toFloat() }
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                .border(3.dp, Color.White.copy(alpha = 0.94f), CircleShape)
                .graphicsLayer { clip = true; shape = CircleShape }
                .transformable(transformState, enabled = enabled),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Обрезка фотографии профиля",
                modifier = Modifier
                    .width(baseWidth)
                    .height(baseHeight)
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.FillBounds,
            )
        }
    }
    LaunchedEffect(bitmap, zoom, offset, viewportPixels, enabled) {
        currentOnCropChanged(if (enabled && viewportPixels > 0f) {
            AvatarCropRequest(bitmap, zoom, offset, viewportPixels)
        } else {
            null
        })
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

private data class AvatarCropRequest(
    val bitmap: Bitmap,
    val zoom: Float,
    val offset: Offset,
    val viewportPixels: Float,
)

internal fun avatarPanBounds(
    sourceRatio: Float,
    viewportPixels: Float,
    zoom: Float,
): Offset {
    if (viewportPixels <= 0f || sourceRatio <= 0f) return Offset.Zero
    val baseWidth = viewportPixels * max(sourceRatio, 1f)
    val baseHeight = viewportPixels * max(1f / sourceRatio, 1f)
    return Offset(
        x = ((baseWidth * zoom - viewportPixels) / 2f).coerceAtLeast(0f),
        y = ((baseHeight * zoom - viewportPixels) / 2f).coerceAtLeast(0f),
    )
}

internal fun cropAvatarBitmap(
    source: Bitmap,
    zoom: Float,
    offset: Offset,
    viewportPixels: Float,
): Bitmap {
    require(source.width > 0 && source.height > 0 && viewportPixels > 0f)
    val baseScale = max(viewportPixels / source.width, viewportPixels / source.height)
    val totalScale = baseScale * zoom.coerceIn(1f, 5f)
    val cropSize = (viewportPixels / totalScale).roundToInt()
        .coerceIn(1, min(source.width, source.height))
    val centerX = source.width / 2f - offset.x / totalScale
    val centerY = source.height / 2f - offset.y / totalScale
    val left = (centerX - cropSize / 2f).roundToInt().coerceIn(0, source.width - cropSize)
    val top = (centerY - cropSize / 2f).roundToInt().coerceIn(0, source.height - cropSize)
    val cropped = Bitmap.createBitmap(source, left, top, cropSize, cropSize)
    val outputSize = min(AVATAR_OUTPUT_SIZE, cropSize)
    if (cropped.width == outputSize) return cropped
    return Bitmap.createScaledBitmap(cropped, outputSize, outputSize, true).also { cropped.recycle() }
}

private fun decodeAvatarBitmap(context: Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: throw IOException("Selected image is unavailable")
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

private const val MAX_EDITOR_EDGE = 2_400
private const val AVATAR_OUTPUT_SIZE = 1_024
