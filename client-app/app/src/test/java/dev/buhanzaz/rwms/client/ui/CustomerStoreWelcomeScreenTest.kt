package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

/** Compose checks for immediate entry and the complete signed-out customer flow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerStoreWelcomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

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
    fun `logo stays within the welcome and the fixed login header`() {
        setAuthContent()
        val logo = composeRule.onNodeWithTag("customer-auth-logo").fetchSemanticsNode().boundsInRoot
        val screen = composeRule.onNodeWithTag("customer-auth-screen").fetchSemanticsNode().boundsInRoot
        assertThat(logo.width).isGreaterThan(0f)
        assertThat(logo.top).isAtLeast(screen.top)
        assertThat(logo.left).isAtLeast(screen.left)
        assertThat(logo.right).isAtMost(screen.right)

        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Вход").assertExists()
        composeRule.onNodeWithText("Войти").assertExists()
        val movedLogo = composeRule.onNodeWithTag("customer-auth-logo").fetchSemanticsNode().boundsInRoot
        val login = composeRule.onNodeWithTag("customer-login-username").fetchSemanticsNode().boundsInRoot
        val back = composeRule.onNodeWithTag("customer-auth-back").fetchSemanticsNode().boundsInRoot
        assertThat(movedLogo.top).isLessThan(logo.top)
        assertThat(movedLogo.bottom).isAtMost(login.top)
        assertThat(movedLogo.left).isAtLeast(back.right)
        assertThat(back.bottom).isAtMost(login.top)
    }

    @Test
    fun `back from recovery returns to login and then to the start actions`() {
        setAuthContent()
        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.onNodeWithTag("customer-login-recovery").performClick()
        composeRule.onNodeWithTag("customer-password-recovery-screen").assertExists()
        composeRule.onNodeWithTag("customer-auth-back").performClick()
        composeRule.onNodeWithTag("customer-login-screen").assertExists()
        composeRule.onNodeWithTag("customer-password-recovery-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("customer-auth-back").performClick()
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
    fun `registration submits contact fields with validated credentials`() {
        val submission = AtomicReference<List<String>?>()
        setAuthContent(
            onRegister = { login, email, password, repeatedPassword, phone ->
                submission.set(listOf(login, email, password, repeatedPassword, phone))
            },
        )

        composeRule.onNodeWithTag("customer-auth-register").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("customer-registration-login").performTextInput("client_01")
        composeRule.onNodeWithTag("customer-registration-email").performTextInput("client@example.test")
        composeRule.onNodeWithTag("customer-registration-password").performTextInput("password-123")
        composeRule.onNodeWithTag("customer-registration-password-repeat").performTextInput("password-123")
        composeRule.onNodeWithTag("customer-registration-phone").performTextInput("+79990000000")
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
    fun `password recovery explains unavailability before asking for any input`() {
        setAuthContent()

        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("customer-login-recovery").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("customer-password-recovery-screen").assertExists()
        composeRule.onNodeWithTag("customer-recovery-phone").assertDoesNotExist()
        composeRule.onNodeWithTag("customer-recovery-submit").assertDoesNotExist()
        composeRule.onNodeWithText("Восстановление пароля пока недоступно").assertExists()
        composeRule.onNodeWithText("Вернуться ко входу").performScrollTo().performClick()
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
            ),
        ).isEqualTo("Введите корректный Email")
        assertThat(
            customerRegistrationValidationMessage(
                "client_01",
                "client@example.test",
                "password-123",
                "password-123",
                "",
            ),
        ).isEqualTo("Введите номер телефона")
        assertThat(
            customerRegistrationValidationMessage(
                "client_01",
                "client@example.test",
                "password-123",
                "password-123",
                "+79990000000",
            ),
        ).isNull()
    }

    private fun setAuthContent(
        message: String? = null,
        onLogin: (String, String, Boolean) -> Unit = { _, _, _ -> },
        onRegister: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
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
