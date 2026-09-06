package dev.buhanzaz.rwms.client.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import dev.buhanzaz.rwms.client.data.CustomerEvidenceFile
import dev.buhanzaz.rwms.client.data.CustomerSignaturePoint
import dev.buhanzaz.rwms.client.data.CustomerSignatureStroke
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen bounded vector-signature surface for accepting one arrived cabin. */
@Composable
fun CustomerSignatureDialog(
    accountingNo: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (List<CustomerSignatureStroke>) -> Unit,
) {
    val strokes = remember { mutableStateListOf<List<SignatureSample>>() }
    var currentStroke by remember { mutableStateOf<List<SignatureSample>>(emptyList()) }
    var canvasWidth by remember { mutableStateOf(1f) }
    var canvasHeight by remember { mutableStateOf(1f) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Приёмка бытовки № $accountingNo", style = MaterialTheme.typography.titleLarge)
                        Text("Распишитесь пальцем в поле ниже")
                    }
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentStroke = listOf(SignatureSample(offset, SystemClock.elapsedRealtime()))
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentStroke = currentStroke + SignatureSample(
                                        change.position,
                                        SystemClock.elapsedRealtime(),
                                    )
                                },
                                onDragEnd = {
                                    if (currentStroke.isNotEmpty()) strokes += currentStroke
                                    currentStroke = emptyList()
                                },
                                onDragCancel = { currentStroke = emptyList() },
                            )
                        },
                ) {
                    canvasWidth = size.width.coerceAtLeast(1f)
                    canvasHeight = size.height.coerceAtLeast(1f)
                    (strokes + listOf(currentStroke)).forEach { samples ->
                        if (samples.isEmpty()) return@forEach
                        val path = Path().apply {
                            moveTo(samples.first().offset.x, samples.first().offset.y)
                            samples.drop(1).forEach { lineTo(it.offset.x, it.offset.y) }
                        }
                        drawPath(path, Color.Black, style = Stroke(width = 5f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { strokes.clear() },
                        enabled = strokes.isNotEmpty() && !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Очистить") }
                    Button(
                        onClick = {
                            onSubmit(strokes.toSignatureStrokes(canvasWidth, canvasHeight))
                        },
                        enabled = strokes.isNotEmpty() && !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Принять") }
                }
            }
        }
    }
}

/** One raw pointer sample used only until the signature command is normalized. */
private data class SignatureSample(val offset: Offset, val elapsedRealtime: Long)

private fun List<List<SignatureSample>>.toSignatureStrokes(width: Float, height: Float): List<CustomerSignatureStroke> {
    val startedAt = flatten().minOf(SignatureSample::elapsedRealtime)
    return take(32).map { samples ->
        CustomerSignatureStroke(
            samples.take(256).map { sample ->
                CustomerSignaturePoint(
                    x = (sample.offset.x / width).coerceIn(0f, 1f),
                    y = (sample.offset.y / height).coerceIn(0f, 1f),
                    elapsedMillis = (sample.elapsedRealtime - startedAt).coerceIn(0L, 600_000L),
                )
            },
        )
    }
}

/** Problem form with CameraX capture, private draft gallery, and gallery import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerProblemDialog(
    accountingNo: String,
    phase: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String, List<CustomerEvidenceFile>) -> Unit,
) {
    val context = LocalContext.current
    val evidence = remember { mutableStateListOf<CustomerEvidenceFile>() }
    var category by remember { mutableStateOf("MISSING_EQUIPMENT") }
    var description by remember { mutableStateOf("") }
    var showCamera by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val importScope = rememberCoroutineScope()
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importTotal by remember { mutableStateOf(0) }
    var importCompleted by remember { mutableStateOf(0) }
    var disposed by remember { mutableStateOf(false) }
    val importing = importTotal > 0
    DisposableEffect(Unit) {
        onDispose {
            disposed = true
            importJob?.cancel()
            evidence.forEach { draft -> draft.file.delete() }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!busy && importTotal == 0 && !disposed) {
            val selected = uris.take((20 - evidence.size).coerceAtLeast(0))
            if (selected.isNotEmpty()) {
                importTotal = selected.size
                importCompleted = 0
                error = null
                importJob = importScope.launch {
                    try {
                        for (uri in selected) {
                            try {
                                evidence += importEvidenceFile(context, uri)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "Не удалось импортировать файл"
                            }
                            importCompleted += 1
                        }
                    } finally {
                        importTotal = 0
                    }
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Проблема с бытовкой № $accountingNo", style = MaterialTheme.typography.headlineSmall)
            Text(phase)
            ProblemCategory("MISSING_EQUIPMENT", "Не хватает мебели или оборудования", category) { category = it }
            ProblemCategory("UNSUITABLE_CABIN", "Бытовка не подходит", category) { category = it }
            ProblemCategory("OTHER", "Другая проблема", category) { category = it }
            CustomerStoreInputField(
                value = description,
                onValueChange = { description = it.take(2000); error = null },
                placeholder = "Описание",
                enabled = !busy,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                height = 112.dp,
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            EvidenceMiniGallery(evidence, enabled = !busy) { draft ->
                if (!draft.file.exists() || draft.file.delete()) {
                    evidence.remove(draft)
                } else {
                    error = "Не удалось удалить файл. Повторите попытку"
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showCamera = true },
                    enabled = evidence.size < 20 && !busy && !importing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Камера")
                }
                OutlinedButton(
                    onClick = { galleryLauncher.launch(arrayOf("image/*", "video/*")) },
                    enabled = evidence.size < 20 && !busy && !importing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Галерея")
                }
            }
            if (importing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Импорт $importCompleted из $importTotal")
                    TextButton(onClick = { importJob?.cancel() }) { Text("Отменить импорт") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { onSubmit(category, description.trim(), evidence.toList()) },
                enabled = description.isNotBlank() && !busy && !importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Отправить")
            }
        }
    }
    if (showCamera) {
        CustomerEvidenceCamera(
            remaining = 20 - evidence.size,
            onClose = { showCamera = false },
            onEvidence = { if (disposed) it.file.delete() else evidence += it },
            onError = { error = it },
        )
    }
}

@Composable
private fun ProblemCategory(value: String, label: String, selected: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(label)
    }
}

@Composable
private fun EvidenceMiniGallery(
    evidence: List<CustomerEvidenceFile>,
    enabled: Boolean,
    onRemove: (CustomerEvidenceFile) -> Unit,
) {
    if (evidence.isEmpty()) {
        Text("Фото и видео не добавлены", style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        evidence.forEach { item ->
            OutlinedCard(Modifier.size(104.dp)) {
                Box(Modifier.fillMaxSize()) {
                    if (item.contentType.startsWith("image/")) {
                        AsyncImage(
                            model = item.file,
                            contentDescription = item.fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Default.Videocam, contentDescription = item.fileName, modifier = Modifier.align(Alignment.Center))
                    }
                    IconButton(
                        onClick = { onRemove(item) },
                        enabled = enabled,
                        modifier = Modifier.align(Alignment.TopEnd).background(Color.White.copy(alpha = 0.8f)),
                    ) { Icon(Icons.Default.Delete, contentDescription = "Удалить ${item.fileName}") }
                }
            }
        }
    }
}

/** Full-screen CameraX session that captures sequential photos and silent videos without system confirmation. */
@Composable
private fun CustomerEvidenceCamera(
    remaining: Int,
    onClose: () -> Unit,
    onEvidence: (CustomerEvidenceFile) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) onError("Для съёмки разрешите доступ к камере")
    }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
        }
    }
    var recording by remember { mutableStateOf<androidx.camera.video.Recording?>(null) }
    DisposableEffect(lifecycleOwner, controller, hasPermission) {
        if (hasPermission) controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            recording?.stop()
            controller.unbind()
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                if (hasPermission) {
                    AndroidView(
                        factory = { PreviewView(it).apply { this.controller = controller } },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("Нет доступа к камере", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
                Row(
                    Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp).align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Можно добавить ещё: $remaining", color = Color.White)
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть камеру", tint = Color.White) }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(20.dp)
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            capturePhoto(context, context.cacheDir, controller, onEvidence, onError)
                        },
                        enabled = hasPermission && remaining > 0 && recording == null,
                        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Сделать фото" },
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Фото")
                    }
                    Button(
                        onClick = {
                            val active = recording
                            if (active != null) {
                                active.stop()
                            } else {
                                recording = startSilentVideo(
                                    context.cacheDir,
                                    context,
                                    controller,
                                    onEvidence,
                                    onError,
                                ) { recording = null }
                            }
                        },
                        enabled = hasPermission && remaining > 0,
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                    ) {
                        Icon(if (recording == null) Icons.Default.Videocam else Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (recording == null) "Видео" else "Стоп")
                    }
                }
            }
        }
    }
}

private fun capturePhoto(
    context: android.content.Context,
    cacheDir: File,
    controller: LifecycleCameraController,
    onEvidence: (CustomerEvidenceFile) -> Unit,
    onError: (String) -> Unit,
) {
    val file = newEvidenceFile(cacheDir, "jpg")
    controller.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onEvidence(CustomerEvidenceFile(UUID.randomUUID().toString(), file, file.name, "image/jpeg"))
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                onError("Не удалось сохранить фото")
            }
        },
    )
}

private fun startSilentVideo(
    cacheDir: File,
    context: android.content.Context,
    controller: LifecycleCameraController,
    onEvidence: (CustomerEvidenceFile) -> Unit,
    onError: (String) -> Unit,
    onFinalized: () -> Unit,
): androidx.camera.video.Recording {
    val file = newEvidenceFile(cacheDir, "mp4")
    return controller.startRecording(
        FileOutputOptions.Builder(file).build(),
        androidx.camera.view.video.AudioConfig.AUDIO_DISABLED,
        ContextCompat.getMainExecutor(context),
    ) { event ->
        if (event is VideoRecordEvent.Finalize) {
            onFinalized()
            if (event.hasError() || file.length() == 0L) {
                file.delete()
                onError("Не удалось сохранить видео")
            } else {
                onEvidence(CustomerEvidenceFile(UUID.randomUUID().toString(), file, file.name, "video/mp4"))
            }
        }
    }
}

private fun newEvidenceFile(cacheDir: File, extension: String): File {
    val directory = File(cacheDir, "customer-problem-evidence").apply { mkdirs() }
    return File(directory, "${UUID.randomUUID()}.$extension")
}

private suspend fun importEvidenceFile(context: android.content.Context, uri: android.net.Uri): CustomerEvidenceFile {
    var unfinishedFile: File? = null
    try {
        return withContext(Dispatchers.IO) {
            val contentType = context.contentResolver.getType(uri)?.takeIf {
                it in SUPPORTED_EVIDENCE_CONTENT_TYPES
            } ?: throw IllegalArgumentException("Unsupported evidence content type")
            val extension = when (contentType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "video/webm" -> "webm"
                "video/mp4" -> "mp4"
                else -> "jpg"
            }
            val file = newEvidenceFile(context.cacheDir, extension)
            unfinishedFile = file
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        currentCoroutineContext().ensureActive()
                        if (count < 0) break
                        total += count
                        require(total <= MAX_EVIDENCE_BYTES) { "Evidence is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("Cannot open evidence")
            require(file.length() in 1..MAX_EVIDENCE_BYTES) { "Evidence is empty or too large" }
            CustomerEvidenceFile(UUID.randomUUID().toString(), file, file.name, contentType)
        }
    } catch (failure: Throwable) {
        // Also covers cancellation while dispatching an already copied file back to the UI.
        withContext(NonCancellable + Dispatchers.IO) { unfinishedFile?.delete() }
        throw failure
    }
}

private const val MAX_EVIDENCE_BYTES = 200L * 1024L * 1024L
private val SUPPORTED_EVIDENCE_CONTENT_TYPES = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "video/mp4",
    "video/webm",
)
