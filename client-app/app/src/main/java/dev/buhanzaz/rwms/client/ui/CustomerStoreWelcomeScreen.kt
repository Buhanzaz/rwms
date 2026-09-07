package dev.buhanzaz.rwms.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.buhanzaz.rwms.client.auth.RegistrationValidator

/** Keeps the route background stable without delaying already available application content. */
@Composable
internal fun CustomerStoreLaunchGate(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CustomerStoreBackground(Modifier.matchParentSize().testTag("customer-store-background"))
        content()
    }
}

internal enum class CustomerAuthenticationPage {
    START,
    LOGIN,
    REGISTRATION,
    PASSWORD_RECOVERY,
}

/**
 * Reproduces the supplied 404-wide auth canvases while retaining native input and PKCE callbacks.
 * [greetingPreviewProgress] freezes the production greeting for Compose Preview.
 */
@Composable
internal fun CustomerAuthenticationScreen(
    message: String?,
    submitting: Boolean,
    onLogin: (String, String, Boolean) -> Unit,
    onRegister: (String, String, String, String, String) -> Unit,
    onContinueAsGuest: () -> Unit = {},
    initialPage: CustomerAuthenticationPage = CustomerAuthenticationPage.START,
    greetingPreviewProgress: Float? = null,
) {
    CustomerTheme(CustomerAppearanceMode.LIGHT) {
        var greetingFinished by rememberSaveable { mutableStateOf(initialPage != CustomerAuthenticationPage.START) }
        val greeting = remember { Animatable(if (greetingFinished) 1f else 0f) }
        val inspecting = LocalInspectionMode.current
        LaunchedEffect(greetingPreviewProgress, inspecting) {
            if (greetingPreviewProgress == null && !inspecting && !greetingFinished) {
                greeting.animateTo(1f, tween(1_500, easing = LinearEasing))
                greetingFinished = true
            }
        }
        val greetingProgress = {
            greetingPreviewProgress?.coerceIn(0f, 1f) ?: if (inspecting) 1f else greeting.value
        }
        Box(Modifier.fillMaxSize().testTag("customer-auth-screen"), contentAlignment = Alignment.TopCenter) {
            CustomerWelcomeAtmosphere(Modifier.matchParentSize())
            BoxWithConstraints(Modifier.widthIn(max = 520.dp).fillMaxSize()) {
                val density = LocalDensity.current
                val scale = maxWidth.value / 404f
                CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                    CustomerAuthenticationContent(
                        message, submitting, onLogin, onRegister, onContinueAsGuest, initialPage, greetingProgress,
                    )
                }
            }
        }
    }
}

/** One logo keeps its width; only the available canvas and the supplied page positions move it. */
@Composable
private fun CustomerAuthenticationContent(
    message: String?,
    submitting: Boolean,
    onLogin: (String, String, Boolean) -> Unit,
    onRegister: (String, String, String, String, String) -> Unit,
    onContinueAsGuest: () -> Unit,
    initialPage: CustomerAuthenticationPage,
    greetingProgress: () -> Float,
) {
    var page by rememberSaveable(initialPage) { mutableStateOf(initialPage) }
    var formHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val previousPage = when (page) {
        CustomerAuthenticationPage.START -> null
        CustomerAuthenticationPage.PASSWORD_RECOVERY -> CustomerAuthenticationPage.LOGIN
        else -> CustomerAuthenticationPage.START
    }
    fun navigate(destination: CustomerAuthenticationPage) {
        focus.clearFocus(force = true)
        keyboard?.hide()
        page = destination
    }
    BackHandler(previousPage != null && !submitting) { previousPage?.let(::navigate) }

    BoxWithConstraints(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().clipToBounds(),
    ) {
        val logoHeight = (maxWidth - 76.dp) / (234f / 96f)
        val formTop = maxHeight - with(density) { formHeightPx.toDp() }
        val logoTarget = when {
            keyboardVisible && page == CustomerAuthenticationPage.REGISTRATION -> -logoHeight - 24.dp
            page == CustomerAuthenticationPage.REGISTRATION -> formTop + 12.dp - logoHeight
            keyboardVisible && page == CustomerAuthenticationPage.LOGIN -> formTop - 40.dp - logoHeight
            keyboardVisible && page == CustomerAuthenticationPage.PASSWORD_RECOVERY -> formTop - 18.dp - logoHeight
            else -> minOf((maxHeight - logoHeight) / 2, formTop - 24.dp - logoHeight)
        }
        val logoTop by animateDpAsState(logoTarget, tween(300), label = "customer-auth-logo-position")
        CustomerStoreLogo(
            Modifier.fillMaxWidth().padding(horizontal = 38.dp).height(logoHeight)
                .graphicsLayer { translationY = logoTop.toPx() }
                .customerGreetingWave(greetingProgress).testTag("customer-auth-logo"),
        )
        val formModifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { formHeightPx = it.height }
        when (page) {
            CustomerAuthenticationPage.START -> CustomerAuthActions(
                onLoginSelected = { navigate(CustomerAuthenticationPage.LOGIN) },
                onRegisterSelected = { navigate(CustomerAuthenticationPage.REGISTRATION) },
                onContinueAsGuest = onContinueAsGuest,
                greetingProgress = greetingProgress,
                modifier = formModifier,
            )
            CustomerAuthenticationPage.LOGIN -> CustomerLoginForm(
                message, submitting, onLogin,
                onForgotPasswordSelected = { navigate(CustomerAuthenticationPage.PASSWORD_RECOVERY) },
                modifier = formModifier,
            )
            CustomerAuthenticationPage.REGISTRATION -> CustomerRegistrationForm(
                message, submitting, onRegister, formModifier,
            )
            CustomerAuthenticationPage.PASSWORD_RECOVERY -> CustomerPasswordRecoveryForm(
                keyboardVisible = keyboardVisible,
                modifier = formModifier,
            )
        }
    }
}

@Composable
private fun CustomerAuthActions(
    onLoginSelected: () -> Unit,
    onRegisterSelected: () -> Unit,
    onContinueAsGuest: () -> Unit,
    greetingProgress: () -> Float,
    modifier: Modifier,
) {
    val loginEnabled by remember(greetingProgress) { derivedStateOf { greetingProgress() > 0.72f } }
    val registrationEnabled by remember(greetingProgress) { derivedStateOf { greetingProgress() > 0.78f } }
    val guestEnabled by remember(greetingProgress) { derivedStateOf { greetingProgress() > 0.84f } }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 38.dp).padding(top = 24.dp, bottom = 48.dp)
            .testTag("customer-auth-start"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CustomerStyledButton("Вход", CustomerAuthLoginButtonStyle, CustomerAuthRegistrationButtonStyle, loginEnabled,
            onLoginSelected, Modifier.greetingActionReveal(greetingProgress, 0.72f, 0.91f, loginEnabled)
                .testTag("customer-auth-login"), authStyle = true)
        Spacer(Modifier.height(16.dp))
        CustomerStyledButton("Регистрация", CustomerAuthRegistrationButtonStyle, CustomerAuthLoginButtonStyle, registrationEnabled,
            onRegisterSelected, Modifier.greetingActionReveal(greetingProgress, 0.78f, 0.96f, registrationEnabled)
                .testTag("customer-auth-register"), authStyle = true)
        Spacer(Modifier.height(14.dp))
        CustomerTextAction("Продолжить без аккаунта", guestEnabled, onContinueAsGuest,
            Modifier.greetingActionReveal(greetingProgress, 0.84f, 1f, guestEnabled)
                .testTag("customer-auth-guest"), color = CustomerStoreGuestText, authStyle = true, textSize = 16.sp)
    }
}

private fun Modifier.greetingActionReveal(progress: () -> Float, start: Float, end: Float, enabled: Boolean): Modifier =
    graphicsLayer {
        val reveal = customerGreetingReveal(progress(), start, end)
        // Preserve the buttons' outer shadows while their opacity is below one.
        compositingStrategy = CompositingStrategy.ModulateAlpha
        alpha = reveal
        translationY = 18.dp.toPx() * (1f - reveal)
    }.semantics { if (!enabled) hideFromAccessibility() }

private data class CustomerAuthFieldSpec(
    val label: String,
    val value: String,
    val onValueChange: (String) -> Unit,
    val placeholder: String,
    val keyboardType: KeyboardType,
    val testTag: String,
    val visualTransformation: VisualTransformation = VisualTransformation.None,
)

@Composable
private fun CustomerLoginForm(
    message: String?,
    submitting: Boolean,
    onLogin: (String, String, Boolean) -> Unit,
    onForgotPasswordSelected: () -> Unit,
    modifier: Modifier,
) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var rememberMe by rememberSaveable { mutableStateOf(false) }
    CustomerAuthForm(
        fields = listOf(
            CustomerAuthFieldSpec("Логин / Email", login, { login = it }, "Введите Логин или Email",
                KeyboardType.Text, "customer-login-username"),
            CustomerAuthFieldSpec("Пароль", password, { password = it }, "Введите Пароль",
                KeyboardType.Password, "customer-login-password", PasswordVisualTransformation()),
        ),
        submitting = submitting,
        submitEnabled = login.isNotBlank() && password.isNotBlank(),
        submitTag = "customer-login-submit",
        onSubmit = { onLogin(login.trim(), password, rememberMe) },
        message = message,
        fieldSpacing = 10.dp,
        buttonSpacing = 8.dp,
        bottomPadding = 37.dp,
        modifier = modifier.testTag("customer-login-screen"),
        auxiliaryContent = { enabled ->
            Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                CustomerRememberMeOption(rememberMe, { rememberMe = it }, enabled,
                    Modifier.testTag("customer-login-remember"))
                CustomerTextAction("Забыли пароль?", enabled, onForgotPasswordSelected,
                    Modifier.testTag("customer-login-recovery"), color = CustomerStoreNavy, authStyle = true)
            }
        },
    )
}

@Composable
private fun CustomerRegistrationForm(
    message: String?,
    submitting: Boolean,
    onRegister: (String, String, String, String, String) -> Unit,
    modifier: Modifier,
) {
    var login by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var repeatedPassword by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    val validation = customerRegistrationValidationMessage(login, email, password, repeatedPassword, phone)
    CustomerAuthForm(
        fields = listOf(
            CustomerAuthFieldSpec("Логин", login, { login = it }, "Введите логин", KeyboardType.Text, "customer-registration-login"),
            CustomerAuthFieldSpec("Email", email, { email = it }, "Введите email", KeyboardType.Email, "customer-registration-email"),
            CustomerAuthFieldSpec("Пароль", password, { password = it }, "Введите пароль", KeyboardType.Password,
                "customer-registration-password", PasswordVisualTransformation()),
            CustomerAuthFieldSpec("Повторите пароль", repeatedPassword, { repeatedPassword = it }, "Повторите пароль",
                KeyboardType.Password, "customer-registration-password-repeat", PasswordVisualTransformation()),
            CustomerAuthFieldSpec("Номер телефона", phone, { phone = it }, "Введите номер телефона", KeyboardType.Phone,
                "customer-registration-phone"),
        ),
        title = "Регистрация",
        submitting = submitting,
        submitEnabled = validation == null,
        submitTag = "customer-registration-submit",
        onSubmit = { onRegister(login.trim(), email.trim(), password, repeatedPassword, phone.trim()) },
        message = message ?: validation.takeIf {
            login.isNotEmpty() || email.isNotEmpty() || password.isNotEmpty() || repeatedPassword.isNotEmpty() || phone.isNotEmpty()
        },
        fieldSpacing = 11.dp,
        bottomPadding = 39.dp,
        titleSpacing = 12.dp,
        buttonSpacing = 36.dp,
        modifier = modifier.testTag("customer-registration-screen"),
    )
}

/** Matches the supplied recovery form and reports the real missing capability when submitted. */
@Composable
private fun CustomerPasswordRecoveryForm(keyboardVisible: Boolean, modifier: Modifier) {
    var contact by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    CustomerAuthForm(
        fields = listOf(CustomerAuthFieldSpec("Номер телефона или email", contact, { contact = it },
            "Введите номер телефона или email", KeyboardType.Email, "customer-recovery-phone")),
        title = "Восстановление\nПароля",
        submitting = false,
        lockSubmit = false,
        submitEnabled = contact.isNotBlank(),
        submitTag = "customer-recovery-submit",
        onSubmit = { message = "Восстановление пароля пока недоступно" },
        message = message,
        titleSpacing = 15.dp,
        labelSpacing = 16.dp,
        buttonSpacing = 28.dp,
        bottomPadding = if (keyboardVisible) 16.dp else 36.dp,
        modifier = modifier.testTag("customer-password-recovery-screen"),
    )
}

/** The footer stays at the lower canvas edge; its one scroll owner keeps every native field reachable. */
@Composable
private fun CustomerAuthForm(
    fields: List<CustomerAuthFieldSpec>,
    submitting: Boolean,
    submitEnabled: Boolean,
    submitTag: String,
    onSubmit: () -> Unit,
    modifier: Modifier,
    title: String? = null,
    message: String? = null,
    fieldSpacing: Dp = 14.dp,
    labelSpacing: Dp = 11.dp,
    titleSpacing: Dp = 8.dp,
    buttonSpacing: Dp = 8.dp,
    bottomPadding: Dp = 36.dp,
    auxiliaryContent: (@Composable (Boolean) -> Unit)? = null,
    lockSubmit: Boolean = true,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequesters = remember(fields.size) { List(fields.size) { FocusRequester() } }
    var submitRequested by remember { mutableStateOf(false) }
    LaunchedEffect(submitting, message) { if (!submitting && message != null) submitRequested = false }
    val enabled = !submitting && !submitRequested
    fun submitOnce() {
        if (!submitEnabled || !enabled) return
        submitRequested = lockSubmit
        focus.clearFocus()
        keyboard?.hide()
        onSubmit()
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 38.dp)
            .padding(top = 20.dp, bottom = bottomPadding),
    ) {
        if (title != null) {
            Text(title, Modifier.fillMaxWidth(), color = CustomerStoreNavy.copy(alpha = 0.84f),
                fontFamily = CustomerActionFont, fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp, lineHeight = 38.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(titleSpacing))
        }
        fields.forEachIndexed { index, field ->
            Text(field.label, color = CustomerStoreNavy, fontFamily = CustomerActionFont,
                fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(labelSpacing))
            CustomerStoreInputField(
                value = field.value,
                onValueChange = field.onValueChange,
                placeholder = field.placeholder,
                enabled = enabled,
                keyboardType = field.keyboardType,
                imeAction = if (index == fields.lastIndex) ImeAction.Done else ImeAction.Next,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onNext = { focusRequesters.getOrNull(index + 1)?.requestFocus() },
                    onDone = { submitOnce() },
                ),
                visualTransformation = field.visualTransformation,
                focusRequester = focusRequesters[index],
                authStyle = true,
                modifier = Modifier.testTag(field.testTag),
            )
            if (index != fields.lastIndex) Spacer(Modifier.height(fieldSpacing))
        }
        auxiliaryContent?.invoke(enabled)
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message, Modifier.testTag("customer-auth-message"), color = MaterialTheme.colorScheme.error,
                fontFamily = CustomerActionFont, fontSize = 14.sp)
        }
        Spacer(Modifier.height(buttonSpacing))
        CustomerStyledButton(
            text = if (submitting || submitRequested) "Подождите…" else "Вход",
            normalStyle = CustomerAuthLoginButtonStyle,
            pressedStyle = CustomerAuthRegistrationButtonStyle,
            enabled = submitEnabled && enabled,
            onClick = ::submitOnce,
            authStyle = true,
            modifier = Modifier.testTag(submitTag),
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
