package dev.buhanzaz.rwms.worker

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold
import kotlin.math.roundToInt

/** Displays task evidence at full size with paging and bounded gesture zoom. */
@Composable
fun PhotoPagerScreen(
    title: String,
    paths: List<String>,
    onBack: () -> Unit,
    viewModel: PhotoViewModel = hiltViewModel(),
) {
    LaunchedEffect(paths) { viewModel.load(paths) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState(pageCount = { paths.size })
    WorkerScreenScaffold(title = title, onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val path = paths[page]
                val bitmap = state.bitmaps[path]
                Box(Modifier.fillMaxSize()) {
                    if (bitmap == null) {
                        Text("Загружаем защищённое фото…", modifier = Modifier.align(Alignment.Center))
                    } else {
                        ZoomableBitmap(
                            bitmap = bitmap.asImageBitmap(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            state.error?.let { Text(it, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
        }
    }
}

@Composable
private fun ZoomableBitmap(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    Image(
        bitmap = bitmap,
        contentDescription = "Фото задания",
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = IntOffset(offset.x + pan.x.roundToInt(), offset.y + pan.y.roundToInt())
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x.toFloat(),
                translationY = offset.y.toFloat(),
            ),
    )
}
