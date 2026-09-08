package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compose checks for immediate entry and the complete signed-out customer flow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerStoreWelcomeScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `ready app content is exposed without an artificial greeting delay`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalCustomerStoreVideoBackgroundEnabled provides false) {
                CustomerTheme {
                    CustomerStoreLaunchGate { Text("Основной контент") }
                }
            }
        }

        composeRule.onNodeWithText("Основной контент").assertExists()
        composeRule.onNodeWithTag("customer-hello-screen").assertDoesNotExist()
    }

    @Test
    fun `start keeps the supplied composition without marketing copy`() {
        setAuthContent()

        composeRule.onNodeWithTag("customer-auth-login").assertIsEnabled()
        composeRule.onNodeWithTag("customer-auth-register").assertIsEnabled()
        composeRule.onNodeWithText("Аренда бытовок").assertDoesNotExist()
        composeRule.onNodeWithText("Бытовка под ваши задачи").assertDoesNotExist()
        composeRule.onNodeWithText("Каталог и заказы доступны после входа.").assertDoesNotExist()
        val screen = composeRule.onNodeWithTag("customer-auth-screen").fetchSemanticsNode().boundsInRoot
        val login = composeRule.onNodeWithTag("customer-auth-login").fetchSemanticsNode().boundsInRoot
        assertThat(login.top).isGreaterThan(screen.center.y)
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `logo keeps the field width and centered position between entry and login`() {
        setAuthContent()
        composeRule.mainClock.advanceTimeBy(1_000)
        val logo = composeRule.onNodeWithTag("customer-auth-logo").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        val loginLogo = composeRule.onNodeWithTag("customer-auth-logo").fetchSemanticsNode().boundsInRoot
        val login = composeRule.onNodeWithTag("customer-login-username").fetchSemanticsNode().boundsInRoot
        assertThat(loginLogo.width).isEqualTo(logo.width)
        assertThat(loginLogo.width).isWithin(1f).of(login.width)
        assertThat(loginLogo.top).isAtLeast(0f)
        assertThat(loginLogo.center.y).isLessThan(logo.center.y)
        assertThat(loginLogo.bottom).isAtMost(login.top)
        composeRule.onNodeWithText("Вход").assertExists()
        composeRule.onNodeWithText("Войти").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h470dp-mdpi")
    fun `short login canvas keeps logo above its first field`() {
        setAuthContent()
        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        val logo = composeRule.onNodeWithTag("customer-auth-logo").fetchSemanticsNode().boundsInRoot
        val field = composeRule.onNodeWithTag("customer-login-username").fetchSemanticsNode().boundsInRoot
        assertThat(logo.bottom).isAtMost(field.top)
    }

    @Test
    fun `back from recovery returns to login and then to the start actions`() {
        setAuthContent()
        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.onNodeWithTag("customer-login-recovery").performClick()
        composeRule.onNodeWithTag("customer-password-recovery-screen").assertExists()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("customer-login-screen").assertExists()
        composeRule.onNodeWithTag("customer-password-recovery-screen").assertDoesNotExist()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("customer-auth-login").assertIsEnabled()
        composeRule.onNodeWithTag("customer-login-screen").assertDoesNotExist()
    }

    @Test
    fun `login selection opens login and forwards remember choice`() {
        val submission = AtomicReference<Triple<String, String, Boolean>?>()
        setAuthContent(
            onLogin = { username, password, remember ->
                submission.set(Triple(username, password, remember))
            },
        )

        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("customer-login-screen").assertExists()
        composeRule.onNodeWithTag("customer-registration-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("customer-login-username").performTextInput("customer")
        composeRule.onNodeWithTag("customer-login-password").performTextInput("secret-pass")
        composeRule.onNodeWithTag("customer-login-remember").performClick()
        composeRule.onNodeWithTag("customer-login-remember").assertIsOn()
        composeRule.onNodeWithTag("customer-login-submit").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertThat(submission.get()).isEqualTo(Triple("customer", "secret-pass", true))
        }
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `registration logo stays fully visible above the scrollable form with keyboard open`() {
        setAuthContent()
        composeRule.onNodeWithTag("customer-auth-register").performScrollTo().performClick()
        composeRule.mainClock.advanceTimeBy(500)
        val initial = composeRule.onNodeWithTag("customer-auth-logo").fetchSemanticsNode().boundsInRoot
        assertThat(initial.height).isGreaterThan(0f)
        dispatchKeyboardInset(338)
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithTag("customer-auth-logo").assertIsDisplayed()
        val keyboardLogo = composeRule.onNodeWithTag("customer-auth-logo").fetchSemanticsNode().boundsInRoot
        assertThat(keyboardLogo.top).isAtLeast(0f)
        assertThat(keyboardLogo.height).isLessThan(initial.height)
        composeRule.onNodeWithTag("customer-registration-phone").performScrollTo().performTextInput("+79990000000")
        dispatchKeyboardInset(0)
        composeRule.mainClock.advanceTimeBy(500)
        val restored = composeRule.onNodeWithTag("customer-auth-logo").fetchSemanticsNode().boundsInRoot
        assertThat(restored.height).isGreaterThan(0f)
        assertThat(restored.width).isWithin(1f).of(initial.width)
    }

    private fun dispatchKeyboardInset(bottom: Int) {
        composeRule.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(composeRule.activity.window.decorView, insets)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `registration submits contact fields with validated credentials`() {
        val submission = AtomicReference<List<String>?>()
        setAuthContent(
            onRegister = { login, email, password, repeatedPassword, phone, firstName, lastName ->
                submission.set(listOf(login, email, password, repeatedPassword, phone, firstName, lastName))
            },
        )

        composeRule.onNodeWithTag("customer-auth-register").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("customer-registration-first-name").performScrollTo().performTextInput("Иван")
        composeRule.onNodeWithTag("customer-registration-last-name").performScrollTo().performTextInput("Петров")
        composeRule.onNodeWithTag("customer-registration-login").performScrollTo().performTextInput("client_01")
        composeRule.onNodeWithTag("customer-registration-email").performScrollTo().performTextInput("client@example.test")
        composeRule.onNodeWithTag("customer-registration-password").performScrollTo().performTextInput("password-123")
        composeRule.onNodeWithTag("customer-registration-password-repeat").performScrollTo().performTextInput("password-123")
        composeRule.onNodeWithTag("customer-registration-phone").performScrollTo().performTextInput("+79990000000")
        composeRule.onNodeWithText("Зарегистрироваться").assertExists()
        composeRule.onNodeWithTag("customer-registration-submit")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertThat(submission.get()).containsExactly(
                "client_01",
                "client@example.test",
                "password-123",
                "password-123",
                "+79990000000",
                "Иван",
                "Петров",
            ).inOrder()
        }
    }

    @Test
    fun `failed login message leaves entered form available for retry`() {
        setAuthContent(message = "Неверный логин или пароль")

        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("customer-login-username").performTextInput("customer")
        composeRule.onNodeWithTag("customer-login-password").performTextInput("secret-pass")
        composeRule.onNodeWithText("Неверный логин или пароль").assertExists()
        composeRule.onNodeWithTag("customer-login-submit").assertIsEnabled()
    }

    @Test
    fun `rapid repeated submit invokes login only once`() {
        val submissions = AtomicInteger()
        setAuthContent(onLogin = { _, _, _ -> submissions.incrementAndGet() })

        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("customer-login-username").performTextInput("customer")
        composeRule.onNodeWithTag("customer-login-password").performTextInput("secret-pass")
        composeRule.onNodeWithTag("customer-login-submit").performClick()
        composeRule.onNodeWithTag("customer-login-submit").performClick()

        composeRule.runOnIdle { assertThat(submissions.get()).isEqualTo(1) }
    }

    @Test
    fun `recovery keeps supplied form and reports unavailable service without fake success`() {
        setAuthContent()
        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.onNodeWithTag("customer-login-recovery").performClick()
        composeRule.onNodeWithTag("customer-recovery-phone").performTextInput("client@example.test")
        composeRule.onNodeWithText("Отправить").assertExists()
        composeRule.onNodeWithTag("customer-recovery-submit").performScrollTo().performClick()
        composeRule.onNodeWithText("Восстановление пароля пока недоступно").assertExists()
        composeRule.onNodeWithTag("customer-recovery-submit").assertIsEnabled()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("customer-login-screen").assertExists()
    }

    @Test
    fun `registration validation includes email and phone before submit`() {
        assertThat(
            customerRegistrationValidationMessage(
                "client_01",
                "wrong",
                "password-123",
                "password-123",
                "+79990000000",
                "Иван",
                "Петров",
            ),
        ).isEqualTo("Введите корректный Email")
        assertThat(
            customerRegistrationValidationMessage(
                "client_01",
                "client@example.test",
                "password-123",
                "password-123",
                "",
                "Иван",
                "Петров",
            ),
        ).isEqualTo("Введите номер телефона")
        assertThat(
            customerRegistrationValidationMessage(
                "client_01",
                "client@example.test",
                "password-123",
                "password-123",
                "+79990000000",
                "Иван",
                "Петров",
            ),
        ).isNull()
    }

    private fun setAuthContent(
        message: String? = null,
        onLogin: (String, String, Boolean) -> Unit = { _, _, _ -> },
        onRegister: (String, String, String, String, String, String, String) -> Unit = { _, _, _, _, _, _, _ -> },
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalCustomerStoreVideoBackgroundEnabled provides false) {
                CustomerTheme {
                    CustomerAuthenticationScreen(
                        message = message,
                        submitting = false,
                        onLogin = onLogin,
                        onRegister = onRegister,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
