package dev.buhanzaz.rwms.client.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import androidx.annotation.RawRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

/** Lets JVM Compose tests use a frame from the supplied video without running Media3. */
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
        colorFilter = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
        } else null,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (horizontalPadding == 0.dp) Modifier else Modifier.paddingHorizontal(horizontalPadding),
            ),
    )
}

/** Calm, opaque backdrop shared by all reading and editing screens. */
@Composable
internal fun CustomerStoreBackground(modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.background))
}

/** Opaque reading surface with the restrained border and depth used by the RWMS web shells. */
@Composable
internal fun CustomerShellSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        modifier = modifier.dropShadow(
            shape,
            Shadow(
                radius = 16.dp,
                spread = 0.dp,
                color = if (dark) Color.Black.copy(alpha = 0.22f) else CustomerStoreNavy.copy(alpha = 0.08f),
                offset = DpOffset(0.dp, 4.dp),
            ),
        ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

/** Shows the supplied authentication artwork without a wash, scrim or opaque overlay. */
@Composable
internal fun CustomerWelcomeAtmosphere(modifier: Modifier = Modifier) {
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.background_caustic_poster),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        if (LocalCustomerStoreVideoBackgroundEnabled.current) {
            CustomerVideoBackground(R.raw.background_caustic, Modifier.matchParentSize())
        }
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
 * Secondary app action uses a solid outlined surface so the main gradient action remains
 * visually distinct. The signed-out login and registration keep their supplied prototype styles.
 */
@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        content = {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontFamily = CustomerActionFont)) {
                content()
            }
        },
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
        modifier = modifier.fillMaxWidth().heightIn(min = height),
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
        targetValue = if (enabled) targetStyle.fillOpacity else targetStyle.fillOpacity * 0.64f,
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
            disabledContentColor = Color.White.copy(alpha = 0.84f),
        ),
        contentPadding = contentPadding,
        interactionSource = source,
        modifier = modifier
            .heightIn(min = 48.dp)
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
        content = {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontFamily = CustomerActionFont)) {
                content()
            }
        },
    )
}

/** Shared native text input with a stable focus border and an accessible label. */
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
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val passwordField = visualTransformation is PasswordVisualTransformation
    var passwordVisible by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = keyboardActions,
        visualTransformation = if (passwordField && passwordVisible) VisualTransformation.None else visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape,
            )
            .semantics { contentDescription = placeholder },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = height)
                    .padding(start = 16.dp, end = if (passwordField) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    innerTextField()
                }
                if (passwordField) {
                    IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = enabled) {
                        Icon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Запомнить",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
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
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val source = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.985f else 1f,
        animationSpec = tween(if (isPressed) 90 else 160, easing = FastOutSlowInEasing),
        label = "customer-text-action-scale",
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.78f else if (enabled) 1f else 0.58f,
        animationSpec = tween(150),
        label = "customer-text-action-alpha",
    )
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
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
