package dev.buhanzaz.rwms.worker

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import kotlin.math.min

/**
 * Displays one authenticated task-media collection as a full-screen, selected-photo-aware pager.
 *
 * The media bytes remain owned by [PhotoViewModel]; this screen only controls presentation state.
 */
@Composable
fun PhotoPagerScreen(
    title: String,
    paths: List<String>,
    initialIndex: Int,
    onBack: () -> Unit,
    viewModel: PhotoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (paths.isEmpty()) {
        WorkerScreenScaffold(title = "0/0 · $title", onBack = onBack) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    text = "В задании нет фотографий",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }
        return
    }

    val pager = rememberPagerState(
        initialPage = photoPagerInitialPage(initialIndex, paths.size),
        pageCount = { paths.size },
    )
    val currentPage = pager.currentPage.coerceIn(paths.indices)
    LaunchedEffect(paths, currentPage) {
        viewModel.show(photoPagerLoadWindow(paths, currentPage))
    }
    var currentPageZoomed by remember(paths) { mutableStateOf(false) }
    LaunchedEffect(currentPage) { currentPageZoomed = false }

    WorkerScreenScaffold(
        title = "${currentPage + 1}/${paths.size} · $title",
        onBack = onBack,
    ) { padding ->
        HorizontalPager(
            state = pager,
            userScrollEnabled = !currentPageZoomed,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { page ->
            val path = paths[page]
            val bitmap = state.bitmaps[path]
            Box(Modifier.fillMaxSize()) {
                if (bitmap == null) {
                    val error = state.errors[path]
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error ?: "Загружаем фото…")
                        if (error != null) {
                            TextButton(onClick = { viewModel.retry(path) }) {
                                Text("Повторить")
                            }
                        }
                    }
                } else {
                    ZoomableBitmap(
                        bitmap = bitmap.asImageBitmap(),
                        active = page == currentPage,
                        onZoomStateChanged = { zoomed ->
                            if (page == pager.currentPage) currentPageZoomed = zoomed
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Clamps a thumbnail index without producing an invalid page for an empty media collection. */
internal fun photoPagerInitialPage(initialIndex: Int, photoCount: Int): Int =
    if (photoCount <= 0) 0 else initialIndex.coerceIn(0, photoCount - 1)

/** Keeps only the selected full-size photo and its immediate pager neighbours in memory. */
internal fun photoPagerLoadWindow(paths: List<String>, selectedIndex: Int): List<String> {
    if (paths.isEmpty()) return emptyList()
    val selected = photoPagerInitialPage(selectedIndex, paths.size)
    return sequenceOf(selected, selected - 1, selected + 1)
        .filter(paths.indices::contains)
        .map(paths::get)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
}

/**
 * Keeps a fitted photo inside the visible viewport after zooming or panning.
 *
 * The returned offset is zero on an axis until the scaled fitted image exceeds that viewport axis.
 */
internal fun boundedPhotoOffset(
    proposed: Offset,
    scale: Float,
    viewportSize: IntSize,
    imageSize: IntSize,
): Offset {
    if (
        viewportSize.width <= 0 ||
        viewportSize.height <= 0 ||
        imageSize.width <= 0 ||
        imageSize.height <= 0 ||
        !scale.isFinite()
    ) {
        return Offset.Zero
    }
    val fittedScale = min(
        viewportSize.width.toFloat() / imageSize.width,
        viewportSize.height.toFloat() / imageSize.height,
    )
    val maxX = ((imageSize.width * fittedScale * scale - viewportSize.width) / 2f)
        .coerceAtLeast(0f)
    val maxY = ((imageSize.height * fittedScale * scale - viewportSize.height) / 2f)
        .coerceAtLeast(0f)
    return Offset(
        x = if (maxX == 0f) 0f else proposed.x.coerceIn(-maxX, maxX),
        y = if (maxY == 0f) 0f else proposed.y.coerceIn(-maxY, maxY),
    )
}

/** Renders one pager page with resettable, bounded pinch, pan and double-tap zoom. */
@Composable
private fun ZoomableBitmap(
    bitmap: ImageBitmap,
    active: Boolean,
    onZoomStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember(bitmap) { mutableFloatStateOf(MIN_PHOTO_SCALE) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    val imageSize = remember(bitmap) { IntSize(bitmap.width, bitmap.height) }

    fun applyTransform(nextScale: Float, proposedOffset: Offset) {
        scale = nextScale.coerceIn(MIN_PHOTO_SCALE, MAX_PHOTO_SCALE)
        offset = if (scale == MIN_PHOTO_SCALE) {
            Offset.Zero
        } else {
            boundedPhotoOffset(proposedOffset, scale, viewportSize, imageSize)
        }
        onZoomStateChanged(scale > MIN_PHOTO_SCALE)
    }

    LaunchedEffect(active) {
        if (!active) applyTransform(MIN_PHOTO_SCALE, Offset.Zero)
    }

    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val previousScale = scale
        val nextScale = (previousScale * zoomChange).coerceIn(MIN_PHOTO_SCALE, MAX_PHOTO_SCALE)
        val viewportCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val zoomRatio = nextScale / previousScale
        val centroidAdjustment = (centroid - viewportCenter) * (1f - zoomRatio)
        applyTransform(
            nextScale = nextScale,
            proposedOffset = offset * zoomRatio + panChange + centroidAdjustment,
        )
    }

    Image(
        bitmap = bitmap,
        contentDescription = "Фото задания",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                viewportSize = size
                offset = boundedPhotoOffset(offset, scale, viewportSize, imageSize)
            }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = { tapPosition ->
                        if (scale > MIN_PHOTO_SCALE) {
                            applyTransform(MIN_PHOTO_SCALE, Offset.Zero)
                        } else {
                            val viewportCenter = Offset(
                                viewportSize.width / 2f,
                                viewportSize.height / 2f,
                            )
                            val targetOffset =
                                (viewportCenter - tapPosition) * (DOUBLE_TAP_PHOTO_SCALE - 1f)
                            applyTransform(DOUBLE_TAP_PHOTO_SCALE, targetOffset)
                        }
                    },
                )
            }
            .transformable(
                state = transformState,
                canPan = { scale > MIN_PHOTO_SCALE },
            )
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
    )
}

private const val MIN_PHOTO_SCALE = 1f
private const val DOUBLE_TAP_PHOTO_SCALE = 2.5f
private const val MAX_PHOTO_SCALE = 5f
