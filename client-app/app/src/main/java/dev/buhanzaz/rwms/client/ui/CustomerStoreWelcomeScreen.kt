package dev.buhanzaz.rwms.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.buhanzaz.rwms.client.auth.RegistrationValidator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val CustomerStoreGreetingDurationMillis = 3_000L

private const val AuthActionAnimationDurationMillis = 600
private const val AuthFieldAnimationDurationMillis = 300

/** Keeps the supplied caustic video mounted once and shows the logo-only greeting for three seconds. */
@Composable
internal fun CustomerStoreLaunchGate(content: @Composable () -> Unit) {
    var greetingFinished by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(greetingFinished) {
        if (!greetingFinished) {
            delay(CustomerStoreGreetingDurationMillis)
            greetingFinished = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        CustomerStoreBackground(
            modifier = Modifier
                .matchParentSize()
                .testTag("customer-store-video-background"),
        )
        if (greetingFinished) {
            content()
        } else {
            CustomerStoreHelloScreen()
        }
    }
}

/** Three-second greeting from the supplied design. */
@Composable
internal fun CustomerStoreHelloScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("customer-hello-screen"),
        contentAlignment = Alignment.Center,
    ) {
        CustomerStoreLogo(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .testTag("customer-hello-logo"),
        )
    }
}

internal enum class CustomerAuthenticationPage {
    START,
    LOGIN,
    REGISTRATION,
    PASSWORD_RECOVERY,
}

/**
 * Owns the complete signed-out flow so selecting login cannot accidentally resolve to the
 * registration destination. Server errors keep the current form and its entered values intact.
 */
@Composable
internal fun CustomerAuthenticationScreen(
    message: String?,
    submitting: Boolean,
    onLogin: (loginOrEmail: String, password: String, rememberMe: Boolean) -> Unit,
    onRegister: (
        login: String,
        email: String,
        password: String,
        repeatedPassword: String,
        phone: String,
    ) -> Unit,
) {
    var currentPage by rememberSaveable { mutableStateOf(CustomerAuthenticationPage.START) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    val previousPage = when (currentPage) {
        CustomerAuthenticationPage.START -> null
        CustomerAuthenticationPage.LOGIN -> CustomerAuthenticationPage.START
        CustomerAuthenticationPage.REGISTRATION -> CustomerAuthenticationPage.START
        CustomerAuthenticationPage.PASSWORD_RECOVERY -> CustomerAuthenticationPage.LOGIN
    }

    BackHandler(enabled = previousPage != null && !submitting) {
        keyboardController?.hide()
        previousPage?.let { currentPage = it }
    }

    val logoOffsetY by animateFloatAsState(
        targetValue = with(density) {
            when (currentPage) {
                CustomerAuthenticationPage.REGISTRATION -> (-230).dp.toPx()
                else -> 0.dp.toPx()
            }
        },
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "customer-auth-logo-offset",
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (imeVisible && currentPage == CustomerAuthenticationPage.REGISTRATION) {
            0f
        } else {
            1f
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "customer-auth-logo-alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("customer-auth-screen"),
    ) {
        CustomerStoreLogo(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 330.dp)
                .graphicsLayer {
                    translationY = logoOffsetY
                    alpha = logoAlpha
                },
        )

        when (currentPage) {
            CustomerAuthenticationPage.START -> CustomerAuthActions(
                onLoginSelected = { currentPage = CustomerAuthenticationPage.LOGIN },
                onRegisterSelected = { currentPage = CustomerAuthenticationPage.REGISTRATION },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .authFormPosition(),
            )

            CustomerAuthenticationPage.LOGIN -> CustomerLoginForm(
                message = message,
                submitting = submitting,
                onLogin = onLogin,
                onForgotPasswordSelected = {
                    currentPage = CustomerAuthenticationPage.PASSWORD_RECOVERY
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .authFormPosition(),
            )

            CustomerAuthenticationPage.REGISTRATION -> CustomerRegistrationForm(
                message = message,
                submitting = submitting,
                onRegister = onRegister,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .authFormPosition(),
            )

            CustomerAuthenticationPage.PASSWORD_RECOVERY -> CustomerPasswordRecoveryForm(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .authFormPosition(),
            )
        }
    }
}

private fun Modifier.authFormPosition(): Modifier = this
    .widthIn(max = 520.dp)
    .fillMaxWidth()
    .statusBarsPadding()
    .navigationBarsPadding()
    .imePadding()
    .padding(start = 38.dp, end = 38.dp, bottom = 24.dp)

/** Animates the three start actions from opposite edges and from below. */
@Composable
private fun CustomerAuthActions(
    onLoginSelected: () -> Unit,
    onRegisterSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.testTag("customer-auth-start"),
    ) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val horizontalTravelPx = with(density) { (maxWidth + 80.dp).toPx() }
        val verticalTravelPx = with(density) { 220.dp.toPx() }
        val loginOffsetX = remember(horizontalTravelPx) { Animatable(-horizontalTravelPx) }
        val registrationOffsetX = remember(horizontalTravelPx) { Animatable(horizontalTravelPx) }
        val guestOffsetY = remember(verticalTravelPx) { Animatable(verticalTravelPx) }
        val appearance = remember { Animatable(0f) }
        var actionsReady by remember { mutableStateOf(false) }
        var exitStarted by remember { mutableStateOf(false) }

        LaunchedEffect(horizontalTravelPx, verticalTravelPx) {
            coroutineScope {
                launch {
                    loginOffsetX.animateTo(
                        0f,
                        tween(AuthActionAnimationDurationMillis, easing = LinearOutSlowInEasing),
                    )
                }
                launch {
                    registrationOffsetX.animateTo(
                        0f,
                        tween(AuthActionAnimationDurationMillis, easing = LinearOutSlowInEasing),
                    )
                }
                launch {
                    guestOffsetY.animateTo(
                        0f,
                        tween(AuthActionAnimationDurationMillis, easing = LinearOutSlowInEasing),
                    )
                }
                launch {
                    appearance.animateTo(
                        1f,
                        tween(AuthActionAnimationDurationMillis, easing = LinearOutSlowInEasing),
                    )
                }
            }
            actionsReady = true
        }

        fun exitTo(next: () -> Unit) {
            if (!actionsReady || exitStarted) return
            exitStarted = true
            scope.launch {
                coroutineScope {
                    launch {
                        loginOffsetX.animateTo(
                            -horizontalTravelPx,
                            tween(AuthActionAnimationDurationMillis, easing = FastOutSlowInEasing),
                        )
                    }
                    launch {
                        registrationOffsetX.animateTo(
                            horizontalTravelPx,
                            tween(AuthActionAnimationDurationMillis, easing = FastOutSlowInEasing),
                        )
                    }
                    launch {
                        guestOffsetY.animateTo(
                            verticalTravelPx,
                            tween(AuthActionAnimationDurationMillis, easing = FastOutSlowInEasing),
                        )
                    }
                    launch {
                        appearance.animateTo(
                            0f,
                            tween(AuthActionAnimationDurationMillis, easing = FastOutSlowInEasing),
                        )
                    }
                }
                next()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = appearance.value
                    scaleX = 0.82f + appearance.value * 0.18f
                    scaleY = 0.82f + appearance.value * 0.18f
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CustomerStyledButton(
                text = "Вход",
                normalStyle = CustomerLoginButtonStyle,
                pressedStyle = CustomerRegistrationButtonStyle,
                enabled = actionsReady && !exitStarted,
                onClick = { exitTo(onLoginSelected) },
                modifier = Modifier
                    .graphicsLayer { translationX = loginOffsetX.value }
                    .testTag("customer-auth-login"),
            )
            Spacer(Modifier.height(16.dp))
            CustomerStyledButton(
                text = "Регистрация",
                normalStyle = CustomerRegistrationButtonStyle,
                pressedStyle = CustomerLoginButtonStyle,
                enabled = actionsReady && !exitStarted,
                onClick = { exitTo(onRegisterSelected) },
                modifier = Modifier
                    .graphicsLayer { translationX = registrationOffsetX.value }
                    .testTag("customer-auth-register"),
            )
            Spacer(Modifier.height(4.dp))
            CustomerTextAction(
                text = "Продолжить без аккаунта",
                enabled = false,
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .graphicsLayer { translationY = guestOffsetY.value }
                    .testTag("customer-guest-access-unavailable"),
                color = CustomerStoreGuestText,
            )
        }
    }
}

private data class CustomerAuthFieldSpec(
    val label: String,
    val value: String,
    val onValueChange: (String) -> Unit,
    val placeholder: String,
    val keyboardType: KeyboardType,
    val testTag: String,
    val visualTransformation: VisualTransformation = VisualTransformation.None,
)

private interface CustomerAuthAuxiliaryContent {
    @Composable
    fun Content(
        enabled: Boolean,
        navigateAfterExit: (afterExit: () -> Unit) -> Unit,
    )
}

@Composable
private fun CustomerLoginForm(
    message: String?,
    submitting: Boolean,
    onLogin: (String, String, Boolean) -> Unit,
    onForgotPasswordSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var rememberMe by rememberSaveable { mutableStateOf(false) }

    CustomerAnimatedAuthForm(
        fields = listOf(
            CustomerAuthFieldSpec(
                label = "Логин",
                value = login,
                onValueChange = { login = it },
                placeholder = "Введите логин",
                keyboardType = KeyboardType.Text,
                testTag = "customer-login-username",
            ),
            CustomerAuthFieldSpec(
                label = "Пароль",
                value = password,
                onValueChange = { password = it },
                placeholder = "Введите пароль",
                keyboardType = KeyboardType.Password,
                testTag = "customer-login-password",
                visualTransformation = PasswordVisualTransformation(),
            ),
        ),
        buttonText = "Вход",
        buttonNormalStyle = CustomerLoginButtonStyle,
        buttonPressedStyle = CustomerRegistrationButtonStyle,
        submitting = submitting,
        submitEnabled = login.isNotBlank() && password.isNotBlank(),
        message = message,
        onSubmit = { onLogin(login.trim(), password, rememberMe) },
        auxiliaryContent = object : CustomerAuthAuxiliaryContent {
            @Composable
            override fun Content(
                enabled: Boolean,
                navigateAfterExit: (() -> Unit) -> Unit,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CustomerRememberMeOption(
                        checked = rememberMe,
                        onCheckedChange = { rememberMe = it },
                        enabled = enabled,
                        modifier = Modifier.testTag("customer-login-remember"),
                    )
                    CustomerTextAction(
                        text = "Забыли пароль?",
                        enabled = enabled,
                        onClick = { navigateAfterExit(onForgotPasswordSelected) },
                        modifier = Modifier.testTag("customer-login-recovery"),
                    )
                }
            }
        },
        modifier = modifier.testTag("customer-login-screen"),
    )
}

@Composable
private fun CustomerRegistrationForm(
    message: String?,
    submitting: Boolean,
    onRegister: (String, String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var login by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var repeatedPassword by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    val validationMessage = customerRegistrationValidationMessage(
        login = login,
        email = email,
        password = password,
        repeatedPassword = repeatedPassword,
        phone = phone,
    )

    CustomerAnimatedAuthForm(
        title = "Регистрация",
        fields = listOf(
            CustomerAuthFieldSpec(
                "Логин",
                login,
                { login = it },
                "Введите логин",
                KeyboardType.Text,
                "customer-registration-login",
            ),
            CustomerAuthFieldSpec(
                "Email",
                email,
                { email = it },
                "Введите email",
                KeyboardType.Email,
                "customer-registration-email",
            ),
            CustomerAuthFieldSpec(
                "Пароль",
                password,
                { password = it },
                "Введите пароль",
                KeyboardType.Password,
                "customer-registration-password",
                PasswordVisualTransformation(),
            ),
            CustomerAuthFieldSpec(
                "Повторите пароль",
                repeatedPassword,
                { repeatedPassword = it },
                "Повторите пароль",
                KeyboardType.Password,
                "customer-registration-password-repeat",
                PasswordVisualTransformation(),
            ),
            CustomerAuthFieldSpec(
                "Номер телефона",
                phone,
                { phone = it },
                "Введите номер телефона",
                KeyboardType.Phone,
                "customer-registration-phone",
            ),
        ),
        buttonText = "Зарегистрироваться",
        buttonNormalStyle = CustomerRegistrationButtonStyle,
        buttonPressedStyle = CustomerLoginButtonStyle,
        submitting = submitting,
        submitEnabled = validationMessage == null,
        message = message,
        validationMessage = validationMessage.takeIf {
            login.isNotEmpty() || email.isNotEmpty() || password.isNotEmpty() ||
                repeatedPassword.isNotEmpty() || phone.isNotEmpty()
        },
        onSubmit = {
            onRegister(
                login.trim(),
                email.trim(),
                password,
                repeatedPassword,
                phone.trim(),
            )
        },
        fieldSpacing = 10.dp,
        titleSpacing = 16.dp,
        buttonSpacing = 20.dp,
        modifier = modifier.testTag("customer-registration-screen"),
    )
}

@Composable
private fun CustomerPasswordRecoveryForm(modifier: Modifier = Modifier) {
    var phone by rememberSaveable { mutableStateOf("") }
    var recoveryMessage by rememberSaveable { mutableStateOf<String?>(null) }

    CustomerAnimatedAuthForm(
        title = "Восстановление\nпароля",
        fields = listOf(
            CustomerAuthFieldSpec(
                "Номер телефона",
                phone,
                {
                    phone = it
                    recoveryMessage = null
                },
                "Введите номер телефона",
                KeyboardType.Phone,
                "customer-recovery-phone",
            ),
        ),
        buttonText = "Восстановить пароль",
        buttonNormalStyle = CustomerRegistrationButtonStyle,
        buttonPressedStyle = CustomerLoginButtonStyle,
        submitting = false,
        submitEnabled = phone.isNotBlank(),
        message = recoveryMessage,
        onSubmit = {
            recoveryMessage = "Восстановление пароля пока недоступно"
        },
        titleSpacing = 20.dp,
        buttonSpacing = 18.dp,
        modifier = modifier.testTag("customer-password-recovery-screen"),
    )
}

/**
 * Shared form motion from the supplied prototype: fields enter from alternating edges, captions
 * follow, and navigation actions leave in the reverse direction.
 */
@Composable
private fun CustomerAnimatedAuthForm(
    fields: List<CustomerAuthFieldSpec>,
    buttonText: String,
    buttonNormalStyle: CustomerActionButtonStyle,
    buttonPressedStyle: CustomerActionButtonStyle,
    submitting: Boolean,
    submitEnabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    validationMessage: String? = null,
    fieldSpacing: Dp = 10.dp,
    labelSpacing: Dp = 6.dp,
    titleSpacing: Dp = 16.dp,
    auxiliarySpacing: Dp = 8.dp,
    buttonSpacing: Dp = 10.dp,
    auxiliaryContent: CustomerAuthAuxiliaryContent? = null,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val horizontalTravelPx = with(density) { (maxWidth + 80.dp).toPx() }
        val verticalTravelPx = with(density) { 220.dp.toPx() }
        val fieldOffsets = remember(horizontalTravelPx, fields.size) {
            List(fields.size) { index ->
                Animatable(if (index % 2 == 0) -horizontalTravelPx else horizontalTravelPx)
            }
        }
        val labelOffsets = remember(horizontalTravelPx, fields.size) {
            List(fields.size) { index ->
                Animatable(if (index % 2 == 0) -horizontalTravelPx else horizontalTravelPx)
            }
        }
        val focusRequesters = remember(fields.size) {
            List(fields.size) { FocusRequester() }
        }
        val titleOffsetX = remember(horizontalTravelPx, title) { Animatable(-horizontalTravelPx) }
        val auxiliaryOffsetX = remember(horizontalTravelPx) { Animatable(-horizontalTravelPx) }
        val buttonOffsetY = remember(verticalTravelPx) { Animatable(verticalTravelPx) }
        var formReady by remember { mutableStateOf(false) }
        var exitStarted by remember { mutableStateOf(false) }
        var submitRequested by remember { mutableStateOf(false) }

        LaunchedEffect(submitting, message) {
            if (!submitting && message != null) submitRequested = false
        }

        LaunchedEffect(horizontalTravelPx, fields.size, title) {
            fieldOffsets.forEach { offset ->
                offset.animateTo(
                    0f,
                    tween(AuthFieldAnimationDurationMillis, easing = LinearOutSlowInEasing),
                )
            }
            coroutineScope {
                labelOffsets.forEach { offset ->
                    launch {
                        offset.animateTo(
                            0f,
                            tween(AuthFieldAnimationDurationMillis, easing = LinearOutSlowInEasing),
                        )
                    }
                }
                if (title != null) {
                    launch {
                        titleOffsetX.animateTo(
                            0f,
                            tween(AuthFieldAnimationDurationMillis, easing = LinearOutSlowInEasing),
                        )
                    }
                }
            }
            if (auxiliaryContent != null) {
                auxiliaryOffsetX.animateTo(
                    0f,
                    tween(AuthActionAnimationDurationMillis, easing = LinearOutSlowInEasing),
                )
            }
            buttonOffsetY.animateTo(
                0f,
                tween(AuthActionAnimationDurationMillis, easing = LinearOutSlowInEasing),
            )
            formReady = true
        }

        fun navigateAfterExit(afterExit: () -> Unit) {
            if (!formReady || exitStarted || submitting || submitRequested) return
            exitStarted = true
            scope.launch {
                coroutineScope {
                    fieldOffsets.forEachIndexed { index, offset ->
                        launch {
                            offset.animateTo(
                                if (index % 2 == 0) -horizontalTravelPx else horizontalTravelPx,
                                tween(AuthFieldAnimationDurationMillis, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                    labelOffsets.forEachIndexed { index, offset ->
                        launch {
                            offset.animateTo(
                                if (index % 2 == 0) -horizontalTravelPx else horizontalTravelPx,
                                tween(AuthFieldAnimationDurationMillis, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                    if (title != null) {
                        launch {
                            titleOffsetX.animateTo(
                                -horizontalTravelPx,
                                tween(AuthFieldAnimationDurationMillis, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                    if (auxiliaryContent != null) {
                        launch {
                            auxiliaryOffsetX.animateTo(
                                -horizontalTravelPx,
                                tween(AuthActionAnimationDurationMillis, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                    launch {
                        buttonOffsetY.animateTo(
                            verticalTravelPx,
                            tween(AuthActionAnimationDurationMillis, easing = FastOutSlowInEasing),
                        )
                    }
                }
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                afterExit()
            }
        }

        fun submitOnce() {
            if (!formReady || !submitEnabled || exitStarted || submitting || submitRequested) return
            submitRequested = true
            focusManager.clearFocus()
            keyboardController?.hide()
            onSubmit()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title != null) {
                Text(
                    text = title,
                    color = CustomerStoreNavy.copy(alpha = 0.84f),
                    fontSize = 28.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationX = titleOffsetX.value },
                )
                Spacer(Modifier.height(titleSpacing))
            }

            fields.forEachIndexed { index, field ->
                val lastField = index == fields.lastIndex
                CustomerAuthFieldGroup(
                    field = field,
                    enabled = formReady && !exitStarted && !submitting && !submitRequested,
                    imeAction = if (lastField) ImeAction.Done else ImeAction.Next,
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onNext = { focusRequesters.getOrNull(index + 1)?.requestFocus() },
                        onDone = { submitOnce() },
                    ),
                    focusRequester = focusRequesters[index],
                    labelSpacing = labelSpacing,
                    labelModifier = Modifier.graphicsLayer {
                        translationX = labelOffsets[index].value
                    },
                    fieldModifier = Modifier.graphicsLayer {
                        translationX = fieldOffsets[index].value
                    },
                )
                if (!lastField) Spacer(Modifier.height(fieldSpacing))
            }

            if (auxiliaryContent != null) {
                Spacer(Modifier.height(auxiliarySpacing))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationX = auxiliaryOffsetX.value },
                ) {
                    auxiliaryContent.Content(
                        enabled = formReady && !exitStarted && !submitting && !submitRequested,
                        navigateAfterExit = ::navigateAfterExit,
                    )
                }
            }

            val visibleMessage = message ?: validationMessage
            if (visibleMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = visibleMessage,
                    color = Color(0xFF8E1C1C),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer-auth-message"),
                )
            }

            Spacer(Modifier.height(buttonSpacing))
            CustomerStyledButton(
                text = if (submitting || submitRequested) "Подождите…" else buttonText,
                normalStyle = buttonNormalStyle,
                pressedStyle = buttonPressedStyle,
                enabled = formReady && submitEnabled && !exitStarted && !submitting && !submitRequested,
                onClick = ::submitOnce,
                modifier = Modifier
                    .graphicsLayer { translationY = buttonOffsetY.value }
                    .testTag(
                        if (buttonText == "Вход") {
                            "customer-login-submit"
                        } else if (buttonText == "Зарегистрироваться") {
                            "customer-registration-submit"
                        } else {
                            "customer-recovery-submit"
                        },
                    ),
            )
        }
    }
}

@Composable
private fun CustomerAuthFieldGroup(
    field: CustomerAuthFieldSpec,
    enabled: Boolean,
    imeAction: ImeAction,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions,
    focusRequester: FocusRequester,
    labelSpacing: Dp,
    labelModifier: Modifier,
    fieldModifier: Modifier,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = field.label,
            color = CustomerStoreNavy,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = labelModifier,
        )
        Spacer(Modifier.height(labelSpacing))
        CustomerStoreInputField(
            value = field.value,
            onValueChange = field.onValueChange,
            placeholder = field.placeholder,
            enabled = enabled,
            keyboardType = field.keyboardType,
            imeAction = imeAction,
            keyboardActions = keyboardActions,
            visualTransformation = field.visualTransformation,
            focusRequester = focusRequester,
            modifier = fieldModifier.testTag(field.testTag),
        )
    }
}

/** Returns the first local registration error before the supported auth request is sent. */
internal fun customerRegistrationValidationMessage(
    login: String,
    email: String,
    password: String,
    repeatedPassword: String,
    phone: String,
): String? {
    RegistrationValidator.validate(login, password, repeatedPassword)?.let { return it }
    if (!CustomerEmailPattern.matches(email.trim())) return "Введите корректный Email"
    if (phone.isBlank()) return "Введите номер телефона"
    return null
}

private val CustomerEmailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
