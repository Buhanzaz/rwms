package dev.buhanzaz.rwms.worker.core.ui

import android.annotation.SuppressLint
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.annotation.RawRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.ceil

const val WorkerStoreGreetingDurationMillis = 1_500L

val WorkerStoreNavy = Color(0xFF204B79)
val WorkerStoreBlue = Color(0xFF549AC5)
val WorkerStoreWaterFallback = Color(0xFF2A8AD9)
val WorkerStoreFieldText = Color(0xFF585858)
val WorkerGlassSurface = Color.White.copy(alpha = 0.70f)
val WorkerGlassSurfaceSoft = Color.White.copy(alpha = 0.54f)
val WorkerGlassBorder = Color.White.copy(alpha = 0.68f)

private data class WorkerActionButtonStyle(
    val gradientStart: Color,
    val gradientEnd: Color,
    val fillOpacity: Float,
)

private val WorkerPrimaryButtonStyle = WorkerActionButtonStyle(
    gradientStart = WorkerStoreBlue,
    gradientEnd = WorkerStoreNavy,
    fillOpacity = 0.84f,
)

private val WorkerSecondaryButtonStyle = WorkerActionButtonStyle(
    gradientStart = WorkerStoreBlue,
    gradientEnd = WorkerStoreNavy,
    fillOpacity = 0.31f,
)

/** Lets deterministic Compose tests replace Media3 with the water fallback color. */
internal val LocalWorkerStoreVideoBackgroundEnabled = staticCompositionLocalOf { true }

/** Keeps the caustic background mounted while the centered logo performs the customer greeting. */
@Composable
fun WorkerStoreLaunchGate(content: @Composable () -> Unit) {
    var greetingFinished by rememberSaveable { mutableStateOf(false) }
    val greeting = remember { Animatable(if (greetingFinished) 1f else 0f) }

    LaunchedEffect(greetingFinished) {
        if (!greetingFinished) {
            greeting.animateTo(
                1f,
                tween(WorkerStoreGreetingDurationMillis.toInt(), easing = LinearEasing),
            )
            greetingFinished = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        WorkerStoreBackground(
            modifier = Modifier.matchParentSize().testTag("worker-store-video-background"),
        )
        if (greetingFinished) {
            content()
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .testTag("worker-hello-screen"),
                contentAlignment = Alignment.Center,
            ) {
                val logoWidth = (minOf(maxWidth, 520.dp) - 76.dp).coerceAtLeast(0.dp)
                WorkerStoreLogo(
                    modifier = Modifier
                        .requiredSize(logoWidth, logoWidth * (96f / 234f))
                        .workerGreetingWave { greeting.value }
                        .testTag("worker-hello-logo"),
                    horizontalPadding = 0.dp,
                )
            }
        }
    }
}

/** Draws the same BLOCK BOX vector used by the customer application. */
@Composable
fun WorkerStoreLogo(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 38.dp,
) {
    Image(
        painter = painterResource(R.drawable.block_box_logo_svg),
        contentDescription = "BlockBox",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(234f / 96f)
            .then(
                if (horizontalPadding == 0.dp) {
                    Modifier
                } else {
                    Modifier.padding(horizontal = horizontalPadding)
                },
            ),
    )
}

/** Renders the shared silent, cropped and looping water background. */
@Composable
fun WorkerStoreBackground(modifier: Modifier = Modifier) {
    if (LocalWorkerStoreVideoBackgroundEnabled.current) {
        WorkerVideoBackground(
            videoRes = R.raw.background_caustic,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(WorkerStoreWaterFallback))
    }
}

/** Primary worker action with the customer application's animated gradient and pressed state. */
@Composable
fun WorkerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    WorkerStyledButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        normalStyle = WorkerPrimaryButtonStyle,
        pressedStyle = WorkerSecondaryButtonStyle,
        content = content,
    )
}

/** Secondary worker action using the translucent inverse of the primary gradient. */
@Composable
fun WorkerOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    border: BorderStroke? = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    WorkerStyledButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        normalStyle = WorkerSecondaryButtonStyle,
        pressedStyle = WorkerPrimaryButtonStyle,
        content = content,
    )
}

@Composable
private fun WorkerStyledButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    border: BorderStroke?,
    contentPadding: PaddingValues,
    interactionSource: MutableInteractionSource?,
    normalStyle: WorkerActionButtonStyle,
    pressedStyle: WorkerActionButtonStyle,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val targetStyle = if (isPressed && enabled) pressedStyle else normalStyle
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = tween(
            durationMillis = if (isPressed) 90 else 160,
            easing = FastOutSlowInEasing,
        ),
        label = "worker-button-scale",
    )
    val gradientStart by animateColorAsState(
        targetValue = targetStyle.gradientStart,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "worker-button-gradient-start",
    )
    val gradientEnd by animateColorAsState(
        targetValue = targetStyle.gradientEnd,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "worker-button-gradient-end",
    )
    val fillOpacity by animateFloatAsState(
        targetValue = if (enabled) targetStyle.fillOpacity else targetStyle.fillOpacity * 0.48f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "worker-button-opacity",
    )

    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        border = border,
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White.copy(alpha = 0.62f),
        ),
        contentPadding = contentPadding,
        interactionSource = source,
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .workerFigmaShadow()
            .clip(shape)
            .drawWithCache {
                val gradient = Brush.linearGradient(
                    colors = listOf(gradientStart, gradientEnd),
                    start = Offset.Zero,
                    end = Offset(size.width, 0f),
                )
                onDrawBehind { drawRect(brush = gradient, alpha = fillOpacity) }
            },
        content = content,
    )
}

/** Borderless translucent field from the customer authentication design. */
@Composable
fun WorkerStoreInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = WorkerStoreFieldText.copy(alpha = 0.90f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        cursorBrush = SolidColor(WorkerStoreNavy),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
            .workerFigmaShadow()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.60f), shape),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = WorkerStoreFieldText.copy(alpha = 0.67f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    innerTextField()
                }
                trailingContent?.invoke()
            }
        },
    )
}

/** Draws the supplied soft shadow without painting over the video background. */
private fun Modifier.workerFigmaShadow(): Modifier = drawWithCache {
    val cornerRadiusPx = 16.dp.toPx()
    val offsetYPx = 10.dp.toPx()
    val blurPx = 24.dp.toPx()
    val spreadPx = (-6).dp.toPx()
    val shadowCornerRadiusPx = (cornerRadiusPx + spreadPx).coerceAtLeast(0f)
    val extraSpacePx = blurPx + abs(spreadPx) + abs(offsetYPx)
    val bitmapWidth = ceil(size.width + extraSpacePx * 2f).toInt().coerceAtLeast(1)
    val bitmapHeight = ceil(size.height + extraSpacePx * 2f).toInt().coerceAtLeast(1)
    val shadowBitmap = createBitmap(bitmapWidth, bitmapHeight)
    val shadowCanvas = AndroidCanvas(shadowBitmap)
    val buttonRight = extraSpacePx + size.width
    val buttonBottom = extraSpacePx + size.height
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.BLACK
        setShadowLayer(
            blurPx,
            0f,
            offsetYPx,
            AndroidColor.argb((255f * 0.251f).toInt(), 0, 0, 0),
        )
    }
    shadowCanvas.drawRoundRect(
        RectF(
            extraSpacePx - spreadPx,
            extraSpacePx - spreadPx,
            buttonRight + spreadPx,
            buttonBottom + spreadPx,
        ),
        shadowCornerRadiusPx,
        shadowCornerRadiusPx,
        shadowPaint,
    )
    shadowPaint.clearShadowLayer()
    val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    shadowCanvas.drawRoundRect(
        RectF(extraSpacePx, extraSpacePx, buttonRight, buttonBottom),
        cornerRadiusPx,
        cornerRadiusPx,
        clearPaint,
    )
    clearPaint.xfermode = null
    val shadowImage = shadowBitmap.asImageBitmap()
    onDrawBehind {
        drawImage(shadowImage, topLeft = Offset(-extraSpacePx, -extraSpacePx))
    }
}

/** Plays one silent video loop only while the hosting lifecycle is resumed. */
@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun WorkerVideoBackground(
    @RawRes videoRes: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(context.applicationContext, videoRes) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            val uri = "android.resource://${context.packageName}/$videoRes".toUri()
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = false
        }
    }

    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> player.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(AndroidColor.rgb(42, 138, 217))
                setKeepContentOnPlayerReset(true)
                this.player = player
            }
        },
        update = { playerView -> playerView.player = player },
    )
}
