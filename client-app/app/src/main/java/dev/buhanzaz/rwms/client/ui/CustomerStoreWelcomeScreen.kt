package dev.buhanzaz.rwms.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import kotlinx.coroutines.delay

internal const val CustomerStoreGreetingDurationMillis = 3_000L

private const val AuthActionAnimationDurationMillis = 420

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
                .widthIn(max = 520.dp)
                .padding(horizontal = 32.dp)
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

    val focusManager = LocalFocusManager.current
    fun navigate(page: CustomerAuthenticationPage) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        currentPage = page
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag("customer-auth-screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        val isStart = currentPage == CustomerAuthenticationPage.START
        val topSpace by animateDpAsState(
            targetValue = if (isStart) maxHeight * 0.30f else 4.dp,
            animationSpec = tween(360, easing = FastOutSlowInEasing),
            label = "customer-auth-logo-position",
        )
        val logoHeight by animateDpAsState(
            targetValue = when {
                isStart -> minOf((maxWidth - 64.dp) / (234f / 96f), 180.dp)
                imeVisible -> 0.dp
                else -> 72.dp
            },
            animationSpec = tween(360, easing = FastOutSlowInEasing),
            label = "customer-auth-logo-height",
        )
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(topSpace))
            CustomerStoreLogo(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .height(logoHeight)
                    .testTag("customer-auth-logo"),
            )
            AnimatedContent(
                targetState = currentPage,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(300)) { it / 12 })
                        .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(180)) { -it / 16 })
                        .using(SizeTransform(clip = false))
                },
                label = "customer-auth-page",
            ) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    val formModifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                    when (page) {
                        CustomerAuthenticationPage.START -> CustomerAuthActions(
                            onLoginSelected = { navigate(CustomerAuthenticationPage.LOGIN) },
                            onRegisterSelected = { navigate(CustomerAuthenticationPage.REGISTRATION) },
                            modifier = formModifier.padding(bottom = 20.dp),
                        )
                        CustomerAuthenticationPage.LOGIN -> CustomerLoginForm(
                            message = message,
                            submitting = submitting,
                            onLogin = onLogin,
                            onForgotPasswordSelected = { navigate(CustomerAuthenticationPage.PASSWORD_RECOVERY) },
                            modifier = formModifier,
                        )
                        CustomerAuthenticationPage.REGISTRATION -> CustomerRegistrationForm(
                            message = message,
                            submitting = submitting,
                            onRegister = onRegister,
                            modifier = formModifier,
                        )
                        CustomerAuthenticationPage.PASSWORD_RECOVERY -> CustomerPasswordRecoveryForm(
                            modifier = formModifier,
                        )
                    }
                }
            }
        }
        if (previousPage != null) {
            IconButton(
                onClick = { navigate(previousPage) },
                enabled = !submitting,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp)
                    .testTag("customer-auth-back"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Translation has no parent alpha layer, so actions enter from the actual screen edges. */
@Composable
private fun CustomerAuthActions(
    onLoginSelected: () -> Unit,
    onRegisterSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.testTag("customer-auth-start")) {
        val density = LocalDensity.current
        val travel = with(density) { (maxWidth + 64.dp).toPx() }
        val entrance = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            entrance.animateTo(1f, tween(AuthActionAnimationDurationMillis, easing = LinearOutSlowInEasing))
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CustomerStyledButton(
                text = "Войти",
                normalStyle = CustomerLoginButtonStyle,
                pressedStyle = CustomerRegistrationButtonStyle,
                enabled = entrance.value == 1f,
                onClick = onLoginSelected,
                modifier = Modifier.graphicsLayer { translationX = -travel * (1f - entrance.value) }
                    .testTag("customer-auth-login"),
            )
            Spacer(Modifier.height(16.dp))
            CustomerStyledButton(
                text = "Регистрация",
                normalStyle = CustomerRegistrationButtonStyle,
                pressedStyle = CustomerLoginButtonStyle,
                enabled = entrance.value == 1f,
                onClick = onRegisterSelected,
                modifier = Modifier.graphicsLayer { translationX = travel * (1f - entrance.value) }
                    .testTag("customer-auth-register"),
            )
            Spacer(Modifier.height(8.dp))
            CustomerTextAction(
                text = "Продолжить без аккаунта",
                enabled = false,
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(44.dp)
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
        title = "Вход",
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
        buttonText = "Войти",
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
        title = "Восстановление пароля",
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

/** One scroll container owns the whole form; navigation animates the page as a single unit. */
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
    fieldSpacing: Dp = 14.dp,
    labelSpacing: Dp = 6.dp,
    titleSpacing: Dp = 24.dp,
    auxiliarySpacing: Dp = 16.dp,
    buttonSpacing: Dp = 20.dp,
    auxiliaryContent: CustomerAuthAuxiliaryContent? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequesters = remember(fields.size) { List(fields.size) { FocusRequester() } }
    var submitRequested by remember { mutableStateOf(false) }
    LaunchedEffect(submitting, message) {
        if (!submitting && message != null) submitRequested = false
    }
    val enabled = !submitting && !submitRequested
    fun submitOnce() {
        if (!submitEnabled || !enabled) return
        submitRequested = true
        focusManager.clearFocus()
        keyboardController?.hide()
        onSubmit()
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(titleSpacing))
        }
        fields.forEachIndexed { index, field ->
            val lastField = index == fields.lastIndex
            CustomerAuthFieldGroup(
                field = field,
                enabled = enabled,
                imeAction = if (lastField) ImeAction.Done else ImeAction.Next,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onNext = { focusRequesters.getOrNull(index + 1)?.requestFocus() },
                    onDone = { submitOnce() },
                ),
                focusRequester = focusRequesters[index],
                labelSpacing = labelSpacing,
                labelModifier = Modifier,
                fieldModifier = Modifier,
            )
            if (!lastField) Spacer(Modifier.height(fieldSpacing))
        }
        if (auxiliaryContent != null) {
            Spacer(Modifier.height(auxiliarySpacing))
            auxiliaryContent.Content(enabled = enabled, navigateAfterExit = { next -> if (enabled) next() })
        }
        val visibleMessage = message ?: validationMessage
        if (visibleMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = visibleMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("customer-auth-message"),
            )
        }
        Spacer(Modifier.height(buttonSpacing))
        CustomerStyledButton(
            text = if (submitting || submitRequested) "Подождите…" else buttonText,
            normalStyle = buttonNormalStyle,
            pressedStyle = buttonPressedStyle,
            enabled = submitEnabled && enabled,
            onClick = ::submitOnce,
            modifier = Modifier.testTag(
                when (buttonText) {
                    "Войти" -> "customer-login-submit"
                    "Зарегистрироваться" -> "customer-registration-submit"
                    else -> "customer-recovery-submit"
                },
            ),
        )
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
