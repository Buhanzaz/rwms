package dev.buhanzaz.rwms.worker.feature.login

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val WorkerLoginAnimationDurationMillis = 600

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

    Box(
        modifier = Modifier.fillMaxSize().testTag("worker-login-screen"),
    ) {
        WorkerStoreLogo(
            modifier = Modifier.align(Alignment.Center).widthIn(max = 330.dp),
        )

        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 38.dp, end = 38.dp, bottom = 24.dp),
        ) {
            val density = LocalDensity.current
            val horizontalTravelPx = with(density) { (maxWidth + 80.dp).toPx() }
            val verticalTravelPx = with(density) { 220.dp.toPx() }
            val usernameOffset = remember(horizontalTravelPx) { Animatable(-horizontalTravelPx) }
            val passwordOffset = remember(horizontalTravelPx) { Animatable(horizontalTravelPx) }
            val buttonOffset = remember(verticalTravelPx) { Animatable(verticalTravelPx) }
            val appearance = remember { Animatable(0f) }
            var formReady by remember { mutableStateOf(false) }

            LaunchedEffect(horizontalTravelPx, verticalTravelPx) {
                coroutineScope {
                    launch {
                        usernameOffset.animateTo(
                            0f,
                            tween(
                                WorkerLoginAnimationDurationMillis,
                                easing = LinearOutSlowInEasing,
                            ),
                        )
                    }
                    launch {
                        passwordOffset.animateTo(
                            0f,
                            tween(
                                WorkerLoginAnimationDurationMillis,
                                easing = LinearOutSlowInEasing,
                            ),
                        )
                    }
                    launch {
                        buttonOffset.animateTo(
                            0f,
                            tween(
                                WorkerLoginAnimationDurationMillis,
                                easing = LinearOutSlowInEasing,
                            ),
                        )
                    }
                    launch {
                        appearance.animateTo(
                            1f,
                            tween(
                                WorkerLoginAnimationDurationMillis,
                                easing = LinearOutSlowInEasing,
                            ),
                        )
                    }
                }
                formReady = true
            }

            fun submit() {
                if (!formReady || isSubmitting || username.isBlank() || password.isBlank()) return
                focusManager.clearFocus()
                keyboardController?.hide()
                onLogin(username.trim(), password)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .graphicsLayer {
                        alpha = appearance.value
                        scaleX = 0.92f + appearance.value * 0.08f
                        scaleY = 0.92f + appearance.value * 0.08f
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WorkerLoginFieldLabel(
                    text = "Логин",
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        translationX = usernameOffset.value
                    },
                )
                Spacer(Modifier.height(6.dp))
                WorkerStoreInputField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "Введите логин",
                    enabled = formReady && !isSubmitting,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    focusRequester = usernameFocus,
                    modifier = Modifier
                        .testTag("worker-login")
                        .graphicsLayer { translationX = usernameOffset.value },
                )
                Spacer(Modifier.height(10.dp))
                WorkerLoginFieldLabel(
                    text = "Пароль",
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        translationX = passwordOffset.value
                    },
                )
                Spacer(Modifier.height(6.dp))
                WorkerStoreInputField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Введите пароль",
                    enabled = formReady && !isSubmitting,
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
                            enabled = formReady && !isSubmitting,
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
                    modifier = Modifier
                        .testTag("worker-password")
                        .graphicsLayer { translationX = passwordOffset.value },
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
                Spacer(Modifier.height(20.dp))
                WorkerButton(
                    onClick = ::submit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .graphicsLayer { translationY = buttonOffset.value }
                        .testTag("worker-login-submit"),
                    enabled = formReady && !isSubmitting && username.isNotBlank() && password.isNotBlank(),
                ) {
                    if (isSubmitting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(20.dp)
                                    .semantics { contentDescription = "Выполняется вход" },
                                strokeWidth = 2.dp,
                            )
                            Text("Входим…", modifier = Modifier.padding(start = 10.dp))
                        }
                    } else {
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
