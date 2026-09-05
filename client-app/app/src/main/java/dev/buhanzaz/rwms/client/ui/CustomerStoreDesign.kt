package dev.buhanzaz.rwms.client.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import androidx.annotation.RawRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.buhanzaz.rwms.client.R

internal val CustomerStoreNavy = Color(0xFF204B79)
internal val CustomerStoreBlue = Color(0xFF549AC5)
internal val CustomerStoreWaterFallback = Color(0xFF2A8AD9)
internal val CustomerStoreGuestText = Color(0xFF4A4848)
internal val CustomerStoreFieldText = Color(0xFF585858)

/** Visual parameters for one normal or pressed gradient state from the supplied auth prototype. */
internal data class CustomerActionButtonStyle(
    val gradientStart: Color,
    val gradientEnd: Color,
    val fillOpacity: Float,
)

internal val CustomerLoginButtonStyle = CustomerActionButtonStyle(
    gradientStart = Color(0xFF78B4D5),
    gradientEnd = Color(0xFF3F79AB),
    fillOpacity = 0.74f,
)

internal val CustomerRegistrationButtonStyle = CustomerActionButtonStyle(
    gradientStart = CustomerStoreBlue,
    gradientEnd = CustomerStoreNavy,
    fillOpacity = 0.94f,
)

/** Lets JVM Compose tests replace Media3 with the deterministic water base color. */
internal val LocalCustomerStoreVideoBackgroundEnabled = staticCompositionLocalOf { true }

/** Draws the exact imported BLOCK BOX vector without raster scaling. */
@Composable
internal fun CustomerStoreLogo(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
) {
    Image(
        painter = painterResource(R.drawable.block_box_logo_svg),
        contentDescription = "Block Box",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(234f / 96f)
            .then(
                if (horizontalPadding == 0.dp) Modifier else Modifier.paddingHorizontal(horizontalPadding),
            ),
    )
}

/** Production background selector shared by every CustomerApp route. */
@Composable
internal fun CustomerStoreBackground(modifier: Modifier = Modifier) {
    if (LocalCustomerStoreVideoBackgroundEnabled.current) {
        CustomerVideoBackground(
            videoRes = R.raw.background_caustic,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(CustomerStoreWaterFallback))
    }
}

/**
 * Drop-in primary button used throughout CustomerApp so existing business screens inherit the
 * supplied high-opacity gradient and pressed-state swap without duplicating visual code.
 */
@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    border: BorderStroke? = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    CustomerStyledButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = shape,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        normalStyle = CustomerRegistrationButtonStyle,
        pressedStyle = CustomerLoginButtonStyle,
        content = content,
    )
}

/**
 * Secondary app action uses the translucent field surface so the main gradient action remains
 * visually distinct. The signed-out login and registration keep their supplied prototype styles.
 */
@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    border: BorderStroke? = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = shape,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        content = content,
    )
}

/** Reusable prototype button with animated gradient, opacity, scale, shadow, and native semantics. */
@Composable
internal fun CustomerStyledButton(
    text: String,
    normalStyle: CustomerActionButtonStyle,
    pressedStyle: CustomerActionButtonStyle,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
) {
    CustomerStyledButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height),
        enabled = enabled,
        normalStyle = normalStyle,
        pressedStyle = pressedStyle,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CustomerStyledButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape = RoundedCornerShape(12.dp),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    normalStyle: CustomerActionButtonStyle,
    pressedStyle: CustomerActionButtonStyle,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val targetStyle = if (isPressed && enabled) pressedStyle else normalStyle
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.985f else 1f,
        animationSpec = tween(
            durationMillis = if (isPressed) 90 else 160,
            easing = FastOutSlowInEasing,
        ),
        label = "customer-button-scale",
    )
    val gradientStart by animateColorAsState(
        targetValue = targetStyle.gradientStart,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "customer-button-gradient-start",
    )
    val gradientEnd by animateColorAsState(
        targetValue = targetStyle.gradientEnd,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "customer-button-gradient-end",
    )
    val fillOpacity by animateFloatAsState(
        targetValue = if (enabled) targetStyle.fillOpacity else targetStyle.fillOpacity * 0.48f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "customer-button-opacity",
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
            .figmaButtonShadow(shape)
            .clip(shape)
            .drawWithCache {
                val gradient = Brush.linearGradient(
                    colors = listOf(gradientStart, gradientEnd),
                    start = Offset.Zero,
                    end = Offset(size.width, 0f),
                )
                onDrawBehind {
                    drawRect(brush = gradient, alpha = fillOpacity)
                }
            },
        content = content,
    )
}

/** Borderless translucent input used by auth and profile forms. */
@Composable
internal fun CustomerStoreInputField(
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
    height: Dp = 56.dp,
    singleLine: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(CustomerStoreNavy),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = height)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** Checkbox row matching the auth prototype while preserving accessibility semantics. */
@Composable
internal fun CustomerRememberMeOption(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(24.dp)
            .clickable(
                enabled = enabled,
                interactionSource = source,
                indication = null,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val checkboxShape = RoundedCornerShape(5.dp)
        Canvas(
            modifier = Modifier
                .size(18.dp)
                .clip(checkboxShape)
                .background(Color.White.copy(alpha = 0.60f))
                .border(1.dp, Color.White.copy(alpha = 0.55f), checkboxShape),
        ) {
            if (checked) {
                val check = Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.52f)
                    lineTo(size.width * 0.42f, size.height * 0.75f)
                    lineTo(size.width * 0.83f, size.height * 0.25f)
                }
                drawPath(
                    path = check,
                    color = CustomerStoreNavy,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = "Запомнить",
            color = CustomerStoreNavy,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Text-only auth action with the prototype's press scale and underline. */
@Composable
internal fun CustomerTextAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = CustomerStoreNavy,
) {
    val source = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.985f else 1f,
        animationSpec = tween(if (isPressed) 90 else 160, easing = FastOutSlowInEasing),
        label = "customer-text-action-scale",
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.55f else 0.97f,
        animationSpec = tween(150),
        label = "customer-text-action-alpha",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                enabled = enabled,
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color.copy(alpha = pressAlpha),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textDecoration = if (isPressed) TextDecoration.Underline else TextDecoration.None,
        )
    }
}

/** A cached shape shadow stays independent of translucent fills and pressed content layers. */
internal fun Modifier.figmaButtonShadow(
    shape: Shape = RoundedCornerShape(12.dp),
): Modifier = dropShadow(
    shape = shape,
    shadow = Shadow(
        radius = 10.dp,
        spread = 0.dp,
        color = CustomerStoreNavy.copy(alpha = 0.18f),
        offset = DpOffset(0.dp, 4.dp),
    ),
)

/**
 * Plays the imported caustic WebM as a silent cropped infinite loop. Playback starts only after the
 * hosting activity is resumed, pauses before its surface leaves the foreground, and releases the
 * player when the app composition is disposed.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun CustomerVideoBackground(
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
            prepare()
        }
    }

    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> player.play()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> player.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player.play() else player.pause()
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

private fun Modifier.paddingHorizontal(value: Dp): Modifier =
    this.then(Modifier.padding(start = value, end = value))
