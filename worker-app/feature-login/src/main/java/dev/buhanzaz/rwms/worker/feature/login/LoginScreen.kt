package dev.buhanzaz.rwms.worker.feature.login

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.buhanzaz.rwms.worker.core.ui.WorkerButton
import dev.buhanzaz.rwms.worker.core.ui.WorkerStoreInputField
import dev.buhanzaz.rwms.worker.core.ui.WorkerStoreLogo
import dev.buhanzaz.rwms.worker.core.ui.WorkerStoreNavy

private val WorkerLoginContentGap = 16.dp
private const val WorkerLoginActionRevealDurationMillis = 300

/** Renders worker credentials in the same animated water design as CustomerApp. */
@Composable
fun LoginScreen(
    failure: String?,
    isSubmitting: Boolean,
    onLogin: (username: String, password: String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val actionReveal = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("worker-login-screen"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val density = LocalDensity.current
            val contentWidth = (minOf(maxWidth, 520.dp) - 76.dp).coerceAtLeast(0.dp)
            val logoHeight = contentWidth * (96f / 234f)
            var formHeightPx by remember { mutableIntStateOf(0) }
            val formHeight = with(density) { formHeightPx.toDp() }
            val formTop = maxHeight - formHeight
            val centeredLogoTop = (maxHeight - logoHeight) / 2
            val targetLogoTop = minOf(
                centeredLogoTop,
                formTop - WorkerLoginContentGap - logoHeight,
            ).coerceAtLeast(0.dp)
            val logoTop by animateDpAsState(
                targetValue = targetLogoTop,
                animationSpec = tween(WorkerLoginActionRevealDurationMillis),
                label = "worker-login-logo-position",
            )

            LaunchedEffect(Unit) {
                actionReveal.animateTo(
                    1f,
                    tween(WorkerLoginActionRevealDurationMillis),
                )
            }

            fun submit() {
                if (isSubmitting || username.isBlank() || password.isBlank()) return
                focusManager.clearFocus()
                keyboardController?.hide()
                onLogin(username.trim(), password)
            }

            Box(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxSize()
                    .align(Alignment.TopCenter),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = logoTop)
                        .requiredSize(
                            width = contentWidth,
                            height = logoHeight,
                        )
                        .testTag("worker-login-logo"),
                ) {
                    WorkerStoreLogo(
                        modifier = Modifier.matchParentSize(),
                        horizontalPadding = 0.dp,
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { formHeightPx = it.height },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 38.dp,
                                end = 38.dp,
                                bottom = WorkerLoginContentGap,
                            ),
                    ) {
                        WorkerLoginFieldLabel(
                            text = "Логин",
                            modifier = Modifier.fillMaxWidth().testTag("worker-login-label"),
                        )
                        Spacer(Modifier.height(6.dp))
                        WorkerStoreInputField(
                            value = username,
                            onValueChange = { username = it },
                            placeholder = "Введите логин",
                            enabled = !isSubmitting,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next,
                            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                            focusRequester = usernameFocus,
                            modifier = Modifier.testTag("worker-login"),
                        )
                        Spacer(Modifier.height(10.dp))
                        WorkerLoginFieldLabel(
                            text = "Пароль",
                            modifier = Modifier.fillMaxWidth().testTag("worker-password-label"),
                        )
                        Spacer(Modifier.height(6.dp))
                        WorkerStoreInputField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = "Введите пароль",
                            enabled = !isSubmitting,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            focusRequester = passwordFocus,
                            trailingContent = {
                                IconButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    enabled = !isSubmitting,
                                ) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                        contentDescription = if (passwordVisible) {
                                            "Скрыть пароль"
                                        } else {
                                            "Показать пароль"
                                        },
                                        tint = WorkerStoreNavy,
                                    )
                                }
                            },
                            modifier = Modifier.testTag("worker-password"),
                        )
                        failure?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = it,
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(WorkerLoginContentGap))
                        WorkerButton(
                            onClick = ::submit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .graphicsLayer {
                                    alpha = actionReveal.value
                                    translationY = 12.dp.toPx() * (1f - actionReveal.value)
                                }
                                .testTag("worker-login-submit"),
                            enabled = !isSubmitting && username.isNotBlank() && password.isNotBlank(),
                        ) {
                            Text(
                                text = "Войти",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkerLoginFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = WorkerStoreNavy,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
