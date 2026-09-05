package dev.buhanzaz.rwms.client.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A local square crop in image coordinates; normalized centers survive viewport size changes. */
internal data class CustomerAvatarCrop(
    val zoom: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {
    /** Keeps every edge inside the decoded image, so neither preview nor export has empty bands. */
    fun sourceRect(imageWidth: Int, imageHeight: Int): Rect {
        require(imageWidth > 0 && imageHeight > 0)
        val edge = min(imageWidth, imageHeight) / zoom.coerceIn(1f, MAX_AVATAR_ZOOM)
        val half = edge / 2f
        val x = (centerX * imageWidth).coerceIn(half, imageWidth - half)
        val y = (centerY * imageHeight).coerceIn(half, imageHeight - half)
        return Rect(x - half, y - half, x + half, y + half)
    }

    /** Moves the image under the circular viewport and preserves the point under a pinch centroid. */
    fun transformed(
        imageWidth: Int,
        imageHeight: Int,
        viewport: Size,
        centroid: Offset,
        pan: Offset,
        zoomChange: Float,
    ): CustomerAvatarCrop {
        val diameter = avatarCropDiameter(viewport)
        if (diameter <= 0f) return this
        val before = sourceRect(imageWidth, imageHeight)
        val nextZoom = (zoom * zoomChange).coerceIn(1f, MAX_AVATAR_ZOOM)
        val nextEdge = min(imageWidth, imageHeight) / nextZoom
        val anchor = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        val center = before.center + anchor * ((before.width - nextEdge) / diameter) - pan * (nextEdge / diameter)
        val candidate = CustomerAvatarCrop(nextZoom, center.x / imageWidth, center.y / imageHeight)
        val bounded = candidate.sourceRect(imageWidth, imageHeight)
        return candidate.copy(centerX = bounded.center.x / imageWidth, centerY = bounded.center.y / imageHeight)
    }
}

/** Decodes a bounded software image with Android's encoded-orientation handling and sRGB output. */
internal fun decodeCustomerAvatar(context: Context, uri: Uri): Bitmap = ImageDecoder.decodeBitmap(
    ImageDecoder.createSource(context.contentResolver, uri),
) { decoder, info, _ ->
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    val factor = min(1f, MAX_AVATAR_DECODE_EDGE.toFloat() / maxOf(info.size.width, info.size.height))
    decoder.setTargetSize(
        (info.size.width * factor).roundToInt().coerceAtLeast(1),
        (info.size.height * factor).roundToInt().coerceAtLeast(1),
    )
}

/** Exports only the selected square as a fresh JPEG; source EXIF/GPS and outside pixels are omitted. */
internal fun renderCustomerAvatarJpeg(bitmap: Bitmap, crop: CustomerAvatarCrop): ByteArray {
    val source = crop.sourceRect(bitmap.width, bitmap.height)
    val output = Bitmap.createBitmap(AVATAR_OUTPUT_EDGE, AVATAR_OUTPUT_EDGE, Bitmap.Config.ARGB_8888)
    return try {
        val canvas = android.graphics.Canvas(output)
        canvas.drawColor(android.graphics.Color.WHITE)
        val scale = AVATAR_OUTPUT_EDGE / source.width
        canvas.scale(scale, scale)
        canvas.translate(-source.left, -source.top)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        ByteArrayOutputStream().use { bytes ->
            check(output.compress(Bitmap.CompressFormat.JPEG, 92, bytes)) { "Avatar export failed" }
            bytes.toByteArray()
        }
    } finally {
        output.recycle()
    }
}

/** Local photo editor on the existing app background; no server command occurs before Done. */
@Composable
internal fun CustomerAvatarCropScreen(
    uri: Uri,
    onCancel: () -> Unit,
    onConfirm: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var crop by remember(uri) { mutableStateOf(CustomerAvatarCrop()) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var exporting by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { decoded = decodeCustomerAvatar(context, uri) }
            bitmap = decoded
            decoded = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Не удалось открыть фото. Вернитесь и выберите другое изображение."
        } finally {
            // A cancelled decode was never handed to Compose. Rendered images are released with the screen.
            decoded?.recycle()
        }
    }
    BackHandler(onBack = onCancel)
    Scaffold(
        modifier = Modifier.testTag("avatar-crop-screen"),
        topBar = { CustomerTopBar("Фото профиля", onBack = onCancel) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Поместите фото в круг", style = MaterialTheme.typography.titleLarge)
            Text(
                "Двигайте и масштабируйте изображение",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val loaded = bitmap
                if (loaded == null) {
                    if (error == null) CircularProgressIndicator(Modifier.size(36.dp))
                } else {
                    val image = remember(loaded) { loaded.asImageBitmap() }
                    Canvas(
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
                            .pointerInput(loaded, exporting) {
                                if (!exporting) detectTransformGestures { centroid, pan, zoom, _ ->
                                    crop = crop.transformed(loaded.width, loaded.height, viewport, centroid, pan, zoom)
                                }
                            }
                            .semantics { contentDescription = "Предпросмотр аватара" }
                            .testTag("avatar-crop-preview"),
                    ) {
                        val diameter = avatarCropDiameter(size)
                        val source = crop.sourceRect(loaded.width, loaded.height)
                        val imageScale = diameter / source.width
                        withTransform({
                            translate(center.x, center.y)
                            scale(imageScale, imageScale, pivot = Offset.Zero)
                            translate(-source.center.x, -source.center.y)
                        }) {
                            drawRect(Color.White, size = Size(loaded.width.toFloat(), loaded.height.toFloat()))
                            drawImage(image)
                        }
                        val outside = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(Offset.Zero, size))
                            addOval(Rect(center = center, radius = diameter / 2f))
                        }
                        drawPath(outside, CustomerStoreNavy.copy(alpha = 0.38f))
                        drawCircle(Color.White, radius = diameter / 2f, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("avatar-crop-error")) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.ZoomOut, contentDescription = null)
                Slider(
                    value = crop.zoom,
                    onValueChange = { value ->
                        val loaded = bitmap
                        if (loaded != null) {
                            crop = crop.transformed(
                                loaded.width, loaded.height, viewport,
                                Offset(viewport.width / 2f, viewport.height / 2f), Offset.Zero, value / crop.zoom,
                            )
                        }
                    },
                    valueRange = 1f..MAX_AVATAR_ZOOM,
                    enabled = bitmap != null && !exporting,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Масштаб фото" }.testTag("avatar-crop-zoom"),
                )
                Icon(Icons.Default.ZoomIn, contentDescription = null)
            }
            Button(
                onClick = {
                    val loaded = bitmap
                    if (loaded != null && !exporting) {
                        exporting = true
                        error = null
                        val selectedCrop = crop
                        scope.launch {
                            try {
                                val jpeg = withContext(Dispatchers.Default) { renderCustomerAvatarJpeg(loaded, selectedCrop) }
                                onConfirm(jpeg)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "Не удалось подготовить фото. Попробуйте ещё раз."
                                exporting = false
                            }
                        }
                    }
                },
                enabled = bitmap != null && viewport.width > 0 && viewport.height > 0 && !exporting,
                modifier = Modifier.fillMaxWidth().testTag("avatar-crop-done"),
            ) {
                if (exporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(if (exporting) "Подготовка фото…" else "Готово")
            }
        }
    }
}

private fun avatarCropDiameter(viewport: Size): Float = min(viewport.width, viewport.height) * 0.9f
private const val MAX_AVATAR_ZOOM = 6f
private const val MAX_AVATAR_DECODE_EDGE = 2048
private const val AVATAR_OUTPUT_EDGE = 1024
