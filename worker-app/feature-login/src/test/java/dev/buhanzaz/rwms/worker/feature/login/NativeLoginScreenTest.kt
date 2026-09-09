package dev.buhanzaz.rwms.worker.feature.login

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.ui.RwmsWorkerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeLoginScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows worker credentials immediately and submits them`() {
        var submitted: Pair<String, String>? = null
        compose.setContent {
            RwmsWorkerTheme {
                LoginScreen(
                    failure = null,
                    isSubmitting = false,
                    onLogin = { username, password -> submitted = username to password },
                )
            }
        }

        compose.onNodeWithContentDescription("BlockBox").assertIsDisplayed()
        compose.onNodeWithTag("worker-login").performTextInput(" worker.login ")
        compose.onNodeWithTag("worker-password").performTextInput("secret password")
        compose.onNodeWithContentDescription("Показать пароль").performClick()
        compose.onNodeWithContentDescription("Скрыть пароль").assertIsDisplayed()
        compose.onNodeWithText("Войти").performClick()

        compose.runOnIdle {
            assertThat(submitted).isEqualTo("worker.login" to "secret password")
        }
    }

    @Test
    fun `submission blocks controls without a visible loading indicator`() {
        compose.setContent {
            RwmsWorkerTheme {
                LoginScreen(
                    failure = "Неверный логин или пароль либо вход для рабочего отключён",
                    isSubmitting = true,
                    onLogin = { _, _ -> error("Disabled login must not submit") },
                )
            }
        }

        compose.onNodeWithText("Неверный логин или пароль либо вход для рабочего отключён")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Выполняется вход").assertDoesNotExist()
        compose.onNodeWithText("Войти").assertIsDisplayed()
        compose.onNodeWithTag("worker-login").assertIsNotEnabled()
        compose.onNodeWithTag("worker-password").assertIsNotEnabled()
    }

    @Test
    fun `password is not retained when login screen is recreated`() {
        var generation by mutableIntStateOf(0)
        compose.setContent {
            RwmsWorkerTheme {
                key(generation) {
                    LoginScreen(
                        failure = null,
                        isSubmitting = false,
                        onLogin = { _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithTag("worker-login").performTextInput("worker.login")
        compose.onNodeWithTag("worker-password").performTextInput("must-not-persist")

        compose.runOnIdle { generation++ }

        val loginText = compose.onNodeWithTag("worker-login")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        val passwordText = compose.onNodeWithTag("worker-password")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertThat(loginText).isEmpty()
        assertThat(passwordText).isEmpty()
        compose.onNodeWithText("Войти").assertIsNotEnabled()
    }

    @Test
    fun `logo keeps its dimensions and moves with the lower form when the IME resizes the viewport`() {
        var viewportHeight by mutableIntStateOf(720)
        compose.setContent {
            RwmsWorkerTheme {
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .height(viewportHeight.dp),
                ) {
                    LoginScreen(
                        failure = null,
                        isSubmitting = false,
                        onLogin = { _, _ -> },
                    )
                }
            }
        }

        val logoBeforeIme = compose.onNodeWithTag("worker-login-logo")
            .fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { viewportHeight = 420 }
        val logoWithIme = compose.onNodeWithTag("worker-login-logo")
            .fetchSemanticsNode().boundsInRoot
        val loginLabelWithIme = compose.onNodeWithTag("worker-login-label")
            .fetchSemanticsNode().boundsInRoot
        val loginWithIme = compose.onNodeWithTag("worker-login").fetchSemanticsNode().boundsInRoot
        val passwordWithIme = compose.onNodeWithTag("worker-password")
            .fetchSemanticsNode().boundsInRoot
        val submitWithIme = compose.onNodeWithTag("worker-login-submit")
            .fetchSemanticsNode().boundsInRoot
        val screenWithIme = compose.onNodeWithTag("worker-login-screen")
            .fetchSemanticsNode().boundsInRoot
        assertThat(logoWithIme.width).isWithin(1f).of(loginWithIme.width)
        assertThat(logoWithIme.width).isWithin(1f).of(submitWithIme.width)
        assertThat(logoWithIme.size).isEqualTo(logoBeforeIme.size)
        assertThat(logoWithIme.bottom).isAtMost(loginLabelWithIme.top)

        val logoToLoginGap = loginLabelWithIme.top - logoWithIme.bottom
        val passwordToButtonGap = submitWithIme.top - passwordWithIme.bottom
        val buttonToImeGap = screenWithIme.bottom - submitWithIme.bottom
        assertThat(logoToLoginGap).isWithin(1f).of(passwordToButtonGap)
        assertThat(logoToLoginGap).isWithin(1f).of(buttonToImeGap)
    }
}
